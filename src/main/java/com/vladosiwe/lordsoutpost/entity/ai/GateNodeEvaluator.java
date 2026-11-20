package com.vladosiwe.lordsoutpost.entity.ai;

import com.vladosiwe.lordsoutpost.entity.OutpostNpc;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.jetbrains.annotations.Nullable;

/**
 * Поиск пути жителей и стражи: закрытая калитка своего аванпоста (загон, огород) для них — как деревянная дверь,
 * её можно открыть ({@link OpenGateGoal}). Чужие калитки и калитки игрока за пределами форта остаются стеной.
 */
public class GateNodeEvaluator extends WalkNodeEvaluator {
    @Nullable private Outpost home;

    @Override
    public void prepare(PathNavigationRegion region, Mob mob) {
        super.prepare(region, mob);
        // Запись форта берётся один раз на поиск пути, а не на каждый узел.
        home = mob instanceof OutpostNpc npc && !npc.hostileFaction() ? npc.outpost() : null;
    }

    @Override
    public void done() {
        home = null;
        super.done();
    }

    @Override
    public PathType getPathType(PathfindingContext context, int x, int y, int z) {
        PathType type = super.getPathType(context, x, y, z);
        if (type == PathType.FENCE && home != null) {
            BlockPos pos = new BlockPos(x, y, z);
            if (opensHere(context.getBlockState(pos), home, pos)) return PathType.DOOR_WOOD_CLOSED;
        }
        return type;
    }

    static boolean opensHere(BlockState state, @Nullable Outpost home, BlockPos pos) {
        return home != null && state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN) && home.contains(pos);
    }
}
