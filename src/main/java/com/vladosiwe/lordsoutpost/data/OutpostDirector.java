package com.vladosiwe.lordsoutpost.data;

import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostTask;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Resource;
import com.vladosiwe.lordsoutpost.outpost.Role;
import com.vladosiwe.lordsoutpost.outpost.WorkZone;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outpost AI Director — центральный планировщик поселения. Раз в макро-тик (5 секунд) он оценивает хозяйство
 * и раздаёт работу: выставляет приоритеты профессиям, создаёт задачи кузнецу, следит за брошенными задачами,
 * решает, чем занять свободных строителей, и при голоде приостанавливает необязательную стройку.
 *
 * У жителей нет собственного «универсального ИИ»: каждая профессия — это специализированная цель
 * ({@code WorkGoal}, {@code BuildGoal}), которая берёт работу отсюда.
 */
public final class OutpostDirector {
    /** Базовый приоритет профессии; планировщик поднимает его при нехватке того, что она добывает. */
    public static final int BASE = 50;
    /** Сколько дней еды считается спокойным запасом. */
    private static final double FOOD_SAFE_DAYS = 4;
    /** Кузнец не тратит последние слитки и кожу на перековку: это запас на ремонт. */
    public static final int SMITH_IRON_RESERVE = 3, SMITH_LEATHER_RESERVE = 5;

    private OutpostDirector() {}

    public static void tick(@Nullable ServerLevel level, Outpost o, long now) {
        assess(o);
        housing(level, o, now);
        smithTasks(level, o, now);
        toolTasks(level, o, now);
        housekeeping(o, now);
    }

    // ============================== Оценка хозяйства ==============================

    /** Дней еды в кладовых при текущем населении (готовая пища и пшеница). */
    public static double foodDays(Outpost o) {
        double perDay = Math.max(1, o.residents.size()) * Simulation.FOOD_PER_DAY * o.law.foodUse();
        return (o.stock(Resource.FOOD) + o.stock(Resource.WHEAT)) / Math.max(0.001, perDay);
    }

    static void assess(Outpost o) {
        EnumMap<Role, Integer> p = o.priorities;
        p.clear();
        for (Role r : Role.VALUES) p.put(r, BASE);

        double food = foodDays(o);
        if (food < FOOD_SAFE_DAYS) {
            int boost = food < 1 ? 45 : food < 2 ? 30 : 15;
            p.merge(Role.FARMER, boost, Integer::sum);
            p.merge(Role.SKOTOVOD, boost, Integer::sum);
        }
        // Критическая нехватка еды: строить подождёт, люди важнее стен.
        o.foodCritical = o.starving || food < 0.5;

        // Самый дефицитный строительный ресурс относительно ближайших шагов стройки.
        Resource scarce = null;
        double worst = Double.MAX_VALUE;
        EnumMap<Resource, Integer> needs = QuestBoard.upcomingNeeds(o);
        for (Resource r : new Resource[]{Resource.WOOD, Resource.STONE, Resource.IRON}) {
            int need = needs.getOrDefault(r, 0);
            if (need <= 0) continue;
            double ratio = o.available(r) / need;
            if (ratio < 1 && ratio < worst) {
                worst = ratio;
                scarce = r;
            }
        }
        if (o.stalledOn == Resource.WOOD || o.stalledOn == Resource.STONE || o.stalledOn == Resource.IRON) scarce = o.stalledOn;
        o.scarce = scarce;
        if (scarce == Resource.WOOD) p.merge(Role.LUMBERJACK, 30, Integer::sum);
        if (scarce == Resource.STONE || scarce == Resource.IRON) p.merge(Role.MINER, 30, Integer::sum);

        // Броня бойцов изношена — кузнецу работа важнее.
        double armor = 0;
        int fighters = 0;
        for (Resident r : o.residents) if (r.role.combat && r.role != Role.CAPTAIN) { armor += r.armor; fighters++; }
        if (fighters > 0 && armor / fighters < 0.7) p.merge(Role.SMITH, 25, Integer::sum);
        // Нечем обороняться — нужны бойцы (ключ SHIELDMAN означает «боец»).
        if (o.fighters() < Simulation.desired(o, Role.SHIELDMAN) / 2) p.merge(Role.SHIELDMAN, 20, Integer::sum);
    }

    /**
     * Чем занять строителя, которому сейчас нечего строить: добывать тот ресурс, на котором стоит стройка.
     * Возвращает профессию, работу которой он выполнит, или null — тогда он отдыхает у своего места.
     */
    @Nullable
    public static Role helperRole(Outpost o) {
        if (o.foodCritical) return o.hasZone(WorkZone.FARM) ? Role.FARMER : null;
        if (o.scarce == Resource.WOOD) return Role.LUMBERJACK;
        if (o.scarce == Resource.STONE || o.scarce == Resource.IRON) return o.hasZone(WorkZone.MINE) ? Role.MINER : null;
        return null;
    }

    // ============================== Жильё ==============================

    /** Сколько жителей поселению нужно по его тиру (все профессии и бойцы). */
    public static int wantedPopulation(Outpost o) {
        int n = 0;
        for (Role r : new Role[]{Role.STEWARD, Role.BUILDER, Role.LUMBERJACK, Role.MINER, Role.SKOTOVOD, Role.FARMER,
                Role.SMITH, Role.CARAVANEER, Role.MAGE, Role.SHIELDMAN}) n += Simulation.desired(o, r);
        return n;
    }

    /**
     * Жилая вместимость. Свободных мест не осталось (или осталось одно), а поселению по его тиру нужно больше жителей —
     * значит, новые поселенцы не придут: планировщик советует Лорду дом. Решение за Лордом; только если он сам
     * включил авто-застройку, планировщик заказывает дом сам — через тот же каталог и те же проверки места.
     */
    static void housing(@Nullable ServerLevel level, Outpost o, long now) {
        o.recommended.clear();
        if (o.state != com.vladosiwe.lordsoutpost.outpost.OutpostState.OWNED) return;
        int free = o.populationCap() - o.residents.size();
        int wanted = wantedPopulation(o);
        boolean building = false;
        for (var p : o.projects) if (p.active() && p.type == com.vladosiwe.lordsoutpost.outpost.Project.Type.HOUSE) building = true;
        if (free <= 1 && wanted > o.residents.size() && !building && o.builtProjects(com.vladosiwe.lordsoutpost.outpost.Project.Type.HOUSE) < 8) {
            o.recommended.put(com.vladosiwe.lordsoutpost.outpost.Project.Type.HOUSE, o.residents.size() + "/" + o.populationCap() + "/" + wanted);
        }
        if (o.autoBuild && level != null && o.online && !o.recommended.isEmpty() && !o.foodCritical && now - o.autoBuildTriedAt > 2400) {
            o.autoBuildTriedAt = now;
            for (var type : o.recommended.keySet()) Projects.orderAuto(level, o, type, now, true);
        }
    }

    // ============================== Кузнец ==============================

    /**
     * Держит на доске по одной задаче ремонта на каждого бойца с изношенной бронёй и одну задачу перековки,
     * когда ремонтировать нечего. Устаревшие задачи (боец погиб, броню уже починили вдали) отменяются.
     * Резерв материалов ставится, только пока склад загружен: его бочки — единственный честный счёт предметов.
     */
    static void smithTasks(@Nullable ServerLevel level, Outpost o, long now) {
        // 1. Снять устаревшие.
        for (OutpostTask t : o.tasks.all()) {
            if (!t.active() || (t.type != OutpostTask.Type.REPAIR_ARMOR && t.type != OutpostTask.Type.UPGRADE_GEAR)) continue;
            Resident target = t.subject == null ? null : o.resident(t.subject);
            boolean valid = target != null && target.active() && target.role.combat
                    && (t.type == OutpostTask.Type.REPAIR_ARMOR ? target.armor < 0.95f : target.gear < maxGear(o));
            if (!valid) o.tasks.set(t, OutpostTask.State.CANCELLED, "obsolete", now);
        }
        if (o.count(Role.SMITH) == 0) return;
        // 2. Ремонт.
        boolean repairs = false;
        for (Resident r : o.residents) {
            if (!r.active() || !r.role.combat || r.armor >= 0.95f) continue;
            repairs = true;
            OutpostTask t = o.tasks.find(OutpostTask.Type.REPAIR_ARMOR, r.id);
            if (t == null) {
                t = o.tasks.create(o, OutpostTask.Type.REPAIR_ARMOR, 60 + (int) ((1 - r.armor) * 40), now);
                t.subject = r.id;
                t.zone = WorkZone.SMITHY;
            }
            if (level != null && o.online && t.executor == null && t.reserved.isEmpty()) Logistics.reserve(level, o, t, smithMaterials(t, r), now);
        }
        // 3. Перековка — только когда чинить нечего и есть запас сверх ремонтного.
        if (repairs) return;
        Resident lowest = null;
        for (Resident r : o.residents) {
            if (r.active() && r.role.combat && r.gear < maxGear(o) && (lowest == null || r.gear < lowest.gear)) lowest = r;
        }
        if (lowest == null) return;
        OutpostTask t = o.tasks.find(OutpostTask.Type.UPGRADE_GEAR, lowest.id);
        if (t == null) {
            for (OutpostTask other : o.tasks.all()) if (other.active() && other.type == OutpostTask.Type.UPGRADE_GEAR) return;
            t = o.tasks.create(o, OutpostTask.Type.UPGRADE_GEAR, 40, now);
            t.subject = lowest.id;
            t.zone = WorkZone.SMITHY;
        }
        if (level != null && o.online && t.executor == null && t.reserved.isEmpty()) {
            Map<Item, Integer> want = smithMaterials(t, lowest);
            boolean spare = Logistics.available(level, o, Items.IRON_INGOT, t) >= want.get(Items.IRON_INGOT) + SMITH_IRON_RESERVE
                    && Logistics.available(level, o, Items.LEATHER, t) >= want.get(Items.LEATHER) + SMITH_LEATHER_RESERVE;
            if (spare) Logistics.reserve(level, o, t, want, now);
            else o.tasks.set(t, OutpostTask.State.PAUSED, "keeping repair reserve", now);
        }
    }

    /** Чем владеет поселение: в бочках склада и в ношах его жителей (только пока чанки загружены). */
    public static int owned(ServerLevel level, Outpost o, Item item) {
        int n = Storehouse.countItem(level, o, item);
        for (Resident r : o.residents) {
            if (r.entity != null && level.getEntity(r.entity) instanceof com.vladosiwe.lordsoutpost.entity.WorkerEntity w) {
                for (int i = 0; i < w.inventory.getContainerSize(); i++) if (w.inventory.getItem(i).is(item)) n += w.inventory.getItem(i).getCount();
            }
        }
        return n;
    }

    /**
     * Инструменты скотовода: ножницы и ведро. Если в поселении их нет, кузнецу ставится заказ из настоящих слитков.
     * Заказ, ставший ненужным (инструмент нашёлся, его положил игрок), снимается, пока за него не взялись.
     */
    static void toolTasks(@Nullable ServerLevel level, Outpost o, long now) {
        if (level == null || !o.online || o.state != com.vladosiwe.lordsoutpost.outpost.OutpostState.OWNED) return;
        boolean herders = o.count(Role.SKOTOVOD) > 0, smith = o.count(Role.SMITH) > 0;
        // Кирку лучше той, что есть у шахтёров, куёт кузнец, если её позволяют тир форта и уровень шахтёров.
        int pickTier = 0;
        for (Resident r : o.residents) if (r.role == Role.MINER) pickTier = Math.max(pickTier, com.vladosiwe.lordsoutpost.entity.WorkerEntity.minerToolTier(o, r));
        int ownedTier = 0;
        for (Item p : new Item[]{Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.STONE_PICKAXE}) {
            if (owned(level, o, p) > 0) ownedTier = Math.max(ownedTier, com.vladosiwe.lordsoutpost.entity.ai.WorkGoal.pickaxeTier(p));
        }
        Item pick = pickTier >= 3 && ownedTier < 3 && Storehouse.countItem(level, o, Items.DIAMOND) >= 3 ? Items.DIAMOND_PICKAXE
                : pickTier >= 2 && ownedTier < 2 ? Items.IRON_PICKAXE : null;
        for (Item product : new Item[]{Items.SHEARS, Items.BUCKET, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE}) {
            boolean tool = product == Items.IRON_PICKAXE || product == Items.DIAMOND_PICKAXE;
            boolean needed = smith && (tool ? product == pick : herders && owned(level, o, product) == 0
                    && (product != Items.BUCKET || Storehouse.countItem(level, o, Items.MILK_BUCKET) < 4));
            OutpostTask t = null;
            for (OutpostTask x : o.tasks.all()) if (x.active() && x.type == OutpostTask.Type.FORGE_TOOL && x.product == product) t = x;
            if (!needed) {
                if (t != null && t.executor == null) o.tasks.set(t, OutpostTask.State.CANCELLED, "not needed", now);
                continue;
            }
            if (t == null) {
                t = o.tasks.create(o, OutpostTask.Type.FORGE_TOOL, 45, now);
                t.product = product;
                t.zone = WorkZone.SMITHY;
            }
            if (t.executor == null && t.reserved.isEmpty()) {
                Map<Item, Integer> recipe = recipe(product);
                if (tool) {
                    // Рукоять — палки из бревна, как на верстаке: 1 бревно → 8 палок, нужно 2, остаток идёт на склад.
                    Item log = Storehouse.findItem(level, o, st -> st.is(net.minecraft.tags.ItemTags.LOGS));
                    if (log == null) {
                        o.tasks.set(t, OutpostTask.State.PAUSED, "no logs", now);
                        continue;
                    }
                    recipe.put(log, 1);
                }
                Logistics.reserve(level, o, t, recipe, now);
            }
        }
    }

    /** Настоящий рецепт верстака: ведро — 3 слитка, ножницы — 2. */
    public static Map<Item, Integer> recipe(@Nullable Item product) {
        Map<Item, Integer> m = new LinkedHashMap<>();
        if (product == Items.BUCKET) m.put(Items.IRON_INGOT, 3);
        else if (product == Items.SHEARS) m.put(Items.IRON_INGOT, 2);
        else if (product == Items.IRON_PICKAXE) m.put(Items.IRON_INGOT, 3);
        else if (product == Items.DIAMOND_PICKAXE) m.put(Items.DIAMOND, 3);
        return m;
    }

    /** Материалы любой кузнечной задачи. */
    public static Map<Item, Integer> materials(OutpostTask t, @Nullable Resident target) {
        if (t.type == OutpostTask.Type.FORGE_TOOL) {
            // Состав заказа зафиксирован резервом (в него входит и конкретное бревно на рукоять).
            Map<Item, Integer> m = new LinkedHashMap<>(recipe(t.product));
            for (var e : (t.carried.isEmpty() ? t.reserved : t.carried).entrySet()) m.putIfAbsent(e.getKey(), e.getValue());
            return m;
        }
        return target == null ? Map.of() : smithMaterials(t, target);
    }

    public static int maxGear(Outpost o) {
        return Math.min(Resident.MAX_GEAR, o.tier);
    }

    /** Реальные материалы операции: ремонт — слиток и кожа, перековка — по 3 на каждый следующий уровень. */
    public static Map<Item, Integer> smithMaterials(OutpostTask t, Resident target) {
        int n = t.type == OutpostTask.Type.REPAIR_ARMOR ? 1 : 3 * (target.gear + 1);
        Map<Item, Integer> m = new LinkedHashMap<>();
        m.put(Items.IRON_INGOT, n);
        m.put(Items.LEATHER, n);
        return m;
    }

    /** Результат работы кузнеца применяется к бойцу один раз — и при физической работе, и вдали. */
    public static void applySmithResult(Outpost o, OutpostTask t, Resident target) {
        if (t.type == OutpostTask.Type.REPAIR_ARMOR) target.armor = Math.min(1f, target.armor + 0.25f);
        else target.gear = Math.min(maxGear(o), target.gear + 1);
        o.dirtyForClients = true;
    }

    // ============================== Порядок ==============================

    /**
     * Брошенные задачи: исполнитель исчез из записи или ушёл с караваном, задача давно не обновлялась.
     * Планируемая задача возвращается в очередь (резерв сохраняется), личный запрос отменяется.
     */
    static void housekeeping(Outpost o, long now) {
        for (OutpostTask t : o.tasks.all()) {
            if (!t.active() || t.executor == null) continue;
            Resident r = o.resident(t.executor);
            boolean gone = r == null || !r.active() || (o.online && r.entity == null);
            boolean stale = now - t.updatedAt > com.vladosiwe.lordsoutpost.outpost.TaskBoard.STALE;
            if (gone || stale) Logistics.cancel(o, t, gone ? "executor gone" : "stale", now);
        }
        o.tasks.prune();
    }
}
