package com.vladosiwe.lordsoutpost.client.render;

import com.vladosiwe.lordsoutpost.entity.OutpostNpc;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/**
 * Модель бойца: человекоподобная (свободные руки держат оружие и щит, работает слой брони),
 * но с развёрткой текстур разбойника и жителя — у них голова, тело и ноги лежат в одних и тех же местах текстуры.
 * Благодаря этому одна модель рисуется и текстурой разбойника, и головой жителя (гибрид 50/50).
 */
public class MarauderModel<T extends OutpostNpc> extends HumanoidModel<T> {
    public MarauderModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0f);
        PartDefinition root = mesh.getRoot();
        PartDefinition head = root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0).addBox(-4f, -10f, -4f, 8f, 10f, 8f), PartPose.ZERO);
        head.addOrReplaceChild("nose", CubeListBuilder.create().texOffs(24, 0).addBox(-1f, -1f, -6f, 2f, 4f, 2f), PartPose.offset(0f, -2f, 0f));
        root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("body", CubeListBuilder.create()
                .texOffs(16, 20).addBox(-4f, 0f, -3f, 8f, 12f, 6f)
                .texOffs(0, 38).addBox(-4f, 0f, -3f, 8f, 20f, 6f, new CubeDeformation(0.5f)), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(40, 46).addBox(-3f, -2f, -2f, 4f, 12f, 4f), PartPose.offset(-5f, 2f, 0f));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(40, 46).mirror().addBox(-1f, -2f, -2f, 4f, 12f, 4f), PartPose.offset(5f, 2f, 0f));
        root.addOrReplaceChild("right_leg", CubeListBuilder.create().texOffs(0, 22).addBox(-2f, 0f, -2f, 4f, 12f, 4f), PartPose.offset(-2f, 12f, 0f));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(0, 22).mirror().addBox(-2f, 0f, -2f, 4f, 12f, 4f), PartPose.offset(2f, 12f, 0f));
        return LayerDefinition.create(mesh, 64, 64);
    }
}
