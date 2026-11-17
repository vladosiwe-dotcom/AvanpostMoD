package com.vladosiwe.lordsoutpost.data;

import com.vladosiwe.lordsoutpost.blueprint.FortBlueprint;
import com.vladosiwe.lordsoutpost.blueprint.Step;
import com.vladosiwe.lordsoutpost.compat.MagicLoot;
import com.vladosiwe.lordsoutpost.config.OutpostConfig;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.Pact;
import com.vladosiwe.lordsoutpost.outpost.Relation;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Resource;
import com.vladosiwe.lordsoutpost.outpost.Role;
import com.vladosiwe.lordsoutpost.server.Notify;
import com.vladosiwe.lordsoutpost.server.OutpostManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Макро-тик A-Life: одна и та же экономика считается и когда игрок стоит во дворе, и когда чанки выгружены.
 * Разница только в отражении: рядом с игроком шаги стройки ставят настоящие строители, а караваны идут по дороге;
 * вдали меняются лишь числа, которые при возвращении игрока превращаются в блоки и сущности.
 *
 * Все ставки заданы «в игровой день на одного работника».
 */
public final class Simulation {
    public static final long DAY = 24000L;
    public static final double FOOD_PER_DAY = 6;
    public static final double BUILDER_STEPS_PER_DAY = 600;
    private static final double MAX_BUILD_CREDIT = 40;
    /** Добыча в игровой день на работника. Камня нужно больше всего: каменные стены, башни и донжон. */
    public static final double WOOD_PER_DAY = 450;
    public static final double STONE_PER_DAY = 650;
    private static final double BUILDER_GATHER_PER_DAY = 200;

    private Simulation() {}

    public static void tick(ServerLevel level, OutpostSavedData data, Outpost o, long now) {
        long dt = now - o.lastSim;
        if (dt <= 0) return;
        o.lastSim = now;
        double days = Math.min(dt, DAY) / (double) DAY;
        RandomSource rnd = level.random;
        switch (o.state) {
            case WILD -> wild(o, now, rnd);
            case CONQUERED -> conquered(data, o, now, rnd);
            case OWNED -> owned(level, data, o, now, days, rnd);
        }
        data.setDirty();
    }

    // ============================== Мародёры ==============================

    private static void wild(Outpost o, long now, RandomSource rnd) {
        if (o.captain() == null) return;
        if (o.fighters() >= o.difficulty.garrison + 1 || now < o.garrisonRegenAt || now < o.alarmUntil) return;
        o.residents.add(OutpostManager.marauder(o, rnd));
        o.garrisonRegenAt = now + DAY / 2;
    }

    private static void conquered(OutpostSavedData data, Outpost o, long now, RandomSource rnd) {
        double reclaim = OutpostConfig.get(OutpostConfig.RECLAIM_DAYS, 5.0) * DAY;
        if (!o.online && now - o.conqueredAt > reclaim) OutpostManager.revertToWild(data, o, now, rnd);
    }

    // ============================== Владение игрока ==============================

    private static void owned(ServerLevel level, OutpostSavedData data, Outpost o, long now, double days, RandomSource rnd) {
        heal(o, now, days);
        boolean steward = o.activeCount(Role.STEWARD) > 0;
        double prod = days * OutpostConfig.get(OutpostConfig.PRODUCTION, 1.0) * o.law.production() * (o.starving ? 0.5 : 1.0);
        double boost = OutpostConfig.get(OutpostConfig.PLAYER_HELP_BOOST, 2.0);

        // Жители, которые сейчас работают руками в мире (игрок рядом), добывают настоящие предметы и несут их на склад:
        // их выработка здесь не считается, иначе ресурс засчитался бы дважды.
        // Стройка упёрлась в дерево или камень: строители без дела сами идут в лес и каменоломню.
        Resource lacking = o.stalledOn;
        if (!o.online && (lacking == Resource.WOOD || lacking == Resource.STONE)) {
            o.add(lacking, o.activeCount(Role.BUILDER) * BUILDER_GATHER_PER_DAY * prod);
        }
        o.add(Resource.WOOD, virtualWorkers(o, Role.LUMBERJACK, now) * WOOD_PER_DAY * prod * (o.boosted(Resource.WOOD, now) ? boost : 1));
        int miners = virtualWorkers(o, Role.MINER, now);
        o.add(Resource.STONE, miners * STONE_PER_DAY * prod * (o.boosted(Resource.STONE, now) ? boost : 1));
        // Железной руды в штреке мало, поэтому шахтёр в мире всё равно даёт половину железа «из промывки».
        double ironMiners = miners + 0.5 * (o.activeCount(Role.MINER) - miners);
        o.add(Resource.IRON, ironMiners * 24 * prod * (o.boosted(Resource.IRON, now) ? boost : 1));
        double foodBoost = o.boosted(Resource.FOOD, now) ? boost : 1;
        int skotovods = virtualWorkers(o, Role.SKOTOVOD, now);
        // Каждый скотовод делит рабочее время между охотой и стадом: половина выработки приходится на каждое направление.
        double huntingWorkers = skotovods * 0.5;
        double herdingWorkers = skotovods * 0.5;
        o.add(Resource.FOOD, huntingWorkers * 36 * prod * foodBoost);
        o.add(Resource.HIDES, huntingWorkers * 10 * prod);
        if (herdingWorkers > 0) {
            // Вдали скотоводы пригоняют животных, пока загон не заполнится, и держат стадо.
            if (o.penAnimals < o.penCapacity() && rnd.nextDouble() < herdingWorkers * 1.5 * days) o.penAnimals++;
            double herd = Math.min(o.penAnimals, herdingWorkers * 8);
            o.add(Resource.FOOD, herd * 5 * prod * foodBoost);
            o.add(Resource.HIDES, herd * 1.2 * prod);
        }

        // Еда.
        double eat = o.residents.size() * FOOD_PER_DAY * days * o.law.foodUse();
        boolean wasStarving = o.starving;
        if (!o.take(Resource.FOOD, eat)) {
            o.setStock(Resource.FOOD, 0);
            o.starving = true;
            if (!wasStarving) {
                o.log(now, "lordsoutpost.event.starving");
                Notify.lord(level.getServer(), o, Component.translatable("lordsoutpost.event.starving"));
            }
        } else {
            o.starving = false;
        }

        build(o, now, days, boost);
        smith(o, now, days, boost);
        mage(level, o, now, days, boost, rnd);
        tribute(o, days, steward);
        grow(o, now, days, boost, rnd);
        assignSquads(o);
        if (steward) QuestBoard.update(o, now);
        Raids.tick(level, data, o, now, rnd);
        Caravans.tick(level, data, o, now, days, steward, rnd);
        o.boosts.values().removeIf(until -> until <= now);
    }

    /** Жители роли, чью выработку считает симуляция: только те, чьи сущности сейчас не загружены в мир. */
    public static int virtualWorkers(Outpost o, Role role, long now) {
        int n = 0;
        for (Resident r : o.residents) {
            if (r.role != role || !r.active()) continue;
            // Загруженный NPC работает физически; нельзя включать ему виртуальную добычу только потому,
            // что он долго шёл к цели или временно не нашёл ресурс.
            boolean physical = o.online && r.entity != null;
            if (!physical) n++;
        }
        return n;
    }

    private static void heal(Outpost o, long now, double days) {
        for (Resident r : o.residents) {
            if (r.status == Resident.Status.WOUNDED && now >= r.statusUntil) {
                r.status = Resident.Status.ACTIVE;
                r.health = Math.max(r.health, 0.6f);
                o.dirtyForClients = true;
            }
            if (r.entity == null && r.health < 1f) r.health = (float) Math.min(1.0, r.health + 0.5 * days);
            if (r.role.combat) r.armor = (float) Math.max(0, r.armor - 0.03 * days);
        }
    }

    private static void build(Outpost o, long now, double days, double boost) {
        o.stalledOn = null;
        if (o.complete()) return;
        List<Step> plan = o.nextPlan();
        int builders = o.activeCount(Role.BUILDER);
        boolean helped = o.boosted(Resource.WOOD, now) || o.boosted(Resource.STONE, now) || o.boosted(Resource.IRON, now);
        double rate = builders * BUILDER_STEPS_PER_DAY * OutpostConfig.get(OutpostConfig.BUILD_SPEED, 1.0) * o.law.buildSpeed()
                * (helped ? boost : 1) * (o.starving ? 0.5 : 1);
        o.buildCredit = Math.min(MAX_BUILD_CREDIT + rate * days, o.buildCredit + rate * days);
        while (o.buildCredit >= 1 && o.steps < plan.size()) {
            Resource cost = plan.get(o.steps).cost();
            if (cost != null && !o.take(cost, 1)) {
                o.stalledOn = cost;
                o.buildCredit = Math.min(o.buildCredit, MAX_BUILD_CREDIT);
                break;
            }
            o.steps++;
            o.buildCredit -= 1;
        }
        o.dirtyForClients = true;
        // Тир считается достроенным, когда выполнен весь план. Если чанки выгружены, блоки встанут при возвращении игрока.
        if (o.steps >= plan.size()) {
            boolean physicalDone = o.placedTier == o.tier + 1 && o.placed >= plan.size() && o.claims.isEmpty();
            if (!o.online || physicalDone) {
                o.tier++;
                o.steps = 0;
                if (physicalDone) {
                    o.placedTier = o.tier + 1;
                    o.placed = 0;
                }
                o.buildCredit = 0;
                o.log(now, "lordsoutpost.event.tier_up", o.tier);
            }
        }
    }

    private static void smith(Outpost o, long now, double days, double boost) {
        int smiths = o.activeCount(Role.SMITH);
        if (smiths == 0) return;
        boolean helped = o.boosted(Resource.HIDES, now) || o.boosted(Resource.IRON, now);
        o.smithWork = Math.min(8, o.smithWork + smiths * 4 * days * (helped ? boost : 1));
        while (o.smithWork >= 1) {
            Resident worst = null;
            for (Resident r : o.residents) if (r.role.combat && r.armor < 0.95f && (worst == null || r.armor < worst.armor)) worst = r;
            if (worst != null) {
                if (o.stock(Resource.HIDES) < 1 || o.stock(Resource.IRON) < 1) return;
                o.take(Resource.HIDES, 1);
                o.take(Resource.IRON, 1);
                worst.armor = Math.min(1f, worst.armor + 0.25f);
            } else {
                // Броня цела — кузнец перековывает снаряжение на следующий уровень.
                int maxGear = Math.min(Resident.MAX_GEAR, o.tier);
                Resident lowest = null;
                for (Resident r : o.residents) if (r.role.combat && r.gear < maxGear && (lowest == null || r.gear < lowest.gear)) lowest = r;
                if (lowest == null) return;
                double iron = 12 * (lowest.gear + 1), hides = 6 * (lowest.gear + 1);
                // Запас на ремонт не трогаем.
                if (o.stock(Resource.IRON) < iron + 10 || o.stock(Resource.HIDES) < hides + 10) return;
                o.take(Resource.IRON, iron);
                o.take(Resource.HIDES, hides);
                lowest.gear++;
                o.dirtyForClients = true;
            }
            o.smithWork -= 1;
        }
    }

    private static void mage(ServerLevel level, Outpost o, long now, double days, double boost, RandomSource rnd) {
        int mages = o.tier >= 3 ? o.activeCount(Role.MAGE) : 0;
        if (mages == 0) return;
        double mult = o.law.magic() * (o.boosted(Resource.ARCANE, now) ? boost : 1);
        o.add(Resource.ARCANE, mages * 10 * days * mult);
        o.arcaneWork += mages * 1.2 * days * mult;
        while (o.arcaneWork >= 1 && o.stock(Resource.ARCANE) >= 20) {
            o.arcaneWork -= 1;
            o.take(Resource.ARCANE, 20);
            for (ItemStack stack : MagicLoot.mageProduct(level, rnd)) addTribute(o, stack);
            o.log(now, "lordsoutpost.event.mage_work");
        }
        o.arcaneWork = Math.min(o.arcaneWork, 3);
    }

    private static void tribute(Outpost o, double days, boolean steward) {
        int tradePacts = 0;
        for (Relation r : o.relations.values()) if (r.pact != Pact.NONE) tradePacts++;
        double gold = (6 * o.tier + 0.4 * o.residents.size() + 6 * tradePacts) * days * o.law.tribute()
                * OutpostConfig.get(OutpostConfig.TRIBUTE, 1.0) * (steward ? 1 : 0.5) * (o.starving ? 0.5 : 1);
        o.add(Resource.GOLD, gold * 0.5);
        o.tributeGold += gold * 0.5;
        flushTributeGold(o);
    }

    /** Целые изумруды из накопленной дани переходят в список предметов для Сундука Дани. */
    public static void flushTributeGold(Outpost o) {
        int whole = (int) Math.floor(o.tributeGold);
        if (whole <= 0) return;
        o.tributeGold -= whole;
        while (whole > 0) {
            int n = Math.min(64, whole);
            addTribute(o, new ItemStack(Items.EMERALD, n));
            whole -= n;
        }
    }

    public static void addTribute(Outpost o, ItemStack stack) {
        if (stack.isEmpty()) return;
        for (ItemStack have : o.tribute) {
            if (ItemStack.isSameItemSameComponents(have, stack) && have.getCount() < have.getMaxStackSize()) {
                int move = Math.min(stack.getCount(), have.getMaxStackSize() - have.getCount());
                have.grow(move);
                stack.shrink(move);
                if (stack.isEmpty()) return;
            }
        }
        if (o.tribute.size() < Outpost.MAX_TRIBUTE_STACKS) o.tribute.add(stack.copy());
        o.dirtyForClients = true;
    }

    private static void grow(Outpost o, long now, double days, double boost, RandomSource rnd) {
        if (o.residents.size() >= o.populationCap() || o.starving) return;
        if (o.stock(Resource.FOOD) < o.residents.size() * FOOD_PER_DAY * 1.5) return;
        o.growth += days * o.law.guardShare() * (o.boosted(Resource.FOOD, now) ? boost : 1);
        if (o.growth < 1) return;
        o.growth -= 1;
        Role role = nextRole(o, rnd);
        Resident r = OutpostManager.addSettler(o, role, rnd);
        o.log(now, "lordsoutpost.event.settler", r.name, role.id);
    }

    private static void assignSquads(Outpost o) {
        for (Resident r : o.residents) if (r.role.combat && r.squad < 0) r.squad = OutpostManager.pickSquad(o);
    }

    // ============================== Состав поселения ==============================

    private static final Role[] PRIORITY = {Role.STEWARD, Role.BUILDER, Role.LUMBERJACK, Role.MINER, Role.SKOTOVOD, Role.SHIELDMAN,
            Role.SMITH, Role.CARAVANEER, Role.MAGE};

    /** Сколько жителей каждой роли нужно поселению этого тира (для бойцов — общее число, ключ SHIELDMAN). */
    public static int desired(Outpost o, Role role) {
        int t = o.tier;
        return switch (role) {
            case STEWARD -> 1;
            case BUILDER -> o.complete() ? 1 : 1 + t;
            case LUMBERJACK -> t == 1 ? 1 : 2;
            case MINER -> t;
            // Сохраняем суммарную численность прежних двух ролей: охотник + скотовод.
            case SKOTOVOD -> t == 3 ? 4 : 2;
            case SMITH -> t >= 2 ? 1 : 0;
            case CARAVANEER -> t - 1;
            case MAGE -> t >= 3 ? 1 : 0;
            case SHIELDMAN -> (int) Math.round((t == 1 ? 3 : t == 2 ? 6 : 10) * o.law.guardShare());
            default -> 0;
        };
    }

    /** Роль для нового жителя: та, которой сильнее всего не хватает. */
    public static Role nextRole(Outpost o, RandomSource rnd) {
        Role best = Role.SHIELDMAN;
        int bestDeficit = Integer.MIN_VALUE;
        for (Role role : PRIORITY) {
            int have = role == Role.SHIELDMAN ? o.fighters() : o.count(role);
            int deficit = desired(o, role) - have;
            // Стройка стоит без ресурса: следующий поселенец — тот, кто его добывает.
            if (o.stalledOn == Resource.STONE && role == Role.MINER && have < o.tier + 2) deficit += 2;
            if (o.stalledOn == Resource.WOOD && role == Role.LUMBERJACK && have < o.tier + 2) deficit += 2;
            if (deficit > bestDeficit) {
                bestDeficit = deficit;
                best = role;
            }
        }
        return best == Role.SHIELDMAN ? OutpostManager.randomFighter(rnd) : best;
    }

    /** Для проверки чертежей: тир не выше максимального. */
    public static boolean validTier(int tier) {
        return tier >= 1 && tier <= FortBlueprint.MAX_TIER;
    }
}
