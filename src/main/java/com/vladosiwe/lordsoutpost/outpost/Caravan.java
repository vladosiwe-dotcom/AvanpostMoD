package com.vladosiwe.lordsoutpost.outpost;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

/**
 * Караван: Караванщик с вьючным животным и конвой. Пока рядом нет игроков, он движется виртуально
 * (точка на прямой между фортами); рядом с игроком превращается в настоящий отряд на дороге.
 */
public final class Caravan {
    public enum Phase {
        OUTBOUND, TRADING, RETURNING;

        public static final Phase[] VALUES = values();
    }

    public final UUID id;
    public final long target;
    public final BlockPos from;
    public final BlockPos to;
    public Phase phase = Phase.OUTBOUND;
    /** Пройдено по текущему плечу, в блоках. */
    public double traveled;
    public long phaseUntil;
    public final EnumMap<Resource, Double> cargo = new EnumMap<>(Resource.class);
    /** Золото, вырученное на месте. */
    public double earned;
    public final UUID leader;
    public final List<UUID> escort = new ArrayList<>();
    /** Сколько блоков пути пройдено по дороге (только при физическом движении). */
    public double roadBlocks;
    public double sampledBlocks;
    public boolean ambushed;

    /** Сущности каравана, пока он в мире (не сохраняются). */
    public transient final List<UUID> entities = new ArrayList<>();
    public transient boolean materialized;
    public transient long stuckSince;
    public transient double lastProgress;

    public Caravan(UUID id, long target, BlockPos from, BlockPos to, UUID leader) {
        this.id = id;
        this.target = target;
        this.from = from;
        this.to = to;
        this.leader = leader;
    }

    public double length() {
        return Math.sqrt(from.distSqr(to));
    }

    /** Текущая точка на прямой между фортами (без высоты). */
    public Vec3 position() {
        double len = Math.max(1, length());
        double f = Math.max(0, Math.min(1, traveled / len));
        BlockPos a = phase == Phase.RETURNING ? to : from;
        BlockPos b = phase == Phase.RETURNING ? from : to;
        if (phase == Phase.TRADING) return Vec3.atCenterOf(to);
        return new Vec3(a.getX() + (b.getX() - a.getX()) * f + 0.5, a.getY(), a.getZ() + (b.getZ() - a.getZ()) * f + 0.5);
    }

    public BlockPos destination() {
        return phase == Phase.RETURNING ? from : to;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("id", id);
        t.putLong("target", target);
        t.put("from", NbtUtils.writeBlockPos(from));
        t.put("to", NbtUtils.writeBlockPos(to));
        t.putByte("phase", (byte) phase.ordinal());
        t.putDouble("traveled", traveled);
        t.putLong("until", phaseUntil);
        CompoundTag c = new CompoundTag();
        for (var e : cargo.entrySet()) c.putDouble(e.getKey().id, e.getValue());
        t.put("cargo", c);
        t.putDouble("earned", earned);
        t.putUUID("leader", leader);
        ListTag esc = new ListTag();
        for (UUID u : escort) esc.add(NbtUtils.createUUID(u));
        t.put("escort", esc);
        t.putDouble("road", roadBlocks);
        t.putDouble("sampled", sampledBlocks);
        t.putBoolean("ambushed", ambushed);
        return t;
    }

    public static Caravan load(CompoundTag t) {
        BlockPos from = NbtUtils.readBlockPos(t, "from").orElse(BlockPos.ZERO);
        BlockPos to = NbtUtils.readBlockPos(t, "to").orElse(BlockPos.ZERO);
        Caravan c = new Caravan(t.getUUID("id"), t.getLong("target"), from, to, t.getUUID("leader"));
        c.phase = Phase.VALUES[Math.max(0, Math.min(2, t.getByte("phase")))];
        c.traveled = t.getDouble("traveled");
        c.phaseUntil = t.getLong("until");
        CompoundTag cargo = t.getCompound("cargo");
        for (Resource r : Resource.VALUES) if (cargo.contains(r.id)) c.cargo.put(r, cargo.getDouble(r.id));
        c.earned = t.getDouble("earned");
        ListTag esc = t.getList("escort", Tag.TAG_INT_ARRAY);
        for (Tag tag : esc) c.escort.add(NbtUtils.loadUUID(tag));
        c.roadBlocks = t.getDouble("road");
        c.sampledBlocks = t.getDouble("sampled");
        c.ambushed = t.getBoolean("ambushed");
        return c;
    }
}
