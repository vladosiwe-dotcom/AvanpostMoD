package com.vladosiwe.lordsoutpost.compat;

import com.google.common.collect.ImmutableMap;
import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.entity.FighterEntity;
import com.vladosiwe.lordsoutpost.registry.ModEntities;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import yesman.epicfight.api.animation.Animator;
import yesman.epicfight.api.animation.LivingMotions;
import yesman.epicfight.api.event.EpicFightEventHooks;
import yesman.epicfight.api.event.types.registry.EntityPatchRegistryEvent;
import yesman.epicfight.gameasset.Animations;
import yesman.epicfight.gameasset.Armatures;
import yesman.epicfight.gameasset.MobCombatBehaviors;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.Factions;
import yesman.epicfight.world.capabilities.entitypatch.MobPatch;
import yesman.epicfight.world.capabilities.entitypatch.mob.AbstractIllagerPatch;
import yesman.epicfight.world.capabilities.item.CapabilityItem;
import yesman.epicfight.world.capabilities.item.CapabilityItem.WeaponCategories;

/** Optional server/common integration with Epic Fight 21.17.x for Minecraft 1.21.1. */
public final class EpicFightCompat {
    public static final String MODID = "epicfight";
    private EpicFightCompat() {}

    public static void register(IEventBus modBus) {
        EpicFightEventHooks.Registry.ENTITY_PATCH.registerEvent(EpicFightCompat::registerEntityPatches);
        modBus.addListener(EpicFightCompat::commonSetup);
        LordsOutpost.LOGGER.info("Epic Fight detected: Lord's Outpost fighter patches enabled");
    }

    private static void registerEntityPatches(EntityPatchRegistryEvent event) {
        event.registerEntityPatch(ModEntities.FIGHTER.get(), OutpostFighterPatch::new);
        event.registerEntityPatch(ModEntities.CAPTAIN.get(), OutpostFighterPatch::new);
    }

    private static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            Armatures.registerEntityTypeArmature(ModEntities.FIGHTER.get(), Armatures.BIPED);
            Armatures.registerEntityTypeArmature(ModEntities.CAPTAIN.get(), Armatures.BIPED);
        });
    }

    public static String patchStatus(FighterEntity fighter) {
        MobPatch<?> patch = EpicFightCapabilities.getEntityPatch(fighter, MobPatch.class);
        return patch == null ? "EF patch=MISSING" : "EF patch=" + patch.getClass().getSimpleName();
    }
}

/** Melee uses Epic Fight animations; ranged roles keep the outpost's own projectile goals. */
final class OutpostFighterPatch<T extends FighterEntity> extends AbstractIllagerPatch<T> {
    OutpostFighterPatch(T original) { super(original, Factions.ILLAGER); }

    @Override
    protected void setWeaponMotions() {
        super.setWeaponMotions();
        this.weaponAttackMotions.put(WeaponCategories.AXE, ImmutableMap.of(
                CapabilityItem.Styles.COMMON, MobCombatBehaviors.VINDICATOR_ONEHAND));
        this.weaponAttackMotions.put(WeaponCategories.SWORD, ImmutableMap.of(
                CapabilityItem.Styles.COMMON, MobCombatBehaviors.VINDICATOR_ONEHAND));
    }

    @Override
    protected void initAI() {
        super.initAI();
        if (this.original.ranged()) this.original.ensureRangedCombatGoals();
    }

    @Override
    public void setAIAsInfantry(boolean holdingRangedWeapon) {
        if (!this.original.ranged()) super.setAIAsInfantry(false);
    }

    @Override
    public void setAIAsMounted(Entity ridingEntity) {
        // Outpost fighters do not mount.
    }

    @Override
    public void initAnimator(Animator animator) {
        super.initAnimator(animator);
        animator.addLivingAnimation(LivingMotions.CHASE, Animations.ILLAGER_WALK);
        animator.addLivingAnimation(LivingMotions.FALL, Animations.BIPED_FALL);
        animator.addLivingAnimation(LivingMotions.MOUNT, Animations.BIPED_MOUNT);
    }

    @Override
    public void updateMotion(boolean considerInaction) {
        if (this.original.ranged()) super.commonAggressiveRangedMobUpdateMotion(considerInaction);
        else super.commonAggressiveMobUpdateMotion(considerInaction);
    }
}
