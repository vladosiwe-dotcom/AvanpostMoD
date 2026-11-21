package com.vladosiwe.lordsoutpost.data;

import com.vladosiwe.lordsoutpost.entity.WorkerEntity;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostTask;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Единая логистика склада. Любой работник, которому нужны материалы, идёт одним путём:
 * <ol>
 *   <li>запрос: задача с перечнем предметов;</li>
 *   <li>проверка: на складе есть столько, сколько не отложено под другие задачи;</li>
 *   <li>резерв: предметы остаются в бочках, но другим недоступны;</li>
 *   <li>выдача: у склада работник забирает их в ношу (реальные предметы из бочек);</li>
 *   <li>работа: предметы расходуются из ноши;</li>
 *   <li>результат идёт на склад обычной разгрузкой;</li>
 *   <li>отмена: резерв снимается, а взятое остаётся в ноше и при разгрузке возвращается в бочки.</li>
 * </ol>
 * Два работника не могут потратить одни и те же предметы: резерв проверяется против всех активных задач.
 */
public final class Logistics {
    private Logistics() {}

    /** Сколько штук предмета можно отложить под задачу: лежит в бочках минус отложенное под другие задачи. */
    public static int available(ServerLevel level, Outpost o, Item item, OutpostTask except) {
        return Math.max(0, Storehouse.countItem(level, o, item) - o.tasks.reserved(item, except));
    }

    /** Резервирует все предметы разом или ничего. */
    public static boolean reserve(ServerLevel level, Outpost o, OutpostTask task, Map<Item, Integer> want, long now) {
        for (var e : want.entrySet()) {
            int already = task.reservedCount(e.getKey());
            if (available(level, o, e.getKey(), task) + already < e.getValue()) {
                o.tasks.set(task, OutpostTask.State.PAUSED, "no " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getKey()).getPath(), now);
                return false;
            }
        }
        task.reserved.clear();
        task.reserved.putAll(want);
        o.tasks.set(task, OutpostTask.State.RESERVED, "", now);
        o.dirtyForClients = true;
        return true;
    }

    /** Личный запрос работника: задача создаётся, назначается ему и сразу резервирует материалы (или не создаётся). */
    public static OutpostTask request(ServerLevel level, Outpost o, Resident executor, OutpostTask.Type type, Map<Item, Integer> want, long now) {
        OutpostTask task = o.tasks.create(o, type, 50, now);
        task.executor = executor.id;
        if (!reserve(level, o, task, new LinkedHashMap<>(want), now)) {
            o.tasks.set(task, OutpostTask.State.CANCELLED, task.reason, now);
            return null;
        }
        return task;
    }

    /**
     * Работник у склада забирает зарезервированное в ношу. Предметы реально списываются из бочек и запаса.
     * Если в ноше нет места или бочки разошлись с записью, ничего не берётся и задача остаётся в резерве.
     */
    public static boolean withdraw(ServerLevel level, Outpost o, OutpostTask task, WorkerEntity worker, long now) {
        return withdraw(level, o, task, worker.inventory, worker.residentId(), now);
    }

    /** То же для любой ноши: например, сумки придворного мага, которая хранится в записи аванпоста. */
    public static boolean withdraw(ServerLevel level, Outpost o, OutpostTask task, net.minecraft.world.Container cargo,
                                   @org.jetbrains.annotations.Nullable java.util.UUID executor, long now) {
        if (task.reserved.isEmpty()) return task.state == OutpostTask.State.IN_PROGRESS;
        int slots = 0;
        for (var e : task.reserved.entrySet()) slots += (int) Math.ceil(e.getValue() / (double) new ItemStack(e.getKey()).getMaxStackSize());
        int free = 0;
        for (int i = 0; i < cargo.getContainerSize(); i++) if (cargo.getItem(i).isEmpty()) free++;
        if (free < slots) {
            task.reason = "cargo full";
            return false;
        }
        java.util.List<ItemStack> taken = new java.util.ArrayList<>();
        for (var e : task.reserved.entrySet()) {
            java.util.List<ItemStack> stacks = Storehouse.takeStacks(level, o, e.getKey(), e.getValue());
            if (stacks == null) {
                // Не вышло взять всё: уже взятое возвращается в бочки тем же путём, что и любая ноша.
                restock(level, o, taken);
                o.tasks.set(task, OutpostTask.State.PAUSED, "stock mismatch", now);
                task.reserved.clear();
                return false;
            }
            taken.addAll(stacks);
        }
        java.util.List<ItemStack> back = new java.util.ArrayList<>();
        for (ItemStack st : taken) {
            int count = st.getCount();
            ItemStack left = addTo(cargo, st);
            if (!left.isEmpty()) back.add(left);
            task.carried.merge(st.getItem(), count - left.getCount(), Integer::sum);
        }
        restock(level, o, back);
        task.reserved.clear();
        task.executor = executor;
        o.tasks.set(task, OutpostTask.State.IN_PROGRESS, "", now);
        OutpostSavedData.get(level).setDirty();
        return true;
    }

    /** Кладёт стопку в контейнер (слияние, затем пустые слоты); возвращает остаток. */
    public static ItemStack addTo(net.minecraft.world.Container c, ItemStack stack) {
        ItemStack left = stack.copy();
        for (int i = 0; i < c.getContainerSize() && !left.isEmpty(); i++) {
            ItemStack have = c.getItem(i);
            if (!have.isEmpty() && ItemStack.isSameItemSameComponents(have, left) && have.getCount() < have.getMaxStackSize()) {
                int n = Math.min(left.getCount(), have.getMaxStackSize() - have.getCount());
                have.grow(n);
                left.shrink(n);
            }
        }
        for (int i = 0; i < c.getContainerSize() && !left.isEmpty(); i++) {
            if (c.getItem(i).isEmpty()) {
                c.setItem(i, left.split(Math.min(left.getCount(), left.getMaxStackSize())));
            }
        }
        c.setChanged();
        return left;
    }

    /** Расходует предмет задачи из ноши работника. Возвращает false, если предмета в ноше нет. */
    public static boolean consume(Outpost o, OutpostTask task, WorkerEntity worker, Item item, int count, long now) {
        int have = 0;
        for (int i = 0; i < worker.inventory.getContainerSize(); i++) {
            ItemStack st = worker.inventory.getItem(i);
            if (st.is(item)) have += st.getCount();
        }
        if (have < count) return false;
        int left = count;
        for (int i = 0; i < worker.inventory.getContainerSize() && left > 0; i++) {
            ItemStack st = worker.inventory.getItem(i);
            if (!st.is(item)) continue;
            int n = Math.min(left, st.getCount());
            st.shrink(n);
            left -= n;
        }
        worker.inventory.setChanged();
        task.carried.computeIfPresent(item, (k, v) -> v - count > 0 ? v - count : null);
        task.updatedAt = now;
        return true;
    }

    public static void complete(Outpost o, OutpostTask task, long now) {
        o.tasks.set(task, OutpostTask.State.COMPLETED, "", now);
        o.dirtyForClients = true;
    }

    /**
     * Отмена. Резерв на складе снимается сразу; то, что работник уже унёс, остаётся у него в ноше и вернётся
     * в бочки при ближайшей разгрузке — предмет не исчезает и не выдаётся повторно.
     */
    public static void cancel(Outpost o, OutpostTask task, String reason, long now) {
        if (!task.active()) return;
        if (task.type.planned) {
            task.executor = null;
            task.carried.clear();
            o.tasks.set(task, task.reserved.isEmpty() ? OutpostTask.State.AVAILABLE : OutpostTask.State.RESERVED, reason, now);
        } else {
            o.tasks.set(task, OutpostTask.State.CANCELLED, reason, now);
        }
        o.dirtyForClients = true;
    }

    /** Возвращает предметы в бочки склада (или в дань, если бочки полны); запас пополняется тем же учётом, что и разгрузка. */
    private static void restock(ServerLevel level, Outpost o, java.util.List<ItemStack> stacks) {
        if (stacks.isEmpty()) return;
        net.minecraft.world.SimpleContainer box = new net.minecraft.world.SimpleContainer(stacks.size());
        for (int i = 0; i < stacks.size(); i++) box.setItem(i, stacks.get(i));
        Storehouse.deposit(level, o, box);
    }
}
