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
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Сводка своего владения в левом верхнем углу: видна, только пока Лорд на территории аванпоста
 * и не открыт никакой экран. Тир и стройка, жители, еда, дефицит и тревоги — то, ради чего иначе
 * пришлось бы открывать окно управления.
 */
public final class OutpostHud {
    private OutpostHud() {}

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui || mc.screen != null || mc.getDebugOverlay().showDebugScreen()) return;
        if (mc.level.dimension() != Level.OVERWORLD) return;
        ClientState.Marker own = ClientState.ownMarker();
        CompoundTag h = ClientState.hud;
        if (own == null || h.isEmpty() || !own.contains(mc.player.blockPosition())) return;
        Font font = mc.font;

        List<Component> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        Component tier = h.getBoolean("complete") ? Component.translatable("lordsoutpost.hud.tier_done", h.getByte("tier"))
                : Component.translatable("lordsoutpost.hud.tier", h.getByte("tier"), Math.round(h.getFloat("progress") * 100));
        lines.add(tier);
        colors.add(Ui.TEXT);
        lines.add(Component.translatable("lordsoutpost.hud.people", h.getInt("pop"), h.getInt("popCap")));
        colors.add(Ui.MUTED);
        float food = h.getFloat("foodDays");
        lines.add(Component.translatable("lordsoutpost.hud.food", String.format("%.1f", food)));
        colors.add(h.getBoolean("starving") ? Ui.BAD : food < 2 ? Ui.WARN : Ui.GOOD);
        if (h.contains("scarce")) {
            Resource r = Resource.byId(h.getString("scarce"));
            if (r != null) {
                lines.add(Component.translatable("lordsoutpost.hud.scarce", r.label()));
                colors.add(Ui.WARN);
            }
        }
        if (h.getInt("projects") > 0) {
            lines.add(Component.translatable("lordsoutpost.hud.projects", h.getInt("projects")));
            colors.add(Ui.ACCENT);
        }
        if (h.getInt("problems") > 0) {
            lines.add(Component.translatable("lordsoutpost.hud.problems", h.getInt("problems")));
            colors.add(Ui.WARN);
        }
        if (h.getBoolean("raid")) {
            lines.add(Component.translatable("lordsoutpost.hud.raid"));
            colors.add(Ui.BAD);
        }

        Component title = Component.literal(own.name.isEmpty() ? "—" : own.name);
        int w = font.width(title);
        for (Component c : lines) w = Math.max(w, font.width(c));
        w += 10;
        int x = 4, y = 4, hgt = 14 + lines.size() * 10;
        g.fill(x, y, x + w, y + hgt, 0x90101620);
        g.fill(x, y, x + 2, y + hgt, Ui.ACCENT);
        g.drawString(font, title, x + 6, y + 3, Ui.ACCENT, true);
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x + 6, y + 14 + i * 10, colors.get(i), true);
    }
}
