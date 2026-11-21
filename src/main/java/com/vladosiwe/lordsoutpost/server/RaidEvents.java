package com.vladosiwe.lordsoutpost.server;

import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.data.ChunkReconciliation;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Ravager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** Разоритель набега, загруженный с диска после конца набега (или перезапуска), не остаётся бродить у форта. */
@EventBusSubscriber(modid = LordsOutpost.MODID)
public final class RaidEvents {
    private RaidEvents() {}

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Ravager r) || !r.getTags().contains(ChunkReconciliation.RAID_TAG)) return;
        if (!event.loadedFromDisk()) return;
        for (Outpost o : OutpostSavedData.get(level).all()) if (o.raid != null && o.raid.beasts.contains(r.getUUID())) return;
        event.setCanceled(true);
    }
}
