package com.vladosiwe.lordsoutpost.data;

import com.vladosiwe.lordsoutpost.config.OutpostConfig;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Pact;
import com.vladosiwe.lordsoutpost.outpost.Raid;
import com.vladosiwe.lordsoutpost.outpost.Relation;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Resource;
import com.vladosiwe.lordsoutpost.server.Notify;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;

/**
 * Набеги. Источник — ближайший дикий аванпост без военного пакта; если все соседи замирены, приходят бродячие банды, но реже.
 * Рядом с игроком набег — настоящий бой у ворот; вдали исход считается по силе обороны.
 */
public final class Raids {
    public static final double RAID_RANGE = 1600;
    public static final long RAID_TIMEOUT = 6000;

    private Raids() {}

    public static void tick(ServerLevel level, OutpostSavedData data, Outpost o, long now, RandomSource rnd) {
        if (!OutpostConfig.get(OutpostConfig.RAIDS, true)) {
            o.raid = null;
            return;
        }
        if (o.raid != null) {
            // Игрок ушёл посреди боя или налётчики слишком долго топчутся — остаток решается по силе.
            if (!o.online || now - o.raid.startedAt > RAID_TIMEOUT) {
                double left = o.raid.strength * remainingShare(o.raid);
                Raid raid = o.raid;
                o.raid = null;
                if (left > 0.5) resolve(level, data, o, raid.source, left, now, rnd);
                else victory(level, o, raid.source, now, 0);
                schedule(data, o, now, rnd);
            }
            return;
        }
        if (o.nextRaidAt <= 0) {
            schedule(data, o, now, rnd);
            return;
        }
        if (now < o.nextRaidAt) return;
        Outpost source = source(data, o);
        double days = (now - o.foundedAt) / (double) Simulation.DAY;
        double strength = (6 + 4 * o.tier + Math.min(20, days * 0.4)) * (source != null ? 1 + 0.25 * source.difficulty.skulls : 0.8)
                * (0.8 + rnd.nextDouble() * 0.4);
        int count = Math.max(3, Math.min(14, (int) Math.round(strength / 5)));
        long sourceId = source == null ? 0 : source.id;
        o.log(now, "lordsoutpost.event.raid_start", sourceName(source));
        Notify.lord(level.getServer(), o, Component.translatable("lordsoutpost.event.raid_start", sourceName(source)));
        if (o.online) {
            o.raid = new Raid(sourceId, strength, now, count);
            o.alarmUntil = now + RAID_TIMEOUT;
        } else {
            resolve(level, data, o, sourceId, strength, now, rnd);
            schedule(data, o, now, rnd);
        }
        o.dirtyForClients = true;
    }

    private static String sourceName(@Nullable Outpost source) {
        return source == null ? "—" : "★".repeat(source.difficulty.skulls);
    }

    private static double remainingShare(Raid raid) {
        int total = raid.toSpawn + raid.killed + raid.entities.size();
        return total <= 0 ? 0 : (raid.toSpawn + raid.entities.size()) / (double) total;
    }

    public static void schedule(OutpostSavedData data, Outpost o, long now, RandomSource rnd) {
        double interval = OutpostConfig.get(OutpostConfig.RAID_INTERVAL_DAYS, 4.0);
        if (source(data, o) == null) interval *= 2;
        o.nextRaidAt = now + (long) (interval * Simulation.DAY * (0.6 + rnd.nextDouble() * 0.8));
    }

    /** Ближайший дикий сосед, с которым нет военного пакта. */
    @Nullable
    public static Outpost source(OutpostSavedData data, Outpost o) {
        Outpost best = null;
        double bestDist = Double.MAX_VALUE;
        for (Outpost other : data.near(o.center.getX(), o.center.getZ(), RAID_RANGE)) {
            if (other == o || other.state != OutpostState.WILD) continue;
            Relation rel = o.relations.get(other.id);
            if (rel != null && rel.pact == Pact.MILITARY) continue;
            double d = other.distanceSqr(o.center.getX(), o.center.getZ());
            if (d < bestDist) {
                bestDist = d;
                best = other;
            }
        }
        return best;
    }

    /** Помощь союзников по военным пактам. */
    public static double allyPower(OutpostSavedData data, Outpost o) {
        double sum = 0;
        for (var e : o.relations.entrySet()) {
            if (e.getValue().pact != Pact.MILITARY) continue;
            Outpost ally = data.byId(e.getKey());
            if (ally == null) continue;
            sum += ally.state == OutpostState.WILD ? 3 * ally.difficulty.skulls : ally.defensePower() * 0.3;
        }
        return sum;
    }

    /** Виртуальный бой: исход и потери по соотношению сил. */
    public static boolean resolve(ServerLevel level, OutpostSavedData data, Outpost o, long source, double strength, long now, RandomSource rnd) {
        double defense = o.defensePower() + allyPower(data, o);
        double p = defense / Math.max(0.001, defense + strength);
        boolean win = rnd.nextDouble() < Math.min(0.97, p * 1.25);
        double hitChance = (1 - p) * (win ? 0.5 : 0.9);
        int wounded = 0, dead = 0;
        int beds = o.infirmaryBeds();
        for (Resident r : o.residents) if (r.status == Resident.Status.WOUNDED) beds--;
        for (Iterator<Resident> it = o.residents.iterator(); it.hasNext(); ) {
            Resident r = it.next();
            if (!r.role.combat || !r.active()) continue;
            r.armor = Math.max(0, r.armor - 0.25f * (float) (1 - p));
            if (rnd.nextDouble() >= hitChance) continue;
            if (beds > 0 || rnd.nextDouble() < 0.5) {
                r.status = Resident.Status.WOUNDED;
                r.statusUntil = now + 6000;
                r.health = 0.3f;
                beds--;
                wounded++;
            } else {
                it.remove();
                dead++;
            }
        }
        if (win) {
            for (Resident r : o.residents) if (r.role.combat && r.active()) r.kills++;
            victory(level, o, source, now, wounded + dead);
        } else {
            for (Resource res : new Resource[]{Resource.WOOD, Resource.STONE, Resource.FOOD, Resource.IRON, Resource.HIDES, Resource.GOLD}) {
                o.setStock(res, o.stock(res) * (0.6 + rnd.nextDouble() * 0.2));
            }
            int civ = 0;
            for (Iterator<Resident> it = o.residents.iterator(); it.hasNext() && civ < 2; ) {
                Resident r = it.next();
                if (!r.role.combat && r.active() && rnd.nextDouble() < 0.1) {
                    it.remove();
                    civ++;
                }
            }
            o.log(now, "lordsoutpost.event.raid_lost", wounded, dead + civ);
            Notify.lord(level.getServer(), o, Component.translatable("lordsoutpost.event.raid_lost", wounded, dead + civ));
        }
        o.dirtyForClients = true;
        return win;
    }

    public static void victory(ServerLevel level, Outpost o, long source, long now, int losses) {
        o.log(now, "lordsoutpost.event.raid_won", losses);
        Notify.lord(level.getServer(), o, Component.translatable("lordsoutpost.event.raid_won", losses));
        o.tributeGold += 4 + o.tier * 2;
        o.alarmUntil = 0;
    }
}
