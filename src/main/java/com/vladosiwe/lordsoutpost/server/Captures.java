package com.vladosiwe.lordsoutpost.server;

import com.vladosiwe.lordsoutpost.compat.SoloStatusCompat;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.network.ModNetwork;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.Quest;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/** Торжественные моменты: падение Капитана, основание владения, награды. */
public final class Captures {
    private Captures() {}

    public static void onCaptainSlain(ServerLevel level, OutpostSavedData data, Outpost o, @Nullable ServerPlayer killer, BlockPos at) {
        level.playSound(null, at, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.HOSTILE, 1.5f, 0.8f);
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, 60, 0.6, 1.0, 0.6, 0.3);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(at.getX(), at.getY(), at.getZ()) > 96 * 96) continue;
            data.knowledge(p.getUUID()).discovered.add(o.id);
            p.connection.send(new ClientboundSetTitleTextPacket(Component.translatable("lordsoutpost.title.conquered").withStyle(ChatFormatting.GOLD)));
            p.connection.send(new ClientboundSetSubtitleTextPacket(Component.translatable("lordsoutpost.title.conquered.sub")));
        }
        if (killer != null) SoloStatusCompat.onOutpostConquered(killer, o.difficulty.skulls);
    }

    /** Штандарт поставлен и проверки пройдены. */
    public static void found(ServerLevel level, OutpostSavedData data, Outpost o, ServerPlayer player, BlockPos standard) {
        long now = level.getGameTime();
        OutpostManager.FoundResult result = OutpostManager.found(data, o, player.getUUID(), player.getGameProfile().getName(), standard, now, level.random);
        if (result != OutpostManager.FoundResult.OK) return;
        data.knowledge(player.getUUID()).discovered.add(o.id);
        level.playSound(null, standard, SoundEvents.RAID_HORN.value(), SoundSource.PLAYERS, 8f, 1f);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, standard.getX() + 0.5, standard.getY() + 1.5, standard.getZ() + 0.5, 40, 1.5, 1.0, 1.5, 0);
        player.connection.send(new ClientboundSetTitleTextPacket(Component.translatable("lordsoutpost.title.lord").withStyle(ChatFormatting.GOLD)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.translatable("lordsoutpost.title.lord.sub", o.name)));
        level.getServer().getPlayerList().broadcastSystemMessage(
                Component.translatable("lordsoutpost.chat.founded", player.getDisplayName(), o.difficulty.label()).withStyle(ChatFormatting.GOLD), false);
        SoloStatusCompat.onBecameLord(player);
        player.refreshDisplayName();
        player.refreshTabListName();
        // Сразу предлагаем дать форту имя.
        ModNetwork.open(player, data, o, ModNetwork.TAB_NAME);
    }

    /** Награда игроку за закрытое задание Доски. */
    public static void questReward(ServerPlayer player, Outpost o, Quest q) {
        player.sendSystemMessage(Notify.prefix(o).append(Component.translatable("lordsoutpost.quest.done", q.title(), q.rewardGold).withStyle(ChatFormatting.GREEN)));
        player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.2f);
        ItemStack reward = new ItemStack(Items.EMERALD, Math.max(1, q.rewardGold / 2));
        if (!player.getInventory().add(reward)) player.drop(reward, false);
        // Вторая половина награды уже лежит в дани (см. QuestBoard.deliver).
        o.tributeGold = Math.max(0, o.tributeGold - q.rewardGold / 2.0);
        SoloStatusCompat.onQuestDone(player, q.rewardGold);
    }
}
