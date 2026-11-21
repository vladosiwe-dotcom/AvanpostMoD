package com.vladosiwe.lordsoutpost.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.client.gui.Ui;
import com.vladosiwe.lordsoutpost.entity.OutpostNpc;
import com.vladosiwe.lordsoutpost.outpost.Role;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Что делают жители — на виду. Над кузнецом за работой: значок изделия (инструмент, оружие, стрелы, доспех) и
 * полоска справа — сколько времени уже вложено в поковку. При наведении прицела на жителя — подсказка у прицела:
 * у кузнеца — что он куёт и сколько готово, у стрелка — стрелы в колчане.
 */
@EventBusSubscriber(modid = LordsOutpost.MODID, value = Dist.CLIENT)
public final class NpcOverlay {
    private NpcOverlay() {}

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.options.hideGui) return;
        Vec3 cam = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        boolean any = false;
        for (OutpostNpc npc : mc.level.getEntitiesOfClass(OutpostNpc.class, mc.player.getBoundingBox().inflate(24),
                n -> !n.craftItem().isEmpty() && n.isAlive())) {
            any = true;
            Vec3 p = npc.getPosition(partial);
            pose.pushPose();
            pose.translate(p.x - cam.x, p.y - cam.y + npc.getBbHeight() + 0.75, p.z - cam.z);
            pose.mulPose(event.getCamera().rotation());
            pose.scale(-0.025f, -0.025f, 0.025f);
            // Подложка, значок изделия слева, полоска прогресса справа.
            Matrix4f m = pose.last().pose();
            VertexConsumer bg = buffers.getBuffer(RenderType.textBackgroundSeeThrough());
            quad(bg, m, -14, -8, 30, 8, 0x90101620);
            quad(bg, m, 4, -2, 28, 2, 0xFF101010);
            quad(bg, m, 4, -2, 4 + (int) (24 * npc.craftProgress()), 2, 0xFF6FCF6F);
            pose.pushPose();
            pose.translate(-6, 0, 0);
            pose.scale(-14f, -14f, 14f);
            mc.getItemRenderer().renderStatic(npc.craftItem(), ItemDisplayContext.GUI, 0xF000F0, OverlayTexture.NO_OVERLAY, pose, buffers, mc.level, 0);
            pose.popPose();
            pose.popPose();
        }
        if (any) buffers.endBatch();
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, int argb) {
        int a = argb >>> 24, r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        vc.addVertex(m, x0, y0, 0).setColor(r, g, b, a).setLight(0xF000F0);
        vc.addVertex(m, x0, y1, 0).setColor(r, g, b, a).setLight(0xF000F0);
        vc.addVertex(m, x1, y1, 0).setColor(r, g, b, a).setLight(0xF000F0);
        vc.addVertex(m, x1, y0, 0).setColor(r, g, b, a).setLight(0xF000F0);
    }

    /** Подсказка у прицела, когда он наведён на жителя. */
    public static void renderHover(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen != null || !(mc.crosshairPickEntity instanceof OutpostNpc npc)) return;
        int x = g.guiWidth() / 2 + 10, y = g.guiHeight() / 2 + 6;
        ItemStack craft = npc.craftItem();
        if (!craft.isEmpty()) {
            Component line = Component.translatable("lordsoutpost.hover.forging", craft.getHoverName(), craft.getCount(), Math.round(npc.craftProgress() * 100));
            box(g, mc, craft, line, x, y);
            y += 20;
        }
        if (npc.ammo() >= 0) {
            Component line = npc.ammo() == 0 ? Component.translatable("lordsoutpost.hover.no_arrows")
                    : Component.translatable("lordsoutpost.hover.arrows", npc.ammo());
            box(g, mc, new ItemStack(Items.ARROW), line, x, y);
        } else if (craft.isEmpty() && npc.role() == Role.SMITH) {
            box(g, mc, new ItemStack(Items.ANVIL), Component.translatable("lordsoutpost.hover.smith_idle"), x, y);
        }
    }

    private static void box(GuiGraphics g, Minecraft mc, ItemStack icon, Component text, int x, int y) {
        int w = mc.font.width(text) + 24;
        g.fill(x, y, x + w, y + 18, 0xB0101620);
        g.fill(x, y, x + 2, y + 18, Ui.ACCENT);
        g.renderItem(icon, x + 3, y + 1);
        g.drawString(mc.font, text, x + 21, y + 5, Ui.TEXT, true);
    }
}
