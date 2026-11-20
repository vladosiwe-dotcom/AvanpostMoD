package com.vladosiwe.lordsoutpost.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.entity.FighterEntity;
import com.vladosiwe.lordsoutpost.outpost.Role;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Отрисовка бойцов. Мародёры дикого форта — разбойники; стража игрока — гибрид: тело разбойника,
 * голова жителя (вторая отрисовка той же модели с текстурой жителя, видна только голова).
 */
public class FighterRenderer extends HumanoidMobRenderer<FighterEntity, MarauderModel<FighterEntity>> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(LordsOutpost.id("fighter"), "main");
    private static final ResourceLocation PILLAGER = ResourceLocation.withDefaultNamespace("textures/entity/illager/pillager.png");
    private static final ResourceLocation VINDICATOR = ResourceLocation.withDefaultNamespace("textures/entity/illager/vindicator.png");
    private static final ResourceLocation VILLAGER = ResourceLocation.withDefaultNamespace("textures/entity/villager/villager.png");

    public FighterRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new MarauderModel<>(ctx.bakeLayer(LAYER)), 0.5f);
        // Ванильный слой брони Epic Fight подменяет на WearableItemLayer по своему biped-скелету.
        addLayer(new HumanoidArmorLayer<>(this, new TallHeadArmorModel(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new TallHeadArmorModel(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), ctx.getModelManager()));
        addLayer(new VillagerHeadLayer(this));
    }

    @Override
    public ResourceLocation getTextureLocation(FighterEntity entity) {
        if (entity.role() == Role.CAPTAIN) return VINDICATOR;
        return entity.hostileFaction() ? PILLAGER : VINDICATOR;
    }

    private static boolean hybrid(FighterEntity entity) {
        return !entity.hostileFaction();
    }

    @Override
    public void render(FighterEntity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffer, int light) {
        ItemStack main = entity.getMainHandItem();
        boolean aggressive = entity.isAggressive();
        model.rightArmPose = main.is(Items.BOW) && aggressive ? HumanoidModel.ArmPose.BOW_AND_ARROW
                : main.is(Items.CROSSBOW) ? (aggressive ? HumanoidModel.ArmPose.CROSSBOW_HOLD : HumanoidModel.ArmPose.ITEM)
                : main.isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        model.leftArmPose = entity.getOffhandItem().is(Items.SHIELD) ? (aggressive ? HumanoidModel.ArmPose.BLOCK : HumanoidModel.ArmPose.ITEM)
                : HumanoidModel.ArmPose.EMPTY;
        model.setAllVisible(true);
        // Куртка — под бронёй не видна (иначе она совпадает с поножами и мерцает), нос — под шлемом.
        model.robe.visible = entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).isEmpty()
                && entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS).isEmpty();
        model.nose.visible = entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty();
        // У гибрида голову разбойника не рисуем: её заменит голова жителя.
        model.head.visible = !hybrid(entity);
        super.render(entity, yaw, partialTick, pose, buffer, light);
        model.head.visible = true;
    }

    /**
     * Модель брони для головы высотой 10 пикселей (у разбойника и жителя), а не 8, как у игрока: шлем вытягивается
     * от шеи вверх, чтобы голова не торчала сквозь него.
     */
    private static final class TallHeadArmorModel extends HumanoidModel<FighterEntity> {
        TallHeadArmorModel(net.minecraft.client.model.geom.ModelPart root) {
            super(root);
        }

        @Override
        public void renderToBuffer(PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer buffer, int light, int overlay, int color) {
            float hx = head.xScale, hy = head.yScale, hz = head.zScale;
            head.yScale = hy * 1.25f;
            head.xScale = hx * 1.04f;
            head.zScale = hz * 1.04f;
            hat.xScale = head.xScale;
            hat.yScale = head.yScale;
            hat.zScale = head.zScale;
            super.renderToBuffer(pose, buffer, light, overlay, color);
            head.xScale = hat.xScale = hx;
            head.yScale = hat.yScale = hy;
            head.zScale = hat.zScale = hz;
        }
    }

    private static final class VillagerHeadLayer extends RenderLayer<FighterEntity, MarauderModel<FighterEntity>> {
        VillagerHeadLayer(RenderLayerParent<FighterEntity, MarauderModel<FighterEntity>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffer, int light, FighterEntity entity, float limbSwing, float limbSwingAmount,
                           float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
            if (!hybrid(entity) || entity.isInvisible()) return;
            MarauderModel<FighterEntity> model = getParentModel();
            model.setAllVisible(false);
            model.head.visible = true;
            model.renderToBuffer(pose, buffer.getBuffer(RenderType.entityCutoutNoCull(VILLAGER)), light, LivingEntityRenderer.getOverlayCoords(entity, 0f), -1);
            model.setAllVisible(true);
            model.head.visible = false;
        }
    }
}
