package com.vladosiwe.lordsoutpost.data;

import com.vladosiwe.lordsoutpost.blueprint.Marker;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Quest;
import com.vladosiwe.lordsoutpost.outpost.Resource;
import com.vladosiwe.lordsoutpost.server.Captures;
import com.vladosiwe.lordsoutpost.server.NpcLifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Склад форта. Пока чанки загружены, бочки склада — это и есть запасы поселения, только в виде предметов:
 *  - жители кладут в них добычу — запас растёт;
 *  - стройка, еда и набеги расходуют запас — предметы исчезают из бочек;
 *  - игрок (Лорд или союзник) кладёт ресурсы — запас растёт и засчитывается в задания Доски; забирает — запас падает.
 * Вдали от игрока запасы живут как числа, а при возвращении бочки снова приводятся в соответствие.
 *
 * Чтобы отличить действия игрока от работы жителей, хранится последнее измеренное содержимое бочек (storeLast):
 * всё, что изменилось помимо кода мода, сделал игрок.
 */
public final class Storehouse {
    /** Ресурсы, которые лежат в бочках. Казна и эссенция хранятся в цитадели и в бочки не выкладываются. */
    public static final Resource[] PHYSICAL = {Resource.WOOD, Resource.STONE, Resource.IRON, Resource.FOOD, Resource.WHEAT, Resource.SEEDS, Resource.HIDES};

    private Storehouse() {}

    public static boolean physical(Resource r) {
        for (Resource p : PHYSICAL) if (p == r) return true;
        return false;
    }

    /** Предмет, которым запас выкладывается в бочки, когда его больше, чем лежит. */
    public static ItemStack representative(Outpost o, Resource r) {
        return switch (r) {
            case WOOD -> new ItemStack(o.palette.log);
            case STONE -> new ItemStack(Items.COBBLESTONE);
            case IRON -> new ItemStack(Items.IRON_INGOT);
            case FOOD -> new ItemStack(Items.BREAD);
            case WHEAT -> new ItemStack(Items.WHEAT);
            case SEEDS -> new ItemStack(Items.WHEAT_SEEDS);
            case HIDES -> new ItemStack(Items.LEATHER);
            default -> ItemStack.EMPTY;
        };
    }

    /** Бочки склада текущего тира, которые сейчас загружены. */
    public static List<Container> barrels(ServerLevel level, Outpost o) {
        List<Container> out = new ArrayList<>();
        for (BlockPos pos : o.markers(Marker.STOCKPILE)) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof Container c) out.add(c);
        }
        return out;
    }

    public static EnumMap<Resource, Double> measure(List<Container> barrels) {
        EnumMap<Resource, Double> m = new EnumMap<>(Resource.class);
        for (Resource r : PHYSICAL) m.put(r, 0.0);
        for (Container c : barrels) {
            for (int s = 0; s < c.getContainerSize(); s++) {
                ResourceItems.Value v = ResourceItems.of(c.getItem(s));
                if (v != null && physical(v.resource())) m.merge(v.resource(), v.perItem() * c.getItem(s).getCount(), Double::sum);
            }
        }
        return m;
    }

    /** Раз в две секунды, пока игрок рядом. */
    public static void sync(ServerLevel level, Outpost o, long now) {
        if (o.state != OutpostState.OWNED) return;
        List<Container> barrels = barrels(level, o);
        if (barrels.isEmpty()) return;
        EnumMap<Resource, Double> have = measure(barrels);
        if (o.storeLast.isEmpty()) o.storeLast.putAll(have);
        for (Resource r : PHYSICAL) {
            // 1. Всё, что изменилось в бочках с прошлого раза помимо мода, сделал игрок.
            double delta = have.get(r) - o.storeLast.getOrDefault(r, 0.0);
            if (delta > 0.01) {
                Quest done = QuestBoard.deliver(o, r, delta, now);
                if (done != null) {
                    ServerPlayer lord = NpcLifecycle.lord(level, o);
                    if (lord != null) Captures.questReward(lord, o, done);
                }
            } else if (delta < -0.01) {
                o.setStock(r, Math.max(0, o.stock(r) + delta));
            }
            // 2. Привести бочки к запасу: лишнее убрать (потрачено), недостающее выложить (добыто вдали или за счёт симуляции).
            double target = o.stock(r);
            double cur = have.get(r);
            ItemStack rep = representative(o, r);
            ResourceItems.Value rv = ResourceItems.of(rep);
            double unit = rv == null ? 1 : rv.perItem();
            if (cur - target >= 1.0) {
                cur -= remove(barrels, r, cur - target);
            } else if (target - cur >= unit && rv != null) {
                int items = (int) Math.floor((target - cur) / unit);
                int put = insert(barrels, rep.copyWithCount(1), items);
                cur += put * unit;
            }
            o.storeLast.put(r, cur);
        }
    }

    /** Убирает предметы ресурса общей стоимостью не меньше amount; возвращает убранную стоимость. */
    private static double remove(List<Container> barrels, Resource r, double amount) {
        double removed = 0;
        for (Container c : barrels) {
            for (int s = 0; s < c.getContainerSize() && removed < amount; s++) {
                ItemStack st = c.getItem(s);
                ResourceItems.Value v = ResourceItems.of(st);
                if (v == null || v.resource() != r) continue;
                int n = (int) Math.min(st.getCount(), Math.ceil((amount - removed) / v.perItem()));
                st.shrink(n);
                removed += n * v.perItem();
                c.setChanged();
            }
        }
        return removed;
    }

    /** Кладёт до count штук предмета; возвращает, сколько поместилось. */
    private static int insert(List<Container> barrels, ItemStack proto, int count) {
        int left = count;
        for (Container c : barrels) {
            for (int s = 0; s < c.getContainerSize() && left > 0; s++) {
                ItemStack st = c.getItem(s);
                if (st.isEmpty()) {
                    int n = Math.min(left, proto.getMaxStackSize());
                    c.setItem(s, proto.copyWithCount(n));
                    left -= n;
                } else if (ItemStack.isSameItemSameComponents(st, proto) && st.getCount() < st.getMaxStackSize()) {
                    int n = Math.min(left, st.getMaxStackSize() - st.getCount());
                    st.grow(n);
                    left -= n;
                }
            }
            c.setChanged();
        }
        return count - left;
    }

    /** Считает конкретный предмет в бочках, не смешивая его с другими видами того же ресурса. */
    public static int countItem(ServerLevel level, Outpost o, net.minecraft.world.item.Item item) {
        int count = 0;
        for (Container c : barrels(level, o)) for (int s = 0; s < c.getContainerSize(); s++) {
            ItemStack st = c.getItem(s);
            if (st.is(item)) count += st.getCount();
        }
        return count;
    }

    /**
     * Забирает реальные предметы из бочек для производственного процесса NPC.
     * Сначала проверяет весь объём, затем меняет и инвентарь, и числовой запас.
     */
    public static boolean takeItem(ServerLevel level, Outpost o, net.minecraft.world.item.Item item, int count) {
        return takeStacks(level, o, item, count) != null;
    }

    /**
     * То же, но возвращает именно вынутые стопки — с их прочностью и прочими данными, чтобы изношенный инструмент
     * не превратился в новый. null — если столько предметов нет (тогда ничего не меняется).
     */
    @org.jetbrains.annotations.Nullable
    public static List<ItemStack> takeStacks(ServerLevel level, Outpost o, net.minecraft.world.item.Item item, int count) {
        if (count <= 0) return List.of();
        ResourceItems.Value value = ResourceItems.of(new ItemStack(item));
        // Предметы без ресурсной стоимости (вёдра, ножницы, факелы, уголь) лежат в бочках сами по себе и в запас не входят.
        // Казна и эссенция (изумруды, алмазы, аметист) в бочках — просто предметы: в запас они не входят и берутся как есть.
        boolean counted = value != null && physical(value.resource());
        double amount = counted ? count * value.perItem() : 0;
        if (counted && o.stock(value.resource()) + 1e-6 < amount) return null;
        List<Container> barrels = barrels(level, o);
        int available = 0;
        for (Container c : barrels) for (int s = 0; s < c.getContainerSize(); s++) {
            ItemStack st = c.getItem(s);
            if (st.is(item)) available += st.getCount();
        }
        if (available < count) return null;
        List<ItemStack> out = new ArrayList<>();
        int left = count;
        for (Container c : barrels) {
            for (int s = 0; s < c.getContainerSize() && left > 0; s++) {
                ItemStack st = c.getItem(s);
                if (!st.is(item)) continue;
                int n = Math.min(left, st.getCount());
                out.add(st.split(n));
                left -= n;
                c.setChanged();
            }
            if (left == 0) break;
        }
        if (counted) {
            o.take(value.resource(), amount);
            o.storeLast.merge(value.resource(), -amount, Double::sum);
        }
        o.dirtyForClients = true;
        return out;
    }

    /** Первый предмет в бочках, подходящий под условие (например, любое бревно), или null. */
    @org.jetbrains.annotations.Nullable
    public static net.minecraft.world.item.Item findItem(ServerLevel level, Outpost o, java.util.function.Predicate<ItemStack> test) {
        for (Container c : barrels(level, o)) for (int s = 0; s < c.getContainerSize(); s++) {
            ItemStack st = c.getItem(s);
            if (!st.isEmpty() && test.test(st)) return st.getItem();
        }
        return null;
    }

    /**
     * Житель разгружает свою ношу. Ресурсы идут в бочки и в запас (не как вклад игрока, поэтому не в задания),
     * остальное — тоже в бочки (уголь, перья, кости — пригодятся Лорду). Не поместилось — в дань.
     */
    public static void deposit(ServerLevel level, Outpost o, Container inventory) {
        List<Container> barrels = barrels(level, o);
        for (int s = 0; s < inventory.getContainerSize(); s++) {
            ItemStack st = inventory.getItem(s);
            if (st.isEmpty()) continue;
            ResourceItems.Value v = ResourceItems.of(st);
            boolean res = v != null && physical(v.resource());
            int count = st.getCount();
            if (res) {
                // Сверх ёмкости склада ресурс не принимается — излишек уходит Лорду в дань.
                double room = o.capacity(v.resource()) - o.stock(v.resource());
                count = (int) Math.max(0, Math.min(count, Math.floor(room / v.perItem())));
            }
            int put = barrels.isEmpty() ? 0 : insert(barrels, st.copyWithCount(1), count);
            if (res && put > 0) {
                double value = put * v.perItem();
                o.add(v.resource(), value);
                o.storeLast.merge(v.resource(), value, Double::sum);
            } else if (res && barrels.isEmpty()) {
                put = count;
                o.add(v.resource(), put * v.perItem());
            }
            o.depositedItems += st.getCount();
            ItemStack rest = st.copyWithCount(st.getCount() - put);
            if (!rest.isEmpty()) Simulation.addTribute(o, rest);
            inventory.setItem(s, ItemStack.EMPTY);
        }
        o.dirtyForClients = true;
    }

    /** Житель выгрузился вместе с чанком, не донеся ношу: её ресурсы засчитываются сразу, прочее — в дань. */
    public static void depositVirtually(Outpost o, Container inventory) {
        for (int s = 0; s < inventory.getContainerSize(); s++) {
            ItemStack st = inventory.getItem(s);
            if (st.isEmpty()) continue;
            ResourceItems.Value v = ResourceItems.of(st);
            if (v != null && physical(v.resource())) {
                // При выгрузке чанка ноша проходит те же ограничения ёмкости, что и обычная разгрузка.
                // Излишек сохраняется в дани, а не исчезает из-за ограничения Outpost.add().
                double room = Math.max(0, o.capacity(v.resource()) - o.stock(v.resource()));
                int accepted = Math.min(st.getCount(), (int) Math.floor((room + 1e-9) / v.perItem()));
                if (accepted > 0) o.add(v.resource(), accepted * v.perItem());
                ItemStack excess = st.copyWithCount(st.getCount() - accepted);
                if (!excess.isEmpty()) Simulation.addTribute(o, excess);
            } else {
                Simulation.addTribute(o, st.copy());
            }
            inventory.setItem(s, ItemStack.EMPTY);
        }
        o.dirtyForClients = true;
    }

    /** Стройка сносит бочку склада: её содержимое уже учтено в запасе, поэтому просто забыть его. */
    public static void forgetBarrel(Outpost o, Container barrel) {
        EnumMap<Resource, Double> m = measure(List.of(barrel));
        for (Resource r : PHYSICAL) o.storeLast.merge(r, -m.get(r), Double::sum);
    }
}
