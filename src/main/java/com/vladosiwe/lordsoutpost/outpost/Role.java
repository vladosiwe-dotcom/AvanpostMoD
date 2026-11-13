package com.vladosiwe.lordsoutpost.outpost;

import net.minecraft.network.chat.Component;

/** Роль жителя. Боевая роль определяется экипировкой, выданной со склада. */
public enum Role {
    STEWARD("steward", false),
    BUILDER("builder", false),
    HUNTER("hunter", false),
    HERDER("herder", false),
    MINER("miner", false),
    LUMBERJACK("lumberjack", false),
    SMITH("smith", false),
    MAGE("mage", false),
    CARAVANEER("caravaneer", false),
    SHIELDMAN("shieldman", true),
    BERSERKER("berserker", true),
    ARCHER("archer", true),
    CROSSBOWMAN("crossbowman", true),
    CAPTAIN("captain", true);

    public static final Role[] VALUES = values();
    public static final Role[] FIGHTERS = {SHIELDMAN, BERSERKER, ARCHER, CROSSBOWMAN};

    public final String id;
    public final boolean combat;

    Role(String id, boolean combat) {
        this.id = id;
        this.combat = combat;
    }

    public boolean ranged() {
        return this == ARCHER || this == CROSSBOWMAN;
    }

    public Component label() {
        return Component.translatable("lordsoutpost.role." + id);
    }

    public static Role byId(String id) {
        for (Role r : VALUES) if (r.id.equals(id)) return r;
        return SHIELDMAN;
    }
}
