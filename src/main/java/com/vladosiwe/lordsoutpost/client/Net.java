package com.vladosiwe.lordsoutpost.client;

import com.vladosiwe.lordsoutpost.network.Action;
import com.vladosiwe.lordsoutpost.network.Payloads;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.function.Consumer;

/** Отправка запросов серверу. */
public final class Net {
    private Net() {}

    public static void action(long outpost, Action action, Consumer<CompoundTag> args) {
        CompoundTag tag = new CompoundTag();
        args.accept(tag);
        PacketDistributor.sendToServer(new Payloads.OutpostAction(outpost, action.ordinal(), tag));
    }

    public static void action(long outpost, Action action) {
        action(outpost, action, t -> { });
    }
}
