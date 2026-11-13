package com.vladosiwe.lordsoutpost.entity;

import com.vladosiwe.lordsoutpost.entity.ai.BuildGoal;
import com.vladosiwe.lordsoutpost.entity.ai.CaravanGoal;
import com.vladosiwe.lordsoutpost.entity.ai.ShelterGoal;
import com.vladosiwe.lordsoutpost.entity.ai.WorkGoal;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Resource;
import com.vladosiwe.lordsoutpost.outpost.Role;
import com.vladosiwe.lordsoutpost.server.Interactions;
import com.vladosiwe.lordsoutpost.server.NpcLifecycle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** Мирный житель: Управляющий, строитель, охотник, скотовод, шахтёр, лесоруб, кузнец, маг, караванщик. */
public class WorkerEntity extends OutpostNpc {
    public WorkerEntity(EntityType<? extends WorkerEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder attributes() {
        return createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.ATTACK_DAMAGE, 2)
                .add(Attributes.FOLLOW_RANGE, 32);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new ShelterGoal(this));
        goalSelector.addGoal(2, new CaravanGoal(this));
        goalSelector.addGoal(3, new BuildGoal(this));
        goalSelector.addGoal(4, new WorkGoal(this));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 6f));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    @Override
    protected void equip(@Nullable Outpost o, Resident r) {
        ItemStack tool = switch (r.role) {
            case BUILDER -> new ItemStack(Items.IRON_SHOVEL);
            case HUNTER -> new ItemStack(Items.BOW);
            case HERDER -> new ItemStack(Items.WHEAT);
            case MINER -> new ItemStack(Items.IRON_PICKAXE);
            case LUMBERJACK -> new ItemStack(Items.IRON_AXE);
            case SMITH -> new ItemStack(Items.IRON_INGOT);
            case MAGE -> new ItemStack(Items.BOOK);
            case CARAVANEER -> new ItemStack(Items.LEAD);
            default -> new ItemStack(Items.WRITABLE_BOOK);
        };
        setItemSlot(EquipmentSlot.MAINHAND, tool);
        noDrops();
    }

    @Override
    public boolean isEnemy(LivingEntity other) {
        return false;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp) {
            Outpost o = outpost();
            if (o != null) Interactions.talk(sp, o, this);
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    /** Добыча охотника идёт на склад, а не падает на землю. */
    @Override
    public boolean killedEntity(ServerLevel level, LivingEntity victim) {
        if (victim instanceof Animal && role() == Role.HUNTER) {
            Outpost o = outpost();
            if (o != null) {
                o.add(Resource.FOOD, 6);
                o.add(Resource.HIDES, 2);
            }
        }
        return super.killedEntity(level, victim);
    }

    @Override
    public boolean doHurtTarget(net.minecraft.world.entity.Entity target) {
        if (target instanceof Animal) target.getPersistentData().putBoolean(NpcLifecycle.HUNTED_TAG, true);
        return super.doHurtTarget(target);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.VILLAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.VILLAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.VILLAGER_DEATH;
    }

    @Override
    public int getAmbientSoundInterval() {
        return 400;
    }
}
