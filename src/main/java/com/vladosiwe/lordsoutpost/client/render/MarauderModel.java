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
    /** Длинная куртка разбойника: под нагрудником и поножами её прячут, иначе она мерцает сквозь броню. */
    public final ModelPart robe;
    /** Нос торчит сквозь шлем — под шлемом его не рисуют. */
    public final ModelPart nose;

    public MarauderModel(ModelPart root) {
        super(root);
        robe = body.getChild("robe");
        nose = head.getChild("nose");
    }

    /**
     * Под Epic Fight тело рисует его скелетная сетка игрока, а эта модель служит лишь «родителем» для слоя брони:
     * Epic Fight печёт сетку брони ({@code HumanoidModelBaker.bakeArmor}), подгоняя её по частям родителя, и потом
     * привязывает к костям — так броня следует за всеми его анимациями. Поэтому под ним размеры частей — ровно как
     * у игрока (голова 8, туловище 8×12×4): с головой разбойника в 10 пикселей шлем пёкся на 2 пикселя выше и висел
     * над головой, с туловищем глубиной 6 — нагрудник был толще тела.
     */
    private static final boolean EPIC_FIGHT = net.neoforged.fml.ModList.get().isLoaded("epicfight");

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0f);
        PartDefinition root = mesh.getRoot();
        if (EPIC_FIGHT) {
            PartDefinition head = root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0).addBox(-4f, -8f, -4f, 8f, 8f, 8f), PartPose.ZERO);
            head.addOrReplaceChild("nose", CubeListBuilder.create(), PartPose.ZERO);
            PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(16, 16).addBox(-4f, 0f, -2f, 8f, 12f, 4f), PartPose.ZERO);
            body.addOrReplaceChild("robe", CubeListBuilder.create(), PartPose.ZERO);
            return LayerDefinition.create(mesh, 64, 64);
        }
        PartDefinition head = root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0).addBox(-4f, -10f, -4f, 8f, 10f, 8f), PartPose.ZERO);
        head.addOrReplaceChild("nose", CubeListBuilder.create().texOffs(24, 0).addBox(-1f, -1f, -6f, 2f, 4f, 2f), PartPose.offset(0f, -2f, 0f));
        root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create()
                .texOffs(16, 20).addBox(-4f, 0f, -3f, 8f, 12f, 6f), PartPose.ZERO);
        body.addOrReplaceChild("robe", CubeListBuilder.create()
                .texOffs(0, 38).addBox(-4f, 0f, -3f, 8f, 20f, 6f, new CubeDeformation(0.5f)), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(40, 46).addBox(-3f, -2f, -2f, 4f, 12f, 4f), PartPose.offset(-5f, 2f, 0f));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(40, 46).mirror().addBox(-1f, -2f, -2f, 4f, 12f, 4f), PartPose.offset(5f, 2f, 0f));
        root.addOrReplaceChild("right_leg", CubeListBuilder.create().texOffs(0, 22).addBox(-2f, 0f, -2f, 4f, 12f, 4f), PartPose.offset(-2f, 12f, 0f));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(0, 22).mirror().addBox(-2f, 0f, -2f, 4f, 12f, 4f), PartPose.offset(2f, 12f, 0f));
        return LayerDefinition.create(mesh, 64, 64);
    }
}
