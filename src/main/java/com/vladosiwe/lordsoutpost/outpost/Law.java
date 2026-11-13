package com.vladosiwe.lordsoutpost.outpost;

import net.minecraft.network.chat.Component;

/**
 * Закон Лорда. Активен только один; у каждого есть цена, поэтому выбор — решение под ситуацию.
 */
public enum Law {
    NONE("none"),
    /** Военное положение: сильнее и многочисленнее стража, но страдает хозяйство. */
    MARTIAL_LAW("martial_law"),
    /** Торговля и Магия: больше дани, прибыльнее караваны, продуктивнее Маг, но слабее оборона. */
    TRADE_AND_MAGIC("trade_and_magic"),
    /** Ударная стройка: вдвое быстрее строительство, но больше расход еды и ниже добыча. */
    SHOCK_CONSTRUCTION("shock_construction");

    public static final Law[] VALUES = values();
    public final String id;

    Law(String id) {
        this.id = id;
    }

    public double buildSpeed() { return this == SHOCK_CONSTRUCTION ? 2.0 : 1.0; }
    public double production() { return this == MARTIAL_LAW ? 0.75 : this == SHOCK_CONSTRUCTION ? 0.85 : 1.0; }
    public double foodUse() { return this == SHOCK_CONSTRUCTION ? 1.25 : 1.0; }
    public double defense() { return this == MARTIAL_LAW ? 1.3 : this == TRADE_AND_MAGIC ? 0.9 : 1.0; }
    public double guardShare() { return this == MARTIAL_LAW ? 1.5 : 1.0; }
    public double tribute() { return this == TRADE_AND_MAGIC ? 1.5 : 1.0; }
    public double caravanProfit() { return this == TRADE_AND_MAGIC ? 1.5 : 1.0; }
    public double magic() { return this == TRADE_AND_MAGIC ? 2.0 : 1.0; }

    public Component label() {
        return Component.translatable("lordsoutpost.law." + id);
    }

    public Component description() {
        return Component.translatable("lordsoutpost.law." + id + ".desc");
    }

    public static Law byId(String id) {
        for (Law l : VALUES) if (l.id.equals(id)) return l;
        return NONE;
    }
}
