package com.vladosiwe.lordsoutpost.mail;

import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Почта. Посылки (дань Лорду, извещения о караванах) ждут получателя в записи мира и выдаются в любом почтомате.
 * Выдача серверная и атомарная: предмет либо уже в инвентаре игрока, либо ещё в письме — третьего не бывает.
 */
public final class Mail {
    /** Сколько полученных писем хранить для истории на игрока. */
    private static final int HISTORY = 16;

    private Mail() {}

    /** Отправляет письмо. Вложения забираются (переданные стопки опустошаются). */
    public static Letter send(MinecraftServer server, UUID recipient, String sender, String subject, List<ItemStack> items, long now, Object... args) {
        OutpostSavedData data = OutpostSavedData.get(server);
        Letter letter = new Letter(UUID.randomUUID(), recipient, sender, subject, now);
        for (Object a : args) letter.args.add(String.valueOf(a));
        for (ItemStack st : items) {
            if (st.isEmpty()) continue;
            letter.items.add(st.copy());
            st.setCount(0);
        }
        data.mail().add(letter);
        data.setDirty();
        ServerPlayer player = server.getPlayerList().getPlayer(recipient);
        if (player != null) {
            player.displayClientMessage(Component.translatable("lordsoutpost.mail.arrived", sender).withStyle(ChatFormatting.GOLD), false);
        }
        return letter;
    }

    public static List<Letter> waiting(OutpostSavedData data, UUID player) {
        List<Letter> out = new ArrayList<>();
        for (Letter l : data.mail()) if (l.waiting() && l.recipient.equals(player)) out.add(l);
        return out;
    }

    /**
     * Почтомат: все ждущие письма игрока раскрываются, вложения переходят в инвентарь. Что не поместилось,
     * остаётся в письме до следующего раза. Возвращает число полностью полученных писем.
     */
    public static int claim(ServerPlayer player, long now) {
        OutpostSavedData data = OutpostSavedData.get(player.server);
        int done = 0, partial = 0;
        for (Letter l : waiting(data, player.getUUID())) {
            player.displayClientMessage(Component.translatable("lordsoutpost.mail.read", l.sender,
                    Component.translatable(l.subject, l.args.toArray())).withStyle(ChatFormatting.YELLOW), false);
            for (Iterator<ItemStack> it = l.items.iterator(); it.hasNext(); ) {
                ItemStack st = it.next();
                player.getInventory().add(st);
                if (st.isEmpty()) it.remove();
            }
            if (l.items.isEmpty()) {
                l.status = Letter.Status.CLAIMED;
                l.claimedAt = now;
                done++;
            } else {
                partial++;
            }
        }
        if (partial > 0) player.displayClientMessage(Component.translatable("lordsoutpost.mail.inventory_full").withStyle(ChatFormatting.RED), false);
        prune(data, player.getUUID());
        data.setDirty();
        return done;
    }

    private static void prune(OutpostSavedData data, UUID player) {
        int claimed = 0;
        for (int i = data.mail().size() - 1; i >= 0; i--) {
            Letter l = data.mail().get(i);
            if (l.recipient.equals(player) && !l.waiting() && ++claimed > HISTORY) data.mail().remove(i);
        }
    }
}
