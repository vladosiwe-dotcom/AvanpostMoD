package com.vladosiwe.lordsoutpost.client.hud;

import com.vladosiwe.lordsoutpost.client.ClientState;
import com.vladosiwe.lordsoutpost.outpost.Difficulty;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Баннер «Найден Аванпост»: название по сложности и черепки. Появляется, держится четыре секунды и гаснет. */
public final class DiscoveryBanner {
    private static final int DURATION = 110;
    private static final ItemStack SKULL = new ItemStack(Items.SKELETON_SKULL);

    private DiscoveryBanner() {}

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.hideGui) return;
        float age = mc.level.getGameTime() - ClientState.bannerStart + delta.getGameTimeDeltaPartialTick(false);
        if (age < 0 || age > DURATION) return;
        float alpha = Mth.clamp(Math.min(age / 10f, (DURATION - age) / 20f), 0f, 1f);
        if (alpha < 0.03f) return;
        Font font = mc.font;
        int skulls = Mth.clamp(ClientState.bannerSkulls, 1, 5);
        Component title = Component.translatable("lordsoutpost.hud.found");
        Component name = Difficulty.bySkulls(skulls).label();
        int w = Math.max(Math.max(font.width(title) * 3 / 2, font.width(name)), skulls * 18) + 36;
        int x = (g.guiWidth() - w) / 2, y = 26 - Math.round((1 - alpha) * 10);
        int a = Math.round(alpha * 255);
        g.fill(x, y, x + w, y + 58, (Math.round(alpha * 190) << 24) | 0x10141C);
        g.fill(x, y, x + w, y + 1, (a << 24) | 0xE2B44C);
        g.fill(x, y + 57, x + w, y + 58, (a << 24) | 0xE2B44C);
        g.pose().pushPose();
        g.pose().translate(g.guiWidth() / 2f, y + 6, 0);
        g.pose().scale(1.5f, 1.5f, 1f);
        g.drawString(font, title, -font.width(title) / 2, 0, (a << 24) | 0xE2B44C, true);
        g.pose().popPose();
        g.drawString(font, name, (g.guiWidth() - font.width(name)) / 2, y + 23, (a << 24) | 0xE8ECF4, true);
        if (alpha > 0.5f) {
            int sx = (g.guiWidth() - skulls * 18) / 2;
            for (int i = 0; i < skulls; i++) g.renderItem(SKULL, sx + i * 18 + 1, y + 36);
        }
    }
}
