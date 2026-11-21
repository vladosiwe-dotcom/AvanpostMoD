package com.vladosiwe.lordsoutpost.entity.ai;

import com.vladosiwe.lordsoutpost.entity.OutpostNpc;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * Житель или стражник открывает калитку своего аванпоста, через которую идёт его путь, проходит и закрывает её за
 * собой, если в проёме никто не стоит (скот не разбегается). Калитку, открытую кем-то другим, не трогает.
 */
public class OpenGateGoal extends Goal {
    private final OutpostNpc mob;
    @Nullable private BlockPos gate;
    private int away;

    public OpenGateGoal(OutpostNpc mob) {
        this.mob = mob;
    }

    @Override
    public boolean canUse() {
        if (mob.level().isClientSide || mob.hostileFaction()) return false;
        Path path = mob.getNavigation().getPath();
        if (path == null || path.isDone()) return false;
        int from = path.getNextNodeIndex();
        for (int i = Math.max(0, from - 1); i < Math.min(path.getNodeCount(), from + 2); i++) {
            BlockPos p = path.getNodePos(i);
            if (mob.distanceToSqr(p.getX() + 0.5, p.getY(), p.getZ() + 0.5) > 6.25) continue;
            if (GateNodeEvaluator.opensHere(mob.level().getBlockState(p), mob.outpost(), p)) {
                gate = p.immutable();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return gate != null && away < 20;
    }

    @Override
    public void start() {
        away = 0;
        set(true);
    }

    @Override
    public void tick() {
        if (gate == null) return;
        double d = mob.distanceToSqr(gate.getX() + 0.5, gate.getY(), gate.getZ() + 0.5);
        away = d > 2.25 ? away + 1 : 0;
    }

    @Override
    public void stop() {
        if (gate != null) {
            boolean blocked = !mob.level().getEntitiesOfClass(Entity.class, new AABB(gate), e -> e.isAlive() && !e.isSpectator()).isEmpty();
            if (!blocked) set(false);
        }
        gate = null;
    }

    private void set(boolean open) {
        BlockState s = mob.level().getBlockState(gate);
        if (!(s.getBlock() instanceof FenceGateBlock) || s.getValue(FenceGateBlock.OPEN) == open) return;
        mob.level().setBlockAndUpdate(gate, s.setValue(FenceGateBlock.OPEN, open));
        mob.level().playSound(null, gate, open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 0.7f, 1f);
    }
}
