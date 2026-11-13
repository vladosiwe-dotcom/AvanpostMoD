package com.vladosiwe.lordsoutpost.server;

import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.entity.OutpostNpc;
import com.vladosiwe.lordsoutpost.network.ModNetwork;
import com.vladosiwe.lordsoutpost.outpost.AccessLevel;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Role;
import com.vladosiwe.lordsoutpost.protection.Access;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Взаимодействие игрока с блоками и жителями аванпоста. */
public final class Interactions {
    private Interactions() {}

    /** Штандарт и Доска заданий: открыть окно управления на нужной вкладке. */
    public static void openAt(ServerPlayer player, BlockPos pos, int tab) {
        OutpostSavedData data = OutpostSavedData.get(player.serverLevel());
        Outpost o = data.findAt(pos);
        if (o == null || o.state != OutpostState.OWNED) {
            player.displayClientMessage(Component.translatable("lordsoutpost.msg.no_lord").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        open(player, data, o, tab);
    }

    public static void open(ServerPlayer player, OutpostSavedData data, Outpost o, int tab) {
        AccessLevel level = Access.level(data, o, player.getUUID());
        if (!level.atLeast(AccessLevel.ALLY)) {
            player.displayClientMessage(Component.translatable("lordsoutpost.msg.no_access", o.name, o.ownerName).withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        ModNetwork.open(player, data, o, tab);
    }

    public static void talk(ServerPlayer player, Outpost o, OutpostNpc npc) {
        Resident r = npc.resident();
        if (r == null) return;
        if (r.role == Role.STEWARD) {
            open(player, OutpostSavedData.get(player.serverLevel()), o, 0);
            return;
        }
        player.displayClientMessage(Component.translatable("lordsoutpost.msg.resident", r.name, r.role.label(), o.name), true);
    }
}
