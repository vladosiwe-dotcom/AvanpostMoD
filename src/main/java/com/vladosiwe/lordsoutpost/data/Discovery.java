package com.vladosiwe.lordsoutpost.data;

import com.vladosiwe.lordsoutpost.config.OutpostConfig;
import com.vladosiwe.lordsoutpost.network.ModNetwork;
import com.vladosiwe.lordsoutpost.network.Payloads;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.server.OutpostManager;
import com.vladosiwe.lordsoutpost.world.OutpostSites;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.Optional;

/**
 * Обнаружение. В радиусе 1200–1500 блоков (у каждого форта свой) аванпост появляется на карте знаком вопроса;
 * в 100–120 блоках игрок его «находит»: баннер с черепками, а знак вопроса сменяется иконкой форта.
 * Записи о фортах в ещё не созданных чанках берутся из предсказания {@link OutpostSites}.
 */
public final class Discovery {
    private Discovery() {}

    public static int hintRadius(Outpost o) {
        int min = OutpostConfig.get(OutpostConfig.RADAR_MIN, 1200), max = Math.max(min, OutpostConfig.get(OutpostConfig.RADAR_MAX, 1500));
        return min + (int) Math.floorMod(o.id * 0x9E3779B97F4A7C15L >>> 20, (long) (max - min + 1));
    }

    public static void tick(ServerLevel level, OutpostSavedData data) {
        int max = Math.max(OutpostConfig.get(OutpostConfig.RADAR_MIN, 1200), OutpostConfig.get(OutpostConfig.RADAR_MAX, 1500));
        int near = OutpostConfig.get(OutpostConfig.DISCOVERY_RANGE, 110);
        long now = level.getGameTime();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            int px = player.getBlockX(), pz = player.getBlockZ();
            ensureRecords(level, data, px, pz, max, now);
            OutpostSavedData.Knowledge k = data.knowledge(player.getUUID());
            for (Outpost o : data.near(px, pz, max)) {
                double dist = Math.sqrt(o.distanceSqr(px, pz));
                if (dist <= hintRadius(o) && k.hinted.add(o.id)) data.setDirty();
                if (dist <= near && k.discovered.add(o.id)) {
                    k.hinted.add(o.id);
                    data.setDirty();
                    ModNetwork.send(player, new Payloads.Discovery(o.difficulty.skulls, o.center.getX(), o.center.getZ()));
                    level.playSound(null, player.blockPosition(), SoundEvents.BELL_RESONATE, SoundSource.PLAYERS, 0.8f, 0.7f);
                    ModNetwork.sendMap(player, data, false, true);
                }
            }
        }
    }

    /** Создаёт записи для фортов сетки вокруг точки, если их ещё нет. */
    public static void ensureRecords(ServerLevel level, OutpostSavedData data, int x, int z, int range, long now) {
        int rx0 = OutpostSavedData.regionOf(x - range), rx1 = OutpostSavedData.regionOf(x + range);
        int rz0 = OutpostSavedData.regionOf(z - range), rz1 = OutpostSavedData.regionOf(z + range);
        for (int rx = rx0; rx <= rx1; rx++)
            for (int rz = rz0; rz <= rz1; rz++) {
                if (data.gridOutpost(rx, rz) != null || data.regionEmpty(rx, rz)) continue;
                Optional<OutpostSites.Site> site = OutpostSites.predict(level, rx, rz);
                if (site.isEmpty()) continue;
                OutpostSites.Site s = site.get();
                OutpostManager.createGenerated(data, OutpostSavedData.regionKey(rx, rz), s.center(), s.layout(), s.palette(), s.rotation(), s.difficulty(), now, level.random);
            }
    }
}
