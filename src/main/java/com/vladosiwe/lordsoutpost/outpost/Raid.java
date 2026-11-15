package com.vladosiwe.lordsoutpost.outpost;

import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Идущий набег на аванпост. */
public final class Raid {
    /** Аванпост-источник набега или 0 для бродячей банды. */
    public final long source;
    public final double strength;
    public final long startedAt;
    /** Сколько налётчиков ещё не вышло на поле (для физического боя). */
    public int toSpawn;
    public int killed;
    public transient final List<UUID> entities = new ArrayList<>();

    public Raid(long source, double strength, long startedAt, int toSpawn) {
        this.source = source;
        this.strength = strength;
        this.startedAt = startedAt;
        this.toSpawn = toSpawn;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putLong("source", source);
        t.putDouble("strength", strength);
        t.putLong("at", startedAt);
        t.putInt("toSpawn", toSpawn);
        t.putInt("killed", killed);
        return t;
    }

    public static Raid load(CompoundTag t) {
        Raid r = new Raid(t.getLong("source"), t.getDouble("strength"), t.getLong("at"), t.getInt("toSpawn"));
        r.killed = t.getInt("killed");
        return r;
    }
}
