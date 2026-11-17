package com.vladosiwe.lordsoutpost.compat;

import com.vladosiwe.lordsoutpost.client.render.FighterRenderer;
import com.vladosiwe.lordsoutpost.client.render.MarauderModel;
import com.vladosiwe.lordsoutpost.entity.FighterEntity;
import com.vladosiwe.lordsoutpost.registry.ModEntities;
import net.minecraft.world.entity.EntityType;
import yesman.epicfight.api.client.event.EpicFightClientEventHooks;
import yesman.epicfight.api.client.event.types.registry.RegisterPatchedRenderersEvent;
import yesman.epicfight.api.client.model.Meshes;
import yesman.epicfight.client.mesh.HumanoidMesh;
import yesman.epicfight.client.renderer.patched.entity.PHumanoidRenderer;

/** Client-only renderer registration; this class is loaded reflectively only when Epic Fight is present. */
public final class EpicFightClientCompat {
    private EpicFightClientCompat() {}

    public static void register() {
        EpicFightClientEventHooks.Registry.ADD_PATCHED_ENTITY.registerEvent(EpicFightClientCompat::registerRenderers);
    }

    private static void registerRenderers(RegisterPatchedRenderersEvent.AddEntity event) {
        event.addPatchedEntityRenderer(ModEntities.FIGHTER.get(), type -> new OutpostFighterRenderer(event.getContext(), type));
        event.addPatchedEntityRenderer(ModEntities.CAPTAIN.get(), type -> new OutpostFighterRenderer(event.getContext(), type));
    }

    private static final class OutpostFighterRenderer extends PHumanoidRenderer<
            FighterEntity, OutpostFighterPatch<FighterEntity>, MarauderModel<FighterEntity>, FighterRenderer, HumanoidMesh> {
        private OutpostFighterRenderer(net.minecraft.client.renderer.entity.EntityRendererProvider.Context context, EntityType<?> entityType) {
            super(Meshes.BIPED, context, entityType);
            initLayerLast(context, entityType);
        }
    }
}
