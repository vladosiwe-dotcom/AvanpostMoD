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
import java.util.List;
import net.minecraft.world.item.ItemStack;
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
            upgradeCargo(o, c);
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
            upgradeCargo(o, c);
            double price = value(c) * (1 + c.length() / 3000.0) * o.law.caravanProfit() * (1 + 0.1 * Math.min(5, rel.caravansDone));
            if (target.state == OutpostState.OWNED) {
                // Владение игрока: товар реально ложится на его склад, а платит его казна — сколько может.
                int delivered = 0;
                for (ItemStack st : c.goods) {
                    ResourceItems.Value v = ResourceItems.of(st);
                    if (v != null && Storehouse.physical(v.resource())) target.addPhysical(v.resource(), v.perItem() * st.getCount());
                    else Simulation.addTribute(target, st.copy());
                    delivered += st.getCount();
                }
                double paid = Math.min(price, target.stock(Resource.GOLD));
                target.take(Resource.GOLD, paid);
                c.earned = paid;
                target.log(now, "lordsoutpost.event.caravan_visit", o.name);
                target.dirtyForClients = true;
                if (target.owner != null) {
                    com.vladosiwe.lordsoutpost.mail.Mail.send(level.getServer(), target.owner, o.name, "lordsoutpost.mail.caravan_delivered",
                            List.of(), now, delivered, (int) Math.round(paid));
                }
            } else {
                // Дикий форт с договором покупает груз у каравана: товар уходит мародёрам.
                c.earned = price;
            }
            c.goods.clear();
            c.cargo.clear();
            c.phase = Caravan.Phase.TRADING;
            c.phaseUntil = now + TRADE_TIME;
            c.traveled = 0;
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
        // Засада отбирает половину настоящего груза.
        upgradeCargo(o, c);
        for (ItemStack st : c.goods) st.setCount(st.getCount() / 2);
        c.goods.removeIf(ItemStack::isEmpty);
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
            // Отложенное под задачи жителей в караван не грузится.
            double spare = Math.min(room, Math.floor(o.available(r) - reserve));
            if (spare >= 8) {
                out.put(r, spare);
                room -= spare;
            }
            if (room <= 0) break;
        }
        return out;
    }

    /** Снимает со склада ресурс целыми предметами (его представитель — бревно, булыжник, слиток…) и кладёт в груз. */
    private static void loadGoods(Outpost o, Caravan c, Resource r, double amount) {
        ItemStack rep = Storehouse.representative(o, r);
        ResourceItems.Value v = ResourceItems.of(rep);
        if (rep.isEmpty() || v == null) return;
        int n = (int) Math.floor(amount / v.perItem());
        if (n <= 0 || !o.take(r, n * v.perItem())) return;
        while (n > 0) {
            int k = Math.min(n, rep.getMaxStackSize());
            c.goods.add(rep.copyWithCount(k));
            n -= k;
        }
    }

    /** Старые сохранения: числовой груз становится настоящими предметами (остаток меньше предмета пропадает как раньше). */
    static void upgradeCargo(Outpost o, Caravan c) {
        if (c.cargo.isEmpty()) return;
        for (var e : c.cargo.entrySet()) {
            ItemStack rep = Storehouse.representative(o, e.getKey());
            ResourceItems.Value v = ResourceItems.of(rep);
            if (rep.isEmpty() || v == null) continue;
            int n = (int) Math.floor(e.getValue() / v.perItem());
            while (n > 0) {
                int k = Math.min(n, rep.getMaxStackSize());
                c.goods.add(rep.copyWithCount(k));
                n -= k;
            }
        }
        c.cargo.clear();
    }

    /** Стоимость груза в золоте по ценам Доски. */
    public static double value(Caravan c) {
        double value = 0;
        for (ItemStack st : c.goods) {
            ResourceItems.Value v = ResourceItems.of(st);
            if (v != null) value += v.perItem() * st.getCount() * QuestBoard.goldValue(v.resource());
        }
        return value;
    }

    /** Вьючное животное каравана погибло в мире: груз падает на землю — его можно отбить или подобрать. */
    public static void dropGoods(ServerLevel level, Outpost o, Caravan c, net.minecraft.world.phys.Vec3 at) {
        upgradeCargo(o, c);
        for (ItemStack st : c.goods) {
            if (st.isEmpty()) continue;
            net.minecraft.world.entity.item.ItemEntity e = new net.minecraft.world.entity.item.ItemEntity(level, at.x, at.y + 0.5, at.z, st.copy());
            e.setDefaultPickUpDelay();
            level.addFreshEntity(e);
        }
        c.goods.clear();
        c.earned = 0;
        o.log(level.getGameTime(), "lordsoutpost.event.caravan_goods_lost");
        o.dirtyForClients = true;
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
        // Груз — настоящие предметы: их снимают со склада (при загруженном форте бочки опустеют при сверке).
        for (var e : cargo.entrySet()) loadGoods(o, c, e.getKey(), e.getValue());
        if (c.goods.isEmpty()) return Result.NO_CARGO;
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
        o.log(now, "lordsoutpost.event.caravan_sent", target.state == OutpostState.WILD ? "★".repeat(target.difficulty.skulls) : target.name);
        o.dirtyForClients = true;
        data.setDirty();
        return Result.OK;
    }
}
