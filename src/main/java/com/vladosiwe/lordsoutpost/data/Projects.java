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

    public enum OrderResult { OK, NOT_HERE, OVERLAPS_FORT, OCCUPIED, TOO_MANY, NO_GROUND, UNEVEN, NO_RESOURCES }

    // ============================== Стоимость и резерв ==============================

    /** Стоимость постройки по шагам её чертежа (одна единица ресурса на блок). */
    public static java.util.EnumMap<Resource, Integer> cost(Outpost o, Project.Type type) {
        java.util.EnumMap<Resource, Integer> out = new java.util.EnumMap<>(Resource.class);
        Project probe = new Project(0, type, o.center, Rotation.NONE);
        for (Step s : probe.steps(o)) if (s.cost() != null) out.merge(s.cost(), 1, Integer::sum);
        return out;
    }

    /** Ещё не потраченная часть стоимости заказа (шаги не оплачены и не поставлены). */
    public static java.util.EnumMap<Resource, Integer> remaining(Outpost o, Project p) {
        java.util.EnumMap<Resource, Integer> out = new java.util.EnumMap<>(Resource.class);
        List<Step> steps = p.steps(o);
        for (int i = Math.max(0, p.paid); i < steps.size(); i++) {
            if (p.placed.get(i)) continue;
            Resource c = steps.get(i).cost();
            if (c != null) out.merge(c, 1, Integer::sum);
        }
        return out;
    }

    /**
     * Резерв заказов: остаток стоимости всех активных заказов, кроме материала, который строители уже вынесли со
     * склада. Эти ресурсы не тратятся на стройку тира, лавку и караваны, пока заказ не выполнен или не отменён.
     * Резерв не хранится отдельно — он считается из состояния заказов, поэтому сохраняется и восстанавливается сам.
     */
    public static double reserved(Outpost o, Resource r) {
        double n = 0;
        for (Project p : o.projects) {
            if (!p.active()) continue;
            n += remaining(o, p).getOrDefault(r, 0);
        }
        return Math.max(0, n - o.buildMaterials.getOrDefault(r, 0.0));
    }

    /** Каких ресурсов не хватает на новый заказ (с учётом резерва уже принятых заказов). */
    public static java.util.EnumMap<Resource, Integer> missing(Outpost o, Project.Type type) {
        java.util.EnumMap<Resource, Integer> out = new java.util.EnumMap<>(Resource.class);
        for (var e : cost(o, type).entrySet()) {
            int lack = (int) Math.ceil(e.getValue() - o.available(e.getKey()));
            if (lack > 0) out.put(e.getKey(), lack);
        }
        return out;
    }

    /**
     * Заказ из RTS-режима: точное место (x, z — центр постройки) и поворот выбирает Лорд, высота — по земле.
     * Проверки те же, что и у обычного заказа, плюс рельеф и наличие ресурсов на складе этого аванпоста.
     * dryRun — только проверить (для предпросмотра).
     */
    public static OrderResult orderAt(ServerLevel level, Outpost o, Project.Type type, int x, int z, int rot, long now, boolean dryRun) {
        int active = 0;
        for (Project p : o.projects) {
            if (p.active()) active++;
            if (p.active() && p.type == type && type.relocates != null) return OrderResult.TOO_MANY;
        }
        if (active >= MAX_ACTIVE) return OrderResult.TOO_MANY;
        if (!level.hasChunk(x >> 4, z >> 4)) return OrderResult.NO_GROUND;
        int y = groundY(level, x, z);
        if (Math.abs(y - o.center.getY()) > 12) return OrderResult.NO_GROUND;
        Project p = new Project(o.projectCounter + 1, type, new BlockPos(x, y, z), Rotation.values()[Math.floorMod(rot, 4)]);
        OrderResult check = check(level, o, p);
        if (check != OrderResult.OK) return check;
        // Рельеф: под полом постройки земля не должна обрываться или вздыматься больше чем на 2 блока.
        List<Step> steps = p.steps(o);
        for (int i = 0; i < steps.size(); i++) {
            if (!p.floor(o, i)) continue;
            BlockPos w = o.world(steps.get(i).pos());
            int g = groundY(level, w.getX(), w.getZ());
            if (Math.abs(g - w.getY()) > 2) return OrderResult.UNEVEN;
        }
        if (!missing(o, type).isEmpty()) return OrderResult.NO_RESOURCES;
        if (dryRun) return OrderResult.OK;
        o.projectCounter++;
        p.orderedAt = now;
        o.projects.add(p);
        o.log(now, "lordsoutpost.event.project_ordered", type.id);
        o.dirtyForClients = true;
        return OrderResult.OK;
    }

    /** Высота земли: верхний блок без стволов, листвы, снега и травы (их строители расчистят). */
    public static int groundY(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
        while (p.getY() > level.getMinBuildHeight()) {
            BlockState s = level.getBlockState(p);
            if (!(s.isAir() || s.is(net.minecraft.tags.BlockTags.LOGS) || s.is(net.minecraft.tags.BlockTags.LEAVES)
                    || s.is(net.minecraft.tags.BlockTags.SNOW) || (s.canBeReplaced() && s.getFluidState().isEmpty()))) break;
            p.move(0, -1, 0);
        }
        return p.getY();
    }

    /** Приоритет заказа: заказы выполняются по порядку списка; up — поднять на одну позицию выше. */
    public static boolean move(Outpost o, long id, boolean up) {
        for (int i = 0; i < o.projects.size(); i++) {
            if (o.projects.get(i).id != id) continue;
            int j = i;
            do {
                j += up ? -1 : 1;
            } while (j >= 0 && j < o.projects.size() && !o.projects.get(j).active());
            if (j < 0 || j >= o.projects.size()) return false;
            Project p = o.projects.remove(i);
            o.projects.add(j, p);
            o.dirtyForClients = true;
            return true;
        }
        return false;
    }

    /** Сколько строителей сейчас работает на этом заказе (держат его шаги). */
    public static int builders(Outpost o, Project p) {
        int n = 0;
        for (long k : o.projectClaims.keySet()) if ((k >>> 20) == p.id) n++;
        return n;
    }

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
        for (Project p : o.projects) {
            if (p.active()) active++;
            // Переносить один объект двумя заказами сразу нельзя.
            if (p.active() && p.type == type && type.relocates != null) return OrderResult.TOO_MANY;
        }
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

    /**
     * Заказ без указания места (кнопка «Построить» у совета планировщика или авто-застройка): место выбирается
     * само по кольцу вокруг форта, входом к форту, по тем же проверкам, что и ручной заказ. Только при загруженном форте.
     */
    public static OrderResult orderAuto(ServerLevel level, Outpost o, Project.Type type, long now, boolean auto) {
        int active = 0;
        for (Project p : o.projects) if (p.active()) active++;
        if (active >= MAX_ACTIVE) return OrderResult.TOO_MANY;
        int inner = Layout.radius(o.version, FortBlueprint.MAX_TIER) + Layout.MARGIN + 4;
        for (int r = inner; r <= inner + EXTENT - 6; r += 3) {
            for (int a = 0; a < 16; a++) {
                double ang = (a * 2 * Math.PI) / 16 + (r % 2) * 0.2;
                int bx = o.center.getX() + (int) Math.round(Math.cos(ang) * r), bz = o.center.getZ() + (int) Math.round(Math.sin(ang) * r);
                double dx = bx - o.center.getX(), dz = bz - o.center.getZ();
                // Вход смотрит на форт: «игрок» стоит ближе к центру и смотрит наружу.
                Direction out = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
                int depth = ProjectDesigner.depth(type);
                BlockPos at = new BlockPos(bx - out.getStepX() * depth, o.center.getY(), bz - out.getStepZ() * depth);
                if (!level.hasChunk(bx >> 4, bz >> 4)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz) - 1;
                if (Math.abs(y - o.center.getY()) > 6) continue;
                Project p = new Project(o.projectCounter + 1, type, new BlockPos(bx, y, bz), rotationFor(out));
                if (check(level, o, p) != OrderResult.OK) continue;
                o.projectCounter++;
                p.orderedAt = now;
                o.projects.add(p);
                o.log(now, auto ? "lordsoutpost.event.project_auto" : "lordsoutpost.event.project_ordered", type.id);
                o.dirtyForClients = true;
                return OrderResult.OK;
            }
        }
        return OrderResult.NO_GROUND;
    }

    static OrderResult check(ServerLevel level, Outpost o, Project p) {
        FortBlueprint bp = o.blueprint();
        Set<BlockPos> taken = new HashSet<>();
        for (Project other : o.projects) {
            if (!other.active() && other.state != Project.State.DONE) continue;
            for (Step s : other.steps(o)) taken.add(s.pos());
        }
        int limit = Layout.radius(o.version, FortBlueprint.MAX_TIER) + Layout.MARGIN + EXTENT;
        for (Step s : p.steps(o)) {
            BlockPos pos = o.world(s.pos());
            if (!o.insideHorizontally(pos.getX(), pos.getZ(), limit) || level.isOutsideBuildHeight(pos)) return OrderResult.NOT_HERE;
            // Мешает только то, что форт уже поставил или поставит в ближайшем тире (не пустые клетки);
            // дальние тиры на заказ не влияют: место под дома Лорда есть и во дворе.
            for (int t = o.tier; t <= Math.min(FortBlueprint.MAX_TIER, o.tier + 1); t++) {
                var cell = bp.design(t).cells.get(s.pos());
                if (cell != null && !cell.state().isAir()) return OrderResult.OVERLAPS_FORT;
            }
            if (taken.contains(s.pos())) return OrderResult.OCCUPIED;
            if (!level.isLoaded(pos)) return OrderResult.OCCUPIED;
            // Природный рельеф (камень, снег, лёд, кусты, деревья) строители расчищают; постройки и блоки игрока — нет.
            BlockState here = level.getBlockState(pos);
            boolean natural = ChunkReconciliation.naturalTerrain(here) && level.getBlockEntity(pos) == null && !o.placedByPlayer(pos);
            if (!natural && !ChunkReconciliation.mayOverwrite(level, o, pos, s.pos(), s.state(), null)) return OrderResult.OCCUPIED;
        }
        return OrderResult.OK;
    }

    // ============================== Оплата (A-Life) ==============================

    /**
     * Тратит оставшийся после плана тира кредит стройки на шаги проектов по порядку заказа.
     * Возвращает остаток кредита. Нехватку ресурса записывает в stalledOn, если стройка тира не стоит сама.
     */
    public static double pay(Outpost o, double credit) {
        // Рядом с игроком заказы оплачивает сам строитель — материалом, который он принёс со склада (см. claimPaying).
        if (o.online) return credit;
        for (Project p : o.projects) {
            if (!p.active()) continue;
            List<Step> steps = p.steps(o);
            while (credit >= 1 && p.paid < steps.size()) {
                Resource cost = steps.get(p.paid).cost();
                // Заказ тратит свой резерв: проверяется запас за вычетом задач жителей, а не за вычетом резерва заказов.
                if (cost != null && !useMaterial(o, cost) && (o.availableForProjects(cost) < 1 || !o.take(cost, 1))) {
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

    /** Есть заказ, шаги которого ещё не оплачены материалом. */
    public static boolean unpaidExists(Outpost o) {
        return unpaid(o) != null;
    }

    public static boolean hasWork(Outpost o) {
        return o.online && (next(o, o.buildClock) >= 0 || unpaid(o) != null);
    }

    // ---------- Материалы строителей для заказов ----------

    /** Тратит единицу материала, уже принесённого строителями (общий запас заказов). */
    static boolean useMaterial(Outpost o, Resource r) {
        double have = o.buildMaterials.getOrDefault(r, 0.0);
        if (have < 1 - 1e-9) return false;
        o.buildMaterials.put(r, have - 1);
        return true;
    }

    public static void addMaterial(Outpost o, Resource r, double amount) {
        o.buildMaterials.merge(r, amount, Double::sum);
    }

    /** Первый ещё не оплаченный шаг активного заказа или null. */
    @Nullable
    private static Project unpaid(Outpost o) {
        for (Project p : o.projects) if (p.active() && p.paid < p.size(o)) return p;
        return null;
    }

    /** Какой материал нужен следующему шагу заказа, если принесённого не хватает (null — хватает или шагов нет). */
    @Nullable
    public static Resource neededMaterial(Outpost o) {
        Project p = unpaid(o);
        if (p == null) return null;
        List<Step> steps = p.steps(o);
        int i = p.paid;
        do {
            Resource cost = steps.get(i).cost();
            if (cost != null && o.buildMaterials.getOrDefault(cost, 0.0) < 1 - 1e-9) return cost;
            i++;
        } while (i < steps.size() && steps.get(i).chain());
        return null;
    }

    /** Сколько единиц ресурса нужно ближайшим неоплаченным шагам заказов (не больше max). */
    public static int upcoming(Outpost o, Resource r, int max) {
        int n = 0;
        for (Project p : o.projects) {
            if (!p.active()) continue;
            List<Step> steps = p.steps(o);
            for (int i = p.paid; i < steps.size() && n < max; i++) if (steps.get(i).cost() == r) n++;
        }
        return n;
    }

    /**
     * Строитель рядом с игроком берёт следующий шаг заказа и оплачивает его принесённым материалом
     * (вместе со связанной половиной двери или кровати). -1 — материала не хватает или шагов нет.
     */
    public static long claimPaying(Outpost o, long now) {
        Project p = unpaid(o);
        if (p == null || neededMaterial(o) != null) return -1;
        List<Step> steps = p.steps(o);
        int first = p.paid;
        do {
            Resource cost = steps.get(p.paid).cost();
            if (cost != null) useMaterial(o, cost);
            p.paid++;
        } while (p.paid < steps.size() && steps.get(p.paid).chain());
        if (p.state == Project.State.PLANNED) p.state = Project.State.BUILDING;
        long k = key(p, first);
        o.projectClaims.put(k, now + CLAIM_TIME);
        o.dirtyForClients = true;
        return k;
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
        if (s == null) return true;
        BlockPos pos = o.world(s.pos());
        BlockState here = level.getBlockState(pos);
        // Природный рельеф под заказом (камень, снег, кусты) строитель заменяет; уступает только постройкам и игроку.
        if (ChunkReconciliation.naturalTerrain(here) && level.getBlockEntity(pos) == null && !o.placedByPlayer(pos)) return false;
        return !ChunkReconciliation.mayOverwrite(level, o, pos, s.pos(), s.state(), null);
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
                if (p.floor(o, i) && !s.state().isAir()) foundation(level, o, pos);
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

    public static void finish(Outpost o, Project p, long now) {
        if (p.placed.cardinality() >= p.size(o) && p.paid >= p.size(o)) {
            p.state = Project.State.DONE;
            o.log(now, "lordsoutpost.event.project_done", p.type.id);
            // Новое место рабочего объекта готово: зона и привязки профессии переезжают сюда.
            if (p.type.relocates != null) {
                var zone = p.type.relocates;
                BlockPos was = o.hasZone(zone) ? o.zone(zone) : p.zoneAnchor();
                o.zones.rebind(zone, p.zoneAnchor(), p.id, was);
                if (zone == com.vladosiwe.lordsoutpost.outpost.WorkZone.MINE) o.mineCursor = 0;
                o.log(now, "lordsoutpost.event.zone_moved", zone.id, p.zoneAnchor().toShortString());
            }
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
