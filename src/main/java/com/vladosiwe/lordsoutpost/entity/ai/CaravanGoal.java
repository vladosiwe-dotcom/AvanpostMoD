package com.vladosiwe.lordsoutpost.entity.ai;

import com.vladosiwe.lordsoutpost.entity.WorkerEntity;
import com.vladosiwe.lordsoutpost.outpost.Caravan;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.server.NpcLifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;

/** Караванщик ведёт караван к цели короткими переходами по поверхности. */
public class CaravanGoal extends Goal {
    private final WorkerEntity mob;
    private double lastDist = Double.MAX_VALUE;

    public CaravanGoal(WorkerEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return !mob.level().isClientSide && mob.caravanId != null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        Outpost o = mob.outpost();
        if (o == null || !(mob.level() instanceof ServerLevel level)) return;
        Caravan c = NpcLifecycle.caravan(o, mob.caravanId);
        if (c == null || c.phase == Caravan.Phase.TRADING) {
            mob.getNavigation().stop();
            return;
        }
        BlockPos dest = c.destination();
        double dx = dest.getX() + 0.5 - mob.getX(), dz = dest.getZ() + 0.5 - mob.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 3) return;
        double hop = Math.min(14, dist);
        int x = (int) Math.floor(mob.getX() + dx / dist * hop), z = (int) Math.floor(mob.getZ() + dz / dist * hop);
        if (!level.hasChunk(x >> 4, z >> 4)) return;
        BlockPos target = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        if (mob.tickCount % 20 == 0 || mob.getNavigation().isDone()) {
            mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.95);
        }
        // Раз в пять секунд проверяем, что караван продвигается; если упёрся в реку или обрыв — переносим на переход вперёд.
        if (mob.tickCount % 100 == 0) {
            if (lastDist - dist < 2.0) {
                mob.getNavigation().stop();
                mob.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            }
            lastDist = dist;
        }
    }
}
