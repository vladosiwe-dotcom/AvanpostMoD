package com.vladosiwe.lordsoutpost.outpost;

import net.minecraft.nbt.CompoundTag;

/** Отношения с соседним аванпостом. */
public final class Relation {
    public Pact pact = Pact.NONE;
    /** Пакт, который мы предложили и который ждёт ответа другого Лорда. */
    public Pact offered = Pact.NONE;
    /** Доля пути между фортами, проложенная по дорогам (0..1); измеряется, когда караван идёт физически. */
    public float roadShare;
    public int caravansDone;

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putByte("pact", (byte) pact.ordinal());
        t.putByte("offered", (byte) offered.ordinal());
        t.putFloat("road", roadShare);
        t.putInt("caravans", caravansDone);
        return t;
    }

    public static Relation load(CompoundTag t) {
        Relation r = new Relation();
        r.pact = Pact.VALUES[Math.max(0, Math.min(2, t.getByte("pact")))];
        r.offered = Pact.VALUES[Math.max(0, Math.min(2, t.getByte("offered")))];
        r.roadShare = Math.max(0, Math.min(1, t.getFloat("road")));
        r.caravansDone = t.getInt("caravans");
        return r;
    }
}
