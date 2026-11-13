package com.vladosiwe.lordsoutpost.registry;

import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.block.LordStandardBlock;
import com.vladosiwe.lordsoutpost.block.OutpostGateBlock;
import com.vladosiwe.lordsoutpost.block.QuestBoardBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(LordsOutpost.MODID);

    /** Решётка ворот: свои проходят сквозь неё, для чужих это стена. */
    public static final DeferredBlock<OutpostGateBlock> OUTPOST_GATE = BLOCKS.registerBlock("outpost_gate", OutpostGateBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(6.0f, 30.0f).sound(SoundType.METAL)
                    .noOcclusion().dynamicShape().isSuffocating((s, l, p) -> false).isViewBlocking((s, l, p) -> false)
                    .pushReaction(PushReaction.BLOCK));

    /** Штандарт Лорда: установка во дворе захваченного форта основывает владение. */
    public static final DeferredBlock<LordStandardBlock> LORD_STANDARD = BLOCKS.registerBlock("lord_standard", LordStandardBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(3.0f, 1200.0f).sound(SoundType.WOOD)
                    .noOcclusion().pushReaction(PushReaction.BLOCK));

    public static final DeferredBlock<QuestBoardBlock> QUEST_BOARD = BLOCKS.registerBlock("quest_board", QuestBoardBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.0f, 6.0f).sound(SoundType.WOOD).noOcclusion());

    private ModBlocks() {}
}
