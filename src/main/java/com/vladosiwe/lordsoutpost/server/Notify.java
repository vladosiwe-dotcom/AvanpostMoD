package com.vladosiwe.lordsoutpost.server;

import com.vladosiwe.lordsoutpost.outpost.Outpost;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Сообщения Лорду о событиях в его владениях (в том числе пока он далеко). */
public final class Notify {
    private Notify() {}

    public static MutableComponent prefix(Outpost o) {
        return Component.literal("[" + (o.name.isEmpty() ? "?" : o.name) + "] ").withStyle(ChatFormatting.GOLD);
    }

    public static void lord(MinecraftServer server, Outpost o, Component message) {
        if (o.owner == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(o.owner);
        if (player != null) player.sendSystemMessage(prefix(o).append(message.copy().withStyle(ChatFormatting.WHITE)));
    }
}
