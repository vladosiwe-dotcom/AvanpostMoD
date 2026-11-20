package com.vladosiwe.lordsoutpost.server;

import com.vladosiwe.lordsoutpost.blueprint.Marker;
import com.vladosiwe.lordsoutpost.compat.SoloStatusCompat;
import com.vladosiwe.lordsoutpost.data.ChunkReconciliation;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.data.Raids;
import com.vladosiwe.lordsoutpost.data.Simulation;
import com.vladosiwe.lordsoutpost.entity.OutpostNpc;
import com.vladosiwe.lordsoutpost.outpost.Caravan;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Role;
import com.vladosiwe.lordsoutpost.outpost.Squad;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Что происходит с записью жителя, когда его сущность выгружается или погибает. */
public final class NpcLifecycle {
    /** Метка вьючного животного каравана (не сохраняется между загрузками чанка). */
    public static final String CARAVAN_TAG = "lordsoutpost_caravan";
    /** Метка дичи, добытой охотником: её добыча идёт на склад. */
    public static final String HUNTED_TAG = "lordsoutpost_hunted";

    private NpcLifecycle() {}

    @Nullable
    public static ServerPlayer lord(ServerLevel level, Outpost o) {
        return o.owner == null ? null : level.getServer().getPlayerList().getPlayer(o.owner);
    }

    @Nullable
    public static Caravan caravan(Outpost o, @Nullable UUID id) {
        if (id == null) return null;
        for (Caravan c : o.caravans) if (c.id.equals(id)) return c;
        return null;
    }

    /** Уровень земли в точке: внутри стен — двор форта, снаружи — поверхность мира. */
    public static BlockPos groundAt(ServerLevel level, Outpost o, int x, int z) {
        if (o.insideHorizontally(x, z, o.radius() - 1)) return new BlockPos(x, o.center.getY() + 1, z);
        if (!level.hasChunk(x >> 4, z >> 4)) return new BlockPos(x, o.center.getY() + 1, z);
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    /** Бойцы, которые сейчас стоят на постах, в порядке раздачи мест: сначала стрелки — им достаются башни и стены. */
    private static List<Resident> posted(Outpost o) {
        List<Resident> out = new ArrayList<>();
        for (int i = 0; i < o.residents.size(); i++) {
            Resident r = o.residents.get(i);
            if (!r.role.combat || r.role == Role.CAPTAIN || !r.active()) continue;
            if (o.state == OutpostState.OWNED) {
                Squad s = o.squads[Math.max(0, Math.min(Squad.COUNT - 1, r.squad))];
                if (s.order == Squad.Order.PATROL || (s.order == Squad.Order.HOLD && s.hold != null)) continue;
            } else if ((i & 1) != 0) {
                continue;
            }
            out.add(r);
        }
        out.sort(Comparator.comparingInt(r -> r.role.ranged() ? 0 : 1));
        return out;
    }

    /**
     * Пост бойца. Стрелки занимают башни и стены (сверху вниз), бойцы ближнего боя — сперва двери склада,
     * потом посты у ворот и во дворе.
     */
    public static BlockPos postFor(Outpost o, Resident r) {
        List<BlockPos> high = new ArrayList<>(o.markers(Marker.GUARD_POST));
        high.sort(Comparator.comparingInt(p -> -p.getY()));
        List<BlockPos> low = new ArrayList<>(o.markers(Marker.STORE_GUARD));
        List<BlockPos> ground = new ArrayList<>(o.markers(Marker.GUARD_POST));
        ground.sort(Comparator.comparingInt(BlockPos::getY));
        low.addAll(ground);
        List<Resident> posted = posted(o);
        boolean ranged = r.role.ranged();
        int index = 0;
        for (Resident other : posted) {
            if (other == r) break;
            if (other.role.ranged() == ranged) index++;
        }
        List<BlockPos> posts = ranged ? high : low;
        if (posts.isEmpty()) posts = high.isEmpty() ? low : high;
        if (posts.isEmpty()) return o.marker(Marker.YARD);
        return posts.get(index % posts.size());
    }

    /** Сущность выгружена (чанк ушёл из памяти, игрок далеко): состояние возвращается в запись. */
    public static void onUnloaded(OutpostNpc npc) {
        if (!(npc.level() instanceof ServerLevel level)) return;
        OutpostSavedData data = OutpostSavedData.get(level);
        if (npc.raidTarget != 0) {
            Outpost target = data.byId(npc.raidTarget);
            if (target != null && target.raid != null && target.raid.entities.remove(npc.getUUID())) target.raid.toSpawn++;
            return;
        }
        Outpost o = data.byId(npc.outpostId());
        if (o == null) return;
        Resident r = npc.residentId() == null ? null : o.resident(npc.residentId());
        // Недонесённая ноша не пропадает: её ресурсы засчитываются сразу.
        // Проверка владения не нужна: ноша не должна исчезать и у форта, который только что потерял Лорда.
        if (npc instanceof com.vladosiwe.lordsoutpost.entity.WorkerEntity w && !w.inventory.isEmpty()) {
            com.vladosiwe.lordsoutpost.data.Storehouse.depositVirtually(o, w.inventory);
            // Ноша жителя уже перенесена в запасы/дань — фиксируем её до выгрузки или завершения сервера.
            data.setDirty();
        }
        if (r != null && npc.getUUID().equals(r.entity)) {
            r.entity = null;
            r.health = Math.max(0.05f, npc.getHealth() / Math.max(1f, npc.getMaxHealth()));
            // Ноша уже на складе: задачи жителя возвращаются в очередь (планируемые) или отменяются (личные запросы).
            o.tasks.releaseExecutor(r.id, "unloaded", level.getGameTime());
        }
        Caravan c = caravan(o, npc.caravanId);
        if (c != null && c.materialized && r != null && r.id.equals(c.leader)) ChunkReconciliation.dematerialize(level, c, npc.getUUID());
    }

    public static void onDeath(OutpostNpc npc, DamageSource source) {
        if (!(npc.level() instanceof ServerLevel level)) return;
        OutpostSavedData data = OutpostSavedData.get(level);
        long now = level.getGameTime();
        if (source.getEntity() instanceof OutpostNpc killer) {
            Resident kr = killer.resident();
            if (kr != null) kr.kills++;
        }
        ServerPlayer killerPlayer = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        if (killerPlayer != null && npc.hostileFaction()) SoloStatusCompat.onMarauderKilled(killerPlayer, npc.role() == Role.CAPTAIN);

        if (npc.raidTarget != 0) {
            Outpost target = data.byId(npc.raidTarget);
            if (target != null && target.raid != null) {
                target.raid.entities.remove(npc.getUUID());
                target.raid.killed++;
                if (target.raid.toSpawn <= 0 && target.raid.entities.isEmpty()) {
                    long src = target.raid.source;
                    target.raid = null;
                    Raids.victory(level, target, src, now, 0);
                    Raids.schedule(data, target, now, level.random);
                }
            }
            return;
        }
        Outpost o = data.byId(npc.outpostId());
        if (o == null || npc.residentId() == null) return;
        Resident r = o.resident(npc.residentId());
        if (r == null) return;
        r.entity = null;
        // Ноша погибшего выпала на землю: задача освобождается, её резерв на складе сохраняется для другого работника.
        o.tasks.releaseExecutor(r.id, "died", now);
        data.setDirty();

        if (o.state == OutpostState.WILD) {
            o.residents.remove(r);
            o.alarmUntil = now + 2400;
            o.garrisonRegenAt = now + Simulation.DAY / 2;
            if (r.role == Role.CAPTAIN) {
                OutpostManager.conquer(data, o, now);
                Captures.onCaptainSlain(level, data, o, killerPlayer, npc.blockPosition());
            }
            return;
        }
        if (o.state != OutpostState.OWNED) return;
        Caravan c = caravan(o, npc.caravanId);
        boolean retinue = r.squad == Squad.RETINUE && o.squads[Squad.RETINUE].order == Squad.Order.RETINUE;
        int beds = o.infirmaryBeds();
        for (Resident other : o.residents) if (other.status == Resident.Status.WOUNDED) beds--;
        // Свиту из похода всегда выносят в лазарет; дома раненого спасает свободная койка.
        if (r.role.combat && (retinue || beds > 0)) {
            r.status = Resident.Status.WOUNDED;
            r.statusUntil = now + 6000;
            r.health = 0.3f;
            r.armor = Math.max(0, r.armor - 0.3f);
            Notify.lord(level.getServer(), o, Component.translatable("lordsoutpost.event.wounded", r.name).withStyle(ChatFormatting.YELLOW));
            o.log(now, "lordsoutpost.event.wounded", r.name);
        } else {
            o.residents.remove(r);
            Notify.lord(level.getServer(), o, Component.translatable("lordsoutpost.event.died", r.name, r.role.label()).withStyle(ChatFormatting.RED));
            o.log(now, "lordsoutpost.event.died", r.name, r.role.id);
        }
        if (c != null) {
            c.escort.remove(r.id);
            if (r.id.equals(c.leader)) ChunkReconciliation.dematerialize(level, c, npc.getUUID());
        }
        o.alarmUntil = Math.max(o.alarmUntil, now + 600);
        o.dirtyForClients = true;
    }

    /** Капитан зовёт подкрепление. */
    public static void reinforce(ServerLevel level, OutpostNpc captain, int count) {
        Outpost o = captain.outpost();
        if (o == null || o.state != OutpostState.WILD) return;
        for (int i = 0; i < count; i++) {
            Resident r = OutpostManager.marauder(o, level.random);
            o.residents.add(r);
            ChunkReconciliation.spawn(level, o, r, captain.blockPosition().offset(level.random.nextInt(5) - 2, 0, level.random.nextInt(5) - 2));
        }
    }
}
