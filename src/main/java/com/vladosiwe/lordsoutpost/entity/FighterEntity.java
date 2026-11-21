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
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
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

    // Создаются лениво: registerGoals вызывается из конструктора Mob, раньше инициализаторов полей.
    private RangedAttackGoal rangedGoal;
    private RangedBowAttackGoal<FighterEntity> bowGoal;

    public FighterEntity(EntityType<? extends FighterEntity> type, Level level) {
        super(type, level);
    }

    /** Epic Fight заменяет ванильные цели ближнего боя своими; стрелкам возвращаем их штатную стрельбу. */
    public void ensureRangedCombatGoals() {
        if (!role().ranged()) return;
        createRangedGoals();
        boolean ranged = false, bow = false;
        for (var w : goalSelector.getAvailableGoals()) {
            if (w.getGoal() == rangedGoal) ranged = true;
            if (w.getGoal() == bowGoal) bow = true;
        }
        if (!ranged) goalSelector.addGoal(2, rangedGoal);
        if (!bow) goalSelector.addGoal(2, bowGoal);
    }

    private void createRangedGoals() {
        if (rangedGoal != null) return;
        // Арбалетчик: выстрел раз в полторы секунды.
        rangedGoal = new RangedAttackGoal(this, 1.0, 30, 18f) {
            @Override
            public boolean canUse() {
                return role() == Role.CROSSBOWMAN && ranged() && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return role() == Role.CROSSBOWMAN && ranged() && super.canContinueToUse();
            }
        };
        // Лучник натягивает лук: видно и в обычной игре, и как прицеливание Epic Fight.
        bowGoal = new RangedBowAttackGoal<>(this, 1.0, 25, 18f) {
            @Override
            public boolean canUse() {
                return role() == Role.ARCHER && ranged() && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return role() == Role.ARCHER && ranged() && super.canContinueToUse();
            }
        };
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

    /** Стреляет: стрелок с луком или арбалетом, у которого есть стрелы (у своих — счёт настоящих стрел). */
    public boolean ranged() {
        return role().ranged() && (hostileFaction() || entityData.get(AMMO) != 0);
    }

    /** Колчан стрелка из записи жителя (для подсказки над ним). */
    public void syncAmmo() {
        Resident r = resident();
        entityData.set(AMMO, hostileFaction() || r == null || !r.role.ranged() ? -1 : r.arrows);
    }

    /**
     * Приказ «Держать позицию»: боец защищает своё место — бьёт врагов в пределах 16 блоков от точки, но не уходит
     * за ними дальше; ушедшая цель бросается, и он возвращается на позицию.
     */
    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (tickCount % 10 != 0 || getTarget() == null || hostileFaction() || !(level() instanceof net.minecraft.server.level.ServerLevel sl)) return;
        Outpost o = outpost();
        var r = resident();
        if (o == null || r == null || r.squad < 0 || r.squad >= o.squads.length) return;
        var squad = o.squads[r.squad];
        if (squad.order != com.vladosiwe.lordsoutpost.outpost.Squad.Order.HOLD || squad.hold == null) return;
        net.minecraft.core.BlockPos spot = com.vladosiwe.lordsoutpost.server.NpcLifecycle.holdSpot(sl, o, squad.hold);
        double reach = ranged() ? 24 : 16;
        if (getTarget().distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5) > reach * reach) setTarget(null);
    }

    /**
     * Добыча с мародёра (идея Guard Villagers: снаряжение бойца — его добыча). Только враги дикого форта и только
     * когда их убил игрок: каждая вещь выпадает с шансом 8,5% (+1% за уровень «Добычи»), потёртой. Свои стражи
     * ничего не роняют: их снаряжение — имущество форта, и оно выдаётся заново.
     */
    @Override
    protected void dropCustomDeathLoot(net.minecraft.server.level.ServerLevel level, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        if (!hostileFaction() || !recentlyHit || !(source.getEntity() instanceof Player)) return;
        float chance = 0.085f + 0.01f * net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantmentLevel(
                level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                        .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.LOOTING), (LivingEntity) source.getEntity());
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = getItemBySlot(slot);
            if (stack.isEmpty() || random.nextFloat() >= chance) continue;
            ItemStack drop = stack.copy();
            if (drop.isDamageableItem()) {
                int max = drop.getMaxDamage();
                drop.setDamageValue(Math.max(0, max - 1 - random.nextInt(Math.max(1, max / 2))));
            }
            spawnAtLocation(drop);
            setItemSlot(slot, ItemStack.EMPTY);
        }
    }

    private static String describe(Object goal) {
        Class<?> c = goal.getClass();
        return c.isAnonymousClass() ? c.getName() + "<" + c.getSuperclass().getSimpleName() + ">" : c.getName();
    }

    /** Классы текущих целей ИИ (для проверки, что Epic Fight подменил ближний бой своими анимированными атаками). */
    public java.util.List<String> goalClassNames() {
        java.util.List<String> out = new java.util.ArrayList<>();
        goalSelector.getAvailableGoals().forEach(w -> out.add((w.isRunning() ? "*" : "") + describe(w.getGoal())));
        targetSelector.getAvailableGoals().forEach(w -> out.add((w.isRunning() ? "*" : "") + describe(w.getGoal())));
        return out;
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.OpenDoorGoal(this, true));
        goalSelector.addGoal(1, new com.vladosiwe.lordsoutpost.entity.ai.OpenGateGoal(this));
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
        createRangedGoals();
        goalSelector.addGoal(1, new com.vladosiwe.lordsoutpost.entity.ai.ShieldLineGoal(this));
        goalSelector.addGoal(2, rangedGoal);
        goalSelector.addGoal(2, bowGoal);
        goalSelector.addGoal(4, new DutyGoal(this));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8f));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, LivingEntity.class, 10, true, false, this::isEnemy));
    }

    @Override
    protected void equip(@Nullable Outpost o, Resident r) {
        entityData.set(AMMO, o == null || o.state != OutpostState.OWNED || !r.role.ranged() ? -1 : r.arrows);
        int gear = Math.max(0, Math.min(Resident.MAX_GEAR, r.gear));
        Item[] armor = ARMOR[gear];
        setItemSlot(EquipmentSlot.HEAD, armorStack(armor[0], r.armor));
        setItemSlot(EquipmentSlot.CHEST, armorStack(armor[1], r.armor));
        setItemSlot(EquipmentSlot.LEGS, armorStack(armor[2], r.armor));
        setItemSlot(EquipmentSlot.FEET, armorStack(armor[3], r.armor));
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

    private static ItemStack armorStack(Item item, float condition) {
        ItemStack stack = new ItemStack(item);
        if (stack.isDamageableItem()) {
            float safe = Math.max(0f, Math.min(1f, condition));
            stack.setDamageValue(Math.min(stack.getMaxDamage() - 1, Math.round(stack.getMaxDamage() * (1f - safe))));
        }
        return stack;
    }

    /** Обновляет экипировку уже загруженного бойца после ремонта или перековки кузнецом. */
    public void refreshResidentEquipment() {
        Resident r = resident();
        if (r == null) return;
        equip(outpost(), r);
        setHealth(Math.min(getHealth(), getMaxHealth()));
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
        // Свои стрелки тратят настоящие стрелы со склада; кончились — стрелять нечем.
        if (!hostileFaction()) {
            Resident r = resident();
            if (r != null) {
                if (r.arrows <= 0) {
                    syncAmmo();
                    return;
                }
                r.arrows--;
                syncAmmo();
            }
        }
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
