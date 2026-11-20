package com.vladosiwe.lordsoutpost.outpost;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

/**
 * Задачи одного аванпоста. Хранит активные задачи и короткую историю завершённых, ведёт учёт резервов склада:
 * предмет, отложенный под одну задачу, не может потратить другая задача или стройка.
 */
public final class TaskBoard {
    /** Сколько завершённых задач хранится для истории и отладки. */
    public static final int HISTORY = 16;
    /** Задача без отклика исполнителя дольше этого считается брошенной (2 игровые минуты). */
    public static final long STALE = 2400;

    private final List<OutpostTask> tasks = new ArrayList<>();
    private long counter;

    public List<OutpostTask> all() {
        return tasks;
    }

    public OutpostTask create(Outpost o, OutpostTask.Type type, int priority, long now) {
        OutpostTask t = new OutpostTask(++counter, o.id, type, priority, now);
        t.state = OutpostTask.State.AVAILABLE;
        tasks.add(t);
        o.dirtyForClients = true;
        return t;
    }

    @Nullable
    public OutpostTask byId(long id) {
        for (OutpostTask t : tasks) if (t.id == id) return t;
        return null;
    }

    /** Активная задача этого типа о конкретном предмете заботы (например, о бойце). */
    @Nullable
    public OutpostTask find(OutpostTask.Type type, @Nullable UUID subject) {
        for (OutpostTask t : tasks) {
            if (t.active() && t.type == type && java.util.Objects.equals(t.subject, subject)) return t;
        }
        return null;
    }

    /** Активная задача, которую выполняет этот житель. */
    @Nullable
    public OutpostTask ofExecutor(UUID executor) {
        for (OutpostTask t : tasks) if (t.active() && executor.equals(t.executor)) return t;
        return null;
    }

    /** Свободная задача для профессии с наибольшим приоритетом. */
    @Nullable
    public OutpostTask nextFor(Role role) {
        OutpostTask best = null;
        for (OutpostTask t : tasks) {
            if (!t.active() || t.type.role != role || t.executor != null) continue;
            if (t.state != OutpostTask.State.AVAILABLE && t.state != OutpostTask.State.RESERVED && t.state != OutpostTask.State.PAUSED) continue;
            if (best == null || t.priority > best.priority || (t.priority == best.priority && t.id < best.id)) best = t;
        }
        return best;
    }

    public int activeCount() {
        int n = 0;
        for (OutpostTask t : tasks) if (t.active()) n++;
        return n;
    }

    /** Сколько штук предмета отложено под активные задачи, кроме указанной. */
    public int reserved(Item item, @Nullable OutpostTask except) {
        int n = 0;
        for (OutpostTask t : tasks) if (t != except && t.active()) n += t.reservedCount(item);
        return n;
    }

    /** Стоимость всех резервов в единицах ресурса (функция переводит предмет в стоимость ресурса или 0). */
    public double reservedValue(ToDoubleFunction<Item> valueOf) {
        double sum = 0;
        for (OutpostTask t : tasks) {
            if (!t.active()) continue;
            for (var e : t.reserved.entrySet()) sum += valueOf.applyAsDouble(e.getKey()) * e.getValue();
        }
        return sum;
    }

    public void set(OutpostTask t, OutpostTask.State state, String reason, long now) {
        t.state = state;
        t.reason = reason;
        t.updatedAt = now;
        if (state.terminal()) {
            t.reserved.clear();
            t.carried.clear();
            t.executor = null;
        }
    }

    /**
     * Исполнитель пропал (выгружен, погиб, ушёл с караваном). Задачи планировщика снимаются с него и ждут другого работника,
     * сохраняя резерв на складе; личные запросы отменяются. Предметы, которые он нёс, уже вернулись на склад
     * (выгрузка) или лежат на земле (смерть), поэтому учёт «в ноше» обнуляется.
     */
    public void releaseExecutor(UUID executor, String reason, long now) {
        for (OutpostTask t : tasks) {
            if (!t.active() || !executor.equals(t.executor)) continue;
            t.carried.clear();
            t.executor = null;
            if (t.type.planned) set(t, t.reserved.isEmpty() ? OutpostTask.State.AVAILABLE : OutpostTask.State.RESERVED, reason, now);
            else set(t, OutpostTask.State.CANCELLED, reason, now);
        }
    }

    /** Убирает лишнюю историю: активные задачи остаются, из завершённых — последние HISTORY. */
    public void prune() {
        int terminal = 0;
        for (OutpostTask t : tasks) if (!t.active()) terminal++;
        for (Iterator<OutpostTask> it = tasks.iterator(); it.hasNext() && terminal > HISTORY; ) {
            if (!it.next().active()) {
                it.remove();
                terminal--;
            }
        }
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putLong("counter", counter);
        ListTag list = new ListTag();
        for (OutpostTask task : tasks) list.add(task.save());
        t.put("tasks", list);
        return t;
    }

    /**
     * Загрузка после перезапуска. Сущностей ещё нет, а ноша выгруженных жителей уже на складе, поэтому
     * незавершённые задачи планировщика возвращаются в очередь, а личные запросы отменяются: никакая работа
     * не повторится дважды и никакой предмет не будет выдан повторно.
     */
    public void load(CompoundTag t, long now) {
        tasks.clear();
        counter = t.getLong("counter");
        ListTag list = t.getList("tasks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            OutpostTask task = OutpostTask.load(list.getCompound(i));
            if (task == null) continue;
            counter = Math.max(counter, task.id);
            if (task.active() && task.executor != null) {
                task.carried.clear();
                task.executor = null;
                if (task.type.planned) task.state = task.reserved.isEmpty() ? OutpostTask.State.AVAILABLE : OutpostTask.State.RESERVED;
                else {
                    task.state = OutpostTask.State.CANCELLED;
                    task.reserved.clear();
                    task.reason = "restart";
                }
            }
            tasks.add(task);
        }
    }
}
