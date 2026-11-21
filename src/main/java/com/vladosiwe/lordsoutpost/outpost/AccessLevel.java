package com.vladosiwe.lordsoutpost.outpost;

import net.minecraft.network.chat.Component;

/** Уровень доступа игрока к аванпосту (Гарнизонный Устав). Порядок важен: выше — больше прав. */
public enum AccessLevel {
    /** Стража атакует в периметре, любые взаимодействия запрещены. */
    HOSTILE("hostile", 0xE5484D),
    /** Внешний двор открыт, цитадель и сундуки закрыты. */
    GUEST("guest", 0xE8C547),
    /** Ворота и цитадель открыты, сундуки и станки доступны, ломать нельзя. */
    ALLY("ally", 0x4CC38A),
    /** Хозяин аванпоста. */
    LORD("lord", 0x6CB6FF);

    public static final AccessLevel[] VALUES = values();
    public final String id;
    public final int color;

    AccessLevel(String id, int color) {
        this.id = id;
        this.color = color;
    }

    public boolean atLeast(AccessLevel other) {
        return ordinal() >= other.ordinal();
    }

    public Component label() {
        return Component.translatable("lordsoutpost.access." + id);
    }

    public static AccessLevel byId(String id) {
        for (AccessLevel l : VALUES) if (l.id.equals(id)) return l;
        return GUEST;
    }
}
