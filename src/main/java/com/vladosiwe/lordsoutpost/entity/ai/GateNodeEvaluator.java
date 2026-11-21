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

    /**
     * Лестницы: обычный поиск пути ходит только по земле. Из клетки с лестницей (или стоя прямо под ней) добавляется
     * переход вверх, а над лестницей — вниз. Лезет житель сам (OutpostNpc.climbLadder), как игрок с зажатым прыжком.
     */
    @Override
    public int getNeighbors(net.minecraft.world.level.pathfinder.Node[] out, net.minecraft.world.level.pathfinder.Node node) {
        int n = super.getNeighbors(out, node);
        var level = currentContext.level();
        BlockPos here = new BlockPos(node.x, node.y, node.z);
        boolean onLadder = climbable(level, here);
        // Вверх: в лестнице, или под лестницей; клетка выше — лестница или свободна (выход наверх).
        if ((onLadder || climbable(level, here.above())) && n < out.length) {
            BlockPos up = here.above();
            if (climbable(level, up) || (onLadder && open(level, up) && open(level, up.above()))) n = add(out, n, node, up);
        }
        // Вниз: под ногами лестница.
        if (climbable(level, here.below()) && n < out.length) n = add(out, n, node, here.below());
        return n;
    }

    private int add(net.minecraft.world.level.pathfinder.Node[] out, int n, net.minecraft.world.level.pathfinder.Node from, BlockPos to) {
        net.minecraft.world.level.pathfinder.Node next = getNode(to.getX(), to.getY(), to.getZ());
        if (next == null || next.closed) return n;
        next.type = PathType.WALKABLE;
        next.costMalus = Math.max(next.costMalus, 0f);
        out[n++] = next;
        return n;
    }

    private static boolean climbable(net.minecraft.world.level.BlockGetter level, BlockPos p) {
        return level.getBlockState(p).is(net.minecraft.tags.BlockTags.CLIMBABLE);
    }

    private static boolean open(net.minecraft.world.level.BlockGetter level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.is(net.minecraft.tags.BlockTags.CLIMBABLE) || s.getCollisionShape(level, p).isEmpty();
    }

    static boolean opensHere(BlockState state, @Nullable Outpost home, BlockPos pos) {
        return home != null && state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN) && home.contains(pos);
    }
}
