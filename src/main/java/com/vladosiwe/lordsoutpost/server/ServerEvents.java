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
        ALifeSimulationManager.catchUp(event.getServer());
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
        com.vladosiwe.lordsoutpost.command.SettlementCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            OutpostSavedData data = OutpostSavedData.get(player.server);
            ModNetwork.sendMap(player, data, false, true);
            // Пока игрок был не в сети, его аванпост могли отобрать или удалить: титул должен это отражать.
            var own = data.lordOutpost(player.getUUID());
            com.vladosiwe.lordsoutpost.compat.SoloStatusCompat.setLord(player, own != null);
            // Тир мог вырасти, пока игрока не было (в том числе пока мир был закрыт).
            if (own != null) com.vladosiwe.lordsoutpost.compat.SoloStatusCompat.onTier(player, own.tier);
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

    /** Добычу с животного, которое забил охотник или скотовод, житель сразу берёт в ношу и несёт на склад. */
    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (!(event.getSource().getEntity() instanceof com.vladosiwe.lordsoutpost.entity.WorkerEntity worker)) return;
        event.getDrops().removeIf(item -> {
            net.minecraft.world.item.ItemStack left = worker.collect(item.getItem());
            if (left.isEmpty()) return true;
            item.setItem(left);
            return false;
        });
    }

    /** Гибель пироманта форта: запись узнаёт об этом, новый придёт через срок. */
    @SubscribeEvent
    public static void onLivingDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntity().level() instanceof net.minecraft.server.level.ServerLevel level) {
            com.vladosiwe.lordsoutpost.compat.Pyromancer.onDeath(level, event.getEntity());
            var tag = event.getEntity().getPersistentData();
            if (tag.hasUUID(NpcLifecycle.CARAVAN_TAG)) {
                // Вьючное животное каравана погибло: его груз падает здесь.
                java.util.UUID id = tag.getUUID(NpcLifecycle.CARAVAN_TAG);
                OutpostSavedData data = OutpostSavedData.get(level);
                for (var o : data.all()) {
                    var c = NpcLifecycle.caravan(o, id);
                    if (c != null) {
                        com.vladosiwe.lordsoutpost.data.Caravans.dropGoods(level, o, c, event.getEntity().position());
                        data.setDirty();
                        break;
                    }
                }
            }
        }
    }

    /**
     * Свои не ранят пироманта форта (шальная стрела стражника), чтобы он не обозлился на гарнизон.
     */
    @SubscribeEvent
    public static void onIncomingDamage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        var target = event.getEntity();
        if (target.level().isClientSide || !target.getPersistentData().contains(com.vladosiwe.lordsoutpost.compat.Pyromancer.TAG)) return;
        if (event.getSource().getEntity() instanceof com.vladosiwe.lordsoutpost.entity.OutpostNpc npc && !npc.hostileFaction()
                && com.vladosiwe.lordsoutpost.compat.Pyromancer.ofOutpost(target, npc.outpostId())) event.setCanceled(true);
    }

    /** Пироманту форта (тело придворного мага) добавляется рабочая цель мага: производство после боя. */
    @SubscribeEvent
    public static void onMageBodyJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof net.minecraft.world.entity.PathfinderMob mob)) return;
        if (!mob.getPersistentData().contains(com.vladosiwe.lordsoutpost.compat.Pyromancer.TAG)) return;
        long id = mob.getPersistentData().getLong(com.vladosiwe.lordsoutpost.compat.Pyromancer.TAG);
        var server = mob.getServer();
        if (server == null) return;
        mob.goalSelector.addGoal(3, new com.vladosiwe.lordsoutpost.entity.ai.MageWorkGoal(mob, () -> OutpostSavedData.get(server).byId(id)));
    }

    /**
     * Тело придворного мага (пиромант Iron's Spells) торгует только тем, что лежит на его полке: собственные сделки
     * пироманта создают товар из ничего, поэтому они перехватываются и открывается полка мага.
     */
    @SubscribeEvent
    public static void onMageInteract(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof net.minecraft.world.entity.LivingEntity body)) return;
        if (!body.getPersistentData().contains(com.vladosiwe.lordsoutpost.compat.Pyromancer.TAG)) return;
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        var o = OutpostSavedData.get(sp.serverLevel()).byId(body.getPersistentData().getLong(com.vladosiwe.lordsoutpost.compat.Pyromancer.TAG));
        if (o == null || !com.vladosiwe.lordsoutpost.entity.MageShelf.open(sp, body, o)) {
            sp.displayClientMessage(net.minecraft.network.chat.Component.translatable("lordsoutpost.msg.mage_refuses", body.getName()), true);
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
