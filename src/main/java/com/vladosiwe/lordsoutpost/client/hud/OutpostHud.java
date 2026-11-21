package com.vladosiwe.lordsoutpost.client.hud;

import com.vladosiwe.lordsoutpost.client.ClientState;
import com.vladosiwe.lordsoutpost.client.gui.Ui;
import com.vladosiwe.lordsoutpost.outpost.Resource;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * Сводка своего владения — карточка в стиле форта: каменная рамка, герб тира, полоса стройки и строка значков
 * (жители, еда, дерево, камень, железо). Тревоги — отдельной строкой и только когда что-то не так: набег, голод,
 * нехватка, проблемы рабочих зон. Видна на территории своего аванпоста, когда не открыт экран.
 * Клавиша H (настраивается): полная → компактная (только герб и тревоги) → скрыта.
 */
public final class OutpostHud {
    private OutpostHud() {}

    private static int mode;

    public static void cycleMode() {
        mode = (mode + 1) % 3;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.translatable("lordsoutpost.hud.mode." + mode), true);
    }

    private static final int STONE = 0xFF4A4F57, STONE_DARK = 0xFF2B2F36, WOOD = 0xE01A140E, GOLD = 0xFFE2B44C;
    private static final String[] NUMERALS = {"I", "II", "III", "IV"};

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mode == 2 || mc.player == null || mc.level == null || mc.options.hideGui || mc.screen != null || mc.getDebugOverlay().showDebugScreen()) return;
        if (mc.level.dimension() != Level.OVERWORLD) return;
        ClientState.Marker own = ClientState.ownMarker();
        CompoundTag h = ClientState.hud;
        if (own == null || h.isEmpty() || !own.contains(mc.player.blockPosition())) return;
        Font font = mc.font;
        boolean compact = mode == 1;

        // Тревоги: только то, что требует внимания.
        Component alert = null;
        int alertColor = Ui.WARN;
        if (h.getBoolean("raid")) {
            alert = Component.translatable("lordsoutpost.hud.raid");
            alertColor = (System.currentTimeMillis() / 400) % 2 == 0 ? Ui.BAD : 0xFFFF9C8C;
        } else if (h.getBoolean("starving")) {
            alert = Component.translatable("lordsoutpost.hud.famine");
            alertColor = Ui.BAD;
        } else if (h.contains("scarce")) {
            Resource r = Resource.byId(h.getString("scarce"));
            if (r != null) alert = Component.translatable("lordsoutpost.hud.scarce", r.label());
        } else if (h.getInt("problems") > 0) {
            alert = Component.translatable("lordsoutpost.hud.problems", h.getInt("problems"));
        }

        int tier = Math.max(1, Math.min(4, h.getByte("tier")));
        boolean done = h.getBoolean("complete");
        float progress = h.getFloat("progress");
        Component title = Component.literal(own.name.isEmpty() ? "—" : own.name);
        int w = Math.max(compact ? 0 : 150, font.width(title) + 34);
        if (alert != null) w = Math.max(w, font.width(alert) + 14);
        int x = 4, y = 4;
        int hgt = 20 + (compact ? 0 : 22) + (alert != null ? 11 : 0);

        // Рамка: камень снаружи, тёмное дерево внутри, золотые уголки.
        g.fill(x - 1, y - 1, x + w + 1, y + hgt + 1, STONE_DARK);
        g.fill(x, y, x + w, y + hgt, STONE);
        g.fill(x + 1, y + 1, x + w - 1, y + hgt - 1, WOOD);
        for (int[] c : new int[][]{{x, y}, {x + w - 3, y}, {x, y + hgt - 3}, {x + w - 3, y + hgt - 3}}) g.fill(c[0], c[1], c[0] + 3, c[1] + 3, GOLD);

        // Герб тира: щит с римской цифрой.
        int sx = x + 4, sy = y + 3;
        g.fill(sx, sy, sx + 16, sy + 11, 0xFF6B1E22);
        g.fill(sx + 2, sy + 11, sx + 14, sy + 13, 0xFF6B1E22);
        g.fill(sx + 5, sy + 13, sx + 11, sy + 15, 0xFF6B1E22);
        String num = NUMERALS[tier - 1];
        g.drawString(font, num, sx + 8 - font.width(num) / 2, sy + 2, GOLD, false);
        g.drawString(font, title, x + 24, y + 4, GOLD, true);
        // Полоса стройки следующего тира (или «завершён»).
        int bx = x + 24, bw = w - 30;
        if (done) {
            g.drawString(font, Component.translatable("lordsoutpost.hud.tier_done_short"), bx, y + 13, Ui.MUTED, false);
        } else {
            g.fill(bx, y + 14, bx + bw, y + 17, 0xFF101010);
            g.fill(bx, y + 14, bx + (int) (bw * Math.max(0, Math.min(1, progress))), y + 17, Ui.GOOD);
        }
        int ry = y + 20;
        if (!compact) {
            // Значки: жители, еда (дни), дерево, камень, железо.
            int ix = x + 5;
            ix = chip(g, font, new ItemStack(Items.PLAYER_HEAD), h.getInt("pop") + "/" + h.getInt("popCap"), ix, ry, Ui.TEXT);
            float food = h.getFloat("foodDays");
            ix = chip(g, font, new ItemStack(Items.BREAD), String.format("%.1f", food), ix, ry, h.getBoolean("starving") ? Ui.BAD : food < 2 ? Ui.WARN : Ui.GOOD);
            ix = chip(g, font, new ItemStack(Items.OAK_LOG), compactNum(h.getInt("wood")), ix, ry, Ui.TEXT);
            ix = chip(g, font, new ItemStack(Items.COBBLESTONE), compactNum(h.getInt("stone")), ix, ry, Ui.TEXT);
            chip(g, font, new ItemStack(Items.IRON_INGOT), compactNum(h.getInt("iron")), ix, ry, Ui.TEXT);
            ry += 22;
        }
        if (alert != null) g.drawString(font, alert, x + 6, ry, alertColor, true);
    }

    private static int chip(GuiGraphics g, Font font, ItemStack icon, String text, int x, int y, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75f, 0.75f, 1f);
        g.renderItem(icon, 0, 0);
        g.pose().popPose();
        g.drawString(font, text, x + 13, y + 3, color, true);
        return x + 15 + font.width(text) + 4;
    }

    private static String compactNum(int n) {
        return n >= 10000 ? (n / 1000) + "k" : n >= 1000 ? String.format("%.1fk", n / 1000f) : String.valueOf(n);
    }
}
