package com.vladosiwe.lordsoutpost.entity.ai;

import com.vladosiwe.lordsoutpost.blueprint.Marker;
import com.vladosiwe.lordsoutpost.data.ChunkReconciliation;
import com.vladosiwe.lordsoutpost.data.Logistics;
import com.vladosiwe.lordsoutpost.data.OutpostDirector;
import com.vladosiwe.lordsoutpost.outpost.OutpostTask;
import com.vladosiwe.lordsoutpost.outpost.WorkZone;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.data.Storehouse;
import com.vladosiwe.lordsoutpost.entity.WorkerEntity;
import com.vladosiwe.lordsoutpost.entity.FighterEntity;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
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
import java.util.UUID;
import java.util.Set;

/**
 * Рабочий день жителя. Пока игрок рядом, жители не изображают работу, а делают её в мире:
 *  - лесоруб расчищает деревья и подлесок во дворе, а за стенами рубит деревья с повторной посадкой;
 *  - шахтёр спускается по стволу и ведёт штрек с отвилками, вынося камень и руду;
 *  - скотовод обслуживает стадо, разводит и стрижёт животных, забивает излишки, а также охотится за стенами из лука;
 *  - любой житель подбирает предметы, лежащие на территории форта.
 * Добыча несётся на склад, в бочки за дверью. Простоя нет: когда настоящей работы нет (вокруг нет деревьев,
 * штрек выработан, дичь кончилась), житель работает у своего места, и тогда его выработку считает симуляция.
 */
public class WorkGoal extends Goal {
    private enum Phase { DECIDE, PICKUP, JOB, DEPOSIT, CRAFT, CHORE, PAUSE }

    private static final int FARM_SEED_RESERVE = 4;
    private static final int FARM_WHEAT_RESERVE = 6;

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
    @Nullable private BlockPos vegetation;
    private final Deque<BlockPos> logs = new ArrayDeque<>();
    /** Природная листва срубленного дерева: её обрывают ради саженцев и палок. */
    private final Deque<BlockPos> leaves = new ArrayDeque<>();
    @Nullable private Block treeLog;
    private boolean felled, saplingSupplied;
    // Шахтёр.
    private int column = -1;
    // Скотовод.
    private int herdAction;
    @Nullable private Animal animal;
    @Nullable private Animal breedingPartner;
    // Охота (вторая обязанность скотовода).
    @Nullable private Animal prey;
    @Nullable private BlockPos farmTarget;
    private boolean farmHarvest;
    /** Задача с общей доски, которую житель сейчас выполняет (кузнец, корм скотовода, семена фермера…). */
    @Nullable private OutpostTask task;
    /** Профессия, работу которой житель выполняет сейчас: своя или назначенная планировщиком (строитель без стройки). */
    private Role acting = Role.STEWARD;
    /** Версия рабочих зон, по которой построены кэши профессии. */
    private int zonesVersion = -1;
    private boolean farmTill;
    private int kills;
    /** Скотовод чередует животноводство и охоту, чтобы одно направление не вытесняло другое. */
    private boolean skotovodHuntNext;
    private boolean skotovodHunting;
    private boolean skotovodScheduleInitialized;
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
                        + " herd=" + herdAction + "/" + herdStage + " hand=" + mob.getMainHandItem().getItem() + " herdTimer=" + timer
                + " inPen=" + (mob.level() instanceof ServerLevel sl && mob.outpost() != null && ChunkReconciliation.penAnimals(sl, mob.outpost()).contains(animal)) : "")
                + (prey != null ? " prey@" + prey.blockPosition().toShortString() : "")
                + (vegetation != null ? " clearing@" + vegetation.toShortString() : "")
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
        vegetation = null;
        logs.clear();
        leaves.clear();
        felled = false;
        saplingSupplied = false;
        if (animal != null && animal.isLeashed() && animal.getLeashHolder() == mob) animal.dropLeash(true, false);
        if (feed != null) holdFeed(false);
        animal = null;
        breedingPartner = null;
        egg = null;
        feed = null;
        herdStage = 0;
        prey = null;
        farmTarget = null;
        farmHarvest = false;
        farmTill = false;
        if (task != null && task.active() && o != null) Logistics.cancel(o, task, "interrupted", mob.level().getGameTime());
        task = null;
        item = null;
        smeltFurnace = null;
    }

    @Override
    public void tick() {
        Outpost o = mob.outpost();
        Resident r = mob.resident();
        if (o == null || r == null || !(mob.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        // Торговец не уходит и не трогает лавку, пока с ним торгуют: предложения окна должны совпадать с товаром.
        if (mob.trading()) {
            mob.getNavigation().stop();
            if (mob.getTradingPlayer() != null) mob.getLookControl().setLookAt(mob.getTradingPlayer());
            return;
        }
        // Лорд перенёс рабочую зону: всё, что житель делал на старом месте, бросается.
        if (zonesVersion != o.zones.version()) {
            if (zonesVersion >= 0) {
                release();
                phase = Phase.DECIDE;
            }
            zonesVersion = o.zones.version();
        }
        Role role = r.role;
        if (role == Role.BUILDER && phase == Phase.DECIDE) {
            // Строителю нечего строить: планировщик решает, какой ресурс он пойдёт добывать руками.
            Role helper = ChunkReconciliation.hasWork(o) || com.vladosiwe.lordsoutpost.data.Projects.hasWork(o) ? null : OutpostDirector.helperRole(o);
            acting = helper == null ? Role.BUILDER : helper;
        } else if (role != Role.BUILDER) {
            acting = role;
        }
        role = acting;
        if (!level.isNight() && mob.isSleeping()) mob.stopSleeping();
        if (level.isNight() && role != Role.BUILDER && phase != Phase.DEPOSIT) {
            if (mob.isSleeping()) return;
            if (mob.carried() > 0) {
                release();
                phase = Phase.DEPOSIT;
                return;
            }
            release();
            List<BlockPos> beds = new ArrayList<>(o.markers(Marker.HOUSING));
            // Дома, построенные по заказу Лорда, — тоже жильё.
            for (com.vladosiwe.lordsoutpost.outpost.Project p : o.projects) {
                if (p.type == com.vladosiwe.lordsoutpost.outpost.Project.Type.HOUSE && p.state == com.vladosiwe.lordsoutpost.outpost.Project.State.DONE) beds.add(p.origin.above());
            }
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
                    case LUMBERJACK -> vegetation != null ? clearVegetation(level, o, r, now) : chop(level, o, r, now);
                    case MINER -> mine(level, o, r, now);
                    case SKOTOVOD -> skotovodHunting ? hunt(level, o, r, now) : herd(level, o, r, now);
                    case FARMER -> farm(level, o, r, now);
                    case SMITH -> smeltFurnace != null ? smelt(level, o, r, now) : smith(level, o, r, now);
                    case CARAVANEER -> shop(level, o, r, now);
                    default -> true;
                };
                if (done) {
                    if (role == Role.SKOTOVOD) skotovodHuntNext = !skotovodHunting;
                    release();
                    // Фермер не бегает на склад после каждой грядки: семена и урожай копятся в ноше до 75%.
                    boolean keep = role == Role.FARMER && !mob.cargoReadyToDeposit();
                    phase = mob.carried() > 0 && !keep ? Phase.DEPOSIT : Phase.DECIDE;
                }
            }
            case CRAFT -> craftBread(level, o, r, now);
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
        if (acting == Role.FARMER && Storehouse.countItem(level, o, Items.WHEAT) >= FARM_WHEAT_RESERVE + 3 && canCarryItem(Items.BREAD)) {
            phase = Phase.CRAFT;
            return;
        }
        item = findItem(o);
        if (item != null) {
            phase = Phase.PICKUP;
            return;
        }
        Role role = acting;
        if (role == Role.SKOTOVOD && !skotovodScheduleInitialized) {
            // Разводим работников по разным направлениям и сохраняем одинаковый порядок после пересоздания сущности.
            skotovodHuntNext = (r.id.getLeastSignificantBits() & 1L) != 0L;
            skotovodScheduleInitialized = true;
        }
        boolean physical = role == Role.LUMBERJACK || role == Role.MINER || role == Role.SKOTOVOD || role == Role.FARMER || role == Role.SMITH
                || role == Role.CARAVANEER;
        if (physical && now >= noJobUntil) {
            boolean found = switch (role) {
                case LUMBERJACK -> findVegetation(level, o) || findTree(level, o);
                case MINER -> {
                    boolean col = claimColumn(level, o);
                    if (col && countCarried(Items.TORCH) == 0 && now >= torchRetryAt) needTorches = true;
                    yield col;
                }
                case FARMER -> findFarmTask(level, o);
                case SMITH -> findSmithTask(level, o, r, now);
                case CARAVANEER -> findShopTask(level, o, r, now);
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
            // Предмет, который игрок сам выбросил, — его дело: житель не уносит его на склад, пока предмет не залежится.
            if (e.getAge() < 3000 && e.getOwner() instanceof net.minecraft.world.entity.player.Player) continue;
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
        if (acting == Role.MINER && shaftUsable(o, mob.level().getMinBuildHeight())) {
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
        BlockPos center = o.zone(WorkZone.WOODYARD);
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
                Outpost occupied = data.findAt(base);
                if ((occupied != null && occupied != o) || (occupied == o && !o.insideWalls(base))
                        || o.claimedTrees.contains(base) || !naturalLeaves(level, top) || plantedByPlayer(data, base)) continue;
                // Сначала освобождаем двор собственного форта, затем идём за деревьями за стенами.
                double d = mob.distanceToSqr(base.getX(), base.getY(), base.getZ()) - (occupied == o ? 1_000_000.0 : 0.0);
                // Форт строится из древесины своей палитры: её породу лесоруб берёт охотнее, а при нехватке дерева — в первую очередь.
                if (level.getBlockState(base).is(o.palette.log)) d -= o.scarce == Resource.WOOD ? 4096 : 1024;
                if (d < bestD) {
                    bestD = d;
                    best = base;
                }
            }
        if (best == null) return false;
        tree = best;
        o.claimedTrees.add(best);
        logs.clear();
        leaves.clear();
        felled = false;
        saplingSupplied = false;
        treeLog = level.getBlockState(best).getBlock();
        return true;
    }

    /** Ищет естественную растительность внутри стен, которая мешает проходам и рабочим площадкам. */
    private boolean findVegetation(ServerLevel level, Outpost o) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        int radius = o.radius();
        int yMin = Math.max(level.getMinBuildHeight(), o.center.getY() - 2);
        int yMax = Math.min(level.getMaxBuildHeight() - 2, o.center.getY() + 8);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = o.center.getX() + dx, z = o.center.getZ() + dz;
                if (!o.insideWalls(new BlockPos(x, o.center.getY() + 1, z)) || !level.hasChunk(x >> 4, z >> 4)) continue;
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                for (int y = Math.max(yMin, ground - 1); y <= Math.min(yMax, ground + 2); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || !state.getFluidState().isEmpty() || level.getBlockEntity(pos) != null
                            || state.is(BlockTags.CROPS) || state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                            || o.placedByPlayer(pos)) continue;
                    if (!(state.canBeReplaced() || state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS)
                            || state.is(BlockTags.REPLACEABLE_BY_TREES))) continue;
                    double d = mob.distanceToSqr(pos.getX(), pos.getY(), pos.getZ());
                    if (d < bestD) {
                        bestD = d;
                        best = pos.immutable();
                    }
                }
            }
        }
        vegetation = best;
        timer = 0;
        return best != null;
    }

    /** Убирает траву, кусты, цветы и молодую поросль внутри стен; посевы и блоки с хранилищами не трогает. */
    private boolean clearVegetation(ServerLevel level, Outpost o, Resident r, long now) {
        if (vegetation == null) return true;
        if (!go(o, vegetation, 2.0, 400)) return false;
        BlockState state = level.getBlockState(vegetation);
        if (state.isAir() || level.getBlockEntity(vegetation) != null || !state.getFluidState().isEmpty()) {
            vegetation = null;
            return true;
        }
        if (timer <= 0) timer = 8;
        mob.getLookControl().setLookAt(vegetation.getX() + 0.5, vegetation.getY() + 0.5, vegetation.getZ() + 0.5);
        if (--timer > 0) {
            if (timer % 4 == 0) mob.swing(InteractionHand.MAIN_HAND);
            return false;
        }
        for (ItemStack drop : Block.getDrops(state, level, vegetation, null, mob, AXE)) {
            ItemStack left = mob.collect(drop);
            if (!left.isEmpty()) mob.spawnAtLocation(left);
        }
        level.levelEvent(2001, vegetation, Block.getId(state));
        level.destroyBlock(vegetation, false, mob);
        r.lastRealWork = now;
        vegetation = null;
        return true;
    }

    /**
     * Дерево выросло из саженца, посаженного игроком (у крупных деревьев 2×2 саженец мог стоять в соседней клетке).
     * Проверяются все аванпосты рядом: декоративное дерево игрока не рубят ни свои, ни соседские лесорубы.
     */
    private static boolean plantedByPlayer(OutpostSavedData data, BlockPos base) {
        for (Outpost other : data.near(base.getX(), base.getZ(), 160)) {
            if (other.playerPlaced.isEmpty()) continue;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    if (other.placedByPlayer(base.offset(dx, 0, dz))) return true;
        }
        return false;
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
        if (felled) return clearCrown(level, o, r, now);
        if (logs.isEmpty()) {
            // За стенами дерево сажают заново: саженец этой породы лесоруб берёт со склада, если он там есть.
            if (!saplingSupplied && !o.insideWalls(tree) && treeLog != null) {
                Item sapling = saplingFor(treeLog).asItem();
                if (sapling != Items.AIR && countCarried(sapling) == 0 && Logistics.available(level, o, sapling, null) > 0) {
                    Supply s = supply(level, o, r, sapling, 1, 0, OutpostTask.Type.FETCH_SAPLING, now);
                    if (s == Supply.BUSY) return false;
                }
                saplingSupplied = true;
            }
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
        felled = true;
        timer = 4;
        return false;
    }

    /** Обрывает природную листву срубленного дерева (саженцы, палки, яблоки — по ванильной добыче), затем сажает саженец. */
    private boolean clearCrown(ServerLevel level, Outpost o, Resident r, long now) {
        if (!leaves.isEmpty()) {
            if (--timer > 0) return false;
            timer = 4;
            BlockPos leaf = leaves.pollFirst();
            BlockState state = level.getBlockState(leaf);
            if (state.getBlock() instanceof LeavesBlock && !state.getValue(LeavesBlock.PERSISTENT)) {
                mob.swing(InteractionHand.MAIN_HAND);
                for (ItemStack drop : Block.getDrops(state, level, leaf, null, mob, AXE)) {
                    ItemStack left = mob.collect(drop);
                    if (!left.isEmpty()) mob.spawnAtLocation(left);
                }
                level.destroyBlock(leaf, false, mob);
            }
            return false;
        }
        replant(level, tree);
        o.claimedTrees.remove(tree);
        tree = null;
        felled = false;
        return true;
    }

    /** Саженец той же породы, что и бревно. */
    private static Block saplingFor(Block log) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(log);
        String path = id.getPath().replace("stripped_", "").replace("_log", "_sapling").replace("_wood", "_sapling");
        if (path.startsWith("mangrove")) path = "mangrove_propagule";
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), path)).orElse(Blocks.OAK_SAPLING);
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
        // Крона: природная листва в пределах 4 блоков от брёвен этого дерева.
        Set<BlockPos> crown = new HashSet<>();
        for (BlockPos p : found) {
            for (BlockPos n : BlockPos.betweenClosed(p.offset(-3, -1, -3), p.offset(3, 3, 3))) {
                if (crown.size() >= 96) break;
                BlockState s = level.getBlockState(n);
                if (s.getBlock() instanceof LeavesBlock && !s.getValue(LeavesBlock.PERSISTENT) && n.getY() >= base.getY()) crown.add(n.immutable());
            }
        }
        List<BlockPos> sorted = new ArrayList<>(crown);
        sorted.sort(Comparator.comparingInt((BlockPos p) -> p.getY()).reversed());
        leaves.addAll(sorted);
    }

    /**
     * Сажает настоящий саженец из ноши (своей породы, иначе любой подходящий) — только за стенами
     * и только если дереву есть где расти: над местом свободно, рядом нет чужих стволов.
     */
    private void replant(ServerLevel level, BlockPos base) {
        // Внутри стен деревья расчищают под двор и постройки; саженцы возвращаются только за пределами форта.
        Outpost o = mob.outpost();
        if ((o != null && o.insideWalls(base)) || treeLog == null || !level.getBlockState(base).isAir()
                || !level.getBlockState(base.below()).is(BlockTags.DIRT)) return;
        for (int dy = 1; dy <= 5; dy++) {
            BlockState above = level.getBlockState(base.above(dy));
            if (!above.isAir() && !(above.getBlock() instanceof LeavesBlock)) return;
        }
        for (BlockPos n : BlockPos.betweenClosed(base.offset(-1, 0, -1), base.offset(1, 1, 1))) {
            if (level.getBlockState(n).is(BlockTags.LOGS)) return;
        }
        Item preferred = saplingFor(treeLog).asItem();
        Item chosen = null;
        if (countCarried(preferred) > 0) chosen = preferred;
        else {
            for (int i = 0; i < mob.inventory.getContainerSize() && chosen == null; i++) {
                ItemStack st = mob.inventory.getItem(i);
                if (st.is(net.minecraft.tags.ItemTags.SAPLINGS) && st.getItem() instanceof net.minecraft.world.item.BlockItem bi
                        && bi.getBlock().defaultBlockState().canSurvive(level, base)) chosen = st.getItem();
            }
        }
        if (!(chosen instanceof net.minecraft.world.item.BlockItem sapling)) return;
        BlockState state = sapling.getBlock().defaultBlockState();
        if (!state.canSurvive(level, base)) return;
        removeCarried(chosen, 1);
        level.setBlockAndUpdate(base, state);
        level.playSound(null, base, SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.8f, 1f);
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

    /** Чем шахтёр заделывает воду и лаву: только блоки, которые он сам несёт. */
    private static final Item[] SEAL_BLOCKS = {Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.DEEPSLATE, Items.ANDESITE,
            Items.DIORITE, Items.GRANITE, Items.TUFF, Items.DIRT, Items.NETHERRACK};
    /** Шахтёру нужно запастись факелами перед сменой; до этого тика он не повторяет неудачную попытку. */
    private boolean needTorches;
    private long torchRetryAt;

    private int sealsCarried() {
        int n = 0;
        for (Item i : SEAL_BLOCKS) n += countCarried(i);
        return n;
    }

    @Nullable
    private BlockState takeSealBlock() {
        for (Item i : SEAL_BLOCKS) {
            if (countCarried(i) > 0 && i instanceof net.minecraft.world.item.BlockItem bi) {
                removeCarried(i, 1);
                return bi.getBlock().defaultBlockState();
            }
        }
        return null;
    }

    /**
     * Факелы на смену: готовые со склада, а если их нет — шахтёр сам делает их у склада по ванильному рецепту
     * из настоящих угля и бревна (бревно → доски → палки; уголь + палка → 4 факела). Остаток палок идёт на склад.
     */
    private Supply torches(ServerLevel level, Outpost o, Resident r, long now) {
        if (countCarried(Items.TORCH) > 0) return Supply.READY;
        if (task == null) {
            int ready = Logistics.available(level, o, Items.TORCH, null);
            if (ready > 0) {
                task = Logistics.request(level, o, r, OutpostTask.Type.FETCH_TORCHES, java.util.Map.of(Items.TORCH, Math.min(16, ready)), now);
            } else {
                Item coal = Logistics.available(level, o, Items.COAL, null) >= 2 ? Items.COAL
                        : Logistics.available(level, o, Items.CHARCOAL, null) >= 2 ? Items.CHARCOAL : null;
                Item log = Storehouse.findItem(level, o, st -> st.is(net.minecraft.tags.ItemTags.LOGS));
                if (coal == null || log == null || Logistics.available(level, o, log, null) < 1) return Supply.NONE;
                java.util.Map<Item, Integer> want = new java.util.LinkedHashMap<>();
                want.put(coal, 2);
                want.put(log, 1);
                task = Logistics.request(level, o, r, OutpostTask.Type.FETCH_TORCHES, want, now);
            }
            if (task == null) return Supply.NONE;
        }
        if (!go(o, o.zone(WorkZone.STOREHOUSE), 1.8, 500)) return Supply.BUSY;
        OutpostTask t = task;
        task = null;
        if (!Logistics.withdraw(level, o, t, mob, now)) {
            Logistics.cancel(o, t, "withdraw failed", now);
            return Supply.NONE;
        }
        Item coal = t.carried.containsKey(Items.COAL) ? Items.COAL : t.carried.containsKey(Items.CHARCOAL) ? Items.CHARCOAL : null;
        Item log = null;
        for (Item i : t.carried.keySet()) if (new ItemStack(i).is(net.minecraft.tags.ItemTags.LOGS)) log = i;
        if (coal != null && log != null && countCarried(coal) >= 2 && countCarried(log) >= 1) {
            removeCarried(coal, 2);
            removeCarried(log, 1);
            mob.swing(InteractionHand.MAIN_HAND);
            for (ItemStack made : new ItemStack[]{new ItemStack(Items.TORCH, 8), new ItemStack(Items.STICK, 6)}) {
                ItemStack left = mob.collect(made);
                if (!left.isEmpty()) mob.spawnAtLocation(left);
            }
            level.playSound(null, mob.blockPosition(), SoundEvents.WOOD_PLACE, SoundSource.NEUTRAL, 0.6f, 1.0f);
        }
        Logistics.complete(o, t, now);
        return countCarried(Items.TORCH) > 0 ? Supply.READY : Supply.NONE;
    }

    /** Кирки от лучшей к худшей; тир 0 — деревянная, 1 — каменная, 2 — железная, 3 — алмазная. */
    private static final Item[] PICKAXES = {Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE};

    public static int pickaxeTier(Item item) {
        return item == Items.DIAMOND_PICKAXE ? 3 : item == Items.IRON_PICKAXE ? 2 : item == Items.STONE_PICKAXE ? 1 : 0;
    }

    /** Лучшая кирка в ноше, которую шахтёру позволяют уровень и тир форта. */
    @Nullable
    private ItemStack carriedPickaxe(int maxTier) {
        ItemStack best = null;
        for (int i = 0; i < mob.inventory.getContainerSize(); i++) {
            ItemStack st = mob.inventory.getItem(i);
            if (st.isEmpty() || !st.is(net.minecraft.tags.ItemTags.PICKAXES) || pickaxeTier(st.getItem()) > maxTier) continue;
            if (best == null || pickaxeTier(st.getItem()) > pickaxeTier(best.getItem())) best = st;
        }
        return best;
    }

    /**
     * Кирка на смену: лучшая допустимая со склада (с её износом). Если на складе нет ни одной, шахтёр сам делает
     * каменную у склада по ванильному рецепту: 3 булыжника + палки из бревна (остаток палок идёт на склад).
     * Железную и алмазную куёт кузнец по заказу планировщика.
     */
    private Supply pickaxe(ServerLevel level, Outpost o, Resident r, long now) {
        int max = WorkerEntity.minerToolTier(o, r);
        if (carriedPickaxe(max) != null) return Supply.READY;
        if (task == null) {
            Item best = null;
            for (Item p : PICKAXES) if (pickaxeTier(p) <= max && Logistics.available(level, o, p, null) > 0) { best = p; break; }
            if (best != null) {
                task = Logistics.request(level, o, r, OutpostTask.Type.FETCH_PICKAXE, java.util.Map.of(best, 1), now);
            } else {
                Item log = Storehouse.findItem(level, o, st -> st.is(net.minecraft.tags.ItemTags.LOGS));
                if (log == null || Logistics.available(level, o, log, null) < 1 || Logistics.available(level, o, Items.COBBLESTONE, null) < 3) return Supply.NONE;
                java.util.Map<Item, Integer> want = new java.util.LinkedHashMap<>();
                want.put(Items.COBBLESTONE, 3);
                want.put(log, 1);
                task = Logistics.request(level, o, r, OutpostTask.Type.FETCH_PICKAXE, want, now);
            }
            if (task == null) return Supply.NONE;
        }
        if (!go(o, o.zone(WorkZone.STOREHOUSE), 1.8, 500)) return Supply.BUSY;
        OutpostTask t = task;
        task = null;
        if (!Logistics.withdraw(level, o, t, mob, now)) {
            Logistics.cancel(o, t, "withdraw failed", now);
            return Supply.NONE;
        }
        Item log = null;
        for (Item i : t.carried.keySet()) if (new ItemStack(i).is(net.minecraft.tags.ItemTags.LOGS)) log = i;
        if (log != null && countCarried(Items.COBBLESTONE) >= 3 && countCarried(log) >= 1) {
            removeCarried(Items.COBBLESTONE, 3);
            removeCarried(log, 1);
            mob.swing(InteractionHand.MAIN_HAND);
            for (ItemStack made : new ItemStack[]{new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STICK, 6)}) {
                ItemStack left = mob.collect(made);
                if (!left.isEmpty()) mob.spawnAtLocation(left);
            }
            level.playSound(null, mob.blockPosition(), SoundEvents.WOOD_PLACE, SoundSource.NEUTRAL, 0.6f, 1.0f);
        }
        Logistics.complete(o, t, now);
        return carriedPickaxe(max) != null ? Supply.READY : Supply.NONE;
    }

    /** Показывает в руке ту кирку, которой шахтёр реально работает (сама кирка лежит в ноше). */
    private void showPickaxe(@Nullable ItemStack pick) {
        ItemStack hand = mob.getMainHandItem();
        if (pick == null) {
            if (!hand.isEmpty()) mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        } else if (!hand.is(pick.getItem())) {
            mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(pick.getItem()));
        }
    }

    /** Добывает блок настоящей киркой: дроп по её свойствам, износ кирки, опыт шахтёра. */
    private void dig(ServerLevel level, Resident r, BlockPos p, BlockState s, ItemStack pick, long now) {
        for (ItemStack drop : Block.getDrops(s, level, p, level.getBlockEntity(p), mob, pick)) {
            ItemStack left = mob.collect(drop);
            if (!left.isEmpty()) mob.spawnAtLocation(left);
        }
        level.destroyBlock(p, false, mob);
        pick.hurtAndBreak(1, level, mob, item -> {});
        mob.inventory.setChanged();
        r.miningXp = Math.min(500, r.miningXp + 1);
        r.lastRealWork = now;
    }

    private boolean mine(ServerLevel level, Outpost o, Resident r, long now) {
        List<Column> cols = minePlan(level, o);
        if (column < 0 || column >= cols.size()) return true;
        Column c = cols.get(column);
        if (timer < 0) {
            Supply tool = pickaxe(level, o, r, now);
            if (tool == Supply.BUSY) return false;
            if (tool == Supply.NONE) {
                // Без кирки камень не добыть: шахтёр сообщает планировщику и ждёт кирку от кузнеца.
                o.zones.report(WorkZone.MINE, "no pickaxe");
                showPickaxe(null);
                noJobUntil = now + 1200;
                return true;
            }
            o.zones.clearProblem(WorkZone.MINE);
        }
        ItemStack pick = carriedPickaxe(WorkerEntity.minerToolTier(o, r));
        if (pick == null) return true;
        showPickaxe(pick);
        if (needTorches && timer < 0) {
            Supply s = torches(level, o, r, now);
            if (s == Supply.BUSY) return false;
            needTorches = false;
            if (s == Supply.NONE) torchRetryAt = now + 6000;
        }
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
        // Вода и лава, подступившие к штреку, заделываются камнем из ноши: штрек не затопит, и его не придётся бросать.
        // Нечем заделать — копать опасно: столбец пропускается, а не вскрывается.
        BlockPos lower = c.floor, upper = c.floor.above();
        List<BlockPos> leaks = new ArrayList<>();
        for (BlockPos p : new BlockPos[]{lower, upper}) {
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (n.equals(lower) || n.equals(upper) || n.equals(c.stand) || n.equals(c.stand.above()) || leaks.contains(n)) continue;
                if (!level.getFluidState(n).isEmpty()) leaks.add(n);
            }
        }
        if (leaks.size() > sealsCarried()) {
            if (column == o.mineCursor) {
                o.mineCursor++;
                OutpostSavedData.get(level).setDirty();
            }
            return !claimColumn(level, o);
        }
        for (BlockPos n : leaks) {
            BlockState seal = takeSealBlock();
            if (seal == null) break;
            level.setBlockAndUpdate(n, seal);
        }
        for (BlockPos p : new BlockPos[]{upper, lower}) {
            BlockState s = level.getBlockState(p);
            if (!s.getFluidState().isEmpty() && s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                continue;
            }
            if (s.isAir() || s.getDestroySpeed(level, p) < 0 || o.design().cells.containsKey(p.subtract(o.center))) continue;
            if (pick.isEmpty()) break;
            dig(level, r, p, s, pick, now);
            // Записываем прогресс шахтёра и курс штрека в SavedData, а не ждём следующего макроцикла.
            OutpostSavedData.get(level).setDirty();
        }
        // Руда, вскрытая в стенах, потолке или полу столбца, добывается сразу: железо идёт из настоящей руды.
        int ores = 0;
        for (BlockPos p : new BlockPos[]{lower, upper}) {
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (ores >= 4 || pick.isEmpty() || n.equals(lower) || n.equals(upper) || n.equals(c.stand) || n.equals(c.stand.above())) continue;
                BlockState ns = level.getBlockState(n);
                if (!ns.is(net.neoforged.neoforge.common.Tags.Blocks.ORES) || o.design().cells.containsKey(n.subtract(o.center))) continue;
                // За рудой может стоять вода или лава: такую жилу не вскрывают.
                boolean wet = false;
                for (Direction e : Direction.values()) if (!level.getFluidState(n.relative(e)).isEmpty()) wet = true;
                if (wet) continue;
                dig(level, r, n, ns, pick, now);
                ores++;
            }
        }
        if (pick.isEmpty()) showPickaxe(null);
        // Факел — только настоящий, из ноши.
        if (c.torch && countCarried(Items.TORCH) > 0 && level.getBlockState(lower).isAir()
                && level.getBlockState(lower.below()).isFaceSturdy(level, lower.below(), Direction.UP)) {
            removeCarried(Items.TORCH, 1);
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

    private static final int CULL = 1, SHEAR = 2, BREED = 3, FETCH = 4, MILK = 5, EGGS = 6;
    /** Корм по ванильным правилам: что подходит животному, решает оно само (Animal.isFood). */
    private static final Item[] FEEDS = {Items.WHEAT, Items.CARROT, Items.POTATO, Items.BEETROOT,
            Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS, Items.MELON_SEEDS, Items.PUMPKIN_SEEDS};
    /** Сколько полных вёдер молока держать на складе; больше скотовод не доит. */
    private static final int MILK_STOCK = 4;
    /** Сколько тиков даётся на одно действие у загона, прежде чем скотовод его бросит (без телепортации). */
    private static final int HERD_PATIENCE = 900;

    /** Животные, которых не удалось привести: их не трогают до указанного тика. */
    private final java.util.Map<UUID, Long> unreachableAnimals = new java.util.HashMap<>();
    @Nullable private ItemEntity egg;
    @Nullable private Item feed;
    /** 0 — снабжение со склада, далее этапы похода в загон и обратно. */
    private int herdStage;

    private enum Supply { READY, BUSY, NONE }

    @Nullable
    private static BlockPos penGate(ServerLevel level, Outpost o) {
        if (!o.hasZone(WorkZone.PEN)) return null;
        BlockPos pen = o.zone(WorkZone.PEN);
        int size = ChunkReconciliation.penSize(o);
        for (BlockPos p : BlockPos.betweenClosed(pen.offset(-size, 0, -size), pen.offset(size, 0, size))) {
            if (level.getBlockState(p).getBlock() instanceof FenceGateBlock) return p.immutable();
        }
        return null;
    }

    /** Точка снаружи у калитки загона. */
    private static BlockPos outsideGate(Outpost o, BlockPos gate) {
        BlockPos pen = o.zone(WorkZone.PEN);
        int dx = gate.getX() - pen.getX(), dz = gate.getZ() - pen.getZ();
        return Math.abs(dx) >= Math.abs(dz) ? gate.offset(Integer.signum(dx), 0, 0) : gate.offset(0, 0, Integer.signum(dz));
    }

    private static AABB penBox(Outpost o) {
        BlockPos pen = o.zone(WorkZone.PEN);
        int size = ChunkReconciliation.penSize(o) - 1;
        return new AABB(pen.getX() - size, pen.getY() - 1, pen.getZ() - size, pen.getX() + size + 1, pen.getY() + 3, pen.getZ() + size + 1);
    }

    /** Неприкосновенный запас фермера: пшеница и семена для следующего посева. */
    private static int feedReserve(Item f) {
        return f == Items.WHEAT ? FARM_WHEAT_RESERVE : f == Items.WHEAT_SEEDS ? FARM_SEED_RESERVE : 0;
    }

    /** Корм для этого животного, который есть в ноше или который можно взять со склада, не трогая запас фермера. */
    @Nullable
    private Item feedFor(ServerLevel level, Outpost o, Animal a, int count) {
        for (Item f : FEEDS) {
            if (!a.isFood(new ItemStack(f))) continue;
            int carried = countCarried(f);
            if (carried >= count || Logistics.available(level, o, f, null) - feedReserve(f) >= count - carried) return f;
        }
        return null;
    }

    private boolean canSupply(ServerLevel level, Outpost o, Item item) {
        return countCarried(item) > 0 || Logistics.available(level, o, item, null) > 0;
    }

    /**
     * Нужный предмет должен оказаться в ноше. Если его нет, житель идёт на склад и берёт его через логистику:
     * резерв, выдача реального предмета из бочек. READY — предмет в ноше, BUSY — житель в пути, NONE — взять негде.
     */
    private Supply supply(ServerLevel level, Outpost o, Resident r, Item item, int count, int reserve, OutpostTask.Type type, long now) {
        int carried = countCarried(item);
        if (carried >= count) return Supply.READY;
        if (task == null) {
            int want = count - carried;
            if (Logistics.available(level, o, item, null) - reserve < want) return Supply.NONE;
            task = Logistics.request(level, o, r, type, java.util.Map.of(item, want), now);
            if (task == null) return Supply.NONE;
        }
        if (!go(o, o.zone(WorkZone.STOREHOUSE), 1.8, 500)) return Supply.BUSY;
        boolean ok = Logistics.withdraw(level, o, task, mob, now);
        if (ok) Logistics.complete(o, task, now);
        else Logistics.cancel(o, task, "withdraw failed", now);
        task = null;
        return ok ? Supply.BUSY : Supply.NONE;
    }

    /** Идёт только ногами: без запасного переноса. Застревание решается терпением задания (HERD_PATIENCE). */
    private boolean walkTo(BlockPos target, double reach) {
        double d = mob.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        if (d <= reach * reach) {
            if (!mob.getNavigation().isDone()) mob.getNavigation().stop();
            return true;
        }
        if (mob.getNavigation().isDone() || mob.tickCount % 20 == 0) {
            mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
        }
        return false;
    }

    private static void setGate(ServerLevel level, BlockPos gate, boolean open) {
        BlockState gs = level.getBlockState(gate);
        if (!(gs.getBlock() instanceof FenceGateBlock) || gs.getValue(FenceGateBlock.OPEN) == open) return;
        level.setBlockAndUpdate(gate, gs.setValue(FenceGateBlock.OPEN, open));
        level.playSound(null, gate, open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 0.8f, 1f);
    }

    private void holdFeed(boolean show) {
        if (show && feed != null) mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(feed));
        else if (!mob.getMainHandItem().is(Items.BOW)) mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
    }

    private boolean planHerd(ServerLevel level, Outpost o, long now) {
        animal = null;
        breedingPartner = null;
        egg = null;
        feed = null;
        herdStage = 0;
        timer = 0;
        if (!o.hasZone(WorkZone.PEN)) return false;
        if (penGate(level, o) == null) {
            o.zones.report(WorkZone.PEN, "no gate");
            return false;
        }
        o.zones.clearProblem(WorkZone.PEN);
        unreachableAnimals.entrySet().removeIf(e -> e.getValue() <= now);
        List<Animal> inside = ChunkReconciliation.penAnimals(level, o);
        int cap = o.penCapacity();
        // 1. Излишек — на мясо.
        if (inside.size() > cap || (inside.size() == cap && o.stock(Resource.FOOD) < o.residents.size() * 4)) {
            animal = inside.stream().filter(a -> !a.isBaby())
                    .max(Comparator.comparingLong(a -> inside.stream().filter(b -> b.getType() == a.getType()).count())).orElse(null);
            herdAction = CULL;
            if (animal != null) return true;
        }
        // 2. Яйца в загоне.
        egg = level.getEntitiesOfClass(ItemEntity.class, penBox(o).inflate(0.5, 1, 0.5), e -> e.isAlive() && e.getItem().is(Items.EGG))
                .stream().findFirst().orElse(null);
        if (egg != null) {
            herdAction = EGGS;
            return true;
        }
        // 3. Стрижка — настоящими ножницами.
        if (canSupply(level, o, Items.SHEARS)) {
            for (Animal a : inside) if (a instanceof Sheep sh && sh.readyForShearing()) { animal = a; herdAction = SHEAR; return true; }
        }
        // 4. Дойка — пустым ведром, пока на складе мало молока.
        if (canSupply(level, o, Items.BUCKET) && Storehouse.countItem(level, o, Items.MILK_BUCKET) < MILK_STOCK) {
            for (Animal a : inside) if (a instanceof Cow && !a.isBaby()) { animal = a; herdAction = MILK; return true; }
        }
        // 5. Разведение — по одному настоящему корму на каждое животное пары.
        if (inside.size() < cap && now >= o.penActionAt) {
            for (Animal a : inside) {
                if (a.isBaby() || a.isInLove() || !a.canFallInLove() || a.getAge() != 0) continue;
                for (Animal b : inside) {
                    if (b != a && b.getType() == a.getType() && !b.isBaby() && !b.isInLove() && b.canFallInLove() && b.getAge() == 0) {
                        Item f = feedFor(level, o, a, 2);
                        if (f == null) continue;
                        animal = a;
                        breedingPartner = b;
                        feed = f;
                        herdAction = BREED;
                        return true;
                    }
                }
            }
        }
        // 6. Пригнать новое животное: приманить кормом в руке и провести через калитку.
        if (inside.size() < Math.min(cap, 4)) {
            BlockPos pen = o.zone(WorkZone.PEN);
            Animal best = null;
            double bestD = Double.MAX_VALUE;
            for (Animal a : level.getEntitiesOfClass(Animal.class, new AABB(pen).inflate(32, 8, 32))) {
                if (!(a instanceof Cow || a instanceof Sheep || a instanceof Pig || a instanceof Chicken) || a.isBaby() || a.isLeashed()
                        || a.hasCustomName() || inside.contains(a) || unreachableAnimals.containsKey(a.getUUID())
                        || a.getPersistentData().contains(NpcLifecycle.CARAVAN_TAG)) continue;
                double d = a.distanceToSqr(mob);
                if (d < bestD && feedFor(level, o, a, 1) != null) {
                    bestD = d;
                    best = a;
                }
            }
            if (best != null) {
                animal = best;
                feed = feedFor(level, o, best, 1);
                herdAction = FETCH;
                return true;
            }
        }
        return false;
    }

    private boolean herd(ServerLevel level, Outpost o, Resident r, long now) {
        BlockPos gate = penGate(level, o);
        if (gate == null) return true;
        BlockPos outside = outsideGate(o, gate);
        boolean targetGone = herdAction == EGGS ? egg == null || !egg.isAlive() : animal == null || !animal.isAlive();
        if (++timer > HERD_PATIENCE) {
            // Не вышло за отведённое время: бросить, закрыть калитку и не возвращаться к этому животному какое-то время.
            if (animal != null) unreachableAnimals.put(animal.getUUID(), now + 2400);
            holdFeed(false);
            // Калитку закрывает только тот, кто снаружи: запереть самого себя в загоне — значит застрять.
            if (!penBox(o).contains(mob.position())) setGate(level, gate, false);
            return true;
        }
        // 0. Снабжение со склада: корм, ножницы, ведро.
        if (herdStage == 0 && !targetGone) {
            Supply s = switch (herdAction) {
                case SHEAR -> supply(level, o, r, Items.SHEARS, 1, 0, OutpostTask.Type.FETCH_TOOLS, now);
                case MILK -> supply(level, o, r, Items.BUCKET, 1, 0, OutpostTask.Type.FETCH_TOOLS, now);
                case BREED -> supply(level, o, r, feed, 2, feedReserve(feed), OutpostTask.Type.FETCH_FEED, now);
                case FETCH -> supply(level, o, r, feed, 1, feedReserve(feed), OutpostTask.Type.FETCH_FEED, now);
                default -> Supply.READY;
            };
            if (s == Supply.NONE) return true;
            if (s == Supply.BUSY) return false;
            herdStage = 1;
        }
        if (herdAction == FETCH) return lure(level, o, r, gate, outside, now);

        // Действия внутри загона: войти через калитку, сделать дело рядом с животным, выйти и закрыть калитку.
        if (herdStage == 1) {
            if (targetGone) return true;
            if (!walkTo(outside, 1.3)) return false;
            setGate(level, gate, true);
            herdStage = 2;
        }
        if (herdStage == 2) {
            if (!targetGone) {
                BlockPos spot = herdAction == EGGS ? egg.blockPosition() : animal.blockPosition();
                mob.getLookControl().setLookAt(spot.getX() + 0.5, spot.getY() + 0.5, spot.getZ() + 0.5);
                if (!walkTo(spot, herdAction == EGGS ? 1.2 : 2.0)) return false;
                mob.swing(InteractionHand.MAIN_HAND);
                if (penAct(level, o, r, now)) r.lastRealWork = now;
            }
            herdStage = 3;
        }
        // 3. Выйти и закрыть калитку.
        holdFeed(false);
        if (!walkTo(outside, 1.3)) return false;
        setGate(level, gate, false);
        return true;
    }

    /** Само действие у животного. Возвращает true, если работа сделана. */
    private boolean penAct(ServerLevel level, Outpost o, Resident r, long now) {
        switch (herdAction) {
            case CULL -> {
                animal.hurt(mob.damageSources().mobAttack(mob), Float.MAX_VALUE);
                return true;
            }
            case EGGS -> {
                ItemStack left = mob.collect(egg.getItem());
                if (left.isEmpty()) egg.discard();
                else egg.setItem(left);
                level.playSound(null, mob.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.3f, 1.2f);
                return true;
            }
            case SHEAR -> {
                if (!(animal instanceof Sheep sheep) || !sheep.readyForShearing()) return false;
                ItemStack shears = carriedStack(Items.SHEARS);
                if (shears == null) return false;
                sheep.shear(SoundSource.NEUTRAL);
                // Ножницы изнашиваются как у игрока.
                shears.hurtAndBreak(1, level, mob, item -> {});
                mob.inventory.setChanged();
                for (ItemEntity wool : level.getEntitiesOfClass(ItemEntity.class, sheep.getBoundingBox().inflate(1.5), e -> e.getAge() < 5)) {
                    ItemStack left = mob.collect(wool.getItem());
                    if (left.isEmpty()) wool.discard();
                    else wool.setItem(left);
                }
                return true;
            }
            case MILK -> {
                if (!(animal instanceof Cow) || animal.isBaby() || countCarried(Items.BUCKET) == 0) return false;
                removeCarried(Items.BUCKET, 1);
                ItemStack left = mob.collect(new ItemStack(Items.MILK_BUCKET));
                if (!left.isEmpty()) mob.spawnAtLocation(left);
                level.playSound(null, animal.blockPosition(), SoundEvents.COW_MILK, SoundSource.NEUTRAL, 0.8f, 1f);
                return true;
            }
            case BREED -> {
                if (breedingPartner == null || !breedingPartner.isAlive() || feed == null
                        || !animal.canFallInLove() || !breedingPartner.canFallInLove()
                        || animal.getAge() != 0 || breedingPartner.getAge() != 0
                        || mob.distanceToSqr(breedingPartner) > 3.5 * 3.5 || countCarried(feed) < 2) return false;
                // Каждое животное съедает по одному настоящему корму из ноши.
                removeCarried(feed, 2);
                animal.setInLove(null);
                breedingPartner.setInLove(null);
                level.playSound(null, animal.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.6f, 1f);
                o.penActionAt = now + 1200;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Перегон: скотовод держит в руке корм, который ест это животное, и оно идёт за ним, как за игроком с кормом,
     * до калитки; там он открывает калитку и загоняет животное на свободное место в загоне, сам оставаясь снаружи.
     * Корм при этом не тратится.
     */
    private boolean lure(ServerLevel level, Outpost o, Resident r, BlockPos gate, BlockPos outside, long now) {
        if (animal == null || !animal.isAlive() || feed == null || countCarried(feed) == 0) {
            holdFeed(false);
            setGate(level, gate, false);
            return true;
        }
        holdFeed(herdStage < 4);
        boolean inPen = ChunkReconciliation.penAnimals(level, o).contains(animal);
        double dist = animal.distanceTo(mob);
        BlockPos pen = o.zone(WorkZone.PEN);
        double gx = gate.getX() + 0.5, gy = gate.getY(), gz = gate.getZ() + 0.5;
        // Пока животное не у калитки — ведём его кормом в руке, как игрок; у калитки — загоняем внутрь, оставаясь снаружи.
        if (herdStage < 3 && dist <= 10) {
            animal.getLookControl().setLookAt(mob, 30f, 30f);
            if (dist > 2.5 && mob.tickCount % 10 == 0) animal.getNavigation().moveTo(mob, 1.1);
        }
        switch (herdStage) {
            case 1 -> {
                // Подойти к животному, чтобы оно заметило корм.
                if (walkTo(animal.blockPosition(), 4.0)) herdStage = 2;
            }
            case 2 -> {
                if (dist > 12) herdStage = 1;
                else if (walkTo(outside, 1.3) && animal.distanceToSqr(gx, gy, gz) <= 4.0 * 4.0) {
                    setGate(level, gate, true);
                    herdStage = 3;
                }
            }
            case 3 -> {
                // Загнать: животное идёт на свободную клетку подальше от калитки, скотовод стоит в проходе снаружи.
                boolean deep = inPen && animal.distanceToSqr(gx, gy, gz) > 1.6 * 1.6;
                if (deep) {
                    herdStage = 4;
                } else if (animal.distanceToSqr(gx, gy, gz) > 8 * 8) {
                    herdStage = 1;
                } else {
                    walkTo(outside, 1.0);
                    mob.getLookControl().setLookAt(animal);
                    if (mob.tickCount % 10 == 0) {
                        BlockPos spot = freePenCell(level, o, pen, gate);
                        animal.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.0);
                    }
                }
            }
            default -> {
                // Животное в загоне: убрать корм и закрыть калитку (скотовод снаружи).
                if (walkTo(outside, 1.3)) {
                    setGate(level, gate, false);
                    if (ChunkReconciliation.penAnimals(level, o).contains(animal)) {
                        animal.setPersistenceRequired();
                        r.lastRealWork = now;
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /** Свободная клетка загона, дальняя от калитки (не занятая другими животными). */
    private static BlockPos freePenCell(ServerLevel level, Outpost o, BlockPos pen, BlockPos gate) {
        int inner = ChunkReconciliation.penSize(o) - 1;
        BlockPos best = pen;
        double bestD = -1;
        for (int dx = -inner; dx <= inner; dx++)
            for (int dz = -inner; dz <= inner; dz++) {
                BlockPos cell = pen.offset(dx, 0, dz);
                if (!level.getEntitiesOfClass(Animal.class, new AABB(cell)).isEmpty()) continue;
                double d = cell.distSqr(gate);
                if (d > bestD) {
                    bestD = d;
                    best = cell;
                }
            }
        return best;
    }

    @Nullable
    private ItemStack carriedStack(Item item) {
        for (int i = 0; i < mob.inventory.getContainerSize(); i++) {
            ItemStack st = mob.inventory.getItem(i);
            if (st.is(item)) return st;
        }
        return null;
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

    // ============================== Кузнец ==============================

    /**
     * Кузнец берёт задачу с общей доски: свою незавершённую или самую важную свободную (ремонт важнее перековки —
     * это решает планировщик приоритетом). Материалы под неё резервируются на складе.
     */
    private boolean findSmithTask(ServerLevel level, Outpost o, Resident r, long now) {
        OutpostTask t = o.tasks.ofExecutor(r.id);
        // Заказ на инструмент берётся, только когда планировщик уже отложил под него материалы (вместе с бревном на рукоять).
        if (t == null) t = o.tasks.nextFor(Role.SMITH, x -> x.type != OutpostTask.Type.FORGE_TOOL || !x.reserved.isEmpty());
        if (t == null) return smeltPlan(level, o, r, now);
        boolean forge = t.type == OutpostTask.Type.FORGE_TOOL;
        Resident target = t.subject == null ? null : o.resident(t.subject);
        if (forge ? t.product == null : (target == null || !target.active() || !target.role.combat)) {
            o.tasks.set(t, OutpostTask.State.CANCELLED, "obsolete", now);
            return false;
        }
        if (t.reserved.isEmpty() && t.carried.isEmpty()) {
            var want = OutpostDirector.materials(t, target);
            boolean spare = t.type != OutpostTask.Type.UPGRADE_GEAR
                    || (Logistics.available(level, o, Items.IRON_INGOT, t) >= want.get(Items.IRON_INGOT) + OutpostDirector.SMITH_IRON_RESERVE
                    && Logistics.available(level, o, Items.LEATHER, t) >= want.get(Items.LEATHER) + OutpostDirector.SMITH_LEATHER_RESERVE);
            if (!spare || !Logistics.reserve(level, o, t, want, now)) {
                noJobUntil = now + 200;
                return false;
            }
        }
        t.executor = r.id;
        t.updatedAt = now;
        task = t;
        return true;
    }

    /**
     * Кузнец забирает отложенные слитки и кожу со склада в ношу, несёт их в кузницу, работает у горна
     * и расходует ровно эти предметы. Результат (починка или новый уровень снаряжения) применяется один раз.
     */
    private boolean smith(ServerLevel level, Outpost o, Resident worker, long now) {
        if (task == null || !task.active()) return true;
        boolean forge = task.type == OutpostTask.Type.FORGE_TOOL;
        Resident target = task.subject == null ? null : o.resident(task.subject);
        if (forge ? task.product == null : (target == null || !target.active() || !target.role.combat)) {
            Logistics.cancel(o, task, "obsolete", now);
            task = null;
            return true;
        }
        task.updatedAt = now;
        var materials = OutpostDirector.materials(task, target);
        if (task.carried.isEmpty()) {
            // 1. На склад за материалами.
            if (!go(o, o.zone(WorkZone.STOREHOUSE), 1.8, 500)) return false;
            if (!Logistics.withdraw(level, o, task, mob, now)) {
                Logistics.cancel(o, task, task.reason.isEmpty() ? "withdraw failed" : task.reason, now);
                task = null;
                noJobUntil = now + 200;
                return true;
            }
            mob.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, mob.blockPosition(), SoundEvents.BARREL_OPEN, SoundSource.NEUTRAL, 0.6f, 1.0f);
            return false;
        }
        // 2. В кузницу.
        BlockPos anvil = o.hasZone(WorkZone.SMITHY) ? o.zone(WorkZone.SMITHY) : o.marker(Marker.STORAGE);
        if (!go(o, anvil, 1.6, 500)) return false;
        if (mob.tickCount % 20 == 0) {
            mob.swing(InteractionHand.MAIN_HAND);
            mob.playSound(mob.tickCount % 80 == 0 ? SoundEvents.SMITHING_TABLE_USE : SoundEvents.ANVIL_USE, 0.5f, 0.9f + mob.getRandom().nextFloat() * 0.2f);
        }
        // Та же шкала smithWork используется вдали: загрузка чанка не удваивает производство.
        o.smithWork = Math.min(8, o.smithWork + 30.0 / 24000.0);
        if (o.smithWork < 1) return false;
        // 3. Расход именно взятых предметов: сначала проверить всё, потом списать.
        for (var e : materials.entrySet()) {
            if (countCarried(e.getKey()) < e.getValue()) {
                Logistics.cancel(o, task, "materials lost", now);
                task = null;
                return true;
            }
        }
        for (var e : materials.entrySet()) Logistics.consume(o, task, mob, e.getKey(), e.getValue(), now);
        o.smithWork -= 1;
        if (forge) {
            // Готовый инструмент — настоящий предмет: в ношу кузнеца, а с ней на склад.
            ItemStack made = new ItemStack(task.product);
            ItemStack left = mob.collect(made);
            if (!left.isEmpty()) mob.spawnAtLocation(left);
            // Рукоять из бревна: из 8 палок ушли 2, остальные — на склад.
            boolean log = false;
            for (Item i : materials.keySet()) if (new ItemStack(i).is(net.minecraft.tags.ItemTags.LOGS)) log = true;
            if (log) {
                ItemStack sticks = mob.collect(new ItemStack(Items.STICK, 6));
                if (!sticks.isEmpty()) mob.spawnAtLocation(sticks);
            }
            level.playSound(null, mob.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.NEUTRAL, 0.7f, 1.1f);
            Logistics.complete(o, task, now);
            task = null;
        } else {
            OutpostDirector.applySmithResult(o, task, target);
            Logistics.complete(o, task, now);
            task = null;
            if (target.entity != null && level.getEntity(target.entity) instanceof FighterEntity fighter) {
                fighter.refreshResidentEquipment();
            }
        }
        worker.lastRealWork = now;
        OutpostSavedData.get(level).setDirty();
        return true;
    }

    private void removeCarried(Item item, int count) {
        for (int i = 0; i < mob.inventory.getContainerSize() && count > 0; i++) {
            ItemStack st = mob.inventory.getItem(i);
            if (!st.is(item)) continue;
            int n = Math.min(count, st.getCount());
            st.shrink(n);
            count -= n;
        }
        mob.inventory.setChanged();
    }

    // ============================== Лавка торговца ==============================

    /**
     * Торговец сначала возвращает на склад товар, которого поселению сейчас не хватает, потом пополняет лавку
     * излишками склада (теми же, что годятся для каравана). Всё через логистику, реальными предметами.
     */
    private boolean findShopTask(ServerLevel level, Outpost o, Resident r, long now) {
        if (!com.vladosiwe.lordsoutpost.data.Shop.hasShop(o)) return false;
        ItemStack back = com.vladosiwe.lordsoutpost.data.Shop.takeBackScarce(o);
        if (back != null) {
            ItemStack left = mob.collect(back);
            if (!left.isEmpty()) com.vladosiwe.lordsoutpost.data.Shop.stock(o, left);
            OutpostSavedData.get(level).setDirty();
            // Найденной работы нет: житель с ношей пойдёт её разгружать.
            return false;
        }
        var want = com.vladosiwe.lordsoutpost.data.Shop.restock(level, o);
        if (want.isEmpty()) return false;
        task = Logistics.request(level, o, r, OutpostTask.Type.STOCK_SHOP, want, now);
        if (task == null) return false;
        task.zone = WorkZone.SHOP;
        return true;
    }

    private boolean shop(ServerLevel level, Outpost o, Resident r, long now) {
        if (task == null || !task.active()) return true;
        if (task.carried.isEmpty()) {
            if (!go(o, o.zone(WorkZone.STOREHOUSE), 1.8, 500)) return false;
            if (!Logistics.withdraw(level, o, task, mob, now)) {
                Logistics.cancel(o, task, "withdraw failed", now);
                task = null;
                return true;
            }
            return false;
        }
        if (!go(o, o.zone(WorkZone.SHOP), 2.0, 500)) return false;
        // Выложить в лавку ровно то, что принесено под эту задачу.
        for (var e : new java.util.ArrayList<>(task.carried.entrySet())) {
            for (int i = 0; i < mob.inventory.getContainerSize(); i++) {
                ItemStack st = mob.inventory.getItem(i);
                if (!st.is(e.getKey())) continue;
                ItemStack left = com.vladosiwe.lordsoutpost.data.Shop.stock(o, st.copy());
                mob.inventory.setItem(i, left);
            }
        }
        mob.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, mob.blockPosition(), SoundEvents.BUNDLE_DROP_CONTENTS, SoundSource.NEUTRAL, 0.6f, 1.0f);
        Logistics.complete(o, task, now);
        task = null;
        r.lastRealWork = now;
        OutpostSavedData.get(level).setDirty();
        return true;
    }

    // ============================== Переплавка в настоящей печи ==============================

    /** Топливо, которое кузнец кладёт в печь (уголь из шахты или древесный). */
    private static final Item[] FUELS = {Items.COAL, Items.CHARCOAL};
    @Nullable private BlockPos smeltFurnace;
    private boolean smeltCollect;

    /** Печь кузницы: сначала доменная (руда плавится вдвое быстрее), потом обычная. */
    @Nullable
    private static net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnaceAt(ServerLevel level, @Nullable BlockPos pos) {
        return pos != null && level.isLoaded(pos) && level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity f ? f : null;
    }

    private static List<BlockPos> smithyFurnaces(ServerLevel level, Outpost o) {
        List<BlockPos> out = new ArrayList<>();
        if (!o.hasZone(WorkZone.SMITHY)) return out;
        BlockPos at = o.zone(WorkZone.SMITHY);
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-3, -1, -3), at.offset(3, 2, 3))) {
            if (level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity) out.add(p.immutable());
        }
        out.sort(Comparator.comparingInt(p -> level.getBlockState(p).is(Blocks.BLAST_FURNACE) ? 0 : 1));
        return out;
    }

    /**
     * Когда заказов нет, кузнец плавит руду: забирает готовые слитки из печей кузницы или загружает в свободную печь
     * сырое железо и уголь со склада. Плавит сама печь — по ванильным правилам и только пока чанк загружен.
     */
    private boolean smeltPlan(ServerLevel level, Outpost o, Resident r, long now) {
        smeltFurnace = null;
        for (BlockPos p : smithyFurnaces(level, o)) {
            var f = furnaceAt(level, p);
            if (f != null && !f.getItem(2).isEmpty()) {
                smeltFurnace = p;
                smeltCollect = true;
                return true;
            }
        }
        if (Logistics.available(level, o, Items.RAW_IRON, null) <= 0) return false;
        for (BlockPos p : smithyFurnaces(level, o)) {
            var f = furnaceAt(level, p);
            if (f == null || !f.getItem(0).isEmpty()) continue;
            boolean fuel = !f.getItem(1).isEmpty();
            for (Item fu : FUELS) if (Logistics.available(level, o, fu, null) > 0) fuel = true;
            if (!fuel) {
                o.zones.report(WorkZone.SMITHY, "no fuel");
                return false;
            }
            smeltFurnace = p;
            smeltCollect = false;
            return true;
        }
        return false;
    }

    private boolean smelt(ServerLevel level, Outpost o, Resident r, long now) {
        var f = furnaceAt(level, smeltFurnace);
        if (f == null) {
            smeltFurnace = null;
            return true;
        }
        if (smeltCollect) {
            if (!go(o, smeltFurnace, 2.2, 500)) return false;
            ItemStack out = f.getItem(2);
            if (!out.isEmpty()) {
                mob.swing(InteractionHand.MAIN_HAND);
                ItemStack left = mob.collect(out.copy());
                f.setItem(2, left);
                f.setChanged();
                level.playSound(null, smeltFurnace, SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 0.6f, 1.0f);
                r.lastRealWork = now;
            }
            smeltFurnace = null;
            return true;
        }
        // Загрузка: сырьё и топливо со склада через логистику, затем в печь.
        if (countCarried(Items.RAW_IRON) == 0) {
            if (task == null) {
                int ore = Math.min(16, Logistics.available(level, o, Items.RAW_IRON, null));
                if (ore <= 0) {
                    smeltFurnace = null;
                    return true;
                }
                java.util.Map<Item, Integer> want = new java.util.LinkedHashMap<>();
                want.put(Items.RAW_IRON, ore);
                int haveFuel = f.getItem(1).getCount() * 8;
                int fuelNeed = Math.max(0, (int) Math.ceil((ore - haveFuel) / 8.0));
                if (fuelNeed > 0) {
                    Item fuel = null;
                    for (Item fu : FUELS) if (Logistics.available(level, o, fu, null) >= fuelNeed && (f.getItem(1).isEmpty() || f.getItem(1).is(fu))) { fuel = fu; break; }
                    if (fuel == null) {
                        o.zones.report(WorkZone.SMITHY, "no fuel");
                        smeltFurnace = null;
                        noJobUntil = now + 600;
                        return true;
                    }
                    want.put(fuel, fuelNeed);
                }
                task = Logistics.request(level, o, r, OutpostTask.Type.SMELT_ORE, want, now);
                if (task == null) {
                    smeltFurnace = null;
                    return true;
                }
                task.zone = WorkZone.SMITHY;
            }
            if (!go(o, o.zone(WorkZone.STOREHOUSE), 1.8, 500)) return false;
            boolean ok = Logistics.withdraw(level, o, task, mob, now);
            if (!ok) {
                Logistics.cancel(o, task, "withdraw failed", now);
                task = null;
                smeltFurnace = null;
                return true;
            }
            return false;
        }
        if (!go(o, smeltFurnace, 2.2, 500)) return false;
        mob.swing(InteractionHand.MAIN_HAND);
        loadSlot(f, 0, Items.RAW_IRON);
        for (Item fu : FUELS) if (countCarried(fu) > 0 && (f.getItem(1).isEmpty() || f.getItem(1).is(fu))) loadSlot(f, 1, fu);
        f.setChanged();
        level.playSound(null, smeltFurnace, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 0.4f, 1.2f);
        if (task != null) {
            Logistics.complete(o, task, now);
            task = null;
        }
        o.zones.clearProblem(WorkZone.SMITHY);
        r.lastRealWork = now;
        smeltFurnace = null;
        return true;
    }

    /** Перекладывает предмет из ноши в слот печи, сколько влезет; остальное остаётся в ноше и вернётся на склад. */
    private void loadSlot(net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity f, int slot, Item item) {
        ItemStack in = f.getItem(slot);
        if (!in.isEmpty() && !in.is(item)) return;
        int room = in.isEmpty() ? item.getDefaultMaxStackSize() : in.getMaxStackSize() - in.getCount();
        int n = Math.min(room, countCarried(item));
        if (n <= 0) return;
        removeCarried(item, n);
        if (in.isEmpty()) f.setItem(slot, new ItemStack(item, n));
        else in.grow(n);
    }

    private int countCarried(Item item) {
        int n = 0;
        for (int i = 0; i < mob.inventory.getContainerSize(); i++) {
            ItemStack st = mob.inventory.getItem(i);
            if (st.is(item)) n += st.getCount();
        }
        return n;
    }

    // ============================== Фермер ==============================

    /** Выбирает созревшую пшеницу раньше пустой грядки, чтобы урожай не пропадал. */
    private boolean findFarmTask(ServerLevel level, Outpost o) {
        BlockPos center = o.zone(WorkZone.FARM);
        if (!level.hasChunk(center.getX() >> 4, center.getZ() >> 4)) return false;
        BlockPos harvest = null;
        BlockPos plant = null;
        BlockPos till = null;
        double harvestDistance = Double.MAX_VALUE;
        double plantDistance = Double.MAX_VALUE;
        double tillDistance = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos crop = center.offset(dx, 0, dz);
                BlockPos soil = crop.below();
                BlockState soilState = level.getBlockState(soil);
                BlockState state = level.getBlockState(crop);
                double distance = mob.distanceToSqr(crop.getX() + 0.5, crop.getY(), crop.getZ() + 0.5);
                if (state.is(Blocks.WHEAT) && state.getValue(CropBlock.AGE) >= 7) {
                    if (distance < harvestDistance) {
                        harvestDistance = distance;
                        harvest = crop.immutable();
                    }
                } else if (state.isAir() && soilState.is(Blocks.FARMLAND) && distance < plantDistance) {
                    plantDistance = distance;
                    plant = crop.immutable();
                } else if (state.isAir() && (soilState.is(Blocks.DIRT) || soilState.is(Blocks.GRASS_BLOCK)) && distance < tillDistance) {
                    tillDistance = distance;
                    till = crop.immutable();
                }
            }
        }
        // Поля нет совсем (зону перенесли на камень или мостовую): фермер сообщает планировщику.
        if (harvest == null && plant == null && till == null) {
            boolean anyField = false;
            for (BlockPos p : BlockPos.betweenClosed(center.offset(-2, -1, -2), center.offset(2, 0, 2))) {
                BlockState st = level.getBlockState(p);
                if (st.is(Blocks.FARMLAND) || st.is(Blocks.WHEAT)) anyField = true;
            }
            if (!anyField) o.zones.report(WorkZone.FARM, "no tillable soil");
        } else {
            o.zones.clearProblem(WorkZone.FARM);
        }
        farmHarvest = harvest != null;
        farmTill = harvest == null && plant == null && till != null;
        farmTarget = harvest != null ? harvest : plant != null ? plant : till;
        return farmTarget != null;
    }

    /** Физически собирает зрелую пшеницу или тратит реальное семя со склада на посадку. */
    private boolean farm(ServerLevel level, Outpost o, Resident r, long now) {
        if (farmTarget == null) return true;
        if (!go(o, farmTarget, 2.2, 300)) return false;
        BlockState crop = level.getBlockState(farmTarget);
        if (farmHarvest && crop.is(Blocks.WHEAT) && crop.getValue(CropBlock.AGE) >= 7) {
            mob.swing(InteractionHand.MAIN_HAND);
            for (ItemStack drop : Block.getDrops(crop, level, farmTarget, null, mob, mob.getMainHandItem())) {
                ItemStack left = mob.collect(drop);
                if (!left.isEmpty()) mob.spawnAtLocation(left);
            }
            level.destroyBlock(farmTarget, false, mob);
            r.lastRealWork = now;
            return true;
        }
        BlockState soil = level.getBlockState(farmTarget.below());
        if (farmTill && crop.isAir() && (soil.is(Blocks.DIRT) || soil.is(Blocks.GRASS_BLOCK))) {
            mob.swing(InteractionHand.MAIN_HAND);
            level.setBlockAndUpdate(farmTarget.below(), Blocks.FARMLAND.defaultBlockState());
            level.playSound(null, farmTarget.below(), SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.7f, 1.0f);
            r.lastRealWork = now;
            return true;
        }
        if (crop.isAir() && soil.is(Blocks.FARMLAND)) {
            if (countCarried(Items.WHEAT_SEEDS) == 0) {
                // Семян в ноше нет: запрос на склад. Неприкосновенный запас семян не выдаётся.
                if (task == null) {
                    int spare = Logistics.available(level, o, Items.WHEAT_SEEDS, null) - FARM_SEED_RESERVE;
                    if (spare <= 0) {
                        noJobUntil = now + 600;
                        return true;
                    }
                    task = Logistics.request(level, o, r, OutpostTask.Type.FETCH_SEEDS, java.util.Map.of(Items.WHEAT_SEEDS, Math.min(8, spare)), now);
                    if (task == null) {
                        noJobUntil = now + 600;
                        return true;
                    }
                    task.zone = WorkZone.FARM;
                }
                if (!go(o, o.zone(WorkZone.STOREHOUSE), 1.8, 500)) return false;
                boolean ok = Logistics.withdraw(level, o, task, mob, now);
                if (ok) Logistics.complete(o, task, now);
                else Logistics.cancel(o, task, "withdraw failed", now);
                task = null;
                if (!ok) {
                    noJobUntil = now + 600;
                    return true;
                }
                return false;
            }
            // Сажается настоящее семя из ноши: собранное с урожая или взятое со склада.
            removeCarried(Items.WHEAT_SEEDS, 1);
            mob.swing(InteractionHand.MAIN_HAND);
            level.setBlockAndUpdate(farmTarget, Blocks.WHEAT.defaultBlockState());
            level.playSound(null, farmTarget, SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.7f, 1.0f);
            r.lastRealWork = now;
            return true;
        }
        return true;
    }

    /** Есть ли место для предмета в ноше, включая объединение с существующей стопкой. */
    private boolean canCarryItem(Item item) {
        for (int slot = 0; slot < mob.inventory.getContainerSize(); slot++) {
            ItemStack stack = mob.inventory.getItem(slot);
            if (stack.isEmpty() || (stack.is(item) && stack.getCount() < stack.getMaxStackSize())) return true;
        }
        return false;
    }

    /** Крафтит хлеб у склада из пшеницы сверх неприкосновенного запаса. */
    private void craftBread(ServerLevel level, Outpost o, Resident r, long now) {
        if (!go(o, o.marker(Marker.STORE_INSIDE), 1.8, 500)) return;
        if (Storehouse.countItem(level, o, Items.WHEAT) < FARM_WHEAT_RESERVE + 3 || !canCarryItem(Items.BREAD)) {
            phase = Phase.DECIDE;
            return;
        }
        if (!Storehouse.takeItem(level, o, Items.WHEAT, 3)) {
            phase = Phase.DECIDE;
            return;
        }
        mob.swing(InteractionHand.MAIN_HAND);
        ItemStack left = mob.collect(new ItemStack(Items.BREAD));
        if (!left.isEmpty()) mob.spawnAtLocation(left);
        level.playSound(null, mob.blockPosition(), SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.7f, 1.0f);
        r.lastRealWork = now;
        phase = Phase.DEPOSIT;
    }

    // ============================== Работа у места (без добычи в мире) ==============================

    private void chore(ServerLevel level, Outpost o, Role role) {
        if (spot == null) spot = workplace(level, o, role);
        if (timer <= 0) {
            if (!go(o, spot, role == Role.SKOTOVOD ? 4.5 : 2.5, 400)) return;
            timer = 140 + mob.getRandom().nextInt(120);
        }
        // Придворный маг работает у стола зачарования: видно, что идёт его работа (и копится шкала в окне «Хозяйство»).
        if (role == Role.MAGE && timer % 10 == 0 && o.tier >= 3 && o.hasZone(WorkZone.MAGE_TOWER)) {
            BlockPos at = o.zone(WorkZone.MAGE_TOWER);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.ENCHANT, at.getX() + 0.5, at.getY() + 1.2, at.getZ() + 0.5,
                    6, 0.6, 0.4, 0.6, 0.6);
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
            case LUMBERJACK -> o.zone(WorkZone.WOODYARD);
            case FARMER -> o.zone(WorkZone.FARM).offset(1, 0, 0);
            case MINER -> o.marker(Marker.MINE);
            case SMITH -> o.design().markers(Marker.SMITHY).isEmpty() ? o.marker(Marker.STORAGE) : o.marker(Marker.SMITHY);
            case MAGE -> o.hasZone(WorkZone.MAGE_TOWER) ? o.zone(WorkZone.MAGE_TOWER) : o.marker(Marker.THRONE);
            case SKOTOVOD -> o.design().markers(Marker.PEN).isEmpty() ? o.marker(Marker.YARD) : o.marker(Marker.PEN);
            case STEWARD -> switch (cycle % 3) {
                case 0 -> o.marker(Marker.THRONE);
                case 1 -> o.marker(Marker.BOARD);
                default -> o.marker(Marker.STORAGE);
            };
            // Торговец стоит в своей лавке.
            case CARAVANEER -> o.zone(WorkZone.SHOP);
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
            case SKOTOVOD, FARMER -> SoundEvents.GRASS_STEP;
            case STEWARD -> SoundEvents.BOOK_PAGE_TURN;
            default -> null;
        };
    }
}
