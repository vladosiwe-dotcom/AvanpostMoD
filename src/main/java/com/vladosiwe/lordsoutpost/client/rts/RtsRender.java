package com.vladosiwe.lordsoutpost.client.rts;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.client.gui.OutpostView;
import com.vladosiwe.lordsoutpost.outpost.Project;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;

/** Предпросмотр в мире: полупрозрачная модель выбранной постройки (зелёная/красная) и контуры принятых заказов. */
@EventBusSubscriber(modid = LordsOutpost.MODID, value = Dist.CLIENT)
public final class RtsRender {
    private static int frames;

    private RtsRender() {}

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || !RtsCamera.active()) return;
        RtsScreen screen = RtsScreen.current;
        if (screen == null) return;
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = event.getCamera().getPosition();
        // После частиц поворот камеры уже в матрице вида RenderSystem; здесь — только сдвиг к камере.
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        // Принятые заказы: голубой контур по блокам постройки.
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (OutpostView.ProjectInfo p : screen.activeOrders()) {
            Project.Type type = Project.Type.byId(p.type());
            if (type == null) continue;
            AABB box = bounds(screen.cells(type, new BlockPos(p.x(), p.y(), p.z()), p.rot()));
            if (box != null) LevelRenderer.renderLineBox(pose, lines, box, 0.35f, 0.7f, 1f, 0.9f);
        }
        List<BlockPos> ghost = screen.ghostCells();
        int state = screen.ghostState();
        if (ghost != null && frames++ % 300 == 0) {
            LordsOutpost.LOGGER.info("RTS: предпросмотр {} клеток, первая {}, камера {}, состояние {}", ghost.size(),
                    ghost.isEmpty() ? "-" : ghost.get(0).toShortString(), cam, state);
        }
        float r = state == 1 ? 0.25f : state == 0 ? 0.95f : 0.85f;
        float g = state == 1 ? 0.95f : state == 0 ? 0.25f : 0.85f;
        float b = state == 1 ? 0.35f : state == 0 ? 0.25f : 0.35f;
        if (ghost != null) {
            AABB box = bounds(ghost);
            if (box != null) LevelRenderer.renderLineBox(pose, lines, box, r, g, b, 1f);
        }
        buffers.endBatch(RenderType.lines());
        if (ghost != null) {
            VertexConsumer fill = buffers.getBuffer(RenderType.debugFilledBox());
            for (BlockPos p : ghost) {
                LevelRenderer.addChainedFilledBoxVertices(pose, fill, p.getX() + 0.08, p.getY() + 0.08, p.getZ() + 0.08,
                        p.getX() + 0.92, p.getY() + 0.92, p.getZ() + 0.92, r, g, b, 0.35f);
            }
            buffers.endBatch(RenderType.debugFilledBox());
        }
        pose.popPose();
    }

    private static AABB bounds(List<BlockPos> cells) {
        if (cells.isEmpty()) return null;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (BlockPos p : cells) {
            x0 = Math.min(x0, p.getX());
            y0 = Math.min(y0, p.getY());
            z0 = Math.min(z0, p.getZ());
            x1 = Math.max(x1, p.getX());
            y1 = Math.max(y1, p.getY());
            z1 = Math.max(z1, p.getZ());
        }
        return new AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
    }
}
