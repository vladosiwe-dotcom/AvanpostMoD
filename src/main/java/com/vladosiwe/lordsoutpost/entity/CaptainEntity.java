package com.vladosiwe.lordsoutpost.entity;

import com.vladosiwe.lordsoutpost.config.OutpostConfig;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.registry.ModItems;
import com.vladosiwe.lordsoutpost.server.NpcLifecycle;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Капитан — босс дикого аванпоста. Его смерть означает захват форта, а в награду падает Штандарт Лорда.
 * Приёмы открываются с ростом сложности: рывок (2 черепка), боевой клич с подкреплением (3),
 * клыки из земли (4), ярость на исходе сил (5).
 */
public class CaptainEntity extends FighterEntity {
    private final ServerBossEvent bossBar = new ServerBossEvent(Component.empty(), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
    private int skulls = 1;
    private int leapCooldown = 100;
    private int fangsCooldown = 160;
    private boolean cried;
    private boolean enraged;

    public CaptainEntity(EntityType<? extends CaptainEntity> type, Level level) {
        super(type, level);
        xpReward = 40;
    }

    public static AttributeSupplier.Builder attributes() {
        return FighterEntity.attributes()
                .add(Attributes.MAX_HEALTH, 100)
                .add(Attributes.ATTACK_DAMAGE, 5)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.6)
                .add(Attributes.FOLLOW_RANGE, 40);
    }

    @Override
    protected void equip(@Nullable Outpost o, Resident r) {
        skulls = o == null ? 1 : o.difficulty.skulls;
        super.equip(o, r);
        setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(skulls >= 5 ? Items.NETHERITE_AXE : skulls >= 3 ? Items.DIAMOND_SWORD : Items.IRON_SWORD));
        setItemSlot(EquipmentSlot.OFFHAND, skulls >= 2 ? new ItemStack(Items.SHIELD) : ItemStack.EMPTY);
        noDrops();
        double health = (60 + 40 * skulls) * OutpostConfig.get(OutpostConfig.CAPTAIN_HEALTH, 1.0);
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(4 + skulls);
        getAttribute(Attributes.ARMOR).setBaseValue(2 * skulls);
        getAttribute(Attributes.SCALE).setBaseValue(1.1 + 0.05 * skulls);
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.31);
        xpReward = 30 + 30 * skulls;
        bossBar.setName(Component.translatable("lordsoutpost.npc.captain_bar", r.name, o == null ? Component.empty() : o.difficulty.label()));
    }

    @Override
    public boolean ranged() {
        return false;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        bossBar.setProgress(Mth.clamp(getHealth() / getMaxHealth(), 0f, 1f));
        LivingEntity target = getTarget();
        if (target == null || !target.isAlive()) return;
        double dist = distanceTo(target);

        if (skulls >= 2 && --leapCooldown <= 0 && dist > 5 && dist < 13 && onGround()) {
            leapCooldown = 140;
            Vec3 dir = target.position().subtract(position()).normalize();
            setDeltaMovement(dir.x * 1.1, 0.45, dir.z * 1.1);
            hasImpulse = true;
            playSound(SoundEvents.RAVAGER_ROAR, 0.7f, 1.3f);
        }
        if (skulls >= 3 && !cried && getHealth() < getMaxHealth() * 0.6f) {
            cried = true;
            warCry();
        }
        if (skulls >= 4 && --fangsCooldown <= 0 && dist < 14) {
            fangsCooldown = 180;
            fangs(target);
        }
        if (skulls >= 5 && !enraged && getHealth() < getMaxHealth() * 0.35f) {
            enraged = true;
            addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 20 * 600, 1));
            addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 20 * 600, 0));
            addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20 * 20, 1));
            playSound(SoundEvents.WITHER_SPAWN, 0.6f, 1.4f);
            if (level() instanceof ServerLevel sl) sl.sendParticles(ParticleTypes.ANGRY_VILLAGER, getX(), getY() + 2, getZ(), 12, 0.6, 0.4, 0.6, 0);
        }
    }

    private void warCry() {
        playSound(SoundEvents.RAID_HORN.value(), 4f, 1f);
        for (FighterEntity ally : level().getEntitiesOfClass(FighterEntity.class, getBoundingBox().inflate(24), this::isFriend)) {
            ally.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 20 * 25, 0));
            ally.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 20 * 25, 0));
        }
        if (level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.CLOUD, getX(), getY() + 1, getZ(), 30, 1.5, 0.5, 1.5, 0.05);
            NpcLifecycle.reinforce(sl, this, 2);
        }
    }

    private void fangs(LivingEntity target) {
        Vec3 dir = target.position().subtract(position()).multiply(1, 0, 1).normalize();
        float yaw = (float) Mth.atan2(dir.z, dir.x);
        for (int i = 1; i <= 10; i++) {
            Vec3 p = position().add(dir.scale(1.25 * i));
            level().addFreshEntity(new EvokerFangs(level(), p.x, target.getY(), p.z, yaw, i, this));
        }
        playSound(SoundEvents.EVOKER_CAST_SPELL, 1f, 0.8f);
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        spawnAtLocation(new ItemStack(ModItems.LORD_STANDARD.get()));
    }

    @Override
    protected boolean shouldDropLoot() {
        return true;
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        bossBar.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        bossBar.removePlayer(player);
    }

    @Override
    public void remove(RemovalReason reason) {
        bossBar.removeAllPlayers();
        super.remove(reason);
    }
}
