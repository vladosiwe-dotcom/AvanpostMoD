package com.vladosiwe.lordsoutpost.entity;

import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.config.OutpostConfig;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.outpost.AccessLevel;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostMember;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Pact;
import com.vladosiwe.lordsoutpost.outpost.Relation;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Role;
import com.vladosiwe.lordsoutpost.protection.Access;
import com.vladosiwe.lordsoutpost.server.NpcLifecycle;
import com.vladosiwe.lordsoutpost.server.Roads;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathType;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Общая основа жителей и бойцов. Сущность — лишь отражение записи {@link Resident}: она не сохраняется в чанк,
 * а при выгрузке возвращает своё состояние в запись. Поэтому у одного жителя не бывает двух тел и никто не теряется.
 */
public abstract class OutpostNpc extends PathfinderMob implements OutpostMember {
    private static final EntityDataAccessor<Integer> ROLE = SynchedEntityData.defineId(OutpostNpc.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> HOSTILE = SynchedEntityData.defineId(OutpostNpc.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> STYLE = SynchedEntityData.defineId(OutpostNpc.class, EntityDataSerializers.INT);
    private static final ResourceLocation ROAD_MODIFIER = LordsOutpost.id("road");

    protected long outpostId;
    @Nullable protected UUID residentId;
    /** Для налётчиков: аванпост, на который идёт набег. */
    public long raidTarget;
    /** Для участников каравана. */
    @Nullable public UUID caravanId;
    /** Счётчик для запасного телепорта, когда путь не находится. */
    public int stuckTicks;

    protected OutpostNpc(EntityType<? extends OutpostNpc> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setPathfindingMalus(PathType.DOOR_OPEN, 0.0f);
        setPathfindingMalus(PathType.WATER, 4.0f);
        if (getNavigation() instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation nav) nav.setCanOpenDoors(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ROLE, Role.SHIELDMAN.ordinal());
        builder.define(HOSTILE, true);
        builder.define(STYLE, 0);
    }

    // ============================== Привязка к записи ==============================

    /** Превращает сущность в отражение жителя: роль, сторона, имя, снаряжение, здоровье. */
    public void bind(Outpost o, Resident r) {
        outpostId = o.id;
        residentId = r.id;
        entityData.set(ROLE, r.role.ordinal());
        entityData.set(HOSTILE, o.state != OutpostState.OWNED);
        entityData.set(STYLE, o.palette.ordinal());
        equip(o, r);
        setHealth(Math.max(1f, getMaxHealth() * r.health));
        refreshName(o, r);
        r.entity = getUUID();
    }

    /** Налётчик или участник засады: не житель, принадлежит стороне мародёров. */
    public void bindRaider(long source, long target, Role role, int gear) {
        outpostId = source;
        residentId = null;
        raidTarget = target;
        entityData.set(ROLE, role.ordinal());
        entityData.set(HOSTILE, true);
        Resident fake = new Resident(UUID.randomUUID(), role, "");
        fake.gear = gear;
        equip(null, fake);
        setHealth(getMaxHealth());
        setCustomName(Component.translatable("lordsoutpost.npc.raider", role.label()).withStyle(ChatFormatting.RED));
    }

    protected void refreshName(Outpost o, Resident r) {
        ChatFormatting color = o.state == OutpostState.OWNED ? ChatFormatting.GREEN : ChatFormatting.RED;
        Component role = o.state == OutpostState.OWNED || r.role == Role.CAPTAIN ? r.role.label() : Component.translatable("lordsoutpost.npc.marauder", r.role.label());
        setCustomName(Component.literal(r.name + " ").append(Component.literal("[").append(role).append("]").withStyle(color)));
    }

    /** Снаряжение и характеристики по роли и уровню снаряжения. */
    protected abstract void equip(@Nullable Outpost o, Resident r);

    protected void noDrops() {
        for (EquipmentSlot slot : EquipmentSlot.values()) setDropChance(slot, 0f);
    }

    @Override
    public long outpostId() {
        return outpostId;
    }

    @Nullable
    public UUID residentId() {
        return residentId;
    }

    public Role role() {
        return Role.VALUES[Math.max(0, Math.min(Role.VALUES.length - 1, entityData.get(ROLE)))];
    }

    public boolean hostileFaction() {
        return entityData.get(HOSTILE);
    }

    /** Палитра форта: по ней клиент выбирает облик жителя. */
    public int style() {
        return entityData.get(STYLE);
    }

    @Nullable
    public Outpost outpost() {
        if (!(level() instanceof ServerLevel sl) || outpostId == 0) return null;
        return OutpostSavedData.get(sl).byId(outpostId);
    }

    @Nullable
    public Resident resident() {
        Outpost o = outpost();
        return o == null || residentId == null ? null : o.resident(residentId);
    }

    // ============================== Жизненный цикл ==============================

    /** Сущность не пишется в чанк: источник правды — запись жителя. */
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public void checkDespawn() {
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!level().isClientSide && reason != RemovalReason.KILLED) NpcLifecycle.onUnloaded(this);
        super.remove(reason);
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!level().isClientSide) NpcLifecycle.onDeath(this, source);
    }

    @Override
    protected boolean shouldDropLoot() {
        return hostileFaction();
    }

    @Override
    protected int getBaseExperienceReward() {
        return hostileFaction() ? 8 : 0;
    }

    // ============================== Свои и чужие ==============================

    /** Кого этот боец никогда не тронет. */
    public boolean isFriend(LivingEntity other) {
        if (other == this) return true;
        if (other instanceof OutpostNpc npc) {
            if (npc.outpostId == outpostId && npc.hostileFaction() == hostileFaction()) return true;
            if (hostileFaction() && npc.hostileFaction()) return true;
            if (!hostileFaction() && !npc.hostileFaction()) {
                Outpost o = outpost();
                Relation rel = o == null ? null : o.relations.get(npc.outpostId);
                return rel != null && rel.pact != Pact.NONE;
            }
            // Гарнизон дикого форта не трогает караваны того, с кем у Капитана договор.
            Outpost wild = hostileFaction() ? outpost() : npc.outpost();
            long otherId = hostileFaction() ? npc.outpostId : outpostId;
            if (wild != null && raidTarget == 0 && npc.raidTarget == 0) {
                Relation rel = wild.relations.get(otherId);
                return rel != null && rel.pact != Pact.NONE;
            }
            return false;
        }
        if (other instanceof Player player) {
            if (player.isCreative() || player.isSpectator()) return true;
            if (hostileFaction()) return false;
            Outpost o = outpost();
            if (o == null || !(level() instanceof ServerLevel sl)) return false;
            return Access.level(OutpostSavedData.get(sl), o, player.getUUID()).atLeast(AccessLevel.ALLY);
        }
        return other.getPersistentData().contains(NpcLifecycle.CARAVAN_TAG) && !hostileFaction();
    }

    /** Кого этот боец атакует сам, без повода. */
    public boolean isEnemy(LivingEntity other) {
        if (!other.isAlive() || isFriend(other)) return false;
        if (other instanceof OutpostNpc npc) return npc.hostileFaction() != hostileFaction();
        if (other instanceof Player player) {
            if (hostileFaction()) return true;
            Outpost o = outpost();
            if (o == null || !(level() instanceof ServerLevel sl)) return false;
            return o.contains(player.blockPosition())
                    && Access.level(OutpostSavedData.get(sl), o, player.getUUID()) == AccessLevel.HOSTILE;
        }
        if (hostileFaction()) return other.getPersistentData().contains(NpcLifecycle.CARAVAN_TAG);
        return other instanceof Enemy && other instanceof Mob;
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        return !isFriend(target) && super.canAttack(target);
    }

    @Override
    public void setTarget(@Nullable LivingEntity target) {
        if (target != null && isFriend(target)) return;
        super.setTarget(target);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Свои не ранят своих: ни Лорд случайным ударом, ни стрела товарища.
        if (!level().isClientSide && source.getEntity() instanceof LivingEntity attacker && attacker != this && isFriend(attacker)) return false;
        return super.hurt(source, amount);
    }

    @Override
    public boolean isAlliedTo(net.minecraft.world.entity.Entity other) {
        return (other instanceof LivingEntity living && isFriend(living)) || super.isAlliedTo(other);
    }

    // ============================== Дороги и застревание ==============================

    @Override
    public void aiStep() {
        super.aiStep();
        if (!level().isClientSide && tickCount % 5 == 0) {
            AttributeInstance speed = getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) {
                boolean road = Roads.onRoad(this);
                if (road != speed.hasModifier(ROAD_MODIFIER)) {
                    speed.removeModifier(ROAD_MODIFIER);
                    if (road) speed.addTransientModifier(new AttributeModifier(ROAD_MODIFIER,
                            OutpostConfig.get(OutpostConfig.ROAD_SPEED, 1.35) - 1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
                }
            }
        }
    }

    /**
     * Идёт к точке; если путь не находится слишком долго, переносится к ней.
     * Нужно для постов на башнях и для редких случаев, когда поиск пути сдаётся.
     *
     * @return true, когда сущность на месте
     */
    public boolean approach(BlockPos pos, double speed, double reach, int patience) {
        double dist = distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        if (dist <= reach * reach) {
            stuckTicks = 0;
            if (!getNavigation().isDone()) getNavigation().stop();
            return true;
        }
        if (getNavigation().isDone() || tickCount % 40 == 0) {
            boolean ok = getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, speed);
            if (!ok) stuckTicks += 20;
        }
        stuckTicks++;
        if (stuckTicks > patience) {
            stuckTicks = 0;
            getNavigation().stop();
            teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            return true;
        }
        return false;
    }
}
