package com.vladosiwe.lordsoutpost.entity.ai;

import com.vladosiwe.lordsoutpost.blueprint.Step;
import com.vladosiwe.lordsoutpost.data.ChunkReconciliation;
import com.vladosiwe.lordsoutpost.data.Projects;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.List;

/**
 * Строитель резервирует шаг чертежа, находит доступное место рядом с ним, расчищает естественные препятствия
 * и только после этого физически ставит блок. Недоступный шаг освобождается, а не появляется сам собой.
 */
public class BuildGoal extends Goal {
    private final WorkerEntity mob;
    private int claim = -1;
    /** Шаг заказанной Лордом постройки (ключ из Projects) или -1. План тира важнее: проект берётся, только когда в плане работы нет. */
    private long projectKey = -1;
    /** Материал, за которым строитель идёт на склад ради заказанной постройки, или null. */
    @Nullable private com.vladosiwe.lordsoutpost.outpost.Resource fetching;
    private int fetchTimeout;
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
        return o != null && (claim >= 0 || projectKey >= 0 || ChunkReconciliation.hasWork(o) || Projects.hasWork(o));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        Outpost o = mob.outpost();
        if (o == null || !(mob.level() instanceof ServerLevel level)) return;
        if (fetching != null) {
            fetchMaterial(level, o);
            return;
        }
        if (!hasJob()) {
            if (--cooldown > 0) return;
            claim = ChunkReconciliation.claim(o, level.getGameTime());
            if (claim >= o.nextPlan().size()) {
                ChunkReconciliation.releaseClaim(o, claim);
                claim = -1;
                cooldown = 20;
                return;
            }
            if (claim < 0) projectKey = Projects.claim(o, level.getGameTime());
            if (claim < 0 && projectKey < 0) {
                // Следующий шаг заказа оплачивается материалом из ноши строителя.
                topUp(o);
                projectKey = Projects.claimPaying(o, level.getGameTime());
                if (projectKey < 0 && Projects.neededMaterial(o) != null && !hasCarried(Projects.neededMaterial(o))) {
                    fetching = Projects.neededMaterial(o);
                    fetchTimeout = 600;
                    lastResult = "fetch-" + fetching.id;
                    return;
                }
            }
            Step step = jobStep(o);
            if (step == null) {
                releaseJob(o);
                lastResult = "no-claim";
                cooldown = 10;
                return;
            }
            target = o.world(step.pos());
            workPosition = null;
            timeout = 200;
            lastResult = projectKey >= 0 ? "claimed-project" : "claimed";
        }

        Step step = jobStep(o);
        if (step == null || !renewJob(o, level.getGameTime())) {
            claim = -1;
            projectKey = -1;
            workPosition = null;
            lastResult = "claim-expired";
            return;
        }
        if (level.getBlockState(target).equals(step.state())) {
            placeJob(level, o, false);
            lastResult = "already-correct";
            workPosition = null;
            cooldown = 2;
            return;
        }

        if (blockedJob(level, o)) {
            // Не сносим сундуки, посевы, декор и постройки игрока ради автоматического чертежа:
            // чертёж уступает клетку, иначе строители вечно возвращались бы к этому шагу и стройка стояла.
            lastResult = "yield-to-player:" + level.getBlockState(target);
            yieldJob(o);
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

        boolean cleared = projectKey >= 0 ? ChunkReconciliation.clearNaturalObstruction(level, o, step, null, mob)
                : ChunkReconciliation.clearNaturalObstruction(level, o, claim, mob);
        if (cleared) {
            lastResult = "cleared-natural-obstruction";
            workPosition = null;
            timeout = 200;
            return;
        }
        mob.swing(InteractionHand.MAIN_HAND);
        placeJob(level, o, true);
        lastResult = "placed";
        workPosition = null;
        cooldown = 8;
    }

    // ---------- Материалы для заказанных построек ----------

    /** Предмет-носитель материала: бревно — дерево, булыжник — камень, слиток — железо. */
    private static boolean carries(ItemStack st, com.vladosiwe.lordsoutpost.outpost.Resource r) {
        var v = com.vladosiwe.lordsoutpost.data.ResourceItems.of(st);
        return v != null && v.resource() == r && st.getComponentsPatch().isEmpty();
    }

    private boolean hasCarried(com.vladosiwe.lordsoutpost.outpost.Resource r) {
        for (int i = 0; i < mob.inventory.getContainerSize(); i++) if (carries(mob.inventory.getItem(i), r)) return true;
        return false;
    }

    /** Пускает в дело один предмет из ноши, если принесённого материала не хватает на следующий шаг. */
    private void topUp(Outpost o) {
        var need = Projects.neededMaterial(o);
        for (int guard = 0; need != null && guard < 4; guard++) {
            boolean used = false;
            for (int i = 0; i < mob.inventory.getContainerSize() && !used; i++) {
                ItemStack st = mob.inventory.getItem(i);
                if (!carries(st, need)) continue;
                var v = com.vladosiwe.lordsoutpost.data.ResourceItems.of(st);
                Projects.addMaterial(o, need, v.perItem());
                st.shrink(1);
                mob.inventory.setChanged();
                used = true;
            }
            if (!used) return;
            need = Projects.neededMaterial(o);
        }
    }

    /** Поход на склад за материалом для заказа: настоящие предметы через логистику, в ношу строителя. */
    private void fetchMaterial(ServerLevel level, Outpost o) {
        var r = fetching;
        long now = level.getGameTime();
        BlockPos store = o.zone(com.vladosiwe.lordsoutpost.outpost.WorkZone.STOREHOUSE);
        if (--fetchTimeout <= 0) {
            fetching = null;
            cooldown = 200;
            lastResult = "fetch-timeout";
            return;
        }
        if (!mob.approach(store, 1.0, 1.8, 500)) {
            lastResult = "fetch-walking";
            return;
        }
        fetching = null;
        Item item = com.vladosiwe.lordsoutpost.data.Storehouse.findItem(level, o, st -> carries(st, r));
        var resident = mob.resident();
        if (item == null || resident == null) {
            // На складе нечего взять: стройка ждёт (дефицит увидит планировщик).
            o.stalledOn = o.stalledOn == null ? r : o.stalledOn;
            cooldown = 400;
            lastResult = "fetch-none";
            return;
        }
        var v = com.vladosiwe.lordsoutpost.data.ResourceItems.of(new ItemStack(item));
        int want = (int) Math.ceil(Projects.upcoming(o, r, 64) / v.perItem());
        int count = Math.max(1, Math.min(Math.min(want, item.getDefaultMaxStackSize()),
                com.vladosiwe.lordsoutpost.data.Logistics.available(level, o, item, null)));
        var task = com.vladosiwe.lordsoutpost.data.Logistics.request(level, o, resident,
                com.vladosiwe.lordsoutpost.outpost.OutpostTask.Type.FETCH_BUILD, java.util.Map.of(item, count), now);
        if (task == null) {
            cooldown = 400;
            lastResult = "fetch-reserve-failed";
            return;
        }
        if (com.vladosiwe.lordsoutpost.data.Logistics.withdraw(level, o, task, mob, now)) {
            com.vladosiwe.lordsoutpost.data.Logistics.complete(o, task, now);
            mob.swing(InteractionHand.MAIN_HAND);
            lastResult = "fetched-" + count;
        } else {
            com.vladosiwe.lordsoutpost.data.Logistics.cancel(o, task, "withdraw failed", now);
            cooldown = 200;
            lastResult = "fetch-withdraw-failed";
        }
    }

    // ---------- Текущая работа: шаг плана тира или шаг заказанной постройки ----------

    private boolean hasJob() {
        return claim >= 0 || projectKey >= 0;
    }

    @Nullable
    private Step jobStep(Outpost o) {
        if (claim >= 0) {
            List<Step> plan = o.nextPlan();
            return claim < plan.size() ? plan.get(claim) : null;
        }
        return projectKey >= 0 ? Projects.step(o, projectKey) : null;
    }

    private boolean renewJob(Outpost o, long now) {
        return claim >= 0 ? ChunkReconciliation.renewClaim(o, claim, now) : Projects.renew(o, projectKey, now);
    }

    private void placeJob(ServerLevel level, Outpost o, boolean effects) {
        if (claim >= 0) ChunkReconciliation.place(level, o, claim, effects);
        else if (projectKey >= 0) Projects.place(level, o, projectKey, effects);
        claim = -1;
        projectKey = -1;
    }

    private boolean blockedJob(ServerLevel level, Outpost o) {
        return claim >= 0 ? ChunkReconciliation.blockedByForeignBlock(level, o, claim) : Projects.blocked(level, o, projectKey);
    }

    private void yieldJob(Outpost o) {
        if (claim >= 0) ChunkReconciliation.yieldToPlayer(o, claim);
        else if (projectKey >= 0) Projects.yieldCell(o, projectKey);
        claim = -1;
        projectKey = -1;
    }

    private int jobFailures(Outpost o) {
        return claim >= 0 ? ChunkReconciliation.stepFailures(o, claim) : projectKey >= 0 ? Projects.failures(o, projectKey) : 0;
    }

    private void releaseJob(@Nullable Outpost o) {
        ChunkReconciliation.releaseClaim(o, claim);
        if (o != null) Projects.release(o, projectKey);
        claim = -1;
        projectKey = -1;
    }

    public boolean reservesWorkPosition(BlockPos pos) {
        return hasJob() && workPosition != null && workPosition.equals(pos);
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
        boolean wide = jobFailures(o) >= 2;
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
        return "BuildGoal{pos=" + mob.blockPosition().toShortString() + ",idx=" + claim + (projectKey >= 0 ? ",project=" + Long.toHexString(projectKey) : "")
                + ",target=" + target.toShortString()
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
        return jobFailures(o) >= 2 ? 5.0 : 4.0;
    }

    /** Шаг недоступен: откладываем его, а строитель берёт следующий. */
    private void defer(Outpost o, long now) {
        if (claim >= 0) ChunkReconciliation.deferStep(o, claim, now);
        else if (projectKey >= 0) Projects.defer(o, projectKey, now);
        claim = -1;
        projectKey = -1;
        workPosition = null;
        workPath = null;
        cooldown = 10;
        mob.getNavigation().stop();
    }

    private void release(Outpost o, int retryTicks) {
        releaseJob(o);
        workPosition = null;
        workPath = null;
        cooldown = retryTicks;
        mob.getNavigation().stop();
    }

    @Override
    public void stop() {
        fetching = null;
        releaseJob(mob.outpost());
        workPosition = null;
        workPath = null;
        mob.getNavigation().stop();
    }
}
