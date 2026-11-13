package com.vladosiwe.lordsoutpost.network;

import com.vladosiwe.lordsoutpost.blueprint.Layout;
import com.vladosiwe.lordsoutpost.data.Caravans;
import com.vladosiwe.lordsoutpost.data.Diplomacy;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.data.Simulation;
import com.vladosiwe.lordsoutpost.entity.OutpostNpc;
import com.vladosiwe.lordsoutpost.outpost.AccessLevel;
import com.vladosiwe.lordsoutpost.outpost.Caravan;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Pact;
import com.vladosiwe.lordsoutpost.outpost.Quest;
import com.vladosiwe.lordsoutpost.outpost.Relation;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Resource;
import com.vladosiwe.lordsoutpost.outpost.Squad;
import com.vladosiwe.lordsoutpost.protection.Access;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Снимки состояния для клиента. Игроку уходит только то, что ему положено знать. */
public final class Views {
    private Views() {}

    private static int[] flat(int[][] outline) {
        int[] out = new int[outline.length * 2];
        for (int i = 0; i < outline.length; i++) {
            out[i * 2] = outline[i][0];
            out[i * 2 + 1] = outline[i][1];
        }
        return out;
    }

    /** Краткие сведения об аванпосте (для карты и списка соседей). */
    private static CompoundTag brief(OutpostSavedData data, Outpost o, UUID viewer, boolean known) {
        CompoundTag t = new CompoundTag();
        t.putLong("id", o.id);
        t.putInt("x", o.center.getX());
        t.putInt("z", o.center.getZ());
        t.putBoolean("known", known);
        if (!known) return t;
        t.putInt("y", o.center.getY());
        t.putByte("skulls", (byte) o.difficulty.skulls);
        t.putByte("state", (byte) o.state.ordinal());
        t.putString("name", o.name);
        t.putString("owner", o.ownerName);
        t.putByte("tier", (byte) o.tier);
        t.putFloat("progress", o.progress());
        t.putString("layout", o.layout.id);
        t.putByte("rot", (byte) o.rotation.ordinal());
        t.putInt("reach", o.reach());
        t.putByte("access", (byte) Access.level(data, o, viewer).ordinal());
        t.putIntArray("outline", flat(o.outline(o.radius())));
        return t;
    }

    /** Карта: слухи, разведанные форты, а для своего аванпоста — отряды, маршруты и караваны. */
    public static CompoundTag map(ServerLevel level, OutpostSavedData data, ServerPlayer player, boolean detailed) {
        CompoundTag tag = new CompoundTag();
        OutpostSavedData.Knowledge k = data.knowledge(player.getUUID());
        Outpost own = data.lordOutpost(player.getUUID());
        ListTag list = new ListTag();
        for (Outpost o : data.all()) {
            boolean known = k.discovered.contains(o.id) || o == own;
            if (!known && !k.hinted.contains(o.id)) continue;
            CompoundTag b = brief(data, o, player.getUUID(), known);
            if (known && own != null && o != own) {
                Relation rel = own.relations.get(o.id);
                if (rel != null) b.putByte("pact", (byte) rel.pact.ordinal());
            }
            list.add(b);
        }
        tag.put("outposts", list);
        tag.putLong("own", own == null ? 0 : own.id);
        if (own == null || !detailed) return tag;

        if (!own.complete()) tag.putIntArray("nextOutline", flat(own.outline(Layout.RADIUS[own.tier + 1])));
        ListTag squads = new ListTag();
        for (Squad s : own.squads) {
            CompoundTag st = new CompoundTag();
            st.putByte("id", (byte) s.id);
            st.putByte("order", (byte) s.order.ordinal());
            long[] route = new long[s.route.size()];
            for (int i = 0; i < route.length; i++) route[i] = s.route.get(i).asLong();
            st.putLongArray("route", route);
            if (s.hold != null) st.putLong("hold", s.hold.asLong());
            // Положение отряда — среднее по бойцам в мире; если их нет в мире, отряд дома.
            double sx = 0, sz = 0;
            int n = 0, members = 0;
            for (Resident r : own.residents) {
                if (!r.role.combat || r.squad != s.id) continue;
                members++;
                Entity e = r.entity == null ? null : level.getEntity(r.entity);
                if (e instanceof OutpostNpc) {
                    sx += e.getX();
                    sz += e.getZ();
                    n++;
                }
            }
            st.putInt("members", members);
            st.putDouble("x", n == 0 ? own.center.getX() : sx / n);
            st.putDouble("z", n == 0 ? own.center.getZ() : sz / n);
            squads.add(st);
        }
        tag.put("squads", squads);
        ListTag caravans = new ListTag();
        for (Caravan c : own.caravans) {
            CompoundTag ct = new CompoundTag();
            Vec3 p = c.position();
            ct.putDouble("x", p.x);
            ct.putDouble("z", p.z);
            ct.putInt("fx", c.from.getX());
            ct.putInt("fz", c.from.getZ());
            ct.putInt("tx", c.to.getX());
            ct.putInt("tz", c.to.getZ());
            ct.putByte("phase", (byte) c.phase.ordinal());
            ct.putBoolean("physical", c.materialized);
            caravans.add(ct);
        }
        tag.put("caravans", caravans);
        return tag;
    }

    /** Полный снимок аванпоста для окна управления. */
    public static CompoundTag outpost(ServerLevel level, OutpostSavedData data, Outpost o, ServerPlayer viewer) {
        long now = level.getGameTime();
        AccessLevel access = Access.level(data, o, viewer.getUUID());
        CompoundTag t = brief(data, o, viewer.getUUID(), true);
        t.putInt("steps", o.steps);
        t.putInt("planSize", o.nextPlan().size());
        t.putBoolean("complete", o.complete());
        t.putLong("day", now / Simulation.DAY + 1);
        t.putBoolean("starving", o.starving);
        if (o.stalledOn != null) t.putString("stalled", o.stalledOn.id);
        t.putBoolean("raid", o.raid != null);
        t.putInt("pen", o.penAnimals);
        t.putInt("penCap", o.penCapacity());
        t.putInt("popCap", o.populationCap());
        t.putInt("tributeStacks", o.tribute.size());

        CompoundTag stock = new CompoundTag();
        CompoundTag cap = new CompoundTag();
        CompoundTag boosts = new CompoundTag();
        for (Resource r : Resource.VALUES) {
            stock.putDouble(r.id, o.stock(r));
            cap.putInt(r.id, o.capacity(r));
            if (o.boosted(r, now)) boosts.putInt(r.id, (int) ((o.boosts.get(r.id) - now) / 20));
        }
        t.put("stock", stock);
        t.put("cap", cap);
        t.put("boosts", boosts);

        ListTag residents = new ListTag();
        for (Resident r : o.residents) {
            CompoundTag rt = new CompoundTag();
            rt.putUUID("id", r.id);
            rt.putString("name", r.name);
            rt.putString("role", r.role.id);
            rt.putByte("status", (byte) r.status.ordinal());
            rt.putFloat("hp", r.health);
            rt.putByte("gear", (byte) r.gear);
            rt.putFloat("armor", r.armor);
            rt.putByte("squad", (byte) r.squad);
            rt.putInt("kills", r.kills);
            if (r.status == Resident.Status.WOUNDED) rt.putInt("heal", (int) Math.max(0, (r.statusUntil - now) / 20));
            residents.add(rt);
        }
        t.put("residents", residents);

        ListTag squads = new ListTag();
        for (Squad s : o.squads) {
            CompoundTag st = new CompoundTag();
            st.putByte("order", (byte) s.order.ordinal());
            st.putInt("waypoints", s.route.size());
            st.putBoolean("hold", s.hold != null);
            squads.add(st);
        }
        t.put("squads", squads);

        t.putString("law", o.law.id);
        t.putInt("lawCooldown", (int) Math.max(0, (o.lawChangedAt + Simulation.DAY - now) / 20));

        ListTag quests = new ListTag();
        for (Quest q : o.quests) quests.add(q.save());
        t.put("quests", quests);

        ListTag chronicle = new ListTag();
        for (Outpost.Event e : o.chronicle) {
            CompoundTag c = new CompoundTag();
            c.putLong("day", e.day());
            c.putString("key", e.key());
            ListTag a = new ListTag();
            for (String s : e.args()) a.add(StringTag.valueOf(s));
            c.put("args", a);
            chronicle.add(c);
        }
        t.put("chronicle", chronicle);

        ListTag caravans = new ListTag();
        for (Caravan c : o.caravans) {
            CompoundTag ct = new CompoundTag();
            ct.putLong("target", c.target);
            ct.putByte("phase", (byte) c.phase.ordinal());
            ct.putFloat("progress", (float) Math.max(0, Math.min(1, c.traveled / Math.max(1, c.length()))));
            ct.putInt("escort", c.escort.size());
            caravans.add(ct);
        }
        t.put("caravans", caravans);
        t.putBoolean("autoCaravans", o.autoCaravans);
        double cargo = 0;
        for (double v : Caravans.surplus(o).values()) cargo += v;
        t.putInt("surplus", (int) cargo);

        // Права и дипломатию видит только Лорд.
        if (access == AccessLevel.LORD) {
            t.put("accessRules", o.access.save());
            ListTag neighbors = new ListTag();
            OutpostSavedData.Knowledge k = data.knowledge(viewer.getUUID());
            for (Outpost other : data.near(o.center.getX(), o.center.getZ(), Diplomacy.RANGE)) {
                if (other == o || other.state == OutpostState.CONQUERED || !k.discovered.contains(other.id)) continue;
                CompoundTag n = new CompoundTag();
                n.putLong("id", other.id);
                n.putString("name", other.name);
                n.putString("owner", other.ownerName);
                n.putByte("state", (byte) other.state.ordinal());
                n.putByte("skulls", (byte) other.difficulty.skulls);
                n.putInt("dist", (int) Math.sqrt(other.distanceSqr(o.center.getX(), o.center.getZ())));
                Relation rel = o.relations.get(other.id);
                Relation theirs = other.relations.get(o.id);
                n.putByte("pact", (byte) (rel == null ? 0 : rel.pact.ordinal()));
                n.putByte("offered", (byte) (rel == null ? 0 : rel.offered.ordinal()));
                n.putByte("incoming", (byte) (theirs == null ? 0 : theirs.offered.ordinal()));
                n.putInt("costTrade", Diplomacy.cost(other, Pact.TRADE));
                n.putInt("costMilitary", Diplomacy.cost(other, Pact.MILITARY));
                n.putInt("caravans", rel == null ? 0 : rel.caravansDone);
                n.putFloat("road", rel == null ? 0 : rel.roadShare);
                neighbors.add(n);
            }
            t.put("neighbors", neighbors);
        }
        return t;
    }

    public static BlockPos pos(long packed) {
        return BlockPos.of(packed);
    }
}
