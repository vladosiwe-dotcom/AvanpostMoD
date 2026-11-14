package com.vladosiwe.lordsoutpost.world;

import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.blueprint.FortBlueprint;
import com.vladosiwe.lordsoutpost.blueprint.Marker;
import com.vladosiwe.lordsoutpost.outpost.Difficulty;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Массовая установка блоков форта. Используется генерацией мира (по одному чанку за вызов)
 * и согласованием, когда форт создан командой или достроен, пока чанки были выгружены.
 */
public final class FortPlacer {
    private static final int MAX_FOUNDATION = 10;

    private FortPlacer() {}

    public static ResourceKey<LootTable> lootTable(Difficulty difficulty) {
        return ResourceKey.create(Registries.LOOT_TABLE, LordsOutpost.id("chests/outpost_" + difficulty.skulls));
    }

    /**
     * @param box границы, в которых разрешено ставить блоки (чанк при генерации мира) или null — без ограничений
     */
    public static void place(WorldGenLevel level, BlockPos center, FortBlueprint bp, Difficulty difficulty,
                             Map<BlockPos, BlockState> snapshot, @Nullable BoundingBox box, RandomSource random) {
        Set<BlockPos> lootChests = new HashSet<>(bp.design(difficulty.tier).markers(Marker.LOOT_CHEST));
        BlockState foundation = bp.palette.foundation.defaultBlockState();
        boolean worldgen = !(level instanceof net.minecraft.server.level.ServerLevel);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
        for (var e : snapshot.entrySet()) {
            BlockPos rel = e.getKey();
            pos.set(center.getX() + rel.getX(), center.getY() + rel.getY(), center.getZ() + rel.getZ());
            if (box != null && !box.isInside(pos)) continue;
            if (level.isOutsideBuildHeight(pos)) continue;
            BlockState state = e.getValue();
            level.setBlock(pos, state, Block.UPDATE_CLIENTS);
            // При генерации мира форма заборов и стен досчитывается, когда чанк станет активным; в живом мире это делает согласование.
            if (worldgen && FortBlueprint.shapeSensitive(state)) level.getChunk(pos).markPosForPostprocessing(pos);
            // Фундамент: если под нижним блоком пустота, заполняем её до твёрдой земли.
            if ((rel.getY() == 0 || rel.getY() == 1) && !state.isAir() && !snapshot.containsKey(rel.below())) {
                for (int d = 1; d <= MAX_FOUNDATION; d++) {
                    below.set(pos.getX(), pos.getY() - d, pos.getZ());
                    if (level.isOutsideBuildHeight(below)) break;
                    BlockState under = level.getBlockState(below);
                    if (!under.isAir() && under.getFluidState().isEmpty() && !under.canBeReplaced()) break;
                    level.setBlock(below, foundation, Block.UPDATE_CLIENTS);
                }
            }
            if (state.is(Blocks.CHEST) && lootChests.contains(rel)) {
                RandomizableContainer.setBlockEntityLootTable(level, random, pos, lootTable(difficulty));
            }
        }
    }
}
