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
        tag.putString("world", data.worldId().toString());
        tag.putLong("own", own == null ? 0 : own.id);
        if (own != null) tag.put("hud", hud(own));
        if (own == null || !detailed) return tag;

        if (!own.complete()) tag.putIntArray("nextOutline", flat(own.outline(Layout.radius(own.version, own.tier + 1))));
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
            t.put("manage", manage(level, o, now));
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

    /**
     * Хозяйство для Лорда: оценки планировщика, задачи жителей, рабочие зоны, лавка, магия и заказанные постройки.
     */
    private static CompoundTag manage(ServerLevel level, Outpost o, long now) {
        CompoundTag m = new CompoundTag();
        m.putFloat("foodDays", (float) com.vladosiwe.lordsoutpost.data.OutpostDirector.foodDays(o));
        if (o.scarce != null) m.putString("scarce", o.scarce.id);
        m.putBoolean("foodCritical", o.foodCritical);
        CompoundTag pr = new CompoundTag();
        for (var e : o.priorities.entrySet()) if (e.getValue() != com.vladosiwe.lordsoutpost.data.OutpostDirector.BASE) pr.putInt(e.getKey().id, e.getValue());
        m.put("priorities", pr);

        ListTag tasks = new ListTag();
        for (com.vladosiwe.lordsoutpost.outpost.OutpostTask task : o.tasks.all()) {
            if (!task.active() || tasks.size() >= 8) continue;
            CompoundTag tt = new CompoundTag();
            tt.putString("type", task.type.name().toLowerCase(java.util.Locale.ROOT));
            tt.putString("state", task.state.name().toLowerCase(java.util.Locale.ROOT));
            Resident who = task.executor == null ? null : o.resident(task.executor);
            Resident subject = task.subject == null ? null : o.resident(task.subject);
            tt.putString("who", who == null ? "" : who.name);
            tt.putString("subject", subject == null ? "" : subject.name);
            tt.putString("reason", task.reason);
            if (task.product != null) tt.putString("product", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(task.product).toString());
            tasks.add(tt);
        }
        m.put("tasks", tasks);

        // Кто чем занят: тела жителей в загруженных чанках; остальные работают «вдали» (A-Life считает их сам).
        java.util.Map<java.util.UUID, com.vladosiwe.lordsoutpost.entity.OutpostNpc> bodies = new java.util.HashMap<>();
        if (o.online) {
            int rr = o.reach() + 24;
            for (var npc : level.getEntitiesOfClass(com.vladosiwe.lordsoutpost.entity.OutpostNpc.class,
                    new net.minecraft.world.phys.AABB(o.center).inflate(rr, 64, rr), n -> n.outpostId() == o.id && n.residentId() != null)) {
                bodies.put(npc.residentId(), npc);
            }
        }
        ListTag people = new ListTag();
        for (com.vladosiwe.lordsoutpost.outpost.Resident r : o.residents) {
            if (!r.active()) continue;
            CompoundTag pt = new CompoundTag();
            pt.putString("name", r.name);
            pt.putString("role", r.role.id);
            var body = bodies.get(r.id);
            String act;
            if (body == null) act = o.online ? "away" : "virtual";
            else if (body.isSleeping()) act = "sleep";
            else if (body instanceof com.vladosiwe.lordsoutpost.entity.WorkerEntity w) {
                java.util.List<String> running = w.goalClassNames();
                if (running.stream().anyMatch(n -> n.startsWith("BuildGoal"))) act = "build";
                else if (running.stream().anyMatch(n -> n.startsWith("MageWorkGoal"))) act = "mage";
                else if (running.stream().anyMatch(n -> n.startsWith("CaravanGoal"))) act = "caravan";
                else if (running.stream().anyMatch(n -> n.startsWith("ShelterGoal"))) act = "shelter";
                else act = w.workGoal == null ? "idle" : w.workGoal.activity();
            } else act = body.getTarget() != null ? "fight" : "guard";
            pt.putString("act", act);
            people.add(pt);
        }
        m.put("people", people);

        ListTag zones = new ListTag();
        for (com.vladosiwe.lordsoutpost.outpost.WorkZone z : com.vladosiwe.lordsoutpost.outpost.WorkZone.VALUES) {
            if (!o.hasZone(z)) continue;
            CompoundTag zt = new CompoundTag();
            zt.putString("id", z.id);
            zt.putBoolean("relocatable", z.relocatable);
            zt.putBoolean("moved", o.zones.override(z) != null);
            String problem = o.zones.problem(z);
            if (problem != null) zt.putString("problem", problem);
            zones.add(zt);
        }
        m.put("zones", zones);

        ListTag shop = new ListTag();
        for (net.minecraft.world.item.ItemStack st : o.shopStock) {
            CompoundTag it = new CompoundTag();
            it.putString("item", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
            it.putInt("count", st.getCount());
            shop.add(it);
        }
        m.put("shop", shop);

        CompoundTag magic = new CompoundTag();
        magic.putInt("mages", o.activeCount(com.vladosiwe.lordsoutpost.outpost.Role.MAGE));
        magic.putBoolean("tower", o.tier >= 3 && o.hasZone(com.vladosiwe.lordsoutpost.outpost.WorkZone.MAGE_TOWER));
        magic.putFloat("arcane", (float) o.stock(Resource.ARCANE));
        magic.putFloat("work", (float) o.arcaneWork);
        magic.putBoolean("iss", com.vladosiwe.lordsoutpost.compat.MagicLoot.ironsLoaded());
        magic.putBoolean("pyroAvailable", com.vladosiwe.lordsoutpost.compat.Pyromancer.available());
        magic.putBoolean("pyromancer", o.pyromancer != null);
        magic.putInt("pyroRejoin", (int) Math.max(0, (o.pyromancerRejoinAt - now) / 20));
        // Полка мага: полные предметы (с зачарованиями и заклинаниями) и цены, как в окне торговли.
        ListTag shelf = new ListTag();
        for (net.minecraft.world.item.ItemStack st : o.magicShelf) {
            if (st.isEmpty()) continue;
            CompoundTag it = new CompoundTag();
            it.put("stack", st.save(level.registryAccess()));
            it.putInt("price", com.vladosiwe.lordsoutpost.data.Shop.magicPrice(st));
            shelf.add(it);
        }
        magic.put("shelf", shelf);
        int satchel = 0;
        for (int i = 0; i < o.mageSatchel.getContainerSize(); i++) satchel += o.mageSatchel.getItem(i).getCount();
        magic.putInt("satchel", satchel);
        if (o.tier >= 3 && o.hasZone(com.vladosiwe.lordsoutpost.outpost.WorkZone.MAGE_TOWER) && o.online) {
            boolean forge = com.vladosiwe.lordsoutpost.data.MageCraft.forge(level, o.zone(com.vladosiwe.lordsoutpost.outpost.WorkZone.MAGE_TOWER)) != null;
            magic.putBoolean("forge", forge);
            var next = com.vladosiwe.lordsoutpost.data.MageCraft.next(level, o, forge);
            if (next != null) magic.putString("next", next.id());
        }
        m.put("magic", magic);

        ListTag projects = new ListTag();
        for (com.vladosiwe.lordsoutpost.outpost.Project p : o.projects) {
            if (p.state == com.vladosiwe.lordsoutpost.outpost.Project.State.CANCELLED) continue;
            CompoundTag pt = new CompoundTag();
            pt.putLong("id", p.id);
            pt.putString("type", p.type.id);
            pt.putByte("state", (byte) p.state.ordinal());
            pt.putFloat("progress", p.progress(o));
            pt.putInt("paid", p.paid);
            pt.putInt("size", p.size(o));
            pt.putInt("x", p.origin.getX());
            pt.putInt("y", p.origin.getY());
            pt.putInt("z", p.origin.getZ());
            pt.putByte("rot", (byte) p.rotation.ordinal());
            if (p.active()) {
                pt.putInt("builders", com.vladosiwe.lordsoutpost.data.Projects.builders(o, p));
                // Нехватка: остатка стоимости больше, чем лежит на складе этого аванпоста.
                CompoundTag lack = new CompoundTag();
                for (var e : com.vladosiwe.lordsoutpost.data.Projects.remaining(o, p).entrySet()) {
                    double have = o.availableForProjects(e.getKey()) + o.buildMaterials.getOrDefault(e.getKey(), 0.0);
                    if (have + 1e-6 < 1 && e.getValue() > 0) lack.putInt(e.getKey().id, e.getValue());
                }
                pt.put("lack", lack);
                CompoundTag rem = new CompoundTag();
                for (var e : com.vladosiwe.lordsoutpost.data.Projects.remaining(o, p).entrySet()) rem.putInt(e.getKey().id, e.getValue());
                pt.put("remaining", rem);
            }
            projects.add(pt);
        }
        m.put("projects", projects);
        // Стоимость каждого типа постройки в ресурсах (по шагам её чертежа).
        CompoundTag costs = new CompoundTag();
        for (com.vladosiwe.lordsoutpost.outpost.Project.Type type : com.vladosiwe.lordsoutpost.outpost.Project.Type.VALUES) {
            CompoundTag c = new CompoundTag();
            var probe = new com.vladosiwe.lordsoutpost.outpost.Project(0, type, o.center, net.minecraft.world.level.block.Rotation.NONE);
            for (var step : probe.steps(o)) if (step.cost() != null) c.putInt(step.cost().id, c.getInt(step.cost().id) + 1);
            costs.put(type.id, c);
        }
        m.put("costs", costs);
        // Для RTS-режима: где форт, его палитра (вид построек), граница заказов и резерв ресурсов.
        m.putInt("cx", o.center.getX());
        m.putInt("cy", o.center.getY());
        m.putInt("cz", o.center.getZ());
        m.putInt("reach", o.reach());
        m.putInt("orderLimit", com.vladosiwe.lordsoutpost.blueprint.Layout.radius(o.version, com.vladosiwe.lordsoutpost.blueprint.FortBlueprint.MAX_TIER)
                + com.vladosiwe.lordsoutpost.blueprint.Layout.MARGIN + com.vladosiwe.lordsoutpost.data.Projects.EXTENT);
        m.putString("palette", o.palette.id);
        m.putString("layout", o.layout.id);
        m.putByte("frot", (byte) o.rotation.ordinal());
        CompoundTag reserved = new CompoundTag();
        for (Resource r : Resource.VALUES) {
            double n = com.vladosiwe.lordsoutpost.data.Projects.reserved(o, r);
            if (n > 0) reserved.putInt(r.id, (int) Math.ceil(n));
        }
        m.put("reserved", reserved);
        m.putInt("projectLimit", com.vladosiwe.lordsoutpost.data.Projects.MAX_ACTIVE);
        m.putBoolean("autoBuild", o.autoBuild);
        CompoundTag rec = new CompoundTag();
        for (var e : o.recommended.entrySet()) rec.putString(e.getKey().id, e.getValue());
        m.put("recommended", rec);
        CompoundTag mat = new CompoundTag();
        for (var e : o.buildMaterials.entrySet()) if (e.getValue() >= 0.01) mat.putFloat(e.getKey().id, e.getValue().floatValue());
        m.put("materials", mat);
        m.putInt("houses", o.builtProjects(com.vladosiwe.lordsoutpost.outpost.Project.Type.HOUSE));
        return m;
    }

    /** Короткая сводка своего аванпоста для HUD (приходит с картой раз в несколько секунд). */
    private static CompoundTag hud(Outpost o) {
        CompoundTag h = new CompoundTag();
        h.putByte("tier", (byte) o.tier);
        h.putFloat("progress", o.progress());
        h.putBoolean("complete", o.complete());
        h.putInt("pop", o.residents.size());
        h.putInt("popCap", o.populationCap());
        h.putFloat("foodDays", (float) com.vladosiwe.lordsoutpost.data.OutpostDirector.foodDays(o));
        h.putBoolean("starving", o.starving || o.foodCritical);
        h.putBoolean("raid", o.raid != null);
        if (o.scarce != null) h.putString("scarce", o.scarce.id);
        h.putInt("problems", o.zones.problems().size());
        h.putInt("tasks", o.tasks.activeCount());
        int projects = 0;
        for (com.vladosiwe.lordsoutpost.outpost.Project p : o.projects) if (p.active()) projects++;
        h.putInt("projects", projects);
        if (!o.recommended.isEmpty()) h.putString("advice", o.recommended.keySet().iterator().next().id);
        return h;
    }

    public static BlockPos pos(long packed) {
        return BlockPos.of(packed);
    }
}
