package com.vladosiwe.lordsoutpost.data;

import com.vladosiwe.lordsoutpost.blueprint.FortBlueprint;
import com.vladosiwe.lordsoutpost.blueprint.Step;
import com.vladosiwe.lordsoutpost.compat.MagicLoot;
import com.vladosiwe.lordsoutpost.config.OutpostConfig;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostTask;
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
    /** Офлайн-выработка одного фермера: пшеница и возврат семян в оборот. */
    public static final double FARM_WHEAT_PER_DAY = 24;
    public static final double FARM_SEEDS_PER_DAY = 4;
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
        // Центральный планировщик: приоритеты профессий, задачи кузнеца, брошенные задачи, пауза стройки при голоде.
        OutpostDirector.tick(level, o, now);
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
        int farmers = virtualWorkers(o, Role.FARMER, now);
        o.add(Resource.WHEAT, farmers * FARM_WHEAT_PER_DAY * prod * (o.boosted(Resource.WHEAT, now) ? boost : 1));
        o.add(Resource.SEEDS, farmers * FARM_SEEDS_PER_DAY * prod);
        int miners = virtualWorkers(o, Role.MINER, now);
        o.add(Resource.STONE, miners * STONE_PER_DAY * prod * (o.boosted(Resource.STONE, now) ? boost : 1));
        // Железо вдали — виртуальная добыча. Шахтёр, работающий в мире, приносит только реально добытую руду:
        // абстрактная «промывка» рядом с игроком давала железо из ничего.
        o.add(Resource.IRON, miners * 24 * prod * (o.boosted(Resource.IRON, now) ? boost : 1));
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

        // Еда: сначала готовые продукты, затем пшеница. Так запас зерна сохраняется для хлеба,
        // пока в кладовой есть другая пища.
        double eat = o.residents.size() * FOOD_PER_DAY * days * o.law.foodUse();
        boolean wasStarving = o.starving;
        double fromFood = Math.min(o.stock(Resource.FOOD), eat);
        if (fromFood > 0) o.take(Resource.FOOD, fromFood);
        double remainingFood = Math.max(0, eat - fromFood);
        double fromWheat = Math.min(o.stock(Resource.WHEAT), remainingFood);
        if (fromWheat > 0) o.take(Resource.WHEAT, fromWheat);
        if (fromFood + fromWheat + 1e-6 < eat) {
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
        boolean projects = false;
        for (com.vladosiwe.lordsoutpost.outpost.Project p : o.projects) if (p.active()) projects = true;
        if (o.complete() && !projects) return;
        List<Step> plan = o.nextPlan();
        int builders = o.activeCount(Role.BUILDER);
        boolean helped = o.boosted(Resource.WOOD, now) || o.boosted(Resource.STONE, now) || o.boosted(Resource.IRON, now);
        double rate = builders * BUILDER_STEPS_PER_DAY * OutpostConfig.get(OutpostConfig.BUILD_SPEED, 1.0) * o.law.buildSpeed()
                * (helped ? boost : 1) * (o.starving ? 0.5 : 1);
        // Голод вдали от игрока: стройка ждёт, накопленный кредит шагов сохраняется до конца голода.
        // Рядом с игроком строители не стоят без дела — строят вдвое медленнее (голодные), пока поле и охота кормят форт.
        if (o.foodCritical && !o.online) return;
        o.buildCredit = Math.min(MAX_BUILD_CREDIT + rate * days, o.buildCredit + rate * days);
        while (o.buildCredit >= 1 && o.steps < plan.size()) {
            Resource cost = plan.get(o.steps).cost();
            // Отложенное под задачи жителей (материалы кузнеца и т. п.) на стройку не идёт.
            if (cost != null && (o.available(cost) < 1 || !o.take(cost, 1))) {
                o.stalledOn = cost;
                o.buildCredit = Math.min(o.buildCredit, MAX_BUILD_CREDIT);
                break;
            }
            o.steps++;
            o.buildCredit -= 1;
        }
        // Заказанные Лордом постройки получают то, что осталось после плана тира.
        o.buildCredit = Projects.pay(o, o.buildCredit);
        Projects.prune(o);
        o.dirtyForClients = true;
        // Тир считается достроенным, когда выполнен весь план. Если чанки выгружены, блоки встанут при возвращении игрока.
        if (!o.complete() && o.steps >= plan.size()) {
            boolean physicalDone = o.placedTier == o.tier + 1 && o.placed >= plan.size() && o.claims.isEmpty();
            if (!o.online || physicalDone) {
                o.tier++;
                o.steps = 0;
                if (physicalDone) {
                    o.placedTier = o.tier + 1;
                    o.placed = 0;
                }
                // Индексы отложенных шагов относились к плану прежнего тира.
                o.deferredSteps.clear();
                o.stepFailures.clear();
                o.doneBits.clear();
                o.buildCredit = 0;
                o.log(now, "lordsoutpost.event.tier_up", o.tier);
            }
        }
    }

    /**
     * Кузнец вдали от игрока выполняет те же задачи с доски, что и в мире (ремонт, затем перековка),
     * тратя тот же объём железа и кожи. Задачу, выполненную виртуально, физический кузнец уже не увидит:
     * учёт общий, работа не повторяется.
     */
    private static void smith(Outpost o, long now, double days, double boost) {
        int smiths = virtualWorkers(o, Role.SMITH, now);
        if (smiths == 0) return;
        boolean helped = o.boosted(Resource.HIDES, now) || o.boosted(Resource.IRON, now);
        o.smithWork = Math.min(8, o.smithWork + smiths * 30 * days * (helped ? boost : 1));
        while (o.smithWork >= 1) {
            // Инструменты куются только в настоящей кузнице: вдали им негде лечь, кроме бочек склада.
            OutpostTask task = o.tasks.nextFor(Role.SMITH, t -> t.type != OutpostTask.Type.FORGE_TOOL);
            if (task == null) return;
            Resident target = task.subject == null ? null : o.resident(task.subject);
            if (target == null) {
                o.tasks.set(task, OutpostTask.State.CANCELLED, "obsolete", now);
                continue;
            }
            var materials = OutpostDirector.smithMaterials(task, target);
            double iron = materials.get(Items.IRON_INGOT) * 4.0, hides = materials.get(Items.LEATHER) * 2.0;
            // Своё отложенное задача может тратить; чужое — нет. Перековка не трогает ремонтный запас.
            double ownIron = task.reservedCount(Items.IRON_INGOT) * 4.0, ownHides = task.reservedCount(Items.LEATHER) * 2.0;
            double spareIron = task.type == OutpostTask.Type.UPGRADE_GEAR && ownIron == 0 ? OutpostDirector.SMITH_IRON_RESERVE * 4.0 : 0;
            double spareHides = task.type == OutpostTask.Type.UPGRADE_GEAR && ownHides == 0 ? OutpostDirector.SMITH_LEATHER_RESERVE * 2.0 : 0;
            if (o.available(Resource.IRON) + ownIron < iron + spareIron || o.available(Resource.HIDES) + ownHides < hides + spareHides) {
                o.tasks.set(task, OutpostTask.State.PAUSED, "no materials", now);
                return;
            }
            task.reserved.clear();
            o.take(Resource.IRON, iron);
            o.take(Resource.HIDES, hides);
            OutpostDirector.applySmithResult(o, task, target);
            o.tasks.set(task, OutpostTask.State.COMPLETED, "virtual", now);
            o.smithWork -= 1;
        }
    }

    /**
     * Придворный маг вдали от игрока. Эссенция больше не берётся из ничего: маг тратит только ту, что поселение
     * получило по-настоящему (вклады по заданиям Доски), и кладёт сделанное на склад (выложится в бочки при возвращении).
     * Рядом с игроком маг работает руками (MageWorkGoal), и здесь его выработка не считается.
     */
    private static void mage(ServerLevel level, Outpost o, long now, double days, double boost, RandomSource rnd) {
        int mages = o.tier >= 3 ? o.activeCount(Role.MAGE) : 0;
        if (mages == 0) return;
        boolean physical = o.online && (o.pyromancer != null || virtualWorkers(o, Role.MAGE, now) == 0);
        if (physical) return;
        double mult = o.law.magic() * (o.boosted(Resource.ARCANE, now) ? boost : 1);
        o.arcaneWork += mages * 1.2 * days * mult;
        while (o.arcaneWork >= 1 && o.stock(Resource.ARCANE) >= 20) {
            o.arcaneWork -= 1;
            o.take(Resource.ARCANE, 20);
            for (ItemStack stack : MagicLoot.mageProduct(level, rnd)) if (!stack.isEmpty()) o.storeItems.add(stack);
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

    /**
     * Кладёт предмет в дань. Предметы никогда не выбрасываются: при переполнении сначала ресурсы уходят
     * на склад (если там есть место), а остальное копится сверх обычного предела в 54 стопки до тех пор,
     * пока Лорд не разберёт Сундук Дани. Переданный стек опустошается.
     */
    public static void addTribute(Outpost o, ItemStack stack) {
        if (stack.isEmpty()) return;
        for (ItemStack have : o.tribute) {
            if (ItemStack.isSameItemSameComponents(have, stack) && have.getCount() < have.getMaxStackSize()) {
                int move = Math.min(stack.getCount(), have.getMaxStackSize() - have.getCount());
                have.grow(move);
                stack.shrink(move);
                if (stack.isEmpty()) {
                    o.dirtyForClients = true;
                    return;
                }
            }
        }
        if (o.tribute.size() >= Outpost.MAX_TRIBUTE_STACKS) {
            // Сундук Дани переполнен: ресурс лучше положить на склад, чем держать мёртвым грузом.
            ResourceItems.Value v = ResourceItems.of(stack);
            if (v != null && Storehouse.physical(v.resource())) {
                double room = Math.max(0, o.capacity(v.resource()) - o.stock(v.resource()));
                int accepted = Math.min(stack.getCount(), (int) Math.floor((room + 1e-9) / v.perItem()));
                if (accepted > 0) {
                    o.add(v.resource(), accepted * v.perItem());
                    stack.shrink(accepted);
                }
            }
            if (!stack.isEmpty() && o.tribute.size() == TRIBUTE_WARN_STACKS) {
                com.vladosiwe.lordsoutpost.LordsOutpost.LOGGER.warn("Форт #{}: в дани {} стопок, Сундук Дани давно не разбирали",
                        Long.toHexString(o.id), o.tribute.size());
            }
        }
        while (!stack.isEmpty()) {
            int n = Math.min(stack.getCount(), stack.getMaxStackSize());
            o.tribute.add(stack.copyWithCount(n));
            stack.shrink(n);
        }
        o.dirtyForClients = true;
    }

    private static final int TRIBUTE_WARN_STACKS = 512;

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

    private static final Role[] PRIORITY = {Role.STEWARD, Role.BUILDER, Role.LUMBERJACK, Role.MINER, Role.SKOTOVOD, Role.FARMER, Role.SHIELDMAN,
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
            case FARMER -> 1;
            case SMITH -> t >= 2 ? 1 : 0;
            case CARAVANEER -> t - 1;
            // Погибший маг сменяется не сразу: новый придёт к башне после срока.
            case MAGE -> t >= 3 && o.lastSim >= o.pyromancerRejoinAt ? 1 : 0;
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
            // Планировщик поднял приоритет профессии (голод, дефицит ресурса, износ брони): поселенец скорее придёт к ней.
            if (deficit > 0) deficit += Math.max(0, o.priorities.getOrDefault(role, OutpostDirector.BASE) - OutpostDirector.BASE) / 15;
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
