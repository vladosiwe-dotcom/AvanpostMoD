package com.vladosiwe.lordsoutpost.entity.ai;

import com.vladosiwe.lordsoutpost.blueprint.Marker;
import com.vladosiwe.lordsoutpost.data.ChunkReconciliation;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.data.Storehouse;
import com.vladosiwe.lordsoutpost.entity.WorkerEntity;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Resource;
import com.vladosiwe.lordsoutpost.outpost.Role;
import com.vladosiwe.lordsoutpost.server.NpcLifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Рабочий день жителя. Пока игрок рядом, жители не изображают работу, а делают её в мире:
 *  - лесоруб находит дерево за стенами, валит его бревно за бревном и сажает саженец;
 *  - шахтёр спускается по стволу и ведёт штрек с отвилками, вынося камень и руду;
 *  - скотовод обслуживает стадо, разводит и стрижёт животных, забивает излишки, а также охотится за стенами из лука;
 *  - любой житель подбирает предметы, лежащие на территории форта.
 * Добыча несётся на склад, в бочки за дверью. Простоя нет: когда настоящей работы нет (вокруг нет деревьев,
 * штрек выработан, дичь кончилась), житель работает у своего места, и тогда его выработку считает симуляция.
 */
public class WorkGoal extends Goal {
    private enum Phase { DECIDE, PICKUP, JOB, DEPOSIT, CHORE, PAUSE }

    private static final ItemStack AXE = new ItemStack(Items.IRON_AXE);
    private static final int MAIN_TUNNEL = 32, BRANCH_EVERY = 4, BRANCH_LENGTH = 6, GENERATIONS = 8;

    private final WorkerEntity mob;
    private Phase phase = Phase.DECIDE;
    private int timer;
    private int cycle;
    private long noJobUntil;
    @Nullable private BlockPos spot;
    @Nullable private ItemEntity item;
    // Лесоруб.
    @Nullable private BlockPos tree;
    private final Deque<BlockPos> logs = new ArrayDeque<>();
    @Nullable private Block treeLog;
    // Шахтёр.
    private int column = -1;
    // Скотовод.
    private int herdAction;
    @Nullable private Animal animal;
    @Nullable private Animal breedingPartner;
    // Охота (вторая обязанность скотовода).
    @Nullable private Animal prey;
    private int kills;
    /** Скотовод чередует животноводство и охоту, чтобы одно направление не вытесняло другое. */
    private boolean skotovodHuntNext;
    private boolean skotovodHunting;
    private int huntPoint = -1;
    private int emptyPoints;

    public WorkGoal(WorkerEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    /** Коротко: чем житель занят (для журнала и отладки). */
    public String status() {
        Resident resident = mob.resident();
        String mining = resident != null && resident.role == Role.MINER
                ? " mining=" + resident.miningLevel() + "/5 xp=" + resident.miningXp + " tool=" + mob.getMainHandItem().getItem()
                : "";
        return phase + (tree != null ? " tree@" + tree.toShortString() : "") + (column >= 0 ? " col" + column : "")
                + (animal != null ? " animal@" + animal.blockPosition().toShortString()
                        + " leash=" + animal.isLeashed() + " herdTimer=" + timer : "")
                + (prey != null ? " prey@" + prey.blockPosition().toShortString() : "")
                + " noJob=" + noJobUntil + " carried=" + mob.carried()
                + " slots=" + mob.occupiedSlots() + "/" + mob.inventory.getContainerSize() + mining;
    }

    @Override
    public boolean canUse() {
        if (mob.level().isClientSide || mob.caravanId != null) return false;
        Outpost o = mob.outpost();
        return o != null && o.state == OutpostState.OWNED;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        release();
        phase = Phase.DECIDE;
    }

    @Nullable
    private static BlockPos freeBed(ServerLevel level, BlockPos around) {
        BlockPos best = null;
        for (BlockPos p : BlockPos.betweenClosed(around.offset(-5, -1, -5), around.offset(5, 2, 5))) {
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof net.minecraft.world.level.block.BedBlock && s.getValue(net.minecraft.world.level.block.BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.HEAD
                    && !s.getValue(net.minecraft.world.level.block.BedBlock.OCCUPIED)
                    && (best == null || p.distSqr(around) < best.distSqr(around))) best = p.immutable();
        }
        return best;
    }

    private void release() {
        Outpost o = mob.outpost();
        if (o != null && tree != null) o.claimedTrees.remove(tree);
        tree = null;
        logs.clear();
        if (animal != null && animal.isLeashed() && animal.getLeashHolder() == mob) animal.dropLeash(true, false);
        animal = null;
        breedingPartner = null;
        prey = null;
        item = null;
    }

    @Override
    public void tick() {
        Outpost o = mob.outpost();
        Resident r = mob.resident();
        if (o == null || r == null || !(mob.level() instanceof ServerLevel level)) return;
        Role role = r.role;
        long now = level.getGameTime();
        if (!level.isNight() && mob.isSleeping()) mob.stopSleeping();
        if (level.isNight() && role != Role.BUILDER && phase != Phase.DEPOSIT) {
            if (mob.isSleeping()) return;
            if (mob.carried() > 0) {
                release();
                phase = Phase.DEPOSIT;
                return;
            }
            release();
            List<BlockPos> beds = o.markers(Marker.HOUSING);
            BlockPos bed = beds.isEmpty() ? o.marker(Marker.YARD) : beds.get(Math.floorMod(r.id.hashCode(), beds.size()));
            if (go(o, bed, 2.0, 400)) {
                // Дошёл до дома: ложится в свободную кровать рядом.
                BlockPos free = freeBed(level, bed);
                if (free != null && mob.blockPosition().distSqr(free) < 16) mob.startSleeping(free);
                else if (free != null) mob.getNavigation().moveTo(free.getX() + 0.5, free.getY(), free.getZ() + 0.5, 0.8);
            }
            phase = Phase.DECIDE;
            return;
        }
        switch (phase) {
            case DECIDE -> decide(level, o, r, now);
            case PICKUP -> pickup(o);
            case JOB -> {
                boolean done = switch (role) {
                    case LUMBERJACK -> chop(level, o, r, now);
                    case MINER -> mine(level, o, r, now);
                    case SKOTOVOD -> skotovodHunting ? hunt(level, o, r, now) : herd(level, o, r, now);
                    default -> true;
                };
                if (done) {
                    if (role == Role.SKOTOVOD) skotovodHuntNext = !skotovodHunting;
                    release();
                    phase = mob.carried() > 0 ? Phase.DEPOSIT : Phase.DECIDE;
                }
            }
            case DEPOSIT -> {
                if (go(o, o.marker(Marker.STORE_INSIDE), 1.8, 500)) {
                    mob.swing(InteractionHand.MAIN_HAND);
                    Storehouse.deposit(level, o, mob.inventory);
                    OutpostSavedData.get(level).setDirty();
                    level.playSound(null, mob.blockPosition(), SoundEvents.BARREL_CLOSE, SoundSource.NEUTRAL, 0.6f, 1.0f);
                    phase = Phase.PAUSE;
                    timer = 20;
                }
            }
            case CHORE -> chore(level, o, role);
            default -> {
                if (--timer <= 0) phase = Phase.DECIDE;
            }
        }
    }

    // ============================== Что делать дальше ==============================

    private void decide(ServerLevel level, Outpost o, Resident r, long now) {
        if (mob.carried() > 0 && mob.cargoReadyToDeposit()) {
            phase = Phase.DEPOSIT;
            return;
        }
        item = findItem(o);
        if (item != null) {
            phase = Phase.PICKUP;
            return;
        }
        Role role = r.role;
        boolean physical = role == Role.LUMBERJACK || role == Role.MINER || role == Role.SKOTOVOD;
        if (physical && now >= noJobUntil) {
            boolean found = switch (role) {
                case LUMBERJACK -> findTree(level, o);
                case MINER -> claimColumn(level, o);
                case SKOTOVOD -> {
                    boolean huntFirst = skotovodHuntNext;
                    boolean selected = huntFirst ? startHunt() : planHerd(level, o, now);
                    if (selected) {
                        skotovodHunting = huntFirst;
                        yield true;
                    }
                    boolean fallbackHunt = !huntFirst;
                    selected = fallbackHunt ? startHunt() : planHerd(level, o, now);
                    skotovodHunting = fallbackHunt;
                    yield selected;
                }
                default -> false;
            };
            if (found) {
                phase = Phase.JOB;
                return;
            }
            noJobUntil = now + 600;
        }
        if (mob.carried() > 0) {
            phase = Phase.DEPOSIT;
            return;
        }
        phase = Phase.CHORE;
        spot = null;
        timer = 0;
    }

    // ============================== Подбор предметов ==============================

    @Nullable
    private ItemEntity findItem(Outpost o) {
        if (mob.inventoryFull()) return null;
        ItemEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (ItemEntity e : mob.level().getEntitiesOfClass(ItemEntity.class, mob.getBoundingBox().inflate(14, 4, 14))) {
            if (!e.isAlive() || e.getAge() < 40 || e.hasPickUpDelay()) continue;
            if (!o.contains(e.blockPosition()) && mob.distanceToSqr(e) > 36) continue;
            double d = mob.distanceToSqr(e);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private void pickup(Outpost o) {
        if (item == null || !item.isAlive()) {
            phase = Phase.DECIDE;
            return;
        }
        if (mob.distanceToSqr(item) <= 2.6 || go(o, item.blockPosition(), 1.2, 200)) {
            ItemStack left = mob.collect(item.getItem());
            mob.level().playSound(null, mob.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.3f, 1.2f);
            if (left.isEmpty()) {
                item.discard();
            } else {
                // Ноша заполнена: не уничтожаем остаток выпавшего стака, который не поместился.
                item.setItem(left);
                if (mob.cargoReadyToDeposit()) {
                    item = null;
                    phase = Phase.DEPOSIT;
                    return;
                }
            }
            item = null;
            phase = Phase.DECIDE;
        }
    }

    // ============================== Передвижение (с шахтным стволом) ==============================

    /**
     * Идёт к точке. Шахтёр, которому нужно из штрека наверх или вниз, лезет по лестнице ствола:
     * поиск пути мобов лестниц не знает, поэтому подъём и спуск ведутся вручную.
     */
    private boolean go(Outpost o, BlockPos target, double reach, int patience) {
        if (mob.role() == Role.MINER && shaftUsable(o, mob.level().getMinBuildHeight())) {
            BlockPos bottom = o.marker(Marker.MINE_SHAFT);
            int floor = o.center.getY() + 1;
            boolean under = mob.getY() < o.center.getY() - 0.5;
            boolean targetUnder = target.getY() < o.center.getY();
            double sx = bottom.getX() + 0.5, sz = bottom.getZ() + 0.5;
            double hd = Math.hypot(mob.getX() - sx, mob.getZ() - sz);
            if (under != targetUnder || (under && hd < 0.7 && mob.getY() > bottom.getY() + 0.5)) {
                boolean up = !targetUnder;
                if (hd > 0.7) {
                    BlockPos entry = up ? bottom : new BlockPos(bottom.getX(), floor, bottom.getZ());
                    if (mob.distanceToSqr(entry.getX() + 0.5, entry.getY(), entry.getZ() + 0.5) < 1.8) {
                        mob.getNavigation().stop();
                        mob.setPos(sx, mob.getY(), sz);
                    } else {
                        mob.approach(entry, 1.0, 0.9, patience);
                    }
                    return false;
                }
                mob.getNavigation().stop();
                if (up) {
                    if (mob.getY() >= o.center.getY() + 0.2) {
                        // Наверху: выбраться из ствола к выходу.
                        BlockPos exit = o.marker(Marker.MINE);
                        Vec3 out = new Vec3(exit.getX() + 0.5 - sx, 0, exit.getZ() + 0.5 - sz).normalize();
                        mob.setDeltaMovement(out.x * 0.3, 0.3, out.z * 0.3);
                    } else {
                        mob.setPos(sx, mob.getY(), sz);
                        mob.setDeltaMovement(0, 0.2, 0);
                    }
                } else {
                    mob.setPos(sx, mob.getY(), sz);
                    if (mob.getY() > bottom.getY() + 0.2) mob.setDeltaMovement(0, -0.15, 0);
                    else {
                        // Внизу: шагнуть в штрек.
                        BlockPos dir = o.marker(Marker.MINE_DIR);
                        mob.setDeltaMovement((dir.getX() - bottom.getX()) * 0.25, 0, (dir.getZ() - bottom.getZ()) * 0.25);
                    }
                }
                return false;
            }
        }
        return mob.approach(target, 1.0, reach, patience);
    }

    // ============================== Лесоруб ==============================

    private boolean findTree(ServerLevel level, Outpost o) {
        BlockPos center = o.marker(Marker.WOODYARD);
        OutpostSavedData data = OutpostSavedData.get(level);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        int radius = 64;
        // Каждый столбец: ствол дерева шириной в один блок, через один его не найти.
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) {
                int x = center.getX() + dx, z = center.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos top = new BlockPos(x, h - 1, z);
                if (!level.getBlockState(top).is(BlockTags.LOGS)) continue;
                BlockPos base = top;
                while (level.getBlockState(base.below()).is(BlockTags.LOGS) && top.getY() - base.getY() < 24) base = base.below();
                if (!level.getBlockState(base.below()).is(BlockTags.DIRT)) continue;
                if (data.findAt(base) != null || o.claimedTrees.contains(base) || !naturalLeaves(level, top)) continue;
                double d = mob.distanceToSqr(base.getX(), base.getY(), base.getZ());
                if (d < bestD) {
                    bestD = d;
                    best = base;
                }
            }
        if (best == null) return false;
        tree = best;
        o.claimedTrees.add(best);
        logs.clear();
        treeLog = level.getBlockState(best).getBlock();
        return true;
    }

    /** Дерево растёт само, если у вершины есть природная (не поставленная игроком) листва. */
    private static boolean naturalLeaves(ServerLevel level, BlockPos top) {
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-2, -1, -2), top.offset(2, 2, 2))) {
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof LeavesBlock && !s.getValue(LeavesBlock.PERSISTENT)) return true;
        }
        return false;
    }

    /** Возвращает true, когда дерево свалено (или пропало). */
    private boolean chop(ServerLevel level, Outpost o, Resident r, long now) {
        if (tree == null) return true;
        if (logs.isEmpty()) {
            if (!go(o, tree, 3.0, 500)) return false;
            collectTree(level, tree);
            if (logs.isEmpty()) return true;
            timer = 12;
        }
        mob.getLookControl().setLookAt(tree.getX() + 0.5, tree.getY() + 1.5, tree.getZ() + 0.5);
        if (--timer > 0) {
            if (timer % 6 == 0) {
                mob.swing(InteractionHand.MAIN_HAND);
                level.playSound(null, tree, SoundEvents.AXE_STRIP, SoundSource.NEUTRAL, 0.5f, 0.9f + mob.getRandom().nextFloat() * 0.2f);
            }
            return false;
        }
        timer = 12;
        BlockPos log = logs.pollFirst();
        BlockState state = level.getBlockState(log);
        if (state.is(BlockTags.LOGS)) {
            for (ItemStack drop : Block.getDrops(state, level, log, null, mob, AXE)) {
                ItemStack left = mob.collect(drop);
                if (!left.isEmpty()) mob.spawnAtLocation(left);
            }
            level.destroyBlock(log, false, mob);
            r.lastRealWork = now;
        }
        if (!logs.isEmpty()) return false;
        replant(level, tree);
        o.claimedTrees.remove(tree);
        tree = null;
        return true;
    }

    /** Все брёвна дерева (связанные, в пределах кроны), сверху вниз. */
    private void collectTree(ServerLevel level, BlockPos base) {
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        List<BlockPos> found = new ArrayList<>();
        queue.add(base);
        seen.add(base);
        // Крупные 2×2 и джунглевые деревья могут содержать заметно больше 64 брёвен.
        while (!queue.isEmpty() && found.size() < 256) {
            BlockPos p = queue.poll();
            if (!level.getBlockState(p).is(BlockTags.LOGS)) continue;
            found.add(p);
            for (BlockPos n : BlockPos.betweenClosed(p.offset(-1, 0, -1), p.offset(1, 1, 1))) {
                BlockPos q = n.immutable();
                if (Math.abs(q.getX() - base.getX()) > 5 || Math.abs(q.getZ() - base.getZ()) > 5 || q.getY() < base.getY()) continue;
                if (seen.add(q)) queue.add(q);
            }
        }
        found.sort(Comparator.comparingInt((BlockPos p) -> p.getY()).reversed());
        logs.addAll(found);
    }

    private void replant(ServerLevel level, BlockPos base) {
        if (treeLog == null || !level.getBlockState(base).isAir() || !level.getBlockState(base.below()).is(BlockTags.DIRT)) return;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(treeLog);
        String path = id.getPath().replace("stripped_", "").replace("_log", "_sapling").replace("_wood", "_sapling");
        if (path.startsWith("mangrove")) path = "mangrove_propagule";
        Block sapling = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), path)).orElse(Blocks.OAK_SAPLING);
        BlockState state = sapling.defaultBlockState();
        if (state.canSurvive(level, base)) {
            level.setBlockAndUpdate(base, state);
            level.playSound(null, base, SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.8f, 1f);
        }
    }

    // ============================== Шахтёр ==============================

    public record Column(BlockPos floor, BlockPos stand, boolean torch) {}

    /** Штрек: главный ход на 32 блока и отвилки по 6 блоков в обе стороны через каждые 4. Строится по меткам ствола. */
    public static List<Column> tunnel(Outpost o) {
        return tunnel(o, Integer.MIN_VALUE);
    }

    /** Штрек форта; пустой, если ствол не помещается над дном мира (minY — нижняя граница высоты мира). */
    public static List<Column> tunnel(Outpost o, int minY) {
        List<Column> out = new ArrayList<>();
        if (!shaftUsable(o, minY)) return out;
        BlockPos shaft = o.marker(Marker.MINE_SHAFT), dirPos = o.marker(Marker.MINE_DIR);
        int dx = Integer.signum(dirPos.getX() - shaft.getX()), dz = Integer.signum(dirPos.getZ() - shaft.getZ());
        int lx = -dz, lz = dx;
        BlockPos prev = shaft;
        // Поколение 0 — главный ход с короткими отвилками; каждое следующее удлиняет все отвилки ещё на BRANCH_LENGTH.
        // Так штрек не кончается за пару дней, а уже выданные номера столбцов (mineCursor) остаются прежними.
        for (int k = 1; k <= MAIN_TUNNEL; k++) {
            BlockPos main = shaft.offset(dx * k, 0, dz * k);
            out.add(new Column(main, prev, k % 6 == 0));
            prev = main;
            if (k % BRANCH_EVERY == 0) branch(out, main, lx, lz, 0);
        }
        for (int g = 1; g < GENERATIONS; g++)
            for (int k = BRANCH_EVERY; k <= MAIN_TUNNEL; k += BRANCH_EVERY) branch(out, shaft.offset(dx * k, 0, dz * k), lx, lz, g);
        return out;
    }

    private static void branch(List<Column> out, BlockPos main, int lx, int lz, int generation) {
        for (int side = -1; side <= 1; side += 2) {
            int from = generation * BRANCH_LENGTH;
            BlockPos stand = main.offset(lx * side * from, 0, lz * side * from);
            for (int j = from + 1; j <= from + BRANCH_LENGTH; j++) {
                BlockPos b = main.offset(lx * side * j, 0, lz * side * j);
                out.add(new Column(b, stand, j % 6 == 0));
                stand = b;
            }
        }
    }

    /** Ствол есть в чертеже и его дно с полом штрека стоит над дном мира. */
    public static boolean shaftUsable(Outpost o, int minY) {
        if (o.design().markers(Marker.MINE_SHAFT).isEmpty()) return false;
        return minY == Integer.MIN_VALUE || o.marker(Marker.MINE_SHAFT).getY() - 1 > minY;
    }

    private int redigColumn = -1, redigTries;
    private List<Column> cachedMinePlan = List.of();
    @Nullable private Outpost cachedMineOutpost;
    private int cachedMineMinY = Integer.MIN_VALUE;

    /** План штрека неизменен для одного дизайна форта; не пересоздаём сотни колонн каждый тик. */
    private List<Column> minePlan(ServerLevel level, Outpost o) {
        int minY = level.getMinBuildHeight();
        if (cachedMineOutpost != o || cachedMineMinY != minY) {
            cachedMinePlan = tunnel(o, minY);
            cachedMineOutpost = o;
            cachedMineMinY = minY;
        }
        return cachedMinePlan;
    }

    /**
     * Следующий столбец штрека. Если клетка, с которой его надо копать, завалена (осыпался гравий, клетку обошли),
     * сначала заново расчищается она: штрек не обрывается из-за одного завала.
     */
    private boolean claimColumn(ServerLevel level, Outpost o) {
        List<Column> cols = minePlan(level, o);
        while (o.mineCursor < cols.size()) {
            int idx = o.mineCursor;
            int target = reachable(level, cols, idx, 12);
            if (target == idx) {
                // Курсор сдвигается только после завершения столбца: сон/выгрузка не должны пропускать недокопанный блок.
                redigTries = 0;
            } else if (target >= 0) {
                if (target != redigColumn) redigTries = 0;
                if (target == redigColumn && ++redigTries > 3) {
                    // Эту клетку не расчистить (бедрок и т. п.) — столбец пропускается.
                    o.mineCursor++;
                    OutpostSavedData.get(level).setDirty();
                    redigTries = 0;
                    continue;
                }
                redigColumn = target;
            } else {
                o.mineCursor++;
                OutpostSavedData.get(level).setDirty();
                continue;
            }
            column = target;
            timer = -1;
            return true;
        }
        return false;
    }

    /** Какой столбец копать, чтобы стал доступен idx: он сам или завал перед ним (в пределах depth шагов назад). */
    private static int reachable(ServerLevel level, List<Column> cols, int idx, int depth) {
        Column c = cols.get(idx);
        if (passable(level, c.stand) && passable(level, c.stand.above())) return idx;
        if (depth == 0) return -1;
        for (int j = idx - 1; j >= 0; j--) {
            if (cols.get(j).floor.equals(c.stand)) return reachable(level, cols, j, depth - 1);
        }
        return -1;
    }

    /** Можно ли стоять в клетке: пусто или лестница (у лестницы есть тонкая коробка столкновения). */
    private static boolean passable(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.is(BlockTags.CLIMBABLE) || s.getCollisionShape(level, p).isEmpty();
    }

    private boolean mine(ServerLevel level, Outpost o, Resident r, long now) {
        mob.refreshMinerTool(o, r);
        List<Column> cols = minePlan(level, o);
        if (column < 0 || column >= cols.size()) return true;
        Column c = cols.get(column);
        if (timer < 0) {
            if (!go(o, c.stand, 1.6, 500)) return false;
            BlockState s = level.getBlockState(c.floor);
            timer = 16 + (int) (Math.max(0.5f, s.getDestroySpeed(level, c.floor)) * 8);
        }
        mob.getLookControl().setLookAt(c.floor.getX() + 0.5, c.floor.getY() + 1, c.floor.getZ() + 0.5);
        if (--timer > 0) {
            if (timer % 5 == 0) {
                mob.swing(InteractionHand.MAIN_HAND);
                level.playSound(null, c.floor, SoundEvents.STONE_HIT, SoundSource.BLOCKS, 0.6f, 0.9f + mob.getRandom().nextFloat() * 0.2f);
            }
            return false;
        }
        // Вода и лава, подступившие к штреку, заделываются булыжником: штрек не затопит, и его не придётся бросать.
        BlockPos lower = c.floor, upper = c.floor.above();
        for (BlockPos p : new BlockPos[]{lower, upper}) {
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (n.equals(lower) || n.equals(upper) || n.equals(c.stand) || n.equals(c.stand.above())) continue;
                if (!level.getFluidState(n).isEmpty()) level.setBlockAndUpdate(n, Blocks.COBBLESTONE.defaultBlockState());
            }
        }
        for (BlockPos p : new BlockPos[]{upper, lower}) {
            BlockState s = level.getBlockState(p);
            if (!s.getFluidState().isEmpty() && s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                continue;
            }
            if (s.isAir() || s.getDestroySpeed(level, p) < 0 || o.design().cells.containsKey(p.subtract(o.center))) continue;
            ItemStack pickaxe = mob.getMainHandItem();
            for (ItemStack drop : Block.getDrops(s, level, p, level.getBlockEntity(p), mob, pickaxe)) {
                ItemStack left = mob.collect(drop);
                if (!left.isEmpty()) mob.spawnAtLocation(left);
            }
            level.destroyBlock(p, false, mob);
            r.miningXp = Math.min(500, r.miningXp + 1);
            r.lastRealWork = now;
            // Записываем прогресс шахтёра и курс штрека в SavedData, а не ждём следующего макроцикла.
            OutpostSavedData.get(level).setDirty();
        }
        if (c.torch && level.getBlockState(lower).isAir() && level.getBlockState(lower.below()).isFaceSturdy(level, lower.below(), Direction.UP)) {
            level.setBlockAndUpdate(lower, Blocks.TORCH.defaultBlockState());
        }
        if (column == o.mineCursor) {
            o.mineCursor++;
            OutpostSavedData.get(level).setDirty();
        }
        if (mob.cargoReadyToDeposit()) return true;
        // Следующий столбец — сразу, не поднимаясь наверх; шахтёр работает до 75% ноши или конца штрека.
        return !claimColumn(level, o);
    }

    // ============================== Скотовод ==============================

    private static final int CULL = 1, SHEAR = 2, BREED = 3, FETCH = 4;

    @Nullable
    private static BlockPos penGate(ServerLevel level, Outpost o) {
        if (o.design().markers(Marker.PEN).isEmpty()) return null;
        BlockPos pen = o.marker(Marker.PEN);
        int size = ChunkReconciliation.penSize(o);
        for (BlockPos p : BlockPos.betweenClosed(pen.offset(-size, 0, -size), pen.offset(size, 0, size))) {
            if (level.getBlockState(p).getBlock() instanceof FenceGateBlock) return p.immutable();
        }
        return null;
    }

    /** Точка снаружи у калитки загона. */
    private static BlockPos outsideGate(Outpost o, BlockPos gate) {
        BlockPos pen = o.marker(Marker.PEN);
        int dx = gate.getX() - pen.getX(), dz = gate.getZ() - pen.getZ();
        return Math.abs(dx) >= Math.abs(dz) ? gate.offset(Integer.signum(dx), 0, 0) : gate.offset(0, 0, Integer.signum(dz));
    }

    private boolean planHerd(ServerLevel level, Outpost o, long now) {
        if (penGate(level, o) == null) return false;
        List<Animal> inside = ChunkReconciliation.penAnimals(level, o);
        int cap = o.penCapacity();
        animal = null;
        breedingPartner = null;
        if (inside.size() > cap || (inside.size() == cap && o.stock(Resource.FOOD) < o.residents.size() * 4)) {
            // Забить взрослое животное того вида, которого больше всего.
            animal = inside.stream().filter(a -> !a.isBaby())
                    .max(Comparator.comparingLong(a -> inside.stream().filter(b -> b.getType() == a.getType()).count())).orElse(null);
            herdAction = CULL;
        }
        if (animal == null) {
            for (Animal a : inside) if (a instanceof Sheep sh && sh.readyForShearing()) { animal = a; herdAction = SHEAR; break; }
        }
        if (animal == null && inside.size() < cap && now >= o.penActionAt && o.stock(Resource.FOOD) >= 2) {
            for (Animal a : inside) {
                if (a.isBaby() || a.isInLove() || !a.canFallInLove() || a.getAge() != 0) continue;
                for (Animal b : inside) {
                    if (b != a && b.getType() == a.getType() && !b.isBaby() && !b.isInLove() && b.canFallInLove() && b.getAge() == 0) {
                        animal = a;
                        breedingPartner = b;
                        herdAction = BREED;
                        break;
                    }
                }
                if (animal != null) break;
            }
        }
        if (animal == null && inside.size() < Math.min(cap, 4)) {
            BlockPos pen = o.marker(Marker.PEN);
            Animal best = null;
            double bestD = Double.MAX_VALUE;
            for (Animal a : level.getEntitiesOfClass(Animal.class, new AABB(pen).inflate(32, 8, 32))) {
                if (!(a instanceof Cow || a instanceof Sheep || a instanceof Pig || a instanceof Chicken) || a.isBaby() || a.isLeashed()
                        || a.hasCustomName() || inside.contains(a) || a.getPersistentData().contains(NpcLifecycle.CARAVAN_TAG)) continue;
                double d = a.distanceToSqr(mob);
                if (d < bestD) {
                    bestD = d;
                    best = a;
                }
            }
            animal = best;
            herdAction = FETCH;
        }
        timer = -1;
        return animal != null;
    }

    private boolean herd(ServerLevel level, Outpost o, Resident r, long now) {
        BlockPos gate = penGate(level, o);
        if (animal == null || !animal.isAlive() || gate == null) return true;
        BlockPos outside = outsideGate(o, gate);
        switch (herdAction) {
            case CULL, SHEAR, BREED -> {
                if (!go(o, outside, 1.2, 400)) return false;
                mob.getLookControl().setLookAt(animal);
                if (timer < 0) timer = 20;
                if (--timer > 0) return false;
                mob.swing(InteractionHand.MAIN_HAND);
                if (herdAction == CULL) {
                    animal.hurt(mob.damageSources().mobAttack(mob), Float.MAX_VALUE);
                } else if (herdAction == SHEAR && animal instanceof Sheep sheep && sheep.readyForShearing()) {
                    sheep.shear(SoundSource.NEUTRAL);
                    for (ItemEntity wool : level.getEntitiesOfClass(ItemEntity.class, sheep.getBoundingBox().inflate(1.5), e -> e.getAge() < 5)) {
                        ItemStack left = mob.collect(wool.getItem());
                        if (left.isEmpty()) wool.discard();
                        else wool.setItem(left);
                    }
                } else if (herdAction == BREED) {
                    // Одна порция корма обслуживает только выбранную пару, а не всё стадо.
                    if (breedingPartner == null || !breedingPartner.isAlive()
                            || !animal.canFallInLove() || !breedingPartner.canFallInLove()
                            || animal.getAge() != 0 || breedingPartner.getAge() != 0
                            || !ChunkReconciliation.penAnimals(level, o).contains(animal)
                            || !ChunkReconciliation.penAnimals(level, o).contains(breedingPartner)) return true;
                    if (!o.take(Resource.FOOD, 2)) return true;
                    animal.setInLove(null);
                    breedingPartner.setInLove(null);
                    level.playSound(null, animal.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.6f, 1f);
                    o.penActionAt = now + 1200;
                }
                r.lastRealWork = now;
                return true;
            }
            default -> {
                // Пригнать: взять животное на поводок, открыть калитку и физически провести его в загон.
                if (!animal.isLeashed() && timer < 0) {
                    if (!go(o, animal.blockPosition(), 2.0, 400)) return false;
                    animal.setLeashedTo(mob, true);
                    // Не сбрасываем этот маркер, если конкретный тип сущности/мод не поддерживает поводок на NPC.
                    // В таком случае дальше работает прямое наведение навигации животного.
                    timer = 0;
                    return false;
                }
                if (!ChunkReconciliation.penAnimals(level, o).contains(animal)) {
                    BlockPos pen = o.marker(Marker.PEN);
                    // После 20 секунд без успешного входа используем страховочное перемещение в центр загона.
                    // Это крайний fallback; основной маршрут всегда проходит через открытую калитку.
                    if (++timer > 400) {
                        animal.teleportTo(pen.getX() + 0.5, pen.getY(), pen.getZ() + 0.5);
                        animal.getNavigation().stop();
                        animal.setPersistenceRequired();
                        if (animal.isLeashed()) animal.dropLeash(true, false);
                        BlockState stuckGate = level.getBlockState(gate);
                        if (stuckGate.getBlock() instanceof FenceGateBlock && stuckGate.getValue(FenceGateBlock.OPEN)) {
                            level.setBlockAndUpdate(gate, stuckGate.setValue(FenceGateBlock.OPEN, false));
                        }
                        r.lastRealWork = now;
                        timer = 0;
                        return true;
                    }
                    int inner = ChunkReconciliation.penSize(o) - 1;
                    int gateDx = Integer.signum(gate.getX() - pen.getX());
                    int gateDz = Integer.signum(gate.getZ() - pen.getZ());
                    // Ведём животное к дальней стороне загона, чтобы поводок действительно натянулся за калиткой.
                    BlockPos herdDestination = pen.offset(-gateDx * inner, 0, -gateDz * inner);
                    boolean workerInside = Math.abs(mob.getX() - (pen.getX() + 0.5)) <= inner + 0.5
                            && Math.abs(mob.getZ() - (pen.getZ() + 0.5)) <= inner + 0.5
                            && Math.abs(mob.getY() - pen.getY()) <= 2.0;
                    if (!workerInside) {
                        if (!go(o, outside, 1.3, 600)) return false;
                        BlockState gs = level.getBlockState(gate);
                        if (gs.getBlock() instanceof FenceGateBlock && !gs.getValue(FenceGateBlock.OPEN)) {
                            level.setBlockAndUpdate(gate, gs.setValue(FenceGateBlock.OPEN, true));
                            level.playSound(null, gate, SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS, 0.8f, 1f);
                        }
                        // Не телепортируем скот: житель проходит через открытую калитку.
                        go(o, herdDestination, 0.8, 800);
                    } else {
                        // Поводок не всегда заставляет моба пройти узкий проход: задаём животному физический маршрут внутрь.
                        BlockState gs = level.getBlockState(gate);
                        if (gs.getBlock() instanceof FenceGateBlock && !gs.getValue(FenceGateBlock.OPEN)) {
                            level.setBlockAndUpdate(gate, gs.setValue(FenceGateBlock.OPEN, true));
                        }
                        if (animal.getNavigation().isDone() || level.getGameTime() % 20 == 0) {
                            animal.getNavigation().moveTo(herdDestination.getX() + 0.5, herdDestination.getY(), herdDestination.getZ() + 0.5, 1.0);
                        }
                    }
                    // Животное должно реально войти в AABB загона; только после этого отпускаем поводок.
                    return false;
                }
                if (animal.isLeashed()) animal.dropLeash(true, false);
                animal.setPersistenceRequired();
                BlockState after = level.getBlockState(gate);
                if (after.getBlock() instanceof FenceGateBlock && after.getValue(FenceGateBlock.OPEN)) {
                    level.setBlockAndUpdate(gate, after.setValue(FenceGateBlock.OPEN, false));
                    level.playSound(null, gate, SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 0.8f, 1f);
                }
                r.lastRealWork = now;
                return true;
            }
        }
    }

    // ============================== Охота (вторая обязанность скотовода) ==============================

    private boolean startHunt() {
        if (mob.outpost() == null || mob.outpost().markers(Marker.OUTER_PATROL).isEmpty()) return false;
        kills = 0;
        emptyPoints = 0;
        prey = null;
        timer = 0;
        return true;
    }

    private boolean hunt(ServerLevel level, Outpost o, Resident r, long now) {
        List<BlockPos> points = o.markers(Marker.OUTER_PATROL);
        if (points.isEmpty()) return true;
        if (prey != null && !prey.isAlive()) {
            kills++;
            r.lastRealWork = now;
            prey = null;
            if (kills >= 2 || mob.cargoReadyToDeposit()) return true;
        }
        if (prey == null) {
            prey = findPrey(o);
            if (prey == null) {
                if (huntPoint < 0) huntPoint = mob.getRandom().nextInt(points.size());
                BlockPos p = points.get(huntPoint % points.size());
                if (go(o, NpcLifecycle.groundAt(level, o, p.getX(), p.getZ()), 3.0, 400)) {
                    huntPoint++;
                    if (++emptyPoints >= points.size()) {
                        noJobUntil = now + 1200;
                        return true;
                    }
                }
                return false;
            }
        }
        // Дичь, забежавшая в форт, отпускается: стрелять во дворе — значит задеть скот в загоне и своих.
        if (o.insideWalls(prey.blockPosition()) || o.insideWalls(mob.blockPosition())) {
            if (o.insideWalls(prey.blockPosition())) prey = null;
            else go(o, prey.blockPosition(), 8.0, 300);
            if (prey == null) return false;
            return false;
        }
        double d = mob.distanceTo(prey);
        mob.getLookControl().setLookAt(prey);
        if (d > 14 || !mob.hasLineOfSight(prey)) {
            go(o, prey.blockPosition(), 8.0, 300);
            return false;
        }
        mob.getNavigation().stop();
        if (--timer > 0) return false;
        timer = 30;
        mob.swing(InteractionHand.MAIN_HAND);
        Arrow arrow = new Arrow(level, mob, new ItemStack(Items.ARROW), mob.getMainHandItem());
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        double dx = prey.getX() - mob.getX(), dy = prey.getY(0.5) - arrow.getY(), dz = prey.getZ() - mob.getZ();
        arrow.shoot(dx, dy + Math.sqrt(dx * dx + dz * dz) * 0.15, dz, 1.6f, 4f);
        level.addFreshEntity(arrow);
        level.playSound(null, mob.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, 0.8f, 1.1f);
        return false;
    }

    @Nullable
    private Animal findPrey(Outpost o) {
        Animal best = null;
        double bestD = 20 * 20;
        // Загон — отдельная защищённая зона: скотовод не должен принимать своё стадо за добычу,
        // даже если загон оказался за пределами основного периметра форта.
        Set<Animal> penned = new HashSet<>();
        AABB livestockArea = null;
        if (!o.design().markers(Marker.PEN).isEmpty()) {
            penned.addAll(ChunkReconciliation.penAnimals((ServerLevel) mob.level(), o));
            // Животные в радиусе сбора скота (32 блока от загона) зарезервированы для скотовода.
            livestockArea = new AABB(o.marker(Marker.PEN)).inflate(32, 8, 32);
        }
        for (Animal a : mob.level().getEntitiesOfClass(Animal.class, mob.getBoundingBox().inflate(20, 6, 20))) {
            if (!a.isAlive() || a.isBaby() || o.insideWalls(a.blockPosition()) || penned.contains(a)
                    || (livestockArea != null && livestockArea.contains(a.getX(), a.getY(), a.getZ())) || a.isLeashed() || a.hasCustomName()
                    || a.getPersistentData().contains(NpcLifecycle.CARAVAN_TAG)) continue;
            if (a instanceof TamableAnimal t && t.isTame()) continue;
            if (a instanceof AbstractHorse h && h.isTamed()) continue;
            double d = mob.distanceToSqr(a);
            if (d < bestD) {
                bestD = d;
                best = a;
            }
        }
        return best;
    }

    // ============================== Работа у места (без добычи в мире) ==============================

    private void chore(ServerLevel level, Outpost o, Role role) {
        if (spot == null) spot = workplace(level, o, role);
        if (timer <= 0) {
            if (!go(o, spot, role == Role.SKOTOVOD ? 4.5 : 2.5, 400)) return;
            timer = 140 + mob.getRandom().nextInt(120);
        }
        if (--timer % 25 == 0) {
            SoundEvent sound = workSound(role);
            if (sound != null) {
                mob.swing(InteractionHand.MAIN_HAND);
                mob.playSound(sound, 0.5f, 0.9f + mob.getRandom().nextFloat() * 0.2f);
            }
        }
        if (timer <= 0) {
            spot = null;
            cycle++;
            phase = Phase.DECIDE;
        }
    }

    private BlockPos workplace(ServerLevel level, Outpost o, Role role) {
        return switch (role) {
            case LUMBERJACK -> o.marker(Marker.WOODYARD);
            case MINER -> o.marker(Marker.MINE);
            case SMITH -> o.design().markers(Marker.SMITHY).isEmpty() ? o.marker(Marker.STORAGE) : o.marker(Marker.SMITHY);
            case MAGE -> o.design().markers(Marker.MAGE).isEmpty() ? o.marker(Marker.THRONE) : o.marker(Marker.MAGE);
            case SKOTOVOD -> o.design().markers(Marker.PEN).isEmpty() ? o.marker(Marker.YARD) : o.marker(Marker.PEN);
            case STEWARD -> switch (cycle % 3) {
                case 0 -> o.marker(Marker.THRONE);
                case 1 -> o.marker(Marker.BOARD);
                default -> o.marker(Marker.STORAGE);
            };
            case CARAVANEER -> {
                List<BlockPos> yard = o.markers(Marker.YARD);
                yield yard.isEmpty() ? o.marker(Marker.STORAGE) : yard.get(yard.size() - 1);
            }
            default -> o.marker(Marker.YARD);
        };
    }

    @Nullable
    private static SoundEvent workSound(Role role) {
        return switch (role) {
            case LUMBERJACK -> SoundEvents.AXE_STRIP;
            case MINER -> SoundEvents.STONE_HIT;
            case SMITH -> SoundEvents.SMITHING_TABLE_USE;
            case MAGE -> SoundEvents.BOOK_PAGE_TURN;
            case SKOTOVOD -> SoundEvents.GRASS_STEP;
            case STEWARD -> SoundEvents.BOOK_PAGE_TURN;
            default -> null;
        };
    }
}
