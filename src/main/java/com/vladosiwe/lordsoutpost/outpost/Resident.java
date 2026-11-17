package com.vladosiwe.lordsoutpost.outpost;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Житель аванпоста. Это запись в сохранении, а не сущность: пока чанки выгружены, житель живёт только здесь.
 * Когда игрок рядом, по записи создаётся сущность; при выгрузке её состояние возвращается в запись.
 */
public final class Resident {
    public enum Status {
        ACTIVE,
        /** Лечится в лазарете до statusUntil. */
        WOUNDED,
        /** Ушёл с караваном. */
        ON_CARAVAN;

        public static final Status[] VALUES = values();
    }

    public static final int MAX_GEAR = 3;

    public final UUID id;
    public Role role;
    public String name;
    /** Доля здоровья 0..1. */
    public float health = 1f;
    /** Уровень снаряжения бойца: 0 кожа, 1 кольчуга, 2 железо, 3 алмаз. */
    public int gear;
    /** Состояние брони 0..1; чинит Кузнец за шкуры и железо. */
    public float armor = 1f;
    public Status status = Status.ACTIVE;
    public long statusUntil;
    /** Отряд бойца (индекс в Outpost.squads) или -1. */
    public int squad = -1;
    public int kills;
    /** Опыт шахтёра за реально добытые блоки; сохраняется вместе с жителем. */
    public int miningXp;

    /** Уровень шахтёра 1–5. Пороги намеренно умеренные: 50 / 150 / 300 / 500 блоков. */
    public int miningLevel() {
        if (miningXp >= 500) return 5;
        if (miningXp >= 300) return 4;
        if (miningXp >= 150) return 3;
        if (miningXp >= 50) return 2;
        return 1;
    }

    /** Сущность, которая сейчас представляет жителя в мире (не сохраняется). */
    @Nullable public transient UUID entity;
    /** Тик, до которого запись считается «занятой» материализацией (защита от дублей). */
    public transient long spawnGrace;
    /** Когда житель последний раз сделал настоящую работу в мире (срубил, выкопал, добыл). Пока он работает руками,
     *  симуляция не начисляет ему выработку числами — иначе ресурс засчитался бы дважды. */
    public transient long lastRealWork = Long.MIN_VALUE / 2;

    public Resident(UUID id, Role role, String name) {
        this.id = id;
        this.role = role;
        this.name = name;
    }

    public boolean active() {
        return status == Status.ACTIVE;
    }

    /** Боевая сила для виртуального боя. */
    public double power() {
        if (!role.combat || status != Status.ACTIVE) return 0;
        double base = role == Role.CAPTAIN ? 12 : 4;
        return base * (1 + 0.35 * gear) * (0.5 + 0.5 * armor) * (0.4 + 0.6 * health) * (1 + Math.min(10, kills) * 0.03);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("id", id);
        t.putString("role", role.id);
        t.putString("name", name);
        t.putFloat("hp", health);
        t.putByte("gear", (byte) gear);
        t.putFloat("armor", armor);
        t.putByte("status", (byte) status.ordinal());
        t.putLong("until", statusUntil);
        t.putByte("squad", (byte) squad);
        t.putInt("kills", kills);
        t.putInt("miningXp", miningXp);
        return t;
    }

    public static Resident load(CompoundTag t) {
        Resident r = new Resident(t.hasUUID("id") ? t.getUUID("id") : UUID.randomUUID(), Role.byId(t.getString("role")), t.getString("name"));
        r.health = Mth.clamp(t.getFloat("hp"), 0.05f, 1f);
        r.gear = Mth.clamp(t.getByte("gear"), 0, MAX_GEAR);
        r.armor = Mth.clamp(t.getFloat("armor"), 0f, 1f);
        r.status = Status.VALUES[Mth.clamp(t.getByte("status"), 0, Status.VALUES.length - 1)];
        r.statusUntil = t.getLong("until");
        r.squad = t.getByte("squad");
        r.kills = t.getInt("kills");
        r.miningXp = Math.max(0, t.getInt("miningXp"));
        return r;
    }
}
