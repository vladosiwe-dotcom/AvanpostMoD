package com.vladosiwe.lordsoutpost.data;

import com.vladosiwe.lordsoutpost.blueprint.Step;
import com.vladosiwe.lordsoutpost.config.OutpostConfig;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.Quest;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Resource;
import com.vladosiwe.lordsoutpost.outpost.Role;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;

/**
 * Доска заданий. Задания не выдумываются: каждое — это реальная нехватка, найденная в хозяйстве.
 * Если жители справились сами, задание исчезает; если помог игрок — он получает награду,
 * а связанная работа ускоряется.
 */
public final class QuestBoard {
    public static final int MAX_QUESTS = 5;
    private static final int LOOKAHEAD = 300;

    private QuestBoard() {}

    public static void update(Outpost o, long now) {
        // Жители справились сами.
        for (Iterator<Quest> it = o.quests.iterator(); it.hasNext(); ) {
            Quest q = it.next();
            if (q.done() || !stillNeeded(o, q)) {
                it.remove();
                o.dirtyForClients = true;
            }
        }
        if (o.activeCount(Role.STEWARD) == 0) return;

        if (!o.complete()) {
            EnumMap<Resource, Integer> need = upcomingNeeds(o);
            for (var e : need.entrySet()) {
                double have = o.stock(e.getKey());
                if (e.getValue() >= 20 && have < e.getValue() * 0.5) {
                    int amount = (int) Math.max(32, Math.min(256, Math.ceil((e.getValue() - have) / 16.0) * 16));
                    offer(o, Quest.Type.BUILD_MATERIAL, e.getKey(), amount, now);
                }
            }
        }
        int pop = o.residents.size();
        if (o.stock(Resource.FOOD) < pop * Simulation.FOOD_PER_DAY) offer(o, Quest.Type.FOOD, Resource.FOOD, 64, now);

        double armor = 0;
        int fighters = 0;
        for (Resident r : o.residents) if (r.role.combat) { armor += r.armor; fighters++; }
        if (fighters > 0 && armor / fighters < 0.6) {
            if (o.stock(Resource.HIDES) < 8) offer(o, Quest.Type.ARMOR_REPAIR, Resource.HIDES, 24, now);
            else if (o.stock(Resource.IRON) < 8) offer(o, Quest.Type.ARMOR_REPAIR, Resource.IRON, 24, now);
        }
        if (o.tier >= 3 && o.activeCount(Role.MAGE) > 0 && o.stock(Resource.ARCANE) < 20) {
            offer(o, Quest.Type.RARE_INGREDIENT, Resource.ARCANE, 16, now);
        }
    }

    /** Сколько каждого ресурса понадобится на ближайшие шаги стройки. */
    public static EnumMap<Resource, Integer> upcomingNeeds(Outpost o) {
        EnumMap<Resource, Integer> need = new EnumMap<>(Resource.class);
        List<Step> plan = o.nextPlan();
        for (int i = o.steps; i < Math.min(plan.size(), o.steps + LOOKAHEAD); i++) {
            Resource cost = plan.get(i).cost();
            if (cost != null) need.merge(cost, 1, Integer::sum);
        }
        return need;
    }

    private static boolean stillNeeded(Outpost o, Quest q) {
        double have = o.stock(q.resource);
        return switch (q.type) {
            case BUILD_MATERIAL -> !o.complete() && have < q.needed + 8;
            case FOOD -> have < o.residents.size() * Simulation.FOOD_PER_DAY * 2;
            case ARMOR_REPAIR -> have < 24;
            case RARE_INGREDIENT -> have < 40;
        };
    }

    private static void offer(Outpost o, Quest.Type type, Resource res, int amount, long now) {
        if (o.quests.size() >= MAX_QUESTS) return;
        for (Quest q : o.quests) if (q.resource == res) return;
        int reward = Math.max(2, (int) Math.round(amount * goldValue(res) * 0.5));
        o.quests.add(new Quest(++o.questCounter, type, res, amount, reward, now));
        o.dirtyForClients = true;
    }

    public static double goldValue(Resource r) {
        return switch (r) {
            case WOOD -> 0.15;
            case STONE -> 0.12;
            case FOOD -> 0.3;
            case HIDES -> 0.6;
            case IRON -> 1.0;
            case ARCANE -> 1.5;
            case GOLD -> 1.0;
        };
    }

    /**
     * Игрок сдал ресурс. Возвращает задание, которое этим закрыто, или null.
     * Ресурс попадает на склад в любом случае.
     */
    @Nullable
    public static Quest deliver(Outpost o, Resource res, double amount, long now) {
        o.add(res, amount);
        o.dirtyForClients = true;
        for (Quest q : o.quests) {
            if (q.resource != res || q.done()) continue;
            q.delivered += amount;
            if (q.done()) {
                int minutes = OutpostConfig.get(OutpostConfig.BOOST_MINUTES, 20);
                o.boosts.put(res.id, now + minutes * 1200L);
                o.tributeGold += q.rewardGold;
                o.log(now, "lordsoutpost.event.quest_done", q.type.name().toLowerCase(java.util.Locale.ROOT), res.id);
                return q;
            }
            return null;
        }
        return null;
    }
}
