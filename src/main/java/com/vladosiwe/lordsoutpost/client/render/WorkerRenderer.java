package com.vladosiwe.lordsoutpost.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vladosiwe.lordsoutpost.entity.WorkerEntity;
import com.vladosiwe.lordsoutpost.outpost.Role;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.CrossedArmsItemLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;

/** Мирные жители выглядят как жители деревни: одежда по палитре форта, наряд по профессии, инструмент в руках. */
public class WorkerRenderer extends MobRenderer<WorkerEntity, VillagerModel<WorkerEntity>> {
    private static final ResourceLocation BASE = ResourceLocation.withDefaultNamespace("textures/entity/villager/villager.png");
    private static final String[] TYPES = {"plains", "taiga", "desert", "swamp"};

    public WorkerRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new VillagerModel<>(ctx.bakeLayer(ModelLayers.VILLAGER)), 0.5f);
        addLayer(new ClothesLayer(this));
        addLayer(new CrossedArmsItemLayer<>(this, ctx.getItemInHandRenderer()));
    }

    @Override
    public ResourceLocation getTextureLocation(WorkerEntity entity) {
        return BASE;
    }

    private static String profession(Role role) {
        return switch (role) {
            case STEWARD -> "cartographer";
            case BUILDER -> "mason";
            case HUNTER -> "fletcher";
            case HERDER -> "shepherd";
            case MINER -> "toolsmith";
            case LUMBERJACK -> "farmer";
            case SMITH -> "armorer";
            case MAGE -> "cleric";
            case CARAVANEER -> "leatherworker";
            default -> "nitwit";
        };
    }

    private static final class ClothesLayer extends RenderLayer<WorkerEntity, VillagerModel<WorkerEntity>> {
        ClothesLayer(RenderLayerParent<WorkerEntity, VillagerModel<WorkerEntity>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffer, int light, WorkerEntity entity, float limbSwing, float limbSwingAmount,
                           float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
            if (entity.isInvisible()) return;
            VillagerModel<WorkerEntity> model = getParentModel();
            String type = TYPES[Math.max(0, Math.min(TYPES.length - 1, entity.style()))];
            int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0f);
            model.renderToBuffer(pose, buffer.getBuffer(RenderType.entityCutoutNoCull(
                    ResourceLocation.withDefaultNamespace("textures/entity/villager/type/" + type + ".png"))), light, overlay, -1);
            model.renderToBuffer(pose, buffer.getBuffer(RenderType.entityCutoutNoCull(
                    ResourceLocation.withDefaultNamespace("textures/entity/villager/profession/" + profession(entity.role()) + ".png"))), light, overlay, -1);
        }
    }
}
