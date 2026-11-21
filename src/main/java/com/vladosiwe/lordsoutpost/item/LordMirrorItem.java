package com.vladosiwe.lordsoutpost.item;

import com.vladosiwe.lordsoutpost.server.LordMirror;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Зеркало Лорда: открывает на 30 секунд малый портал на верхний этаж донжона (тир 3), пройти могут двое.
 * Привязано к аванпосту: без аванпоста рассыпается; не выбрасывается, не передаётся, хранится только у Лорда
 * или в его личном сундуке. Вся логика — в {@link LordMirror}.
 */
public class LordMirrorItem extends Item {
    public LordMirrorItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(stack);
        return LordMirror.use((ServerPlayer) player, stack) ? InteractionResultHolder.consume(stack) : InteractionResultHolder.fail(stack);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        String name = LordMirror.outpostName(stack);
        if (!name.isEmpty()) tooltip.add(Component.translatable("item.lordsoutpost.lord_mirror.bound", name).withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.lordsoutpost.lord_mirror.desc").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.lordsoutpost.lord_mirror.rules").withStyle(ChatFormatting.DARK_PURPLE));
    }
}
