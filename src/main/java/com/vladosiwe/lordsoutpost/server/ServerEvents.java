package com.vladosiwe.lordsoutpost.server;

import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.command.OutpostCommands;
import com.vladosiwe.lordsoutpost.config.OutpostConfig;
import com.vladosiwe.lordsoutpost.data.ALifeSimulationManager;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.network.BiomeTiles;
import com.vladosiwe.lordsoutpost.network.ModNetwork;
import com.vladosiwe.lordsoutpost.world.OutpostSites;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Серверные события: тик A-Life, дороги, титул, служебная уборка. */
@EventBusSubscriber(modid = LordsOutpost.MODID)
public final class ServerEvents {
    private static final ResourceLocation ROAD_MODIFIER = LordsOutpost.id("road");

    private ServerEvents() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ALifeSimulationManager.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        OutpostSites.clearCache();
        BiomeTiles.clear();
        ModNetwork.reset();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ALifeSimulationManager.reset(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        OutpostSites.clearCache();
        BiomeTiles.clear();
        ModNetwork.reset();
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        OutpostCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ModNetwork.sendMap(player, OutpostSavedData.get(player.server), false, true);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ModNetwork.forget(event.getEntity().getUUID());
    }

    /** По дорогам игрок идёт быстрее: тракт между фортами имеет смысл прокладывать. */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % 5 != 0) return;
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        boolean road = OutpostConfig.get(OutpostConfig.ROADS_FOR_PLAYERS, true) && !player.isPassenger() && Roads.onRoad(player);
        if (road == speed.hasModifier(ROAD_MODIFIER)) return;
        speed.removeModifier(ROAD_MODIFIER);
        if (road) {
            speed.addTransientModifier(new AttributeModifier(ROAD_MODIFIER, OutpostConfig.get(OutpostConfig.ROAD_SPEED, 1.35) - 1,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    /** Титул «Лорд» перед именем в чате. */
    @SubscribeEvent
    public static void onNameFormat(PlayerEvent.NameFormat event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !OutpostConfig.get(OutpostConfig.LORD_PREFIX, true)) return;
        if (OutpostSavedData.get(player.server).lordOutpost(player.getUUID()) == null) return;
        event.setDisplayname(Component.translatable("lordsoutpost.title.prefix").withStyle(ChatFormatting.GOLD).append(" ").append(event.getDisplayname().copy().withStyle(ChatFormatting.WHITE)));
    }

    /** И в списке игроков. */
    @SubscribeEvent
    public static void onTabName(PlayerEvent.TabListNameFormat event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !OutpostConfig.get(OutpostConfig.LORD_PREFIX, true)) return;
        if (OutpostSavedData.get(player.server).lordOutpost(player.getUUID()) == null) return;
        Component base = event.getDisplayName() == null ? player.getName() : event.getDisplayName();
        event.setDisplayName(Component.translatable("lordsoutpost.title.prefix").withStyle(ChatFormatting.GOLD).append(" ").append(base.copy().withStyle(ChatFormatting.WHITE)));
    }

    /** Добыча охотника уже учтена на складе — на землю она не падает. */
    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (event.getEntity().getPersistentData().contains(NpcLifecycle.HUNTED_TAG)
                && event.getSource().getEntity() instanceof com.vladosiwe.lordsoutpost.entity.WorkerEntity) {
            event.setCanceled(true);
        }
    }

    /** Вьючное животное каравана, сохранившееся в чанке, не должно появиться второй раз: караван воссоздаётся из записи. */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() && event.getEntity().getPersistentData().contains(NpcLifecycle.CARAVAN_TAG)) {
            event.setCanceled(true);
        }
    }
}
