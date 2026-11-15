package com.vladosiwe.lordsoutpost.client;

import com.vladosiwe.lordsoutpost.client.gui.OutpostScreen;
import com.vladosiwe.lordsoutpost.client.gui.OutpostView;
import com.vladosiwe.lordsoutpost.client.map.BiomeLayer;
import com.vladosiwe.lordsoutpost.network.Payloads;
import net.minecraft.client.Minecraft;

/** Клиентские обработчики пакетов. Класс загружается только на клиенте. */
public final class ClientPackets {
    private ClientPackets() {}

    public static void openOutpost(Payloads.OpenOutpost p) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new OutpostScreen(new OutpostView(p.data()), p.tab()));
    }

    public static void outpostData(Payloads.OutpostData p) {
        if (Minecraft.getInstance().screen instanceof OutpostScreen screen) screen.update(new OutpostView(p.data()));
    }

    public static void mapData(Payloads.MapData p) {
        ClientState.readMap(p.data());
    }

    public static void discovery(Payloads.Discovery p) {
        Minecraft mc = Minecraft.getInstance();
        ClientState.bannerSkulls = p.skulls();
        ClientState.bannerStart = mc.level == null ? 0 : mc.level.getGameTime();
    }

    public static void biomeTile(Payloads.BiomeTile p) {
        BiomeLayer.accept(p.rx(), p.rz(), p.colors());
    }
}
