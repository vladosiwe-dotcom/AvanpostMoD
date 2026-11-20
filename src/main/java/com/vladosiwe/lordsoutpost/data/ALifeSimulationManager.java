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

    /** Шаг догона в тиках: минута игры. Мельче не нужно, ставки считаются на долю дня. */
    private static final long CATCH_UP_STEP = 1200;

    /**
     * Догон при запуске сервера: пока мир был закрыт, игровое время стоит, но форты должны жить дальше.
     * Реальное время отсутствия (не больше {@code economy.offlineCatchUpDays}) прогоняется макро-тиками «задним числом»:
     * от (сейчас − отсутствие) до сейчас. Все форты в это время вне игры, поэтому всё считается виртуально.
     * Блоки построенного встанут, когда игрок подойдёт.
     */
    public static void catchUp(MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (level == null) return;
        OutpostSavedData data = OutpostSavedData.get(level);
        long away = data.awayMillis;
        data.awayMillis = 0;
        double maxDays = com.vladosiwe.lordsoutpost.config.OutpostConfig.get(com.vladosiwe.lordsoutpost.config.OutpostConfig.OFFLINE_DAYS, 7.0);
        long ticks = Math.min(away / 50, (long) (maxDays * Simulation.DAY));
        if (ticks < SIM_INTERVAL) return;
        catchUp(level, data, ticks);
    }

    public static void catchUp(ServerLevel level, OutpostSavedData data, long ticks) {
        for (Outpost o : new java.util.ArrayList<>(data.all())) {
            if (data.byId(o.id) == o) catchUp(level, data, o, ticks);
        }
        data.setDirty();
        com.vladosiwe.lordsoutpost.LordsOutpost.LOGGER.info("Lord's Outpost: форты прожили {} игровых дн. за время, пока мир был закрыт",
                Math.round(ticks / (double) Simulation.DAY * 10) / 10.0);
    }

    /** Один форт проживает {@code ticks} тиков, закончившихся сейчас. */
    public static void catchUp(ServerLevel level, OutpostSavedData data, Outpost o, long ticks) {
        long now = level.getGameTime();
        long from = now - ticks;
        int days = (int) Math.round(ticks / (double) Simulation.DAY * 10);
        {
            int tier = o.tier;
            o.lastSim = Math.min(o.lastSim, from);
            for (long t = from + CATCH_UP_STEP; t < now; t += CATCH_UP_STEP) Simulation.tick(level, data, o, t);
            Simulation.tick(level, data, o, now);
            if (o.state == OutpostState.OWNED) {
                o.log(now, "lordsoutpost.event.catch_up", days / 10 + "." + days % 10, tier, o.tier);
            }
        }
    }

    /** Сброс несохраняемого состояния при остановке сервера (важно для одиночной игры, где JVM живёт дальше). */
    public static void reset(MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (level == null) return;
        OutpostSavedData data = OutpostSavedData.get(level);
        // ServerStoppingEvent приходит до сохранения мира. Сущности жителей не пишутся в чанк, поэтому их ношу
        // и здоровье нужно вернуть в запись сейчас: при закрытии мира сущности не проходят через выгрузку.
        for (Outpost o : data.all()) {
            for (var r : new java.util.ArrayList<>(o.residents)) {
                if (r.entity != null && level.getEntity(r.entity) instanceof com.vladosiwe.lordsoutpost.entity.OutpostNpc npc) npc.detach();
            }
        }
        data.setDirty();
        for (Outpost o : data.all()) ChunkReconciliation.forget(o);
    }
}
