package com.vladosiwe.lordsoutpost.outpost;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Задача жителя в общей системе задач аванпоста. Задачи создаёт центральный планировщик ({@code OutpostDirector})
 * или сам житель, когда ему нужны материалы со склада. Всё, что задача держит, привязано к её id:
 * зарезервированные на складе предметы, предметы, уже взятые в ношу, рабочая зона и цель.
 */
public final class OutpostTask {
    public enum State {
        CREATED, AVAILABLE, RESERVED, IN_PROGRESS, PAUSED, COMPLETED, CANCELLED, FAILED;

        public static final State[] VALUES = values();

        /** Задача завершена тем или иным образом и больше ничего не держит. */
        public boolean terminal() {
            return this == COMPLETED || this == CANCELLED || this == FAILED;
        }
    }

    public enum Type {
        /** Кузнец чинит броню бойца (subject — житель). */
        REPAIR_ARMOR(Role.SMITH, true),
        /** Кузнец перековывает снаряжение бойца на следующий уровень. */
        UPGRADE_GEAR(Role.SMITH, true),
        /** Скотовод кормит пару животных в загоне настоящим кормом. */
        BREED_ANIMALS(Role.SKOTOVOD, false),
        /** Фермер берёт семена со склада для посева. */
        FETCH_SEEDS(Role.FARMER, false),
        /** Шахтёр берёт со склада факелы или уголь с бревном, чтобы сделать факелы для штрека. */
        FETCH_TORCHES(Role.MINER, false),
        /** Кузнец куёт инструмент (product) из настоящих слитков. */
        FORGE_TOOL(Role.SMITH, true),
        /** Скотовод берёт со склада корм для приманивания и разведения. */
        FETCH_FEED(Role.SKOTOVOD, false),
        /** Скотовод берёт со склада инструменты (ножницы, ведро). */
        FETCH_TOOLS(Role.SKOTOVOD, false),
        /** Шахтёр берёт кирку со склада или материалы, чтобы сделать каменную. */
        FETCH_PICKAXE(Role.MINER, false),
        /** Кузнец берёт сырую руду и топливо для печи кузницы. */
        SMELT_ORE(Role.SMITH, false),
        /** Лесоруб берёт со склада саженец той же породы, чтобы посадить его на месте срубленного дерева. */
        FETCH_SAPLING(Role.LUMBERJACK, false),
        /** Торговец несёт излишки со склада в свою лавку. */
        STOCK_SHOP(Role.CARAVANEER, false),
        /** Строитель берёт со склада материал для заказанной Лордом постройки. */
        FETCH_BUILD(Role.BUILDER, false);

        public static final Type[] VALUES = values();

        public final Role role;
        /** Задача планировщика: переживает перезапуск и передаётся другому работнику. Иначе — личный запрос работника. */
        public final boolean planned;

        Type(Role role, boolean planned) {
            this.role = role;
            this.planned = planned;
        }

        public static Type byName(String name) {
            for (Type t : VALUES) if (t.name().equals(name)) return t;
            return null;
        }
    }

    public final long id;
    public final long outpostId;
    public final Type type;
    public int priority;
    public State state = State.CREATED;
    /** Житель, который выполняет задачу. */
    @Nullable public UUID executor;
    /** Предмет заботы: боец, которому чинят броню, и т. п. */
    @Nullable public UUID subject;
    @Nullable public WorkZone zone;
    @Nullable public BlockPos target;
    /** Что производится (для кузнечных заказов). */
    @Nullable public Item product;
    /** Предметы, отложенные на складе под эту задачу (ещё лежат в бочках, но другим недоступны). */
    public final Map<Item, Integer> reserved = new LinkedHashMap<>();
    /** Предметы, которые исполнитель уже забрал со склада в ношу под эту задачу. */
    public final Map<Item, Integer> carried = new LinkedHashMap<>();
    public double progress;
    public final long createdAt;
    public long updatedAt;
    public int attempts;
    public String reason = "";

    public OutpostTask(long id, long outpostId, Type type, int priority, long now) {
        this.id = id;
        this.outpostId = outpostId;
        this.type = type;
        this.priority = priority;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean active() {
        return !state.terminal();
    }

    public int reservedCount(Item item) {
        return reserved.getOrDefault(item, 0);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putLong("id", id);
        t.putLong("outpost", outpostId);
        t.putString("type", type.name());
        t.putInt("priority", priority);
        t.putByte("state", (byte) state.ordinal());
        if (executor != null) t.putUUID("executor", executor);
        if (subject != null) t.putUUID("subject", subject);
        if (zone != null) t.putString("zone", zone.id);
        if (target != null) t.putLong("target", target.asLong());
        if (product != null) t.putString("product", BuiltInRegistries.ITEM.getKey(product).toString());
        t.put("reserved", items(reserved));
        t.put("carried", items(carried));
        t.putDouble("progress", progress);
        t.putLong("created", createdAt);
        t.putLong("updated", updatedAt);
        t.putInt("attempts", attempts);
        t.putString("reason", reason);
        return t;
    }

    @Nullable
    public static OutpostTask load(CompoundTag t) {
        Type type = Type.byName(t.getString("type"));
        if (type == null) return null;
        OutpostTask task = new OutpostTask(t.getLong("id"), t.getLong("outpost"), type, t.getInt("priority"), t.getLong("created"));
        task.state = State.VALUES[Math.max(0, Math.min(State.VALUES.length - 1, t.getByte("state")))];
        task.executor = t.hasUUID("executor") ? t.getUUID("executor") : null;
        task.subject = t.hasUUID("subject") ? t.getUUID("subject") : null;
        task.zone = t.contains("zone") ? WorkZone.byId(t.getString("zone")) : null;
        task.target = t.contains("target") ? BlockPos.of(t.getLong("target")) : null;
        if (t.contains("product")) {
            ResourceLocation pid = ResourceLocation.tryParse(t.getString("product"));
            Item item = pid == null ? Items.AIR : BuiltInRegistries.ITEM.get(pid);
            task.product = item == Items.AIR ? null : item;
        }
        readItems(t.getList("reserved", Tag.TAG_COMPOUND), task.reserved);
        readItems(t.getList("carried", Tag.TAG_COMPOUND), task.carried);
        task.progress = t.getDouble("progress");
        task.updatedAt = t.getLong("updated");
        task.attempts = t.getInt("attempts");
        task.reason = t.getString("reason");
        return task;
    }

    private static ListTag items(Map<Item, Integer> map) {
        ListTag list = new ListTag();
        for (var e : map.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putString("item", BuiltInRegistries.ITEM.getKey(e.getKey()).toString());
            c.putInt("count", e.getValue());
            list.add(c);
        }
        return list;
    }

    private static void readItems(ListTag list, Map<Item, Integer> into) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(c.getString("item"));
            if (id == null) continue;
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == Items.AIR || c.getInt("count") <= 0) continue;
            into.merge(item, c.getInt("count"), Integer::sum);
        }
    }

    @Override
    public String toString() {
        return "#" + id + " " + type + " " + state + (reason.isEmpty() ? "" : " (" + reason + ")") + " p" + priority
                + (reserved.isEmpty() ? "" : " reserved=" + reserved.size()) + (carried.isEmpty() ? "" : " carried=" + carried.size());
    }
}
