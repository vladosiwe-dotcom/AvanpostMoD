package com.vladosiwe.lordsoutpost.registry;

import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.entity.CaptainEntity;
import com.vladosiwe.lordsoutpost.entity.FighterEntity;
import com.vladosiwe.lordsoutpost.entity.WorkerEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, LordsOutpost.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<FighterEntity>> FIGHTER = ENTITIES.register("fighter",
            () -> EntityType.Builder.<FighterEntity>of(FighterEntity::new, MobCategory.MISC).sized(0.6f, 1.95f).eyeHeight(1.74f)
                    .clientTrackingRange(10).noSave().build("fighter"));
    public static final DeferredHolder<EntityType<?>, EntityType<CaptainEntity>> CAPTAIN = ENTITIES.register("captain",
            () -> EntityType.Builder.<CaptainEntity>of(CaptainEntity::new, MobCategory.MISC).sized(0.6f, 1.95f).eyeHeight(1.74f)
                    .clientTrackingRange(10).noSave().build("captain"));
    public static final DeferredHolder<EntityType<?>, EntityType<WorkerEntity>> WORKER = ENTITIES.register("worker",
            () -> EntityType.Builder.<WorkerEntity>of(WorkerEntity::new, MobCategory.MISC).sized(0.6f, 1.95f).eyeHeight(1.62f)
                    .clientTrackingRange(10).noSave().build("worker"));

    private ModEntities() {}

    public static void attributes(EntityAttributeCreationEvent event) {
        event.put(FIGHTER.get(), FighterEntity.attributes().build());
        event.put(CAPTAIN.get(), CaptainEntity.attributes().build());
        event.put(WORKER.get(), WorkerEntity.attributes().build());
    }
}
