package com.vladosiwe.lordsoutpost.entity.ai;

import com.vladosiwe.lordsoutpost.data.ChunkReconciliation;
import com.vladosiwe.lordsoutpost.entity.WorkerEntity;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.pathfinder.Path;
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
    @Nullable private Path workPath;
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
        if (level.getBlockState(target).equals(o.nextPlan().get(claim).state())) {
            ChunkReconciliation.place(level, o, claim, false);
            lastResult = "already-correct";
            claim = -1;
            workPosition = null;
            cooldown = 2;
            return;
        }

        if (ChunkReconciliation.blockedByForeignBlock(level, o, claim)) {
            // Не сносим сундуки, посевы, декор и постройки игрока ради автоматического чертежа:
            // чертёж уступает клетку, иначе строители вечно возвращались бы к этому шагу и стройка стояла.
            lastResult = "yield-to-player:" + level.getBlockState(target);
            ChunkReconciliation.yieldToPlayer(o, claim);
            claim = -1;
            workPosition = null;
            workPath = null;
            cooldown = 2;
            return;
        }

        if (workPosition != null && reservedByAnotherBuilder(level, o, workPosition)) {
            workPosition = null;
            workPath = null;
        }
        if (workPosition == null || !validWorkPosition(level, workPosition)) {
            workPosition = findWorkPosition(level, o, target);
        }
        if (workPosition == null) {
            lastResult = "no-reachable-work-position";
            if (--timeout <= 0) defer(o, level.getGameTime());
            return;
        }

        mob.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        double wx = workPosition.getX() + 0.5;
        double wy = workPosition.getY();
        double wz = workPosition.getZ() + 0.5;
        if (mob.distanceToSqr(wx, wy, wz) > 0.64) {
            lastResult = "walking";
            if (--timeout <= 0) {
                defer(o, level.getGameTime());
                return;
            }
            Path path = mob.getNavigation().getPath();
            if (path == null || path.isDone() || !workPosition.equals(path.getTarget())) {
                if (workPath != null && workPath.canReach()) mob.getNavigation().moveTo(workPath, 1.0);
                else mob.getNavigation().moveTo(wx, wy, wz, 1.0);
            }
            return;
        }

        mob.getNavigation().stop();
        // Строитель работает с соседней устойчивой клетки, в пределах обычной досягаемости руки.
        double eyeY = mob.getY() + mob.getEyeHeight();
        double dx = target.getX() + 0.5 - (mob.getX());
        double dy = target.getY() + 0.5 - eyeY;
        double dz = target.getZ() + 0.5 - (mob.getZ());
        double maxReach = reach(o) + 0.5;
        if (dx * dx + dy * dy + dz * dz > maxReach * maxReach) {
            workPosition = null;
            lastResult = "out-of-reach";
            if (--timeout <= 0) defer(o, level.getGameTime());
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

    public boolean reservesWorkPosition(BlockPos pos) {
        return claim >= 0 && workPosition != null && workPosition.equals(pos);
    }

    private boolean reservedByAnotherBuilder(ServerLevel level, Outpost o, BlockPos feet) {
        return !level.getEntitiesOfClass(WorkerEntity.class, new net.minecraft.world.phys.AABB(o.center).inflate(128, 64, 128),
                other -> other != mob && other.outpostId() == o.id
                        && other.getUUID().compareTo(mob.getUUID()) < 0 && other.reservesBuildPosition(feet)).isEmpty();
    }

    /** Ищет устойчивую соседнюю клетку с воздухом для тела и свободным пространством над головой. */
    @Nullable
    private BlockPos findWorkPosition(ServerLevel level, Outpost o, BlockPos block) {
        BlockPos best = null;
        Path bestPath = null;
        workPath = null;
        double bestDistance = Double.MAX_VALUE;
        double tx = block.getX() + 0.5, ty = block.getY() + 0.5, tz = block.getZ() + 0.5;
        // После двух неудач строитель ищет опору шире: с соседнего яруса лесов, со стены или крыши рядом.
        boolean wide = ChunkReconciliation.stepFailures(o, claim) >= 2;
        int span = wide ? 2 : 1, up = wide ? 2 : 1, down = wide ? -6 : -4;
        double reach = reach(o);
        for (int dy = up; dy >= down; dy--) {
            for (int dx = -span; dx <= span; dx++) {
                for (int dz = -span; dz <= span; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos feet = new BlockPos(block.getX() + dx, block.getY() + dy, block.getZ() + dz);
                    if (level.isOutsideBuildHeight(feet) || level.isOutsideBuildHeight(feet.above())) continue;
                    if (!level.getBlockState(feet).getFluidState().isEmpty()
                            || !level.getBlockState(feet.above()).getFluidState().isEmpty()
                            || !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                            || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) continue;
                    BlockPos floor = feet.below();
                    if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) continue;
                    if (!level.getEntitiesOfClass(WorkerEntity.class, new net.minecraft.world.phys.AABB(feet), other -> other != mob).isEmpty()) continue;
                    if (reservedByAnotherBuilder(level, o, feet)) continue;

                    double eyeY = feet.getY() + mob.getEyeHeight();
                    double rx = tx - (feet.getX() + 0.5), ry = ty - eyeY, rz = tz - (feet.getZ() + 0.5);
                    if (rx * rx + ry * ry + rz * rz > reach * reach) continue;
                    double distance = mob.distanceToSqr(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                    if (distance >= bestDistance) continue;
                    Path path = mob.getNavigation().createPath(feet, 0);
                    if (path == null || !path.canReach()) continue;
                    bestDistance = distance;
                    best = feet.immutable();
                    bestPath = path;
                }
            }
        }
        workPath = bestPath;
        return best;
    }

    public String debugStatus() {
        return "BuildGoal{pos=" + mob.blockPosition().toShortString() + ",idx=" + claim + ",target=" + target.toShortString()
                + ",block=" + mob.level().getBlockState(target)
                + ",work=" + (workPosition == null ? "-" : workPosition.toShortString())
                + ",path=" + (workPath == null ? "-" : workPath.getTarget().toShortString() + "/" + workPath.getNodeCount())
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

    /** Обычная досягаемость руки 4 блока; для трудных клеток — 5, как у игрока. */
    private double reach(Outpost o) {
        return ChunkReconciliation.stepFailures(o, claim) >= 2 ? 5.0 : 4.0;
    }

    /** Шаг недоступен: откладываем его, а строитель берёт следующий. */
    private void defer(Outpost o, long now) {
        ChunkReconciliation.deferStep(o, claim, now);
        claim = -1;
        workPosition = null;
        workPath = null;
        cooldown = 10;
        mob.getNavigation().stop();
    }

    private void release(Outpost o, int retryTicks) {
        ChunkReconciliation.releaseClaim(o, claim);
        claim = -1;
        workPosition = null;
        workPath = null;
        cooldown = retryTicks;
        mob.getNavigation().stop();
    }

    @Override
    public void stop() {
        ChunkReconciliation.releaseClaim(mob.outpost(), claim);
        claim = -1;
        workPosition = null;
        workPath = null;
        mob.getNavigation().stop();
    }
}
