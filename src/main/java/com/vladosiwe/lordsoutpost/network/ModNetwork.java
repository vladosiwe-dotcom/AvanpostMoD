package com.vladosiwe.lordsoutpost.network;

import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.server.Interactions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Регистрация пакетов и рассылка обновлений тем, у кого открыты окно аванпоста или карта. */
@EventBusSubscriber(modid = LordsOutpost.MODID)
public final class ModNetwork {
    /** Особая «вкладка»: окно ввода имени форта сразу после основания. */
    public static final int TAB_NAME = 100;

    private static final Map<UUID, Long> VIEWERS = new HashMap<>();
    private static final Set<UUID> MAP_OPEN = new HashSet<>();
    private static final Map<UUID, Integer> LAST_MAP_HASH = new HashMap<>();

    private ModNetwork() {}

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        // Тела лямбд ссылаются на клиентский класс: JVM разрешит его только при выполнении, а на сервере это не случается.
        r.playToClient(Payloads.OpenOutpost.TYPE, Payloads.OpenOutpost.CODEC,
                (p, ctx) -> com.vladosiwe.lordsoutpost.client.ClientPackets.openOutpost(p));
        r.playToClient(Payloads.OutpostData.TYPE, Payloads.OutpostData.CODEC,
                (p, ctx) -> com.vladosiwe.lordsoutpost.client.ClientPackets.outpostData(p));
        r.playToClient(Payloads.MapData.TYPE, Payloads.MapData.CODEC,
                (p, ctx) -> com.vladosiwe.lordsoutpost.client.ClientPackets.mapData(p));
        r.playToClient(Payloads.Discovery.TYPE, Payloads.Discovery.CODEC,
                (p, ctx) -> com.vladosiwe.lordsoutpost.client.ClientPackets.discovery(p));
        r.playToClient(Payloads.BiomeTile.TYPE, Payloads.BiomeTile.CODEC,
                (p, ctx) -> com.vladosiwe.lordsoutpost.client.ClientPackets.biomeTile(p));
        r.playToServer(Payloads.OutpostAction.TYPE, Payloads.OutpostAction.CODEC,
                (p, ctx) -> { if (ctx.player() instanceof ServerPlayer sp) ServerActions.handle(sp, p); });
        r.playToServer(Payloads.MapRequest.TYPE, Payloads.MapRequest.CODEC,
                (p, ctx) -> { if (ctx.player() instanceof ServerPlayer sp) mapRequest(sp, p.open()); });
        r.playToServer(Payloads.TileRequest.TYPE, Payloads.TileRequest.CODEC,
                (p, ctx) -> { if (ctx.player() instanceof ServerPlayer sp) BiomeTiles.request(sp.server.overworld(), sp, p.rx(), p.rz()); });
        r.playToServer(Payloads.OpenRequest.TYPE, Payloads.OpenRequest.CODEC,
                (p, ctx) -> { if (ctx.player() instanceof ServerPlayer sp) openRequest(sp, p); });
    }

    /** Отправка с проверкой: у игрока без мода (тестовый игрок, чужой FakePlayer) канала нет. */
    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection != null && player.connection.hasChannel(payload)) PacketDistributor.sendToPlayer(player, payload);
    }

    public static void open(ServerPlayer player, OutpostSavedData data, Outpost o, int tab) {
        VIEWERS.put(player.getUUID(), o.id);
        send(player, new Payloads.OpenOutpost(tab, Views.outpost(player.server.overworld(), data, o, player)));
    }

    public static void refresh(ServerPlayer player, OutpostSavedData data, Outpost o) {
        send(player, new Payloads.OutpostData(Views.outpost(player.server.overworld(), data, o, player)));
    }

    public static void closed(ServerPlayer player) {
        VIEWERS.remove(player.getUUID());
    }

    public static void forget(UUID player) {
        VIEWERS.remove(player);
        MAP_OPEN.remove(player);
        LAST_MAP_HASH.remove(player);
    }

    public static void reset() {
        VIEWERS.clear();
        MAP_OPEN.clear();
        LAST_MAP_HASH.clear();
    }

    private static void mapRequest(ServerPlayer player, boolean open) {
        if (open) MAP_OPEN.add(player.getUUID());
        else MAP_OPEN.remove(player.getUUID());
        sendMap(player, OutpostSavedData.get(player.server), open, true);
    }

    private static void openRequest(ServerPlayer player, Payloads.OpenRequest request) {
        OutpostSavedData data = OutpostSavedData.get(player.server);
        Outpost o = request.outpost() == 0 ? data.lordOutpost(player.getUUID()) : data.byId(request.outpost());
        if (o == null) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("lordsoutpost.msg.not_lord"), true);
            return;
        }
        Interactions.open(player, data, o, Math.max(0, Math.min(4, request.tab())));
    }

    public static void sendMap(ServerPlayer player, OutpostSavedData data, boolean detailed, boolean force) {
        ServerLevel level = player.server.overworld();
        CompoundTag tag = Views.map(level, data, player, detailed);
        int hash = tag.hashCode();
        Integer last = LAST_MAP_HASH.put(player.getUUID(), hash);
        if (force || last == null || last != hash) send(player, new Payloads.MapData(tag));
    }

    /** Раз в секунду: обновить открытые окна и карты; карта без подробностей нужна всем — по ней клиент знает права на воротах. */
    public static void sync(MinecraftServer server, OutpostSavedData data, long now) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            Long viewing = VIEWERS.get(id);
            if (viewing != null) {
                Outpost o = data.byId(viewing);
                if (o == null) VIEWERS.remove(id);
                else if (o.dirtyForClients || now % 100 == 0) refresh(player, data, o);
            }
            boolean mapOpen = MAP_OPEN.contains(id);
            if (mapOpen || now % 60 == 0) sendMap(player, data, mapOpen, false);
        }
        for (Outpost o : data.all()) o.dirtyForClients = false;
    }
}
