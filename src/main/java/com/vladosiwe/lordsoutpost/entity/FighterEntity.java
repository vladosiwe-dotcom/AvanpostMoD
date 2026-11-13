package com.vladosiwe.lordsoutpost.entity;

import com.vladosiwe.lordsoutpost.entity.ai.DutyGoal;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Role;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Боец: стражник аванпоста игрока или мародёр дикого форта. Роль определяется выданным оружием:
 * щитовик (меч и щит), берсерк (топор), лучник, арбалетчик.
 */
public class FighterEntity extends OutpostNpc implements RangedAttackMob {
    private static final Item[][] ARMOR = {
            {Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS},
            {Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS},
            {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS},
            {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS}};
    private static final Item[] SWORDS = {Items.STONE_SWORD, Items.IRON_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD};
    private static final Item[] AXES = {Items.STONE_AXE, Items.IRON_AXE, Items.IRON_AXE, Items.DIAMOND_AXE};

    public FighterEntity(EntityType<? extends FighterEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder attributes() {
        return createMobAttributes()
                .add(Attributes.MAX_HEALTH, 24)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 3)
                .add(Attributes.FOLLOW_RANGE, 36)
                .add(Attributes.ARMOR, 0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.1);
    }

    public boolean ranged() {
        return role().ranged();
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.15, true) {
            @Override
            public boolean canUse() {
                return !ranged() && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return !ranged() && super.canContinueToUse();
            }
        });
        goalSelector.addGoal(2, new RangedAttackGoal(this, 1.0, 30, 18f) {
            @Override
            public boolean canUse() {
                return ranged() && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return ranged() && super.canContinueToUse();
            }
        });
        goalSelector.addGoal(4, new DutyGoal(this));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8f));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, LivingEntity.class, 10, true, false, this::isEnemy));
    }

    @Override
    protected void equip(@Nullable Outpost o, Resident r) {
        int gear = Math.max(0, Math.min(Resident.MAX_GEAR, r.gear));
        Item[] armor = ARMOR[gear];
        setItemSlot(EquipmentSlot.HEAD, new ItemStack(armor[0]));
        setItemSlot(EquipmentSlot.CHEST, new ItemStack(armor[1]));
        setItemSlot(EquipmentSlot.LEGS, new ItemStack(armor[2]));
        setItemSlot(EquipmentSlot.FEET, new ItemStack(armor[3]));
        setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        switch (r.role) {
            case BERSERKER -> setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(AXES[gear]));
            case ARCHER -> setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            case CROSSBOWMAN -> setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
            default -> {
                setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(SWORDS[gear]));
                setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            }
        }
        noDrops();
        double health = 24 + 6 * gear + (r.role == Role.BERSERKER ? 4 : 0);
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(r.role == Role.BERSERKER ? 0.33 : 0.3);
        // Берсерк бьёт сильнее, но топор и так даёт урон; у лучников ближний бой слабый.
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(r.role.ranged() ? 2 : 3 + Math.min(10, r.kills) * 0.1);
        boolean martial = o != null && o.state == OutpostState.OWNED && o.law == com.vladosiwe.lordsoutpost.outpost.Law.MARTIAL_LAW;
        if (martial) getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(getAttribute(Attributes.ATTACK_DAMAGE).getBaseValue() * 1.25);
    }

    /** Щитовик принимает на щит половину урона спереди. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!level().isClientSide && role() == Role.SHIELDMAN && amount > 0 && !source.is(DamageTypes.MAGIC) && !source.is(DamageTypes.FALL)
                && !source.is(DamageTypes.FELL_OUT_OF_WORLD) && !source.is(DamageTypes.GENERIC_KILL)) {
            Vec3 from = source.getSourcePosition();
            if (from != null) {
                Vec3 look = getViewVector(1f);
                Vec3 to = from.subtract(position()).multiply(1, 0, 1).normalize();
                if (to.dot(look.multiply(1, 0, 1).normalize()) > 0.3) {
                    amount *= 0.5f;
                    if (random.nextInt(3) == 0) playSound(SoundEvents.SHIELD_BLOCK, 0.8f, 0.9f + random.nextFloat() * 0.2f);
                }
            }
        }
        return super.hurt(source, amount);
    }

    @Override
    public void performRangedAttack(LivingEntity target, float power) {
        ItemStack weapon = getMainHandItem();
        boolean crossbow = weapon.is(Items.CROSSBOW);
        AbstractArrow arrow = ProjectileUtil.getMobArrow(this, new ItemStack(Items.ARROW), power, weapon);
        arrow.setBaseDamage(arrow.getBaseDamage() + (crossbow ? 1.5 : 0.5) + 0.4 * gearLevel());
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        double dx = target.getX() - getX();
        double dy = target.getY(0.3333) - arrow.getY();
        double dz = target.getZ() - getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + dist * 0.2, dz, crossbow ? 2.0f : 1.6f, crossbow ? 4f : 8f);
        playSound(crossbow ? SoundEvents.CROSSBOW_SHOOT : SoundEvents.SKELETON_SHOOT, 1f, 1f / (random.nextFloat() * 0.4f + 0.8f));
        level().addFreshEntity(arrow);
    }

    private int gearLevel() {
        Item helmet = getItemBySlot(EquipmentSlot.HEAD).getItem();
        for (int i = 0; i < ARMOR.length; i++) if (ARMOR[i][0] == helmet) return i;
        return 0;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return hostileFaction() ? SoundEvents.PILLAGER_AMBIENT : null;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PILLAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PILLAGER_DEATH;
    }

    @Override
    public int getAmbientSoundInterval() {
        return 240;
    }
}
