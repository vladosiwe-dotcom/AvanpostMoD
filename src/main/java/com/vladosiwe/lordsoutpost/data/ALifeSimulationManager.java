package com.vladosiwe.lordsoutpost.data;

import com.vladosiwe.lordsoutpost.compat.SoloStatusCompat;
import com.vladosiwe.lordsoutpost.network.ModNetwork;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.server.NpcLifecycle;
import com.vladosiwe.lordsoutpost.server.Notify;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Дирижёр A-Life. Каждый тик сервера:
 *  1) согласование — отражение записей в загруженных чанках (блоки, сущности, караваны);
 *  2) макро-тик — раз в пять секунд на аванпост считается экономика, независимо от того, загружены ли его чанки;
 *  3) раз в секунду — обнаружение аванпостов игроками и рассылка обновлений клиентам.
 * Чанки при этом принудительно не загружаются: далёкий форт существует только как запись и почти ничего не стоит серверу.
 */
public final class ALifeSimulationManager {
    /** Период макро-тика в тиках. */
    public static final long SIM_INTERVAL = 100;

    private ALifeSimulationManager() {}

    public static void tick(MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (level == null) return;
        OutpostSavedData data = OutpostSavedData.get(level);
        long now = level.getGameTime();
        ChunkReconciliation.tick(level, data, now);
        for (Outpost o : data.all()) {
            if (now - o.lastSim < SIM_INTERVAL) continue;
            int tier = o.tier;
            Simulation.tick(level, data, o, now);
            if (o.tier != tier && o.state == OutpostState.OWNED) {
                Notify.lord(server, o, Component.translatable("lordsoutpost.event.tier_up", o.tier));
                ServerPlayer lord = NpcLifecycle.lord(level, o);
                if (lord != null) SoloStatusCompat.onTier(lord, o.tier);
            }
        }
        if (now % 20 == 0) {
            Discovery.tick(level, data);
            ModNetwork.sync(server, data, now);
        }
    }

    /** Сброс несохраняемого состояния при остановке сервера (важно для одиночной игры, где JVM живёт дальше). */
    public static void reset(MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (level == null) return;
        for (Outpost o : OutpostSavedData.get(level).all()) ChunkReconciliation.forget(o);
    }
}
