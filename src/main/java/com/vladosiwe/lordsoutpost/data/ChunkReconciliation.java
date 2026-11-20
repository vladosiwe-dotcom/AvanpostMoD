package com.vladosiwe.lordsoutpost.data;

import com.vladosiwe.lordsoutpost.blueprint.FortBlueprint;
import com.vladosiwe.lordsoutpost.blueprint.Layout;
import com.vladosiwe.lordsoutpost.blueprint.Marker;
import com.vladosiwe.lordsoutpost.blueprint.Step;
import com.vladosiwe.lordsoutpost.compat.MagicLoot;
import com.vladosiwe.lordsoutpost.config.OutpostConfig;
import com.vladosiwe.lordsoutpost.entity.FighterEntity;
import com.vladosiwe.lordsoutpost.entity.OutpostNpc;
import com.vladosiwe.lordsoutpost.entity.WorkerEntity;
import com.vladosiwe.lordsoutpost.outpost.Caravan;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Quest;
import com.vladosiwe.lordsoutpost.outpost.Raid;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Resource;
import com.vladosiwe.lordsoutpost.outpost.Role;
import com.vladosiwe.lordsoutpost.outpost.Squad;
import com.vladosiwe.lordsoutpost.registry.ModEntities;
import com.vladosiwe.lordsoutpost.server.Captures;
import com.vladosiwe.lordsoutpost.server.NpcLifecycle;
import com.vladosiwe.lordsoutpost.server.OutpostManager;
import com.vladosiwe.lordsoutpost.server.Roads;
import com.vladosiwe.lordsoutpost.world.FortPlacer;
import com.vladosiwe.lordsoutpost.world.OutpostSites;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Согласование (Reconciliation): превращает виртуальное состояние аванпоста в блоки и сущности, когда чанки загружены,
 * и следит, чтобы мир не расходился с записью. Всё, что здесь делается, идемпотентно: повторный вызов ничего не ломает,
 * поэтому сбой, перезапуск сервера или выгрузка посреди работы не оставляют дублей и дыр.
 */
public final class ChunkReconciliation {
    private static final long CLAIM_TIME = 240;
    private static final int BACKLOG = 40;

    private ChunkReconciliation() {}

    public static void tick(ServerLevel level, OutpostSavedData data, long now) {
        List<Outpost> removed = null;
        for (Outpost o : data.all()) {
            if (Math.floorMod(now + o.id, 20L) == 0) {
                boolean on = isLoaded(level, o);
                if (on && !o.online) {
                    o.online = true;
                    o.onlineSince = now;
                    o.penSpawned = false;
                    if (!arrive(level, data, o, now)) {
                        if (removed == null) removed = new ArrayList<>();
                        removed.add(o);
                        continue;
                    }
                } else if (!on && o.online) {
                    o.online = false;
                    o.claims.clear();
                    o.deferredSteps.clear();
                    o.stepFailures.clear();
                    o.projectClaims.clear();
                }
                if (o.online) syncResidents(level, data, o, now);
                if (o.state == OutpostState.OWNED) {
                    retinue(level, o, now);
                    caravans(level, data, o, now);
                    if (o.online) com.vladosiwe.lordsoutpost.compat.Pyromancer.tick(level, o, now);
                }
            }
            if (o.online) online(level, data, o, now);
        }
        if (removed != null) for (Outpost o : removed) data.remove(o);
    }

    public static boolean isLoaded(ServerLevel level, Outpost o) {
        if (!level.isPositionEntityTicking(o.center)) return false;
        int r = o.reach() + Layout.MARGIN;
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2)
                if (!level.hasChunk((o.center.getX() + sx * r) >> 4, (o.center.getZ() + sz * r) >> 4)) return false;
        return true;
    }

    // ============================== Возвращение игрока ==============================

    /**
     * Чанки форта только что загрузились: мир приводится в соответствие с записью.
     * Возвращает false, если форта в мире нет и запись нужно удалить (чанки созданы до установки мода).
     */
    private static boolean arrive(ServerLevel level, OutpostSavedData data, Outpost o, long now) {
        if (!o.manual && !o.verified) {
            o.verified = true;
            if (!structurePresent(level, o)) {
                if (OutpostConfig.get(OutpostConfig.RETROFIT, false)) {
                    o.basePlaced = false;
                    o.groundTier = 0;
                } else {
                    data.markRegionEmpty(OutpostSavedData.regionOf(o.center.getX()), OutpostSavedData.regionOf(o.center.getZ()));
                    return false;
                }
            }
        }
        FortBlueprint bp = o.blueprint();
        // Лава и вода во дворе (озеро сгенерировалось раньше форта или натекло): убираем, кроме воды самого чертежа.
        int cleared = com.vladosiwe.lordsoutpost.world.FortPlacer.clearFluids(level, o.center, o.reach() + Layout.MARGIN, null,
                rel -> { for (int t = 1; t <= FortBlueprint.MAX_TIER; t++) if (bp.design(t).cells.containsKey(rel)) return true; return false; });
        if (cleared > 0) com.vladosiwe.lordsoutpost.LordsOutpost.LOGGER.info("Форт #{}: убрано {} блоков жидкости с площадки", Long.toHexString(o.id), cleared);
        if (!o.basePlaced) {
            int baseTier = o.difficulty.tier;
            int baseSteps = Outpost.initialSteps(bp, o.difficulty);
            int top = baseSteps > 0 ? baseTier + 1 : baseTier;
            levelGround(level, o, 0, top, Integer.MAX_VALUE);
            FortPlacer.place(level, o.center, bp, o.difficulty, bp.snapshot(baseTier, baseSteps), null, level.random);
            reshape(level, o, bp.snapshot(baseTier, baseSteps).keySet());
            o.basePlaced = true;
            o.groundTier = top;
            o.groundCursor = 0;
            o.placedTier = baseTier + 1;
            o.placed = baseSteps;
        }
        // Тиры, достроенные без игрока: доставляем хвосты их планов.
        o.doneBits.clear();
        o.claims.clear();
        o.deferredSteps.clear();
        o.stepFailures.clear();
        while (o.placedTier <= o.tier) {
            ensureGround(level, o, o.placedTier, Integer.MAX_VALUE);
            List<Step> plan = bp.plan(o.placedTier);
            com.vladosiwe.lordsoutpost.blueprint.Design before = bp.design(o.placedTier - 1);
            for (int i = o.placed; i < plan.size(); i++) placeTail(level, o, plan.get(i), before);
            o.placedTier++;
            o.placed = 0;
        }
        if (!o.complete()) {
            List<Step> plan = o.nextPlan();
            if (o.steps > o.placed) ensureGround(level, o, o.placedTier, Integer.MAX_VALUE);
            for (int i = o.placed; i < Math.min(o.steps, plan.size()); i++) placeTail(level, o, plan.get(i), o.design());
            o.placed = Math.min(o.steps, plan.size());
        }
        if (o.state == OutpostState.WILD && !o.lootSeeded) seedTreasure(level, o);
        Projects.arrive(level, o);
        data.setDirty();
        return true;
    }

    private static boolean structurePresent(ServerLevel level, Outpost o) {
        Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(OutpostSites.STRUCTURE);
        if (structure == null) return false;
        ChunkAccess chunk = level.getChunk(o.center.getX() >> 4, o.center.getZ() >> 4, ChunkStatus.STRUCTURE_STARTS, false);
        if (chunk == null) return false;
        StructureStart start = chunk.getStartForStructure(structure);
        if (start != null && start.isValid()) return true;
        // Начало структуры хранится в чанке-кандидате сетки; если центр оказался в соседнем чанке,
        // форт всё равно стоит — его видно по ссылкам структуры в чанке центра.
        StructureStart byReference = level.structureManager().getStructureAt(o.center.above(), structure);
        return byReference != null && byReference.isValid();
    }

    /** Редкая добыча в сундуке Капитана. */
    private static void seedTreasure(ServerLevel level, Outpost o) {
        o.lootSeeded = true;
        if (!(level.getBlockEntity(o.marker(Marker.TREASURE)) instanceof Container chest)) return;
        int skulls = o.difficulty.skulls;
        List<ItemStack> loot = new ArrayList<>();
        loot.add(new ItemStack(Items.EMERALD, 4 * skulls + level.random.nextInt(4)));
        loot.add(new ItemStack(Items.IRON_INGOT, 3 + 2 * skulls));
        loot.add(new ItemStack(Items.GOLD_INGOT, 1 + skulls));
        if (skulls >= 3) loot.add(new ItemStack(Items.DIAMOND, skulls - 2));
        if (skulls >= 3) loot.add(new ItemStack(Items.EXPERIENCE_BOTTLE, 2 * skulls));
        if (skulls >= 4) loot.add(new ItemStack(Items.GOLDEN_APPLE, skulls - 3));
        if (skulls >= 5) loot.add(new ItemStack(Items.TOTEM_OF_UNDYING));
        if (skulls >= 4) loot.addAll(MagicLoot.captainScrolls(level, level.random));
        int slot = 0;
        for (ItemStack stack : loot) {
            while (slot < chest.getContainerSize() && !chest.getItem(slot).isEmpty()) slot++;
            if (slot >= chest.getContainerSize()) break;
            chest.setItem(slot++, stack);
        }
        chest.setChanged();
    }

    // ============================== Пока игрок рядом ==============================

    private static void online(ServerLevel level, OutpostSavedData data, Outpost o, long now) {
        if (o.placedTier <= o.tier) {
            // Тир завершился прямо сейчас (последний блок ещё не стоял) — доводим до конца.
            arrive(level, data, o, now);
        }
        o.buildClock = now;
        if (!o.complete() && o.placedTier == o.tier + 1) {
            if (o.groundTier < o.placedTier && (o.steps > o.placed || o.state == OutpostState.OWNED)) ensureGround(level, o, o.placedTier, 24);
            List<Step> plan = o.nextPlan();
            if (!o.claims.isEmpty()) {
                List<Integer> expired = null;
                for (var e : o.claims.entrySet()) {
                    if (e.getValue() <= now) {
                        if (expired == null) expired = new ArrayList<>();
                        expired.add(e.getKey());
                    }
                }
                if (expired != null) {
                    boolean hasBuilder = o.activeCount(Role.BUILDER) > 0;
                    for (int idx : expired) {
                        if (hasBuilder) releaseClaim(o, idx);
                        else placeOrYield(level, o, idx, true);
                    }
                }
            }
            // Пока есть активные строители, блоки ставят только они. Автодостройка — страховка для форта без строителей.
            int limit = Math.min(o.steps, plan.size());
            if (o.activeCount(Role.BUILDER) == 0 && o.groundTier >= o.placedTier
                    && limit - o.placed - o.claims.size() > BACKLOG) {
                int idx = nextFree(o, limit);
                if (idx >= 0) placeOrYield(level, o, idx, false);
            }
        }
        long phase = Math.floorMod(now + o.id, 40L);
        if (o.state == OutpostState.OWNED) {
            if (phase == 7) containers(level, o, now);
            if (Math.floorMod(now + o.id, 200L) == 11) pen(level, o);
            if (phase == 13) repair(level, o);
            if (o.raid != null && phase % 20 == 3) raid(level, data, o, now);
        }
    }

    // ---------- Стройка ----------

    public static boolean hasWork(Outpost o) {
        if (!o.online || o.complete() || o.placedTier != o.tier + 1 || o.groundTier < o.placedTier) return false;
        return nextFree(o, Math.min(o.steps, o.nextPlan().size())) >= 0;
    }

    private static int nextFree(Outpost o, int limit) {
        for (int i = o.placed; i < limit; i++) {
            if (o.doneBits.get(i) || o.claims.containsKey(i)) continue;
            Long retry = o.deferredSteps.get(i);
            if (retry != null && retry > o.buildClock) continue;
            return i;
        }
        return -1;
    }

    /**
     * Строитель не смог добраться до шага: шаг откладывается с растущей паузой (3 с, 6 с, 12 с … до 5 мин),
     * чтобы остальные шаги шли своим чередом, а невозможная задача не повторялась каждый тик.
     */
    public static void deferStep(Outpost o, int index, long now) {
        if (index < 0) return;
        o.claims.remove(index);
        int fails = o.stepFailures.merge(index, 1, Integer::sum);
        long delay = 60L << Math.min(6, fails - 1);
        o.deferredSteps.put(index, now + Math.min(6000L, delay));
        if (fails == 6) {
            List<Step> plan = o.nextPlan();
            if (index < plan.size()) {
                BlockPos p = o.world(plan.get(index).pos());
                com.vladosiwe.lordsoutpost.LordsOutpost.LOGGER.info("Форт #{}: шаг стройки {} у {} недоступен строителям, отложен",
                        Long.toHexString(o.id), index, p.toShortString());
            }
        }
    }

    /** Сколько раз этот шаг уже откладывался. */
    public static int stepFailures(Outpost o, int index) {
        return o.stepFailures.getOrDefault(index, 0);
    }

    /**
     * Клетку шага занимает постройка игрока: чертёж уступает. Шаг считается выполненным без установки блока,
     * поэтому стройка идёт дальше, а постройка игрока остаётся на месте.
     */
    public static void yieldToPlayer(Outpost o, int index) {
        List<Step> plan = o.nextPlan();
        o.claims.remove(index);
        if (index < 0 || index >= plan.size() || o.placedTier != o.tier + 1) return;
        int i = index;
        do {
            if (i >= o.placed) {
                o.doneBits.set(i);
                o.claims.remove(i);
                o.deferredSteps.remove(i);
                o.stepFailures.remove(i);
            }
            i++;
        } while (i < plan.size() && plan.get(i).chain());
        advancePlaced(o);
    }

    private static void advancePlaced(Outpost o) {
        while (o.doneBits.get(o.placed)) {
            o.doneBits.clear(o.placed);
            o.deferredSteps.remove(o.placed);
            o.stepFailures.remove(o.placed);
            o.placed++;
        }
    }

    /** Строитель резервирует следующий доступный шаг чертежа. */
    public static int claim(Outpost o, long now) {
        if (!hasWork(o)) return -1;
        int idx = nextFree(o, Math.min(o.steps, o.nextPlan().size()));
        if (idx >= 0) o.claims.put(idx, now + CLAIM_TIME);
        return idx;
    }

    /** Продлевает заявку, пока строитель действительно продолжает работать над этим шагом. */
    public static boolean renewClaim(Outpost o, int index, long now) {
        if (index < 0 || !o.claims.containsKey(index)) return false;
        o.claims.put(index, now + CLAIM_TIME);
        return true;
    }

    /** Освобождает шаг, если строитель не смог до него добраться или был прерван. */
    public static void releaseClaim(@Nullable Outpost o, int index) {
        if (o != null && index >= 0) o.claims.remove(index);
    }

    /** Признаки естественной растительности/мусора, который строитель может расчистить без разрушения построек. */
    public static boolean isNaturalObstruction(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.SAPLINGS) || state.is(BlockTags.REPLACEABLE_BY_TREES)
                || (state.canBeReplaced() && state.getFluidState().isEmpty() && !state.is(BlockTags.CROPS));
    }

    /** Естественный грунт можно заменить только там, где чертёж действительно строит блок, но не на пустом месте. */
    private static boolean isNaturalTerrain(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM);
    }

    private static boolean isNaturalObstruction(BlockState current, BlockState desired) {
        return isNaturalObstruction(current) || (!desired.isAir() && isNaturalTerrain(current));
    }

    /** Не даём строителю перезаписывать блоки игрока, которые не принадлежат предыдущему чертежу форта. */
    public static boolean blockedByForeignBlock(ServerLevel level, Outpost o, int index) {
        List<Step> plan = o.nextPlan();
        if (index < 0 || index >= plan.size()) return true;
        Step step = plan.get(index);
        return !mayOverwrite(level, o, o.world(step.pos()), step.pos(), step.state(), o.design());
    }

    /**
     * Можно ли чертежу занять клетку: она пуста, в ней уже нужный блок, блок прежнего тира форта, жидкость
     * или естественная помеха. Всё, что поставил игрок, и любые блоки с содержимым чертёж не трогает.
     */
    public static boolean mayOverwrite(ServerLevel level, Outpost o, BlockPos pos, BlockPos rel, BlockState desired,
                                       @Nullable com.vladosiwe.lordsoutpost.blueprint.Design previous) {
        BlockState old = level.getBlockState(pos);
        if (old.isAir() || old.equals(desired)) return true;
        if (o.placedByPlayer(pos)) return false;
        var prev = previous == null ? null : previous.cells.get(rel);
        if (prev != null && prev.state().getBlock() == old.getBlock()) return true;
        if (old.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return true;
        return level.getBlockEntity(pos) == null && isNaturalObstruction(old, desired);
    }

    /** Ставит шаг, выполненный без игрока, только если клетка не занята чужой постройкой. */
    private static void placeTail(ServerLevel level, Outpost o, Step step, com.vladosiwe.lordsoutpost.blueprint.Design before) {
        BlockPos pos = o.world(step.pos());
        if (level.isOutsideBuildHeight(pos)) return;
        if (!mayOverwrite(level, o, pos, step.pos(), step.state(), before)) return;
        placeStep(level, o, step, false);
        o.clearPlayerMarks(pos);
    }

    /**
     * Естественный рельеф и растительность, которые можно срезать при выравнивании площадки.
     * Всё прочее (доски, кирпич, стекло, блоки с содержимым) считается постройкой и остаётся на месте.
     */
    public static boolean naturalTerrain(BlockState s) {
        return s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD)
                || s.is(BlockTags.SAND) || s.is(BlockTags.SNOW) || s.is(BlockTags.ICE) || s.is(BlockTags.TERRACOTTA)
                || s.is(net.neoforged.neoforge.common.Tags.Blocks.ORES) || s.is(net.neoforged.neoforge.common.Tags.Blocks.GRAVELS)
                || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS) || s.is(BlockTags.REPLACEABLE) || s.is(BlockTags.REPLACEABLE_BY_TREES)
                || s.is(Blocks.CLAY) || s.is(Blocks.CACTUS) || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.BAMBOO) || s.is(Blocks.PUMPKIN)
                || s.is(Blocks.MELON) || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE)
                || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.MUSHROOM_STEM)
                || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POWDER_SNOW)
                || s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock;
    }

    /** Строитель вручную убирает естественную растительность, попавшую в ячейку нового здания. */
    public static boolean clearNaturalObstruction(ServerLevel level, Outpost o, int index, WorkerEntity builder) {
        List<Step> plan = o.nextPlan();
        if (index < 0 || index >= plan.size()) return false;
        return clearNaturalObstruction(level, o, plan.get(index), o.design(), builder);
    }

    /** То же для любого шага (в том числе шага заказанной постройки, где прежнего чертежа нет). */
    public static boolean clearNaturalObstruction(ServerLevel level, Outpost o, Step step,
                                                  @Nullable com.vladosiwe.lordsoutpost.blueprint.Design before, WorkerEntity builder) {
        BlockPos pos = o.world(step.pos());
        BlockState old = level.getBlockState(pos);
        if (old.isAir() || old.equals(step.state()) || level.getBlockEntity(pos) != null || o.placedByPlayer(pos)
                || !isNaturalObstruction(old, step.state())) return false;
        var previous = before == null ? null : before.cells.get(step.pos());
        if (previous != null && previous.state() == old) return false;
        builder.swing(InteractionHand.MAIN_HAND);
        for (ItemStack drop : Block.getDrops(old, level, pos, null, builder, builder.getMainHandItem())) {
            ItemStack left = builder.collect(drop);
            if (!left.isEmpty()) builder.spawnAtLocation(left);
        }
        level.levelEvent(2001, pos, Block.getId(old));
        level.destroyBlock(pos, false, builder);
        return true;
    }

    /** Автодостройка без строителей: ставит шаг, но клетку с постройкой игрока оставляет как есть. */
    private static void placeOrYield(ServerLevel level, Outpost o, int index, boolean effects) {
        if (blockedByForeignBlock(level, o, index)) yieldToPlayer(o, index);
        else place(level, o, index, effects);
    }

    /** Ставит шаг текущего плана вместе с привязанными к нему (вторая половина кровати). */
    public static void place(ServerLevel level, Outpost o, int index, boolean effects) {
        List<Step> plan = o.nextPlan();
        o.claims.remove(index);
        if (index < 0 || index >= plan.size() || o.placedTier != o.tier + 1) return;
        int i = index;
        do {
            if (i >= o.placed && !o.doneBits.get(i)) {
                placeStep(level, o, plan.get(i), effects);
                o.clearPlayerMarks(o.world(plan.get(i).pos()));
                o.doneBits.set(i);
                o.claims.remove(i);
            }
            i++;
        } while (i < plan.size() && plan.get(i).chain());
        advancePlaced(o);
    }

    public static void placeStep(ServerLevel level, Outpost o, Step step, boolean effects) {
        BlockPos pos = o.world(step.pos());
        if (level.isOutsideBuildHeight(pos)) return;
        BlockState state = step.state();
        if (FortBlueprint.shapeSensitive(state)) state = Block.updateFromNeighbourShapes(state, level, pos);
        BlockState old = level.getBlockState(pos);
        if (old == state) return;
        // Содержимое сундука, который сносят при перестройке, не пропадает: оно уходит в дань.
        // Бочки склада — исключение: их содержимое уже учтено в запасе и выложится в новый склад.
        if (level.getBlockEntity(pos) instanceof Container container) {
            boolean stockpile = o.markers(Marker.STOCKPILE).contains(pos);
            if (stockpile) Storehouse.forgetBarrel(o, container);
            for (int s = 0; s < container.getContainerSize(); s++) {
                ItemStack stack = container.removeItemNoUpdate(s);
                if (stockpile) {
                    ResourceItems.Value v = ResourceItems.of(stack);
                    if (v != null && Storehouse.physical(v.resource())) continue;
                }
                if (!stack.isEmpty()) Simulation.addTribute(o, stack);
            }
        }
        if (state.isAir() && effects && !old.isAir()) level.levelEvent(2001, pos, Block.getId(old));
        level.setBlock(pos, state, Block.UPDATE_ALL);
        if (effects && !state.isAir()) {
            var sound = state.getSoundType(level, pos, null);
            level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, 0.7f, sound.getPitch() * 0.9f);
        }
        if (!state.isAir() && (step.pos().getY() == 0 || step.pos().getY() == 1)) {
            BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
            for (int d = 1; d <= 8; d++) {
                below.set(pos.getX(), pos.getY() - d, pos.getZ());
                BlockState under = level.getBlockState(below);
                if (!under.isAir() && under.getFluidState().isEmpty() && !under.canBeReplaced()) break;
                level.setBlock(below, o.palette.foundation.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    /** После массовой установки пересчитывает формы заборов, стен и ступеней. */
    private static void reshape(ServerLevel level, Outpost o, Iterable<BlockPos> cells) {
        for (BlockPos rel : cells) {
            BlockPos pos = o.world(rel);
            BlockState state = level.getBlockState(pos);
            if (!FortBlueprint.shapeSensitive(state)) continue;
            BlockState fixed = Block.updateFromNeighbourShapes(state, level, pos);
            if (fixed != state) level.setBlock(pos, fixed, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
    }

    // ---------- Площадка ----------

    private static void ensureGround(ServerLevel level, Outpost o, int tier, int budget) {
        if (o.groundTier >= tier) return;
        if (levelGround(level, o, o.groundTier, tier, budget)) {
            o.groundTier = tier;
            o.groundCursor = 0;
        }
    }

    /**
     * Выравнивает площадку под периметр тира: срезает всё выше уровня двора и подсыпает землю в ямы.
     * Обрабатывается кольцо между старым и новым периметром, не больше budget колонок за вызов.
     *
     * @return true, когда площадка готова
     */
    private static boolean levelGround(ServerLevel level, Outpost o, int fromTier, int toTier, int budget) {
        int outer = Layout.RADIUS[toTier] + Layout.MARGIN - 1;
        int inner = fromTier <= 0 ? -1 : Layout.RADIUS[fromTier] + Layout.MARGIN - 1;
        int side = 2 * outer + 1;
        int total = side * side;
        int baseY = o.center.getY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (o.groundCursor < total && budget > 0) {
            int idx = o.groundCursor++;
            int x = o.center.getX() - outer + idx % side, z = o.center.getZ() - outer + idx / side;
            if (!o.insideHorizontally(x, z, outer) || (inner >= 0 && o.insideHorizontally(x, z, inner))) continue;
            budget--;
            for (int y = baseY + 26; y > baseY; y--) {
                pos.set(x, y, z);
                BlockState s = level.getBlockState(pos);
                // Срезается только естественный рельеф: постройки игрока за прежними стенами остаются.
                if (s.isAir() || o.placedByPlayer(pos) || level.getBlockEntity(pos) != null || !naturalTerrain(s)) continue;
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            for (int d = 0; d <= 8; d++) {
                pos.set(x, baseY - d, z);
                BlockState s = level.getBlockState(pos);
                if (!s.isAir() && s.getFluidState().isEmpty() && !s.canBeReplaced()) break;
                if (o.placedByPlayer(pos)) break;
                level.setBlock(pos, (d == 0 ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        return o.groundCursor >= total;
    }

    // ---------- Ремонт ----------

    /** Проверяет несколько блоков готовой части форта; выбитые восстанавливаются за счёт склада. */
    private static void repair(ServerLevel level, Outpost o) {
        var cells = o.design().cells;
        if (cells.isEmpty() || o.placedTier != o.tier + 1) return;
        if (!o.repairs.isEmpty()) {
            BlockPos rel = o.repairs.pollFirst();
            var cell = cells.get(rel);
            if (cell != null && !overridden(o, rel) && !o.removedByPlayer(o.world(rel))) {
                Resource cost = FortBlueprint.costOf(cell.state());
                if (cost == null || o.take(cost, 1)) placeStep(level, o, new Step(rel, cell.state(), false, cost), true);
                else o.repairs.addLast(rel);
            }
            return;
        }
        // Обход по одному слою за раз: достаточно редко, чтобы не грузить сервер, и достаточно часто, чтобы пролом заделали за минуты.
        int n = 0;
        Iterator<BlockPos> it = cells.keySet().iterator();
        int skip = o.repairCursor;
        while (it.hasNext() && skip-- > 0) it.next();
        while (it.hasNext() && n++ < 48) {
            BlockPos rel = it.next();
            o.repairCursor++;
            var cell = cells.get(rel);
            if (cell.state().isAir() || rel.getY() <= 0 || overridden(o, rel) || o.removedByPlayer(o.world(rel))) continue;
            Block want = cell.state().getBlock();
            if (!(want == Blocks.COBBLESTONE || FortBlueprint.costOf(cell.state()) != null)) continue;
            if (level.getBlockState(o.world(rel)).isAir() && o.repairs.size() < 64) o.repairs.addLast(rel);
        }
        if (!it.hasNext()) o.repairCursor = 0;
    }

    /** Клетка уже перестроена шагами следующего тира — чинить её по старому чертежу нельзя. */
    private static boolean overridden(Outpost o, BlockPos rel) {
        if (o.complete() || o.steps == 0) return false;
        var next = o.blueprint().design(o.tier + 1).cells.get(rel);
        var cur = o.design().cells.get(rel);
        return next == null || cur == null || next.state() != cur.state();
    }

    // ---------- Сундуки ----------

    private static void containers(ServerLevel level, Outpost o, long now) {
        Simulation.flushTributeGold(o);
        if (!o.tribute.isEmpty() && level.getBlockEntity(o.marker(Marker.TRIBUTE)) instanceof Container chest) {
            for (Iterator<ItemStack> it = o.tribute.iterator(); it.hasNext(); ) {
                ItemStack stack = it.next();
                insert(chest, stack);
                if (stack.isEmpty()) it.remove();
            }
            chest.setChanged();
        }
        Storehouse.sync(level, o, now);
    }

    private static void insert(Container chest, ItemStack stack) {
        for (int s = 0; s < chest.getContainerSize() && !stack.isEmpty(); s++) {
            ItemStack have = chest.getItem(s);
            if (have.isEmpty()) {
                chest.setItem(s, stack.copy());
                stack.setCount(0);
            } else if (ItemStack.isSameItemSameComponents(have, stack) && have.getCount() < have.getMaxStackSize()) {
                int move = Math.min(stack.getCount(), have.getMaxStackSize() - have.getCount());
                have.grow(move);
                stack.shrink(move);
            }
        }
    }

    // ---------- Загон ----------

    /**
     * Загон. Сразу после прихода игрока в нём появляется столько животных, сколько записано (их развели вдали);
     * дальше запись следует за настоящим загоном, где скотовод сам пригоняет, разводит и забивает скот.
     */
    private static void pen(ServerLevel level, Outpost o) {
        if (o.design().markers(Marker.PEN).isEmpty()) return;
        BlockPos pen = o.marker(Marker.PEN);
        List<Animal> animals = penAnimals(level, o);
        if (!o.penSpawned) {
            o.penSpawned = true;
            for (int i = animals.size(); i < Math.min(o.penAnimals, o.penCapacity()); i++) {
                EntityType<? extends Animal> type = switch (level.random.nextInt(3)) {
                    case 0 -> EntityType.COW;
                    case 1 -> EntityType.SHEEP;
                    default -> EntityType.PIG;
                };
                Animal animal = type.create(level);
                if (animal == null) continue;
                animal.moveTo(pen.getX() + 0.5 + level.random.nextInt(3) - 1, pen.getY(), pen.getZ() + 0.5 + level.random.nextInt(3) - 1, level.random.nextFloat() * 360, 0);
                animal.setPersistenceRequired();
                level.addFreshEntity(animal);
            }
            return;
        }
        o.penAnimals = animals.size();
    }

    public static int penSize(Outpost o) {
        return o.tier == 1 ? 2 : 3;
    }

    /** Скот внутри загона. */
    public static List<Animal> penAnimals(ServerLevel level, Outpost o) {
        BlockPos pen = o.marker(Marker.PEN);
        int size = penSize(o) - 1;
        AABB box = new AABB(pen.getX() - size, pen.getY() - 1, pen.getZ() - size, pen.getX() + size + 1, pen.getY() + 3, pen.getZ() + size + 1);
        return level.getEntitiesOfClass(Animal.class, box, a -> a.isAlive() && (a instanceof Cow || a instanceof Sheep || a instanceof Pig || a instanceof Chicken));
    }

    // ============================== Жители ==============================

    private static void syncResidents(ServerLevel level, OutpostSavedData data, Outpost o, long now) {
        ServerPlayer lord = o.state == OutpostState.OWNED ? NpcLifecycle.lord(level, o) : null;
        boolean retinueOut = lord != null && lord.level() == level && o.squads[Squad.RETINUE].order == Squad.Order.RETINUE;
        for (Resident r : o.residents) {
            Entity entity = r.entity == null ? null : level.getEntity(r.entity);
            boolean present = entity instanceof OutpostNpc npc && npc.isAlive();
            if (!present) r.entity = null;
            boolean should = r.status == Resident.Status.ACTIVE;
            if (should && retinueOut && r.role.combat && r.squad == Squad.RETINUE) continue;
            if (should && !present && now >= r.spawnGrace) {
                spawn(level, o, r, spawnSpot(level, o, r));
            } else if (!should && present && ((OutpostNpc) entity).caravanId == null) {
                entity.discard();
            }
        }
        // Сущности, у которых больше нет записи (гарнизон захваченного форта), уходят.
        int r = o.reach() + 40;
        AABB box = new AABB(o.center).inflate(r, 48, r);
        for (OutpostNpc npc : level.getEntitiesOfClass(OutpostNpc.class, box, n -> n.outpostId() == o.id && n.raidTarget == 0)) {
            if (npc.residentId() == null || o.resident(npc.residentId()) == null) {
                level.sendParticles(ParticleTypes.POOF, npc.getX(), npc.getY() + 1, npc.getZ(), 8, 0.3, 0.5, 0.3, 0.02);
                npc.discard();
            }
        }
    }

    private static BlockPos spawnSpot(ServerLevel level, Outpost o, Resident r) {
        if (r.role == Role.CAPTAIN || r.role == Role.STEWARD) return o.marker(Marker.THRONE);
        if (r.role.combat) return NpcLifecycle.postFor(o, r);
        return switch (r.role) {
            case LUMBERJACK -> o.zone(com.vladosiwe.lordsoutpost.outpost.WorkZone.WOODYARD);
            case MINER -> o.zone(com.vladosiwe.lordsoutpost.outpost.WorkZone.MINE);
            case SKOTOVOD -> o.marker(Marker.STORAGE);
            default -> o.marker(Marker.YARD);
        };
    }

    /** Создаёт сущность по записи жителя. */
    @Nullable
    public static OutpostNpc spawn(ServerLevel level, Outpost o, Resident r, BlockPos at) {
        EntityType<? extends OutpostNpc> type = r.role == Role.CAPTAIN ? ModEntities.CAPTAIN.get() : r.role.combat ? ModEntities.FIGHTER.get() : ModEntities.WORKER.get();
        OutpostNpc npc = type.create(level);
        if (npc == null) return null;
        BlockPos pos = free(level, at);
        npc.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.random.nextFloat() * 360, 0);
        npc.bind(o, r);
        r.spawnGrace = level.getGameTime() + 40;
        if (!level.addFreshEntity(npc)) {
            r.entity = null;
            return null;
        }
        return npc;
    }

    private static BlockPos free(ServerLevel level, BlockPos at) {
        BlockPos.MutableBlockPos pos = at.mutable();
        for (int i = 0; i < 6; i++) {
            if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) break;
            pos.move(0, 1, 0);
        }
        return pos.immutable();
    }

    // ---------- Свита ----------

    private static void retinue(ServerLevel level, Outpost o, long now) {
        if (o.squads[Squad.RETINUE].order != Squad.Order.RETINUE) return;
        ServerPlayer lord = NpcLifecycle.lord(level, o);
        if (lord == null || lord.level() != level || !lord.isAlive() || lord.isSpectator()) return;
        for (Resident r : o.residents) {
            if (!r.role.combat || r.squad != Squad.RETINUE || !r.active()) continue;
            Entity entity = r.entity == null ? null : level.getEntity(r.entity);
            if (entity instanceof OutpostNpc npc && npc.isAlive()) continue;
            r.entity = null;
            if (now < r.spawnGrace) continue;
            OutpostNpc npc = spawn(level, o, r, lord.blockPosition());
            if (npc != null) com.vladosiwe.lordsoutpost.entity.ai.DutyGoal.teleportNear(npc, lord);
        }
    }

    // ============================== Набег ==============================

    private static void raid(ServerLevel level, OutpostSavedData data, Outpost o, long now) {
        Raid raid = o.raid;
        if (raid == null) return;
        raid.entities.removeIf(id -> !(level.getEntity(id) instanceof OutpostNpc npc) || !npc.isAlive());
        if (raid.toSpawn > 0) {
            BlockPos gate = o.marker(Marker.GATE);
            double dx = gate.getX() - o.center.getX(), dz = gate.getZ() - o.center.getZ();
            double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
            Outpost source = data.byId(raid.source);
            int gear = source == null ? 0 : Math.min(Resident.MAX_GEAR, (source.difficulty.skulls - 1) / 2);
            for (int i = 0; i < 2 && raid.toSpawn > 0; i++) {
                double side = (level.random.nextDouble() - 0.5) * 24;
                int x = (int) (gate.getX() + dx / len * 34 - dz / len * side), z = (int) (gate.getZ() + dz / len * 34 + dx / len * side);
                if (!level.hasChunk(x >> 4, z >> 4)) break;
                BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                FighterEntity raider = ModEntities.FIGHTER.get().create(level);
                if (raider == null) break;
                raider.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
                raider.bindRaider(raid.source, o.id, OutpostManager.randomFighter(level.random), gear);
                if (level.addFreshEntity(raider)) {
                    raid.entities.add(raider.getUUID());
                    raid.toSpawn--;
                }
            }
        }
        if (raid.toSpawn <= 0 && raid.entities.isEmpty()) {
            o.raid = null;
            Raids.victory(level, o, raid.source, now, 0);
            Raids.schedule(data, o, now, level.random);
            return;
        }
        // Защищать некому, налётчики хозяйничают: через полминуты набег считается проигранным.
        boolean defended = false;
        for (Resident r : o.residents) if (r.role.combat && r.active()) defended = true;
        if (defended || raid.entities.isEmpty()) {
            o.defenselessSince = 0;
        } else if (o.defenselessSince == 0) {
            o.defenselessSince = now;
        } else if (now - o.defenselessSince > 600) {
            // Копия: при удалении налётчик сам вычёркивает себя из raid.entities (NpcLifecycle.onUnloaded).
            for (UUID id : new ArrayList<>(raid.entities)) if (level.getEntity(id) instanceof OutpostNpc npc) npc.discard();
            o.raid = null;
            o.defenselessSince = 0;
            for (Resource res : new Resource[]{Resource.WOOD, Resource.STONE, Resource.FOOD, Resource.IRON, Resource.HIDES, Resource.GOLD}) {
                o.setStock(res, o.stock(res) * 0.65);
            }
            o.log(now, "lordsoutpost.event.raid_lost", 0, 0);
            com.vladosiwe.lordsoutpost.server.Notify.lord(level.getServer(), o, Component.translatable("lordsoutpost.event.raid_lost", 0, 0));
            Raids.schedule(data, o, now, level.random);
        }
    }

    // ============================== Караваны ==============================

    private static void caravans(ServerLevel level, OutpostSavedData data, Outpost o, long now) {
        for (Iterator<Caravan> it = o.caravans.iterator(); it.hasNext(); ) {
            Caravan c = it.next();
            Outpost target = data.byId(c.target);
            if (target == null) continue;
            if (!c.materialized) {
                Vec3 p = c.position();
                BlockPos at = BlockPos.containing(p.x, o.center.getY(), p.z);
                if (c.phase != Caravan.Phase.TRADING && level.isPositionEntityTicking(at) && playerNearby(level, p.x, p.z, 96)) materialize(level, o, c);
                continue;
            }
            Resident leader = o.resident(c.leader);
            Entity entity = leader == null || leader.entity == null ? null : level.getEntity(leader.entity);
            if (!(entity instanceof OutpostNpc npc) || !npc.isAlive()) {
                dematerialize(level, c, null);
                continue;
            }
            if (c.phase == Caravan.Phase.TRADING) {
                if (now >= c.phaseUntil) {
                    c.phase = Caravan.Phase.RETURNING;
                    c.traveled = 0;
                    c.ambushed = false;
                }
                continue;
            }
            // Пройденный путь — проекция ведущего на прямую между фортами; заодно меряем долю дороги.
            BlockPos a = c.phase == Caravan.Phase.RETURNING ? c.to : c.from;
            BlockPos b = c.destination();
            double len = Math.max(1, c.length());
            double ux = (b.getX() - a.getX()) / len, uz = (b.getZ() - a.getZ()) / len;
            double proj = Math.max(0, Math.min(len, (npc.getX() - a.getX()) * ux + (npc.getZ() - a.getZ()) * uz));
            double delta = Math.max(0, proj - c.traveled);
            if (delta > 0) {
                c.sampledBlocks += delta;
                if (Roads.onRoad(npc)) c.roadBlocks += delta;
                c.traveled = proj;
                o.dirtyForClients = true;
            }
            if (!c.ambushed && delta > 0 && level.random.nextDouble() < 0.03 * delta / 100.0) {
                c.ambushed = true;
                ambush(level, o, npc);
            }
            double dx = npc.getX() - b.getX(), dz = npc.getZ() - b.getZ();
            if (dx * dx + dz * dz < 49) {
                c.traveled = len;
                if (Caravans.arrive(level, data, o, target, c, now)) {
                    dematerialize(level, c, null);
                    it.remove();
                }
            }
        }
    }

    /** Игрок рядом по горизонтали: высота не важна (игрок может быть в горах или в шахте над дорогой каравана). */
    private static boolean playerNearby(ServerLevel level, double x, double z, double range) {
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            double dx = p.getX() - x, dz = p.getZ() - z;
            if (dx * dx + dz * dz <= range * range) return true;
        }
        return false;
    }

    private static void materialize(ServerLevel level, Outpost o, Caravan c) {
        Resident leader = o.resident(c.leader);
        if (leader == null) return;
        Vec3 p = c.position();
        int x = (int) Math.floor(p.x), z = (int) Math.floor(p.z);
        BlockPos at = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        c.entities.clear();
        OutpostNpc lead = spawn(level, o, leader, at);
        if (lead == null) return;
        lead.caravanId = c.id;
        c.entities.add(lead.getUUID());
        Llama llama = EntityType.LLAMA.create(level);
        if (llama != null) {
            llama.moveTo(at.getX() + 1.5, at.getY(), at.getZ() + 0.5, 0, 0);
            llama.setTamed(true);
            llama.setChest(true);
            llama.getPersistentData().putUUID(NpcLifecycle.CARAVAN_TAG, c.id);
            llama.setPersistenceRequired();
            if (level.addFreshEntity(llama)) {
                llama.setLeashedTo(lead, true);
                c.entities.add(llama.getUUID());
            }
        }
        for (UUID id : c.escort) {
            Resident r = o.resident(id);
            if (r == null) continue;
            OutpostNpc guard = spawn(level, o, r, at.offset(level.random.nextInt(5) - 2, 0, level.random.nextInt(5) - 2));
            if (guard != null) {
                guard.caravanId = c.id;
                c.entities.add(guard.getUUID());
            }
        }
        c.materialized = true;
    }

    /** Караван уходит из загруженной области: сущности исчезают, путь продолжается в симуляции. */
    public static void dematerialize(ServerLevel level, Caravan c, @Nullable UUID alreadyGone) {
        c.materialized = false;
        for (UUID id : new ArrayList<>(c.entities)) {
            if (id.equals(alreadyGone)) continue;
            Entity e = level.getEntity(id);
            if (e != null) e.discard();
        }
        c.entities.clear();
    }

    @Nullable
    public static Entity caravanLeader(ServerLevel level, OutpostNpc member) {
        Outpost o = member.outpost();
        Caravan c = o == null ? null : NpcLifecycle.caravan(o, member.caravanId);
        if (c == null) return null;
        Resident leader = o.resident(c.leader);
        return leader == null || leader.entity == null ? null : level.getEntity(leader.entity);
    }

    private static void ambush(ServerLevel level, Outpost o, OutpostNpc leader) {
        int count = 2 + level.random.nextInt(3);
        for (int i = 0; i < count; i++) {
            int x = leader.getBlockX() + level.random.nextInt(25) - 12, z = leader.getBlockZ() + level.random.nextInt(25) - 12;
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            FighterEntity raider = ModEntities.FIGHTER.get().create(level);
            if (raider == null) continue;
            raider.moveTo(x + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5, 0, 0);
            raider.bindRaider(0, 0, OutpostManager.randomFighter(level.random), 0);
            raider.setTarget(leader);
            level.addFreshEntity(raider);
        }
        o.log(level.getGameTime(), "lordsoutpost.event.ambush");
    }

    /** Все сущности мода, привязанные к записям, которых больше нет, убираются при остановке сервера сами: они не сохраняются. */
    public static void forget(Outpost o) {
        o.online = false;
        o.claims.clear();
        o.doneBits.clear();
        for (Resident r : o.residents) r.entity = null;
        for (Caravan c : o.caravans) {
            c.materialized = false;
            c.entities.clear();
        }
        if (o.raid != null) o.raid.entities.clear();
    }
}
