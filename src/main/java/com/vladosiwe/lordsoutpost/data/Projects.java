package com.vladosiwe.lordsoutpost.data;

import com.vladosiwe.lordsoutpost.blueprint.FortBlueprint;
import com.vladosiwe.lordsoutpost.blueprint.Layout;
import com.vladosiwe.lordsoutpost.blueprint.ProjectDesigner;
import com.vladosiwe.lordsoutpost.blueprint.Step;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.Project;
import com.vladosiwe.lordsoutpost.outpost.Resource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Заказанные Лордом постройки. Правила, которые не дают им сломать жизнь форта:
 * <ul>
 *   <li>проект не может занять ни одной клетки чертежей тиров (текущего и будущих) — развитие форта не упрётся в пристройку;</li>
 *   <li>он не ставится поверх построек игрока, сундуков и других проектов;</li>
 *   <li>шаги платятся из того же кредита стройки и тех же ресурсов, но только после шагов тира — план форта важнее;</li>
 *   <li>при голоде стройка проектов стоит вместе со всей стройкой;</li>
 *   <li>рядом с игроком блоки ставят строители, вдали оплаченные шаги встают при его возвращении.</li>
 * </ul>
 */
public final class Projects {
    public static final int MAX_ACTIVE = 3;
    /** Насколько дальше периметра третьего тира можно строить. */
    public static final int EXTENT = 24;
    private static final long CLAIM_TIME = 240;

    public enum OrderResult { OK, NOT_HERE, OVERLAPS_FORT, OCCUPIED, TOO_MANY, NO_GROUND }

    private Projects() {}

    /** Вход постройки смотрит на того, кто её заказал. */
    public static Rotation rotationFor(Direction facing) {
        return switch (facing) {
            case SOUTH -> Rotation.CLOCKWISE_180;
            case EAST -> Rotation.CLOCKWISE_90;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /** Ставит заказ перед игроком (по направлению взгляда). Ничего не меняет, если место не годится. */
    public static OrderResult order(ServerLevel level, Outpost o, Project.Type type, BlockPos at, Direction facing, long now) {
        int active = 0;
        for (Project p : o.projects) if (p.active()) active++;
        if (active >= MAX_ACTIVE) return OrderResult.TOO_MANY;
        Direction dir = facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
        int x = at.getX() + dir.getStepX() * ProjectDesigner.depth(type), z = at.getZ() + dir.getStepZ() * ProjectDesigner.depth(type);
        if (!level.hasChunk(x >> 4, z >> 4)) return OrderResult.NO_GROUND;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        if (Math.abs(y - o.center.getY()) > 12) return OrderResult.NO_GROUND;
        Project p = new Project(o.projectCounter + 1, type, new BlockPos(x, y, z), rotationFor(dir));
        OrderResult check = check(level, o, p);
        if (check != OrderResult.OK) return check;
        o.projectCounter++;
        p.orderedAt = now;
        o.projects.add(p);
        o.log(now, "lordsoutpost.event.project_ordered", type.id);
        o.dirtyForClients = true;
        return OrderResult.OK;
    }

    static OrderResult check(ServerLevel level, Outpost o, Project p) {
        FortBlueprint bp = o.blueprint();
        Set<BlockPos> taken = new HashSet<>();
        for (Project other : o.projects) {
            if (!other.active() && other.state != Project.State.DONE) continue;
            for (Step s : other.steps(o)) taken.add(s.pos());
        }
        int limit = Layout.RADIUS[FortBlueprint.MAX_TIER] + Layout.MARGIN + EXTENT;
        for (Step s : p.steps(o)) {
            BlockPos pos = o.world(s.pos());
            if (!o.insideHorizontally(pos.getX(), pos.getZ(), limit) || level.isOutsideBuildHeight(pos)) return OrderResult.NOT_HERE;
            for (int t = o.tier; t <= FortBlueprint.MAX_TIER; t++) {
                if (bp.design(t).cells.containsKey(s.pos())) return OrderResult.OVERLAPS_FORT;
            }
            if (taken.contains(s.pos())) return OrderResult.OCCUPIED;
            if (!level.isLoaded(pos) || !ChunkReconciliation.mayOverwrite(level, o, pos, s.pos(), s.state(), null)) return OrderResult.OCCUPIED;
        }
        return OrderResult.OK;
    }

    // ============================== Оплата (A-Life) ==============================

    /**
     * Тратит оставшийся после плана тира кредит стройки на шаги проектов по порядку заказа.
     * Возвращает остаток кредита. Нехватку ресурса записывает в stalledOn, если стройка тира не стоит сама.
     */
    public static double pay(Outpost o, double credit) {
        for (Project p : o.projects) {
            if (!p.active()) continue;
            List<Step> steps = p.steps(o);
            while (credit >= 1 && p.paid < steps.size()) {
                Resource cost = steps.get(p.paid).cost();
                if (cost != null && (o.available(cost) < 1 || !o.take(cost, 1))) {
                    if (o.stalledOn == null) o.stalledOn = cost;
                    return credit;
                }
                p.paid++;
                credit -= 1;
                if (p.state == Project.State.PLANNED) p.state = Project.State.BUILDING;
            }
            if (credit < 1) break;
        }
        return credit;
    }

    // ============================== Работа строителей ==============================

    public static long key(Project p, int index) {
        return (p.id << 20) | index;
    }

    @Nullable
    public static Project project(Outpost o, long key) {
        long id = key >>> 20;
        for (Project p : o.projects) if (p.id == id) return p;
        return null;
    }

    public static int index(long key) {
        return (int) (key & 0xFFFFF);
    }

    @Nullable
    public static Step step(Outpost o, long key) {
        Project p = project(o, key);
        if (p == null) return null;
        int i = index(key);
        List<Step> steps = p.steps(o);
        return i < steps.size() ? steps.get(i) : null;
    }

    private static long next(Outpost o, long now) {
        for (Project p : o.projects) {
            if (!p.active()) continue;
            for (int i = 0; i < Math.min(p.paid, p.size(o)); i++) {
                if (p.placed.get(i)) continue;
                long k = key(p, i);
                if (o.projectClaims.containsKey(k)) continue;
                Long retry = o.projectDeferred.get(k);
                if (retry != null && retry > now) continue;
                return k;
            }
        }
        return -1;
    }

    public static boolean hasWork(Outpost o) {
        return o.online && next(o, o.buildClock) >= 0;
    }

    public static long claim(Outpost o, long now) {
        if (!o.online) return -1;
        long k = next(o, now);
        if (k >= 0) o.projectClaims.put(k, now + CLAIM_TIME);
        return k;
    }

    public static boolean renew(Outpost o, long key, long now) {
        if (!o.projectClaims.containsKey(key)) return false;
        o.projectClaims.put(key, now + CLAIM_TIME);
        return true;
    }

    public static void release(Outpost o, long key) {
        if (key >= 0) o.projectClaims.remove(key);
    }

    public static void defer(Outpost o, long key, long now) {
        o.projectClaims.remove(key);
        int fails = o.projectFailures.merge(key, 1, Integer::sum);
        o.projectDeferred.put(key, now + Math.min(6000L, 60L << Math.min(6, fails - 1)));
    }

    public static int failures(Outpost o, long key) {
        return o.projectFailures.getOrDefault(key, 0);
    }

    public static boolean blocked(ServerLevel level, Outpost o, long key) {
        Step s = step(o, key);
        return s == null || !ChunkReconciliation.mayOverwrite(level, o, o.world(s.pos()), s.pos(), s.state(), null);
    }

    /** Ставит шаг проекта (с привязанной половиной двери или кровати). */
    public static void place(ServerLevel level, Outpost o, long key, boolean effects) {
        Project p = project(o, key);
        o.projectClaims.remove(key);
        if (p == null || !p.active()) return;
        List<Step> steps = p.steps(o);
        int i = index(key);
        do {
            if (i < steps.size() && !p.placed.get(i)) {
                Step s = steps.get(i);
                BlockPos pos = o.world(s.pos());
                ChunkReconciliation.placeStep(level, o, s, effects);
                if (p.floor(o, i)) foundation(level, o, pos);
                // Заказ Лорда — его постройка: ремонт, лесоруб и чертежи тиров её не трогают.
                if (!s.state().isAir()) o.notePlayerPlaced(pos);
                p.placed.set(i);
                o.projectClaims.remove(key(p, i));
            }
            i++;
        } while (i < steps.size() && steps.get(i).chain());
        finish(o, p, level.getGameTime());
    }

    /** Клетку проекта занял игрок, пока шла стройка: проект уступает её, как и план тира. */
    public static void yieldCell(Outpost o, long key) {
        Project p = project(o, key);
        o.projectClaims.remove(key);
        if (p == null) return;
        List<Step> steps = p.steps(o);
        int i = index(key);
        do {
            p.placed.set(i);
            i++;
        } while (i < steps.size() && steps.get(i).chain());
        finish(o, p, o.buildClock);
    }

    /** Подсыпка под пол, чтобы постройка на неровной земле не висела в воздухе. */
    private static void foundation(ServerLevel level, Outpost o, BlockPos floor) {
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
        for (int d = 1; d <= 6; d++) {
            below.set(floor.getX(), floor.getY() - d, floor.getZ());
            BlockState under = level.getBlockState(below);
            if (!under.isAir() && under.getFluidState().isEmpty() && !under.canBeReplaced()) break;
            level.setBlock(below, o.palette.foundation.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private static void finish(Outpost o, Project p, long now) {
        if (p.placed.cardinality() >= p.size(o) && p.paid >= p.size(o)) {
            p.state = Project.State.DONE;
            o.log(now, "lordsoutpost.event.project_done", p.type.id);
        }
        o.dirtyForClients = true;
    }

    /** Игрок вернулся: оплаченные вдали шаги встают в мире (кроме клеток, которые занял игрок). */
    public static void arrive(ServerLevel level, Outpost o) {
        o.projectClaims.clear();
        o.projectDeferred.clear();
        o.projectFailures.clear();
        for (Project p : o.projects) {
            if (!p.active()) continue;
            List<Step> steps = p.steps(o);
            for (int i = 0; i < Math.min(p.paid, steps.size()); i++) {
                if (p.placed.get(i) || !level.isLoaded(o.world(steps.get(i).pos()))) continue;
                long k = key(p, i);
                if (blocked(level, o, k)) yieldCell(o, k);
                else place(level, o, k, false);
            }
        }
    }

    /** Отмена: поставленное остаётся постройкой Лорда, оплаченное, но не поставленное, возвращается на склад. */
    public static boolean cancel(Outpost o, long id, long now) {
        for (Project p : o.projects) {
            if (p.id != id || !p.active()) continue;
            List<Step> steps = p.steps(o);
            Map<Resource, Integer> refund = new HashMap<>();
            for (int i = 0; i < Math.min(p.paid, steps.size()); i++) {
                Resource cost = steps.get(i).cost();
                if (cost != null && !p.placed.get(i)) refund.merge(cost, 1, Integer::sum);
            }
            for (var e : refund.entrySet()) o.addPhysical(e.getKey(), e.getValue());
            p.state = Project.State.CANCELLED;
            o.projectClaims.keySet().removeIf(k -> (k >>> 20) == p.id);
            o.log(now, "lordsoutpost.event.project_cancelled", p.type.id);
            o.dirtyForClients = true;
            return true;
        }
        return false;
    }

    /** Чистит историю: из завершённых и отменённых проектов хранятся последние 8 (постройки нужны для подсчёта домов). */
    public static void prune(Outpost o) {
        int cancelled = 0;
        for (int i = o.projects.size() - 1; i >= 0; i--) {
            if (o.projects.get(i).state == Project.State.CANCELLED && ++cancelled > 4) o.projects.remove(i);
        }
    }
}
