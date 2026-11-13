package com.vladosiwe.lordsoutpost.entity.ai;

import com.vladosiwe.lordsoutpost.blueprint.Marker;
import com.vladosiwe.lordsoutpost.data.ChunkReconciliation;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.entity.FighterEntity;
import com.vladosiwe.lordsoutpost.entity.OutpostNpc;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Role;
import com.vladosiwe.lordsoutpost.outpost.Squad;
import com.vladosiwe.lordsoutpost.server.NpcLifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.List;

/**
 * Служба бойца, когда нет противника: пост на стене, круговой патруль, удержание позиции,
 * сопровождение Лорда, конвой каравана или (для налётчика) марш к воротам цели.
 */
public class DutyGoal extends Goal {
    private final FighterEntity mob;
    private int waypoint = -1;
    private int waypointTicks;

    public DutyGoal(FighterEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return !mob.level().isClientSide && mob.getTarget() == null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (mob.raidTarget != 0) {
            Outpost target = OutpostSavedData.get(level).byId(mob.raidTarget);
            if (target != null) walk(target.marker(Marker.YARD), 1.0, 5);
            return;
        }
        if (mob.caravanId != null) {
            Entity leader = ChunkReconciliation.caravanLeader(level, mob);
            if (leader != null) follow(leader, 3.5, 30);
            return;
        }
        Outpost o = mob.outpost();
        Resident r = mob.resident();
        if (o == null || r == null) return;

        if (o.state != OutpostState.OWNED) {
            if (r.role == Role.CAPTAIN) {
                mob.approach(o.marker(Marker.THRONE), 1.0, 2.5, 300);
            } else if ((o.residents.indexOf(r) & 1) == 0) {
                post(o, r);
            } else {
                patrol(level, o, o.markers(Marker.PATROL), false);
            }
            return;
        }
        Squad squad = o.squads[Math.max(0, Math.min(Squad.COUNT - 1, r.squad))];
        switch (squad.order) {
            case RETINUE -> {
                ServerPlayer lord = NpcLifecycle.lord(level, o);
                if (lord != null && lord.level() == level && lord.isAlive()) follow(lord, 4, 26);
                else post(o, r);
            }
            case HOLD -> {
                if (squad.hold != null) mob.approach(NpcLifecycle.groundAt(level, o, squad.hold.getX(), squad.hold.getZ()), 1.0, 3, 400);
                else post(o, r);
            }
            case PATROL -> {
                boolean custom = squad.route.size() >= 2;
                patrol(level, o, custom ? squad.route : o.markers(squad.id == Squad.WATCH ? Marker.OUTER_PATROL : Marker.PATROL), true);
            }
            default -> post(o, r);
        }
    }

    private void post(Outpost o, Resident r) {
        BlockPos post = NpcLifecycle.postFor(o, r);
        // Ушёл слишком далеко от дома (например, после роспуска Свиты) — возвращается в запись.
        if (mob.distanceToSqr(post.getX(), post.getY(), post.getZ()) > 110 * 110) {
            mob.discard();
            return;
        }
        mob.approach(post, 1.0, 1.6, 240);
    }

    private void patrol(ServerLevel level, Outpost o, List<BlockPos> points, boolean resolveGround) {
        if (points.isEmpty()) return;
        if (waypoint < 0) waypoint = Math.floorMod(mob.getUUID().hashCode(), points.size());
        BlockPos raw = points.get(waypoint % points.size());
        BlockPos target = resolveGround ? NpcLifecycle.groundAt(level, o, raw.getX(), raw.getZ()) : raw;
        waypointTicks++;
        double dx = mob.getX() - (target.getX() + 0.5), dz = mob.getZ() - (target.getZ() + 0.5);
        if (dx * dx + dz * dz < 9 || waypointTicks > 600) {
            waypoint = (waypoint + 1) % points.size();
            waypointTicks = 0;
            return;
        }
        if (mob.getNavigation().isDone() || mob.tickCount % 40 == 0) {
            mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.85);
        }
    }

    private void walk(BlockPos target, double speed, double reach) {
        if (mob.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5) <= reach * reach) return;
        if (mob.getNavigation().isDone() || mob.tickCount % 40 == 0) {
            mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speed);
        }
    }

    private void follow(Entity leader, double keep, double teleportAt) {
        double dist = mob.distanceTo(leader);
        if (dist > teleportAt) {
            teleportNear(mob, leader);
            return;
        }
        if (dist > keep) {
            if (mob.getNavigation().isDone() || mob.tickCount % 10 == 0) mob.getNavigation().moveTo(leader, dist > 10 ? 1.3 : 1.05);
        } else {
            mob.getNavigation().stop();
        }
    }

    public static void teleportNear(OutpostNpc mob, Entity leader) {
        for (int i = 0; i < 10; i++) {
            int x = leader.getBlockX() + mob.getRandom().nextInt(7) - 3;
            int z = leader.getBlockZ() + mob.getRandom().nextInt(7) - 3;
            for (int dy = 2; dy >= -2; dy--) {
                BlockPos p = new BlockPos(x, leader.getBlockY() + dy, z);
                if (mob.level().getBlockState(p.below()).isSolid() && mob.level().getBlockState(p).getCollisionShape(mob.level(), p).isEmpty()
                        && mob.level().getBlockState(p.above()).getCollisionShape(mob.level(), p.above()).isEmpty()) {
                    mob.getNavigation().stop();
                    mob.teleportTo(x + 0.5, p.getY(), z + 0.5);
                    return;
                }
            }
        }
    }
}
