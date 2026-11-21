package com.vladosiwe.lordsoutpost.block;

import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.entity.OutpostNpc;
import com.vladosiwe.lordsoutpost.outpost.AccessLevel;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.protection.Access;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Дверь склада. Обычная деревянная дверь для своих: жители форта открывают её сами (ИИ открывания дверей),
 * Лорд и союзники — рукой. Гостям, врагам, налётчикам и жителям чужих фортов она не открывается.
 * Вне аванпоста ведёт себя как простая дверь.
 */
public class StorehouseDoorBlock extends DoorBlock {
    public StorehouseDoorBlock(Properties properties) {
        super(BlockSetType.SPRUCE, properties);
    }

    @Nullable
    private static Outpost outpostAt(Level level, BlockPos pos) {
        return level instanceof ServerLevel sl ? OutpostSavedData.get(sl).findAt(pos) : null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide) {
            Outpost o = outpostAt(level, pos);
            boolean admin = player.isCreative() && player.hasPermissions(2);
            if (o != null && o.state == OutpostState.OWNED && !admin
                    && !Access.level(OutpostSavedData.get((ServerLevel) level), o, player.getUUID()).atLeast(AccessLevel.ALLY)) {
                player.displayClientMessage(Component.translatable("lordsoutpost.msg.store_locked").withStyle(ChatFormatting.RED), true);
                // Клиент мог уже показать открытую дверь — вернуть ему настоящее состояние.
                level.sendBlockUpdated(pos, state, state, 3);
                return InteractionResult.FAIL;
            }
        }
        return super.useWithoutItem(state, level, pos, player, hit);
    }

    /** Через этот метод двери открывают мобы. */
    @Override
    public void setOpen(@Nullable Entity entity, Level level, BlockState state, BlockPos pos, boolean open) {
        if (open && entity instanceof OutpostNpc npc && !level.isClientSide) {
            Outpost o = outpostAt(level, pos);
            if (o != null && (npc.outpostId() != o.id || npc.raidTarget != 0)) return;
        }
        super.setOpen(entity, level, state, pos, open);
    }
}
