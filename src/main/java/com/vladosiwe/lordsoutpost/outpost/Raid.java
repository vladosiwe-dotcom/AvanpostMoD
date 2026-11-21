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
    /** Разорители набега (ванильные Ravager), не жители и не бойцы. */
    public transient final List<UUID> beasts = new ArrayList<>();
    /** Момент штурма: до него — предупреждение (5 мин), лагерь (за 2 мин) и горн (за 30 с). 0 — штурм сразу. */
    public long assaultAt;
    /** Лагерь налётчиков в 100+ блоках от форта. */
    public net.minecraft.core.BlockPos camp;
    public boolean camped, horn;
    /** Сколько разорителей придёт и будет ли знаменосец. */
    public int ravagers;
    public boolean banner = true;
    /** Накопленные удары по решётке ворот. */
    public transient double gateDamage;

    public static final long WARNING = 6000, CAMP_BEFORE = 2400, HORN_BEFORE = 600;

    public boolean beforeAssault(long now) {
        return now < assaultAt;
    }

    /** Подготовка: лагерь стоит, жители прячутся, дозор возвращается. */
    public boolean preparing(long now) {
        return now >= assaultAt - CAMP_BEFORE;
    }

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
        t.putLong("assault", assaultAt);
        if (camp != null) t.putLong("camp", camp.asLong());
        t.putBoolean("camped", camped);
        t.putBoolean("horn", horn);
        t.putInt("ravagers", ravagers);
        t.putBoolean("banner", banner);
        return t;
    }

    public static Raid load(CompoundTag t) {
        Raid r = new Raid(t.getLong("source"), t.getDouble("strength"), t.getLong("at"), t.getInt("toSpawn"));
        r.killed = t.getInt("killed");
        r.assaultAt = t.getLong("assault");
        if (t.contains("camp")) r.camp = net.minecraft.core.BlockPos.of(t.getLong("camp"));
        r.camped = t.getBoolean("camped");
        r.horn = t.getBoolean("horn");
        r.ravagers = t.getInt("ravagers");
        r.banner = !t.contains("banner") || t.getBoolean("banner");
        return r;
    }
}
