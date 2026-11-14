package com.vladosiwe.lordsoutpost.client.gui;

import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Общие приёмы рисования интерфейса мода: панели, кнопки, полосы, линии под углом. */
public final class Ui {
    public static final int BG = 0xE0141821, PANEL = 0xF01C222E, BORDER = 0xFF3A4660, ACCENT = 0xFFE2B44C;
    public static final int TEXT = 0xFFE8ECF4, MUTED = 0xFF8C96AA, GOOD = 0xFF4CC38A, BAD = 0xFFE5484D, WARN = 0xFFE8C547;

    /** Кликабельная область текущего кадра. */
    public record Hit(int x, int y, int w, int h, Runnable action, @Nullable Component tooltip) {
        public boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** Список областей пересобирается каждый кадр: интерфейс рисуется по состоянию, без отдельного дерева виджетов. */
    public static final class Hits {
        private final List<Hit> list = new ArrayList<>();

        public void clear() {
            list.clear();
        }

        public void add(int x, int y, int w, int h, Runnable action, @Nullable Component tooltip) {
            list.add(new Hit(x, y, w, h, action, tooltip));
        }

        public boolean click(double mx, double my) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Hit h = list.get(i);
                if (h.contains(mx, my) && h.action != null) {
                    h.action.run();
                    return true;
                }
            }
            return false;
        }

        @Nullable
        public Component tooltip(double mx, double my) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Hit h = list.get(i);
                if (h.contains(mx, my) && h.tooltip != null) return h.tooltip;
            }
            return null;
        }
    }

    private Ui() {}

    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL);
        g.renderOutline(x, y, w, h, BORDER);
    }

    public static void bar(GuiGraphics g, int x, int y, int w, int h, float frac, int color) {
        g.fill(x, y, x + w, y + h, 0xFF0C0F15);
        int fill = Math.round((w - 2) * Math.max(0f, Math.min(1f, frac)));
        if (fill > 0) g.fill(x + 1, y + 1, x + 1 + fill, y + h - 1, color);
        g.renderOutline(x, y, w, h, 0xFF2A3346);
    }

    /** Кнопка. Возвращает true, если курсор над ней. */
    public static boolean button(GuiGraphics g, Font font, Hits hits, int x, int y, int w, int h, Component label,
                                 boolean enabled, boolean active, int mx, int my, Runnable action, @Nullable Component tooltip) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        int bg = !enabled ? 0xFF1A1E27 : active ? 0xFF4A3F1E : hover ? 0xFF2F3B54 : 0xFF232B3B;
        g.fill(x, y, x + w, y + h, bg);
        g.renderOutline(x, y, w, h, active ? ACCENT : hover && enabled ? 0xFF6C82AE : BORDER);
        int color = !enabled ? 0xFF5A6272 : active ? ACCENT : TEXT;
        FormattedCharSequence text = fit(font, label, w - 4);
        g.drawString(font, text, x + (w - font.width(text)) / 2, y + (h - 8) / 2, color, false);
        hits.add(x, y, w, h, enabled ? action : null, tooltip);
        return hover;
    }

    public static FormattedCharSequence fit(Font font, Component text, int width) {
        if (font.width(text) <= width) return text.getVisualOrderText();
        FormattedText cut = font.substrByWidth(text, Math.max(0, width - font.width("…")));
        return net.minecraft.locale.Language.getInstance().getVisualOrder(FormattedText.composite(cut, FormattedText.of("…")));
    }

    public static void text(GuiGraphics g, Font font, Component text, int x, int y, int width, int color) {
        g.drawString(font, fit(font, text, width), x, y, color, false);
    }

    public static void line(GuiGraphics g, double x1, double y1, double x2, double y2, float width, int color) {
        double dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01f) return;
        g.pose().pushPose();
        g.pose().translate(x1, y1, 0);
        g.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(dy, dx)));
        g.pose().scale(1f, width, 1f);
        g.fill(0, 0, Math.max(1, Math.round(len)), 1, color);
        g.pose().popPose();
    }

    public static void dashed(GuiGraphics g, double x1, double y1, double x2, double y2, float width, int color, double dash, double phase) {
        double dx = x2 - x1, dy = y2 - y1;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01) return;
        double ux = dx / len, uy = dy / len;
        for (double d = -((phase % (dash * 2)) + dash * 2) % (dash * 2); d < len; d += dash * 2) {
            double a = Math.max(0, d), b = Math.min(len, d + dash);
            if (b > a) line(g, x1 + ux * a, y1 + uy * a, x1 + ux * b, y1 + uy * b, width, color);
        }
    }

    /** Замкнутый контур по точкам (x, z, x, z...) с преобразованием мир -> экран. */
    public interface Projection {
        double x(double worldX);

        double y(double worldZ);
    }

    public static void polygon(GuiGraphics g, int[] pts, Projection p, float width, int color, boolean dashedLine, double phase) {
        int n = pts.length / 2;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double x1 = p.x(pts[i * 2] + 0.5), y1 = p.y(pts[i * 2 + 1] + 0.5), x2 = p.x(pts[j * 2] + 0.5), y2 = p.y(pts[j * 2 + 1] + 0.5);
            if (dashedLine) dashed(g, x1, y1, x2, y2, width, color, 5, phase);
            else line(g, x1, y1, x2, y2, width, color);
        }
    }
}
