package com.vladosiwe.lordsoutpost.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.client.hud.DiscoveryBanner;
import com.vladosiwe.lordsoutpost.client.map.BiomeLayer;
import com.vladosiwe.lordsoutpost.client.map.MapScreen;
import com.vladosiwe.lordsoutpost.client.map.MapTiles;
import com.vladosiwe.lordsoutpost.client.render.FighterRenderer;
import com.vladosiwe.lordsoutpost.client.render.MarauderModel;
import com.vladosiwe.lordsoutpost.client.render.WorkerRenderer;
import com.vladosiwe.lordsoutpost.network.Payloads;
import com.vladosiwe.lordsoutpost.protection.GateAccess;
import com.vladosiwe.lordsoutpost.registry.ModEntities;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Клиентская регистрация: отрисовка сущностей, клавиши, слой HUD, события карты. Только клиент. */
public final class ClientSetup {
    public static final String CATEGORY = "key.categories.lordsoutpost";
    public static final KeyMapping MAP_KEY = new KeyMapping("key.lordsoutpost.map", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, CATEGORY);
    public static final KeyMapping MANAGE_KEY = new KeyMapping("key.lordsoutpost.manage", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, CATEGORY);

    private ClientSetup() {}

    @SuppressWarnings("removal")
    @EventBusSubscriber(modid = LordsOutpost.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        private ModBus() {}

        @SubscribeEvent
        public static void setup(FMLClientSetupEvent event) {
            GateAccess.clientCheck = ClientState::canPassGate;
        }

        @SubscribeEvent
        public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(ModEntities.FIGHTER.get(), FighterRenderer::new);
            event.registerEntityRenderer(ModEntities.CAPTAIN.get(), FighterRenderer::new);
            event.registerEntityRenderer(ModEntities.WORKER.get(), WorkerRenderer::new);
        }

        @SubscribeEvent
        public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
            event.registerLayerDefinition(FighterRenderer.LAYER, MarauderModel::createBodyLayer);
        }

        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent event) {
            event.register(MAP_KEY);
            event.register(MANAGE_KEY);
        }

        @SubscribeEvent
        public static void hud(RegisterGuiLayersEvent event) {
            event.registerAboveAll(LordsOutpost.id("discovery"), DiscoveryBanner::render);
        }

    }

    @EventBusSubscriber(modid = LordsOutpost.MODID, value = Dist.CLIENT)
    public static final class GameBus {
        private GameBus() {}

        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return;
            MapTiles.tick(mc);
            while (MAP_KEY.consumeClick()) {
                if (mc.screen == null) mc.setScreen(new MapScreen());
            }
            while (MANAGE_KEY.consumeClick()) {
                if (mc.screen == null) PacketDistributor.sendToServer(new Payloads.OpenRequest(0, 0));
            }
        }

        @SubscribeEvent
        public static void chunkLoad(ChunkEvent.Load event) {
            if (event.getLevel().isClientSide()) MapTiles.enqueue(event.getChunk().getPos());
        }

        @SubscribeEvent
        public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
            MapTiles.reset();
            BiomeLayer.reset();
            ClientState.clear();
        }
    }
}
