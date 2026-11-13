package com.vladosiwe.lordsoutpost.item;

import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.server.Captures;
import com.vladosiwe.lordsoutpost.server.OutpostManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.List;

/** Штандарт Лорда: ставится во дворе захваченного форта и делает игрока его Лордом. */
public class LordStandardItem extends BlockItem {
    public LordStandardItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide || !(ctx.getPlayer() instanceof ServerPlayer player) || !(level instanceof ServerLevel sl)) {
            // Клиент не знает, разрешена ли установка: решение за сервером.
            return level.isClientSide ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        }
        if (level.dimension() != Level.OVERWORLD) {
            player.displayClientMessage(Component.translatable("lordsoutpost.standard.outside").withStyle(ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }
        BlockPos pos = ctx.getClickedPos();
        OutpostSavedData data = OutpostSavedData.get(sl);
        Outpost o = data.findAt(pos);
        OutpostManager.FoundResult check = OutpostManager.canFound(data, o, player.getUUID(), pos);
        if (check != OutpostManager.FoundResult.OK) {
            player.displayClientMessage(Component.translatable("lordsoutpost.standard." + check.name().toLowerCase(java.util.Locale.ROOT)).withStyle(ChatFormatting.RED), true);
            player.inventoryMenu.sendAllDataToRemote();
            return InteractionResult.FAIL;
        }
        InteractionResult result = super.place(ctx);
        if (result.consumesAction()) Captures.found(sl, data, o, player, pos);
        return result;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.lordsoutpost.lord_standard.desc").withStyle(ChatFormatting.GRAY));
    }
}
