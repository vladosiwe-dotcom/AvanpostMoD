package com.vladosiwe.lordsoutpost.client;

import com.vladosiwe.lordsoutpost.network.Payloads;

/** Клиентские обработчики пакетов. Класс загружается только на клиенте. */
public final class ClientPackets {
    private ClientPackets() {}

    public static void openOutpost(Payloads.OpenOutpost p) {}

    public static void outpostData(Payloads.OutpostData p) {}

    public static void mapData(Payloads.MapData p) {}

    public static void discovery(Payloads.Discovery p) {}

    public static void biomeTile(Payloads.BiomeTile p) {}
}
