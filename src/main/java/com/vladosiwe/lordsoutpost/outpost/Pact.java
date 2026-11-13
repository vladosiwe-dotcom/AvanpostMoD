package com.vladosiwe.lordsoutpost.outpost;

import net.minecraft.network.chat.Component;

/** Договор между двумя аванпостами. Порядок важен: военный пакт включает торговый. */
public enum Pact {
    NONE("none"),
    /** Торговый пакт: можно слать караваны, растёт дань. */
    TRADE("trade"),
    /** Военный пакт: сосед не устраивает набеги и помогает в обороне. */
    MILITARY("military");

    public static final Pact[] VALUES = values();
    public final String id;

    Pact(String id) {
        this.id = id;
    }

    public Component label() {
        return Component.translatable("lordsoutpost.pact." + id);
    }
}
