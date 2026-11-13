package com.vladosiwe.lordsoutpost.data;

import com.vladosiwe.lordsoutpost.blueprint.Marker;
import com.vladosiwe.lordsoutpost.config.OutpostConfig;
import com.vladosiwe.lordsoutpost.outpost.Caravan;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Pact;
import com.vladosiwe.lordsoutpost.outpost.Relation;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Resource;
import com.vladosiwe.lordsoutpost.outpost.Role;
import com.vladosiwe.lordsoutpost.outpost.Squad;
import com.vladosiwe.lordsoutpost.server.Notify;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.UUID;

/**
 * Караваны. Вдали от игроков караван — точка, которая движется по прямой между фортами с учётом дорог и риска засады.
 * Когда точка попадает в загруженные чанки, согласование превращает её в настоящий отряд, и тогда путь
 * считает уже не эта формула, а ноги сущностей (см. ChunkReconciliation).
 */
public final class Caravans {
    public enum Result { OK, NO_PACT, NO_LEADER, NO_CARGO, INVALID }

    /** Скорость гружёного каравана, блоков в секунду. */
    public static final double BASE_SPEED = 2.6;
    public static final int MAX_CARGO = 160;
    public static final int MIN_CARGO_VALUE = 12;
    public static final long TRADE_TIME = 600;

    private Caravans() {}

    public static void tick(ServerLevel level, OutpostSavedData data, Outpost o, long now, double days, boolean steward, RandomSource rnd) {
        for (Iterator<Caravan> it = o.caravans.iterator(); it.hasNext(); ) {
            Caravan c = it.next();
            Resident leader = o.resident(c.leader);
            Outpost target = data.byId(c.target);
            if (leader == null || target == null) {
                release(o, c);
                it.remove();
                o.log(now, "lordsoutpost.event.caravan_lost");
                continue;
            }
            if (c.materialized) continue;
            if (advance(level, data, o, target, c, now, days * Simulation.DAY / 20.0, rnd)) it.remove();
        }
        if (steward && o.autoCaravans && now >= o.nextCaravanAt) {
            Outpost partner = bestPartner(data, o);
            if (partner != null && dispatch(data, o, partner, now) == Result.OK) o.nextCaravanAt = now + 2400;
            else o.nextCaravanAt = now + 1200;
        }
    }

    /**
     * Виртуальное движение. seconds — сколько секунд игрового времени прошло.
     * Возвращает true, когда караван вернулся домой и его нужно убрать из списка.
     */
    public static boolean advance(ServerLevel level, OutpostSavedData data, Outpost o, Outpost target, Caravan c, long now, double seconds, RandomSource rnd) {
        Relation rel = o.relations.get(target.id);
        if (c.phase == Caravan.Phase.TRADING) {
            if (now < c.phaseUntil) return false;
            c.phase = Caravan.Phase.RETURNING;
            c.traveled = 0;
            c.ambushed = false;
            o.dirtyForClients = true;
            return false;
        }
        double road = rel == null ? 0 : rel.roadShare;
        double speed = BASE_SPEED * (1 + (OutpostConfig.get(OutpostConfig.ROAD_SPEED, 1.35) - 1) * road);
        double step = speed * seconds;
        // Засада: риск копится с каждой сотней блоков пути, военный пакт и конвой его снижают.
        if (!c.ambushed) {
            double risk = 0.03 * step / 100.0;
            if (rel != null && rel.pact == Pact.MILITARY) risk *= 0.3;
            if (rnd.nextDouble() < risk) ambush(level, o, c, now, rnd);
        }
        c.traveled += step;
        o.dirtyForClients = true;
        if (c.traveled < c.length()) return false;
        return arrive(level, data, o, target, c, now);
    }

    /** Караван дошёл до конца плеча. Возвращает true, если он вернулся домой. */
    public static boolean arrive(ServerLevel level, OutpostSavedData data, Outpost o, Outpost target, Caravan c, long now) {
        Relation rel = o.relations.computeIfAbsent(target.id, k -> new Relation());
        if (c.phase == Caravan.Phase.OUTBOUND) {
            double value = 0;
            for (var e : c.cargo.entrySet()) value += e.getValue() * QuestBoard.goldValue(e.getKey());
            c.earned = value * (1 + c.length() / 3000.0) * o.law.caravanProfit() * (1 + 0.1 * Math.min(5, rel.caravansDone));
            c.cargo.clear();
            c.phase = Caravan.Phase.TRADING;
            c.phaseUntil = now + TRADE_TIME;
            c.traveled = 0;
            if (target.state == OutpostState.OWNED) {
                // Торговля выгодна обеим сторонам.
                target.add(Resource.GOLD, c.earned * 0.15);
                target.log(now, "lordsoutpost.event.caravan_visit", o.name);
            }
            o.dirtyForClients = true;
            return false;
        }
        // Дома: выручка делится между казной и данью Лорду.
        o.add(Resource.GOLD, c.earned * 0.5);
        o.tributeGold += c.earned * 0.5;
        Simulation.flushTributeGold(o);
        if (c.sampledBlocks > 40) rel.roadShare = (float) Math.max(0, Math.min(1, c.roadBlocks / c.sampledBlocks));
        rel.caravansDone++;
        release(o, c);
        int earned = (int) Math.round(c.earned);
        o.log(now, "lordsoutpost.event.caravan_back", earned);
        Notify.lord(level.getServer(), o, Component.translatable("lordsoutpost.event.caravan_back", earned));
        o.dirtyForClients = true;
        data.setDirty();
        return true;
    }

    private static void ambush(ServerLevel level, Outpost o, Caravan c, long now, RandomSource rnd) {
        c.ambushed = true;
        double power = 3;
        for (UUID id : c.escort) {
            Resident r = o.resident(id);
            if (r != null) power += r.power() / (r.status == Resident.Status.ON_CARAVAN ? 1 : 2) + (r.role.combat ? 4 * (1 + 0.35 * r.gear) : 0);
        }
        double attack = 4 + rnd.nextDouble() * 8;
        if (rnd.nextDouble() < power / (power + attack)) {
            o.log(now, "lordsoutpost.event.ambush_won");
            return;
        }
        c.cargo.replaceAll((res, amount) -> amount * 0.5);
        c.earned *= 0.5;
        if (!c.escort.isEmpty()) {
            Resident victim = o.resident(c.escort.get(rnd.nextInt(c.escort.size())));
            if (victim != null) {
                victim.health = 0.3f;
                victim.armor = Math.max(0, victim.armor - 0.4f);
            }
        }
        o.log(now, "lordsoutpost.event.ambush_lost");
        Notify.lord(level.getServer(), o, Component.translatable("lordsoutpost.event.ambush_lost"));
    }

    /** Участники каравана возвращаются к обычной службе. */
    public static void release(Outpost o, Caravan c) {
        Resident leader = o.resident(c.leader);
        if (leader != null && leader.status == Resident.Status.ON_CARAVAN) leader.status = Resident.Status.ACTIVE;
        for (UUID id : c.escort) {
            Resident r = o.resident(id);
            if (r != null && r.status == Resident.Status.ON_CARAVAN) r.status = Resident.Status.ACTIVE;
        }
    }

    @Nullable
    private static Outpost bestPartner(OutpostSavedData data, Outpost o) {
        Outpost best = null;
        int bestDone = Integer.MAX_VALUE;
        for (var e : o.relations.entrySet()) {
            if (e.getValue().pact == Pact.NONE) continue;
            Outpost t = data.byId(e.getKey());
            if (t == null || t.state == OutpostState.CONQUERED) continue;
            boolean busy = false;
            for (Caravan c : o.caravans) if (c.target == t.id) busy = true;
            if (!busy && e.getValue().caravansDone < bestDone) {
                bestDone = e.getValue().caravansDone;
                best = t;
            }
        }
        return best;
    }

    /** Излишки, которые можно продать, не оголяя стройку и кладовые. */
    public static EnumMap<Resource, Double> surplus(Outpost o) {
        EnumMap<Resource, Double> out = new EnumMap<>(Resource.class);
        EnumMap<Resource, Integer> needs = QuestBoard.upcomingNeeds(o);
        double room = MAX_CARGO;
        for (Resource r : new Resource[]{Resource.IRON, Resource.HIDES, Resource.FOOD, Resource.WOOD, Resource.STONE}) {
            double reserve = switch (r) {
                case FOOD -> o.residents.size() * Simulation.FOOD_PER_DAY * 3;
                case HIDES -> 20;
                case IRON -> 30 + needs.getOrDefault(r, 0);
                default -> 100 + needs.getOrDefault(r, 0);
            };
            double spare = Math.min(room, Math.floor(o.stock(r) - reserve));
            if (spare >= 8) {
                out.put(r, spare);
                room -= spare;
            }
            if (room <= 0) break;
        }
        return out;
    }

    public static Result dispatch(OutpostSavedData data, Outpost o, Outpost target, long now) {
        if (o.state != OutpostState.OWNED || target == o || target.state == OutpostState.CONQUERED) return Result.INVALID;
        Relation rel = o.relations.get(target.id);
        if (rel == null || rel.pact == Pact.NONE) return Result.NO_PACT;
        Resident leader = null;
        for (Resident r : o.residents) if (r.role == Role.CARAVANEER && r.active()) { leader = r; break; }
        if (leader == null) return Result.NO_LEADER;
        EnumMap<Resource, Double> cargo = surplus(o);
        double value = 0;
        for (var e : cargo.entrySet()) value += e.getValue() * QuestBoard.goldValue(e.getKey());
        if (value < MIN_CARGO_VALUE) return Result.NO_CARGO;

        Caravan c = new Caravan(UUID.randomUUID(), target.id, o.marker(Marker.CARAVAN), target.marker(Marker.CARAVAN), leader.id);
        for (var e : cargo.entrySet()) {
            o.take(e.getKey(), e.getValue());
            c.cargo.put(e.getKey(), e.getValue());
        }
        leader.status = Resident.Status.ON_CARAVAN;
        // Конвой: до трёх бойцов Дозора, а если их нет — Стражи.
        int want = 2 + (o.tier >= 3 ? 1 : 0);
        for (int squad : new int[]{Squad.WATCH, Squad.GUARD}) {
            for (Resident r : o.residents) {
                if (c.escort.size() >= want) break;
                if (r.role.combat && r.active() && r.squad == squad && o.fighters() - c.escort.size() > 2) {
                    r.status = Resident.Status.ON_CARAVAN;
                    c.escort.add(r.id);
                }
            }
        }
        o.caravans.add(c);
        o.log(now, "lordsoutpost.event.caravan_sent", target.state == OutpostState.WILD ? target.difficulty.skulls + "*" : target.name);
        o.dirtyForClients = true;
        data.setDirty();
        return Result.OK;
    }
}
