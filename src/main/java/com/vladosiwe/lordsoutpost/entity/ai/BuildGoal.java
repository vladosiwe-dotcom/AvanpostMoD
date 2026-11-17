package com.vladosiwe.lordsoutpost.entity.ai;

import com.vladosiwe.lordsoutpost.data.ChunkReconciliation;
import com.vladosiwe.lordsoutpost.entity.WorkerEntity;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * Строитель резервирует шаг чертежа, находит доступное место рядом с ним, расчищает естественные препятствия
 * и только после этого физически ставит блок. Недоступный шаг освобождается, а не появляется сам собой.
 */
public class BuildGoal extends Goal {
    private final WorkerEntity mob;
    private int claim = -1;
    private BlockPos target = BlockPos.ZERO;
    @Nullable private BlockPos workPosition;
    private int timeout;
    private int cooldown;
    private String lastResult = "idle";

    public BuildGoal(WorkerEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.level().isClientSide || mob.role() != Role.BUILDER || mob.caravanId != null || mob.cargoReadyToDeposit()) return false;
        Outpost o = mob.outpost();
        return o != null && (claim >= 0 || ChunkReconciliation.hasWork(o));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        Outpost o = mob.outpost();
        if (o == null || !(mob.level() instanceof ServerLevel level)) return;
        if (claim < 0) {
            if (--cooldown > 0) return;
            claim = ChunkReconciliation.claim(o, level.getGameTime());
            if (claim < 0) {
                lastResult = "no-claim";
                cooldown = 10;
                return;
            }
            if (claim >= o.nextPlan().size()) {
                ChunkReconciliation.releaseClaim(o, claim);
                claim = -1;
                cooldown = 20;
                return;
            }
            target = o.world(o.nextPlan().get(claim).pos());
            workPosition = null;
            timeout = 200;
            lastResult = "claimed";
        }

        if (!ChunkReconciliation.renewClaim(o, claim, level.getGameTime())) {
            claim = -1;
            workPosition = null;
            lastResult = "claim-expired";
            return;
        }
        if (ChunkReconciliation.blockedByForeignBlock(level, o, claim)) {
            // Не сносим сундуки, посевы, декор и постройки игрока ради автоматического чертежа.
            lastResult = "blocked-foreign:" + level.getBlockState(target);
            release(o, 40);
            return;
        }

        if (workPosition == null || !validWorkPosition(level, workPosition)) {
            workPosition = findWorkPosition(level, target);
        }
        if (workPosition == null) {
            lastResult = "no-reachable-work-position";
            if (--timeout <= 0) release(o, 40);
            return;
        }

        mob.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        double wx = workPosition.getX() + 0.5;
        double wy = workPosition.getY();
        double wz = workPosition.getZ() + 0.5;
        if (mob.distanceToSqr(wx, wy, wz) > 0.64) {
            lastResult = "walking";
            if (--timeout <= 0) {
                release(o, 40);
                mob.getNavigation().stop();
                return;
            }
            if (mob.getNavigation().isDone() || mob.tickCount % 20 == 0) {
                mob.getNavigation().moveTo(wx, wy, wz, 1.0);
            }
            return;
        }

        mob.getNavigation().stop();
        // Строитель работает с соседней устойчивой клетки, в пределах обычной досягаемости руки.
        double eyeY = mob.getY() + mob.getEyeHeight();
        double dx = target.getX() + 0.5 - (mob.getX());
        double dy = target.getY() + 0.5 - eyeY;
        double dz = target.getZ() + 0.5 - (mob.getZ());
        if (dx * dx + dy * dy + dz * dz > 4.5 * 4.5) {
            workPosition = null;
            lastResult = "out-of-reach";
            if (--timeout <= 0) release(o, 40);
            return;
        }

        if (ChunkReconciliation.clearNaturalObstruction(level, o, claim, mob)) {
            lastResult = "cleared-natural-obstruction";
            workPosition = null;
            timeout = 200;
            return;
        }
        mob.swing(InteractionHand.MAIN_HAND);
        ChunkReconciliation.place(level, o, claim, true);
        lastResult = "placed";
        claim = -1;
        workPosition = null;
        cooldown = 8;
    }

    /** Ищет устойчивую соседнюю клетку с воздухом для тела и свободным пространством над головой. */
    @Nullable
    private BlockPos findWorkPosition(ServerLevel level, BlockPos block) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        double tx = block.getX() + 0.5, ty = block.getY() + 0.5, tz = block.getZ() + 0.5;
        for (int dy = 1; dy >= -4; dy--) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos feet = new BlockPos(block.getX() + dx, block.getY() + dy, block.getZ() + dz);
                    if (level.isOutsideBuildHeight(feet) || level.isOutsideBuildHeight(feet.above())) continue;
                    if (!level.getBlockState(feet).getFluidState().isEmpty()
                            || !level.getBlockState(feet.above()).getFluidState().isEmpty()
                            || !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                            || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) continue;
                    BlockPos floor = feet.below();
                    if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) continue;

                    double eyeY = feet.getY() + mob.getEyeHeight();
                    double rx = tx - (feet.getX() + 0.5), ry = ty - eyeY, rz = tz - (feet.getZ() + 0.5);
                    if (rx * rx + ry * ry + rz * rz > 4.0 * 4.0) continue;
                    double distance = mob.distanceToSqr(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                    if (distance >= bestDistance) continue;
                    var path = mob.getNavigation().createPath(feet, 0);
                    if (path == null || !path.canReach()) continue;
                    bestDistance = distance;
                    best = feet.immutable();
                }
            }
        }
        return best;
    }

    public String debugStatus() {
        return "BuildGoal{idx=" + claim + ",target=" + target.toShortString()
                + ",block=" + mob.level().getBlockState(target)
                + ",work=" + (workPosition == null ? "-" : workPosition.toShortString())
                + ",timeout=" + timeout + ",cooldown=" + cooldown + ",state=" + lastResult + "}";
    }

    private boolean validWorkPosition(ServerLevel level, BlockPos feet) {
        if (level.isOutsideBuildHeight(feet) || level.isOutsideBuildHeight(feet.above())) return false;
        return level.getBlockState(feet).getFluidState().isEmpty()
                && level.getBlockState(feet.above()).getFluidState().isEmpty()
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP);
    }

    private void release(Outpost o, int retryTicks) {
        ChunkReconciliation.releaseClaim(o, claim);
        claim = -1;
        workPosition = null;
        cooldown = retryTicks;
        mob.getNavigation().stop();
    }

    @Override
    public void stop() {
        ChunkReconciliation.releaseClaim(mob.outpost(), claim);
        claim = -1;
        workPosition = null;
        mob.getNavigation().stop();
    }
}
