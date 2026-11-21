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
    /** Хозяйство (только для Лорда): планировщик, задачи, зоны, лавка, магия, заказанные постройки. */
    @Nullable public final Manage manage;

    public record PersonInfo(String name, String role, String activity, @org.jetbrains.annotations.Nullable java.util.UUID id, int roleWait) {}

    public record TaskInfo(String type, String state, String who, String subject, String reason, String product) {}

    public record ZoneInfo(String id, boolean relocatable, boolean moved, String problem) {}

    public record ProjectInfo(long id, String type, int state, float progress, int paid, int size,
                              int x, int y, int z, int rot, int builders, EnumMap<Resource, Integer> lack, EnumMap<Resource, Integer> remaining,
                              int level, int maxLevel, boolean upgrade, boolean upgrading, EnumMap<Resource, Integer> upCost) {
        public boolean active() {
            return state <= 1;
        }

        public boolean done() {
            return state == 2;
        }
    }

    private static EnumMap<Resource, Integer> resources(CompoundTag t) {
        EnumMap<Resource, Integer> out = new EnumMap<>(Resource.class);
        for (String k : t.getAllKeys()) {
            Resource r = Resource.byId(k);
            if (r != null) out.put(r, t.getInt(k));
        }
        return out;
    }

    public static final class Manage {
        public final float foodDays;
        @Nullable public final Resource scarce;
        public final boolean foodCritical;
        public final java.util.Map<Role, Integer> priorities = new EnumMap<>(Role.class);
        public final List<TaskInfo> tasks = new ArrayList<>();
        public final List<PersonInfo> people = new ArrayList<>();
        public final List<ZoneInfo> zones = new ArrayList<>();
        public final List<net.minecraft.world.item.ItemStack> shop = new ArrayList<>();
        public final int mages, pyroRejoin, projectLimit, houses;
        public final boolean tower, iss, pyroAvailable, pyromancer, forge;
        /** Полка придворного мага и цены в изумрудах. */
        public final List<net.minecraft.world.item.ItemStack> shelf = new ArrayList<>();
        public final List<Integer> shelfPrices = new ArrayList<>();
        public final int satchel;
        public final String nextCraft;
        public final float arcane, work;
        public final List<ProjectInfo> projects = new ArrayList<>();
        public final boolean autoBuild;
        /** Советы планировщика: тип постройки -> «жителей/мест/нужно». */
        public final java.util.Map<String, String> recommended = new java.util.LinkedHashMap<>();
        public final EnumMap<Resource, Float> materials = new EnumMap<>(Resource.class);
        public final java.util.Map<String, EnumMap<Resource, Integer>> costs = new java.util.HashMap<>();
        /** Для RTS-режима: центр форта, граница заказов, палитра построек, резерв ресурсов под заказы. */
        public final int cx, cy, cz, reach, orderLimit;
        public final String palette;
        public final EnumMap<Resource, Integer> reserved;

        Manage(CompoundTag m) {
            foodDays = m.getFloat("foodDays");
            scarce = m.contains("scarce") ? Resource.byId(m.getString("scarce")) : null;
            foodCritical = m.getBoolean("foodCritical");
            CompoundTag pr = m.getCompound("priorities");
            for (String k : pr.getAllKeys()) priorities.put(Role.byId(k), pr.getInt(k));
            ListTag ts = m.getList("tasks", Tag.TAG_COMPOUND);
            for (int i = 0; i < ts.size(); i++) {
                CompoundTag t = ts.getCompound(i);
                tasks.add(new TaskInfo(t.getString("type"), t.getString("state"), t.getString("who"), t.getString("subject"),
                        t.getString("reason"), t.getString("product")));
            }
            ListTag pl = m.getList("people", Tag.TAG_COMPOUND);
            for (int i = 0; i < pl.size(); i++) {
                CompoundTag p = pl.getCompound(i);
                people.add(new PersonInfo(p.getString("name"), p.getString("role"), p.getString("act"), p.hasUUID("id") ? p.getUUID("id") : null, p.getInt("roleWait")));
            }
            ListTag zs = m.getList("zones", Tag.TAG_COMPOUND);
            for (int i = 0; i < zs.size(); i++) {
                CompoundTag z = zs.getCompound(i);
                zones.add(new ZoneInfo(z.getString("id"), z.getBoolean("relocatable"), z.getBoolean("moved"), z.getString("problem")));
            }
            ListTag sh = m.getList("shop", Tag.TAG_COMPOUND);
            for (int i = 0; i < sh.size(); i++) {
                CompoundTag it = sh.getCompound(i);
                var id = net.minecraft.resources.ResourceLocation.tryParse(it.getString("item"));
                if (id == null) continue;
                var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
                shop.add(new net.minecraft.world.item.ItemStack(item, Math.max(1, it.getInt("count"))));
            }
            CompoundTag mg = m.getCompound("magic");
            mages = mg.getInt("mages");
            tower = mg.getBoolean("tower");
            arcane = mg.getFloat("arcane");
            work = mg.getFloat("work");
            iss = mg.getBoolean("iss");
            pyroAvailable = mg.getBoolean("pyroAvailable");
            pyromancer = mg.getBoolean("pyromancer");
            pyroRejoin = mg.getInt("pyroRejoin");
            forge = mg.getBoolean("forge");
            satchel = mg.getInt("satchel");
            nextCraft = mg.getString("next");
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level != null) {
                ListTag shelfTag = mg.getList("shelf", Tag.TAG_COMPOUND);
                for (int i = 0; i < shelfTag.size(); i++) {
                    CompoundTag it = shelfTag.getCompound(i);
                    int price = it.getInt("price");
                    net.minecraft.world.item.ItemStack.parse(mc.level.registryAccess(), it.getCompound("stack")).ifPresent(st -> {
                        shelf.add(st);
                        shelfPrices.add(price);
                    });
                }
            }
            ListTag ps = m.getList("projects", Tag.TAG_COMPOUND);
            for (int i = 0; i < ps.size(); i++) {
                CompoundTag p = ps.getCompound(i);
                projects.add(new ProjectInfo(p.getLong("id"), p.getString("type"), p.getByte("state"), p.getFloat("progress"), p.getInt("paid"), p.getInt("size"),
                        p.getInt("x"), p.getInt("y"), p.getInt("z"), p.getByte("rot"), p.getInt("builders"),
                        resources(p.getCompound("lack")), resources(p.getCompound("remaining")),
                        Math.max(1, p.getByte("level")), Math.max(1, p.getByte("maxLevel")), p.getBoolean("upgrade"), p.getBoolean("upgrading"),
                        resources(p.getCompound("upCost"))));
            }
            CompoundTag cs = m.getCompound("costs");
            for (String type : cs.getAllKeys()) {
                EnumMap<Resource, Integer> c = new EnumMap<>(Resource.class);
                CompoundTag ct = cs.getCompound(type);
                for (String r : ct.getAllKeys()) {
                    Resource res = Resource.byId(r);
                    if (res != null) c.put(res, ct.getInt(r));
                }
                costs.put(type, c);
            }
            projectLimit = m.getInt("projectLimit");
            cx = m.getInt("cx");
            cy = m.getInt("cy");
            cz = m.getInt("cz");
            reach = m.getInt("reach");
            orderLimit = m.getInt("orderLimit");
            palette = m.getString("palette");
            reserved = resources(m.getCompound("reserved"));
            autoBuild = m.getBoolean("autoBuild");
            CompoundTag rec = m.getCompound("recommended");
            for (String k : rec.getAllKeys()) recommended.put(k, rec.getString(k));
            CompoundTag mat = m.getCompound("materials");
            for (String k : mat.getAllKeys()) {
                Resource r = Resource.byId(k);
                if (r != null) materials.put(r, mat.getFloat(k));
            }
            houses = m.getInt("houses");
        }

        public int activeProjects() {
            int n = 0;
            for (ProjectInfo p : projects) if (p.state() <= 1) n++;
            return n;
        }
    }
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
        manage = t.contains("manage") ? new Manage(t.getCompound("manage")) : null;
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
        for (String prefix : new String[]{"lordsoutpost.role.", "lordsoutpost.law.", "lordsoutpost.pact.", "lordsoutpost.resource.", "lordsoutpost.questkind.",
                "lordsoutpost.project.", "lordsoutpost.zone.", "lordsoutpost.craft."}) {
            if (arg.matches("[a-z_]+") && I18n.exists(prefix + arg)) return I18n.get(prefix + arg);
        }
        return arg;
    }
}
