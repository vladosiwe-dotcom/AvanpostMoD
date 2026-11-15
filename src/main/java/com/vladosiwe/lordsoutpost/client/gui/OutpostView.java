package com.vladosiwe.lordsoutpost.client.gui;

import com.vladosiwe.lordsoutpost.outpost.AccessLevel;
import com.vladosiwe.lordsoutpost.outpost.AccessRules;
import com.vladosiwe.lordsoutpost.outpost.Law;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Quest;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Resource;
import com.vladosiwe.lordsoutpost.outpost.Role;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

/** Снимок аванпоста, пришедший с сервера, в удобном для окна виде. */
public final class OutpostView {
    public record Person(UUID id, String name, Role role, Resident.Status status, float hp, int gear, float armor, int squad, int kills, int heal) {}

    public record SquadInfo(int order, int waypoints, boolean hold) {}

    public record Entry(long day, String key, List<String> args) {}

    public record CaravanInfo(long target, int phase, float progress, int escort) {}

    public record Neighbor(long id, String name, String owner, OutpostState state, int skulls, int dist, int pact, int offered, int incoming,
                           int costTrade, int costMilitary, int caravans, float road) {
        public Component label() {
            if (state == OutpostState.OWNED) return Component.literal(name.isEmpty() ? owner : name);
            return Component.translatable("lordsoutpost.ui.wild_fort", "★".repeat(skulls));
        }
    }

    public final long id;
    public final String name, owner;
    public final OutpostState state;
    public final int skulls, tier, steps, planSize, pen, penCap, popCap, tributeStacks, lawCooldown, surplus;
    public final float progress;
    public final boolean complete, starving, raid, autoCaravans;
    public final long day;
    @Nullable public final Resource stalled;
    public final AccessLevel access;
    public final EnumMap<Resource, Double> stock = new EnumMap<>(Resource.class);
    public final EnumMap<Resource, Integer> cap = new EnumMap<>(Resource.class);
    public final EnumMap<Resource, Integer> boosts = new EnumMap<>(Resource.class);
    public final List<Person> people = new ArrayList<>();
    public final List<SquadInfo> squads = new ArrayList<>();
    public final Law law;
    public final List<Quest> quests = new ArrayList<>();
    public final List<Entry> chronicle = new ArrayList<>();
    public final List<CaravanInfo> caravans = new ArrayList<>();
    @Nullable public final AccessRules rules;
    public final List<Neighbor> neighbors = new ArrayList<>();

    public OutpostView(CompoundTag t) {
        id = t.getLong("id");
        name = t.getString("name");
        owner = t.getString("owner");
        state = OutpostState.VALUES[Math.max(0, Math.min(2, t.getByte("state")))];
        skulls = t.getByte("skulls");
        tier = t.getByte("tier");
        progress = t.getFloat("progress");
        steps = t.getInt("steps");
        planSize = t.getInt("planSize");
        complete = t.getBoolean("complete");
        day = t.getLong("day");
        starving = t.getBoolean("starving");
        stalled = t.contains("stalled") ? Resource.byId(t.getString("stalled")) : null;
        raid = t.getBoolean("raid");
        pen = t.getInt("pen");
        penCap = t.getInt("penCap");
        popCap = t.getInt("popCap");
        tributeStacks = t.getInt("tributeStacks");
        access = AccessLevel.VALUES[Math.max(0, Math.min(3, t.getByte("access")))];
        CompoundTag st = t.getCompound("stock"), cp = t.getCompound("cap"), bo = t.getCompound("boosts");
        for (Resource r : Resource.VALUES) {
            stock.put(r, st.getDouble(r.id));
            cap.put(r, cp.getInt(r.id));
            if (bo.contains(r.id)) boosts.put(r, bo.getInt(r.id));
        }
        ListTag res = t.getList("residents", Tag.TAG_COMPOUND);
        for (int i = 0; i < res.size(); i++) {
            CompoundTag r = res.getCompound(i);
            people.add(new Person(r.getUUID("id"), r.getString("name"), Role.byId(r.getString("role")),
                    Resident.Status.VALUES[Math.max(0, Math.min(2, r.getByte("status")))], r.getFloat("hp"), r.getByte("gear"),
                    r.getFloat("armor"), r.getByte("squad"), r.getInt("kills"), r.getInt("heal")));
        }
        ListTag sq = t.getList("squads", Tag.TAG_COMPOUND);
        for (int i = 0; i < sq.size(); i++) {
            CompoundTag s = sq.getCompound(i);
            squads.add(new SquadInfo(s.getByte("order"), s.getInt("waypoints"), s.getBoolean("hold")));
        }
        law = Law.byId(t.getString("law"));
        lawCooldown = t.getInt("lawCooldown");
        ListTag qs = t.getList("quests", Tag.TAG_COMPOUND);
        for (int i = 0; i < qs.size(); i++) quests.add(Quest.load(qs.getCompound(i)));
        ListTag ch = t.getList("chronicle", Tag.TAG_COMPOUND);
        for (int i = 0; i < ch.size(); i++) {
            CompoundTag c = ch.getCompound(i);
            List<String> args = new ArrayList<>();
            ListTag a = c.getList("args", Tag.TAG_STRING);
            for (int j = 0; j < a.size(); j++) args.add(a.getString(j));
            chronicle.add(new Entry(c.getLong("day"), c.getString("key"), args));
        }
        ListTag car = t.getList("caravans", Tag.TAG_COMPOUND);
        for (int i = 0; i < car.size(); i++) {
            CompoundTag c = car.getCompound(i);
            caravans.add(new CaravanInfo(c.getLong("target"), c.getByte("phase"), c.getFloat("progress"), c.getInt("escort")));
        }
        autoCaravans = t.getBoolean("autoCaravans");
        surplus = t.getInt("surplus");
        if (t.contains("accessRules")) {
            rules = new AccessRules();
            rules.load(t.getCompound("accessRules"));
        } else {
            rules = null;
        }
        ListTag nb = t.getList("neighbors", Tag.TAG_COMPOUND);
        for (int i = 0; i < nb.size(); i++) {
            CompoundTag n = nb.getCompound(i);
            neighbors.add(new Neighbor(n.getLong("id"), n.getString("name"), n.getString("owner"),
                    OutpostState.VALUES[Math.max(0, Math.min(2, n.getByte("state")))], n.getByte("skulls"), n.getInt("dist"),
                    n.getByte("pact"), n.getByte("offered"), n.getByte("incoming"), n.getInt("costTrade"), n.getInt("costMilitary"),
                    n.getInt("caravans"), n.getFloat("road")));
        }
    }

    public boolean lord() {
        return access == AccessLevel.LORD;
    }

    public int count(Role role) {
        int n = 0;
        for (Person p : people) if (p.role() == role) n++;
        return n;
    }

    public int fighters() {
        int n = 0;
        for (Person p : people) if (p.role().combat) n++;
        return n;
    }

    @Nullable
    public Neighbor neighbor(long id) {
        for (Neighbor n : neighbors) if (n.id() == id) return n;
        return null;
    }

    /** Строка хроники. Аргументы-идентификаторы (роль, закон, пакт, ресурс) показываются переведёнными. */
    public static Component describe(Entry e) {
        Object[] args = new Object[e.args().size()];
        for (int i = 0; i < args.length; i++) args[i] = pretty(e.args().get(i));
        return Component.translatable(e.key(), args);
    }

    private static String pretty(String arg) {
        for (String prefix : new String[]{"lordsoutpost.role.", "lordsoutpost.law.", "lordsoutpost.pact.", "lordsoutpost.resource.", "lordsoutpost.questkind."}) {
            if (arg.matches("[a-z_]+") && I18n.exists(prefix + arg)) return I18n.get(prefix + arg);
        }
        return arg;
    }
}
