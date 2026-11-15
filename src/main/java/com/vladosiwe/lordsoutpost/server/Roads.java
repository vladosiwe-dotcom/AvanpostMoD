package com.vladosiwe.lordsoutpost.server;

import com.vladosiwe.lordsoutpost.LordsOutpost;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Дороги: тропинка, гравий, булыжник и всё из тега lordsoutpost:roads. По ним караваны и игроки идут быстрее. */
public final class Roads {
    public static final TagKey<Block> ROADS = TagKey.create(Registries.BLOCK, LordsOutpost.id("roads"));

    private Roads() {}

    public static boolean isRoad(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(ROADS);
    }

    /** Стоит ли сущность на дороге (тропинка ниже полного блока, поэтому проверяются два уровня). */
    public static boolean onRoad(Entity entity) {
        if (!entity.onGround()) return false;
        BlockPos feet = BlockPos.containing(entity.getX(), entity.getY() + 0.2, entity.getZ());
        BlockState at = entity.level().getBlockState(feet);
        return at.is(ROADS) || entity.level().getBlockState(feet.below()).is(ROADS);
    }
}
