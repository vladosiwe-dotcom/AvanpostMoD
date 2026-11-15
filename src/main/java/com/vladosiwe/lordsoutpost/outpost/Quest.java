package com.vladosiwe.lordsoutpost.outpost;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * Задание Доски: реальная нехватка поселения. Игрок может закрыть её, сдав ресурс,
 * тогда связанная работа идёт вдвое быстрее. Без игрока жители восполнят нехватку сами, просто медленнее.
 */
public final class Quest {
    public enum Type {
        /** Не хватает материала для стройки. */
        BUILD_MATERIAL,
        /** Нечем чинить броню стражи. */
        ARMOR_REPAIR,
        /** Заканчивается еда. */
        FOOD,
        /** Магу нужны редкие ингредиенты. */
        RARE_INGREDIENT;

        public static final Type[] VALUES = values();
    }

    public final int id;
    public final Type type;
    public final Resource resource;
    public final int needed;
    public double delivered;
    public final int rewardGold;
    public final long createdAt;

    public Quest(int id, Type type, Resource resource, int needed, int rewardGold, long createdAt) {
        this.id = id;
        this.type = type;
        this.resource = resource;
        this.needed = needed;
        this.rewardGold = rewardGold;
        this.createdAt = createdAt;
    }

    public boolean done() {
        return delivered >= needed;
    }

    public Component title() {
        return Component.translatable("lordsoutpost.quest." + type.name().toLowerCase(java.util.Locale.ROOT), resource.label());
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putByte("type", (byte) type.ordinal());
        t.putString("res", resource.id);
        t.putInt("needed", needed);
        t.putDouble("delivered", delivered);
        t.putInt("reward", rewardGold);
        t.putLong("at", createdAt);
        return t;
    }

    public static Quest load(CompoundTag t) {
        Resource res = Resource.byId(t.getString("res"));
        Quest q = new Quest(t.getInt("id"), Type.VALUES[Math.max(0, Math.min(Type.VALUES.length - 1, t.getByte("type")))],
                res == null ? Resource.WOOD : res, Math.max(1, t.getInt("needed")), t.getInt("reward"), t.getLong("at"));
        q.delivered = t.getDouble("delivered");
        return q;
    }
}
