package com.vladosiwe.lordsoutpost.client.map;

import com.mojang.math.Axis;
import com.vladosiwe.lordsoutpost.client.ClientSetup;
import com.vladosiwe.lordsoutpost.client.ClientState;
import com.vladosiwe.lordsoutpost.client.Net;
import com.vladosiwe.lordsoutpost.client.gui.Ui;
import com.vladosiwe.lordsoutpost.network.Action;
import com.vladosiwe.lordsoutpost.network.Payloads;
import com.vladosiwe.lordsoutpost.outpost.AccessLevel;
import com.vladosiwe.lordsoutpost.outpost.Difficulty;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Squad;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Карта аванпостов (клавиша M). Слои снизу вверх: биомы неразведанных земель, рельеф увиденного,
 * периметры фортов и пунктир расширения, маршруты патрулей, караваны, метки фортов и игроки.
 * Перетаскивание — сдвиг, колесо — масштаб, щелчок по метке — сведения и действия.
 */
public class MapScreen extends Screen implements Ui.Projection {
    private static final int NONE = 0, ROUTE = 1, HOLD = 2;
    private static final int[] SQUAD_COLORS = {0xFF5AA9FF, 0xFF58D68D, 0xFFE2B44C};

    private double cx, cz;
    private double zoom = 1.0;
    private boolean dragged;
    private long selected;
    private int mode = NONE;
    private int modeSquad;
    private final List<BlockPos> route = new ArrayList<>();
    private final Ui.Hits hits = new Ui.Hits();
    private final int presetSquad;

    public MapScreen() {
        this(-1);
    }

    /** Открыть карту сразу в режиме прокладки маршрута отряда. */
    public MapScreen(int routeSquad) {
        super(Component.translatable("lordsoutpost.map.title"));
        this.presetSquad = routeSquad;
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && cx == 0 && cz == 0) {
            cx = mc.player.getX();
            cz = mc.player.getZ();
        }
        PacketDistributor.sendToServer(new Payloads.MapRequest(true));
        if (presetSquad >= 0 && mode == NONE) {
            ClientState.Marker own = ClientState.ownMarker();
            if (own != null) {
                cx = own.x;
                cz = own.z;
                zoom = 2.0;
            }
            startRoute(presetSquad);
        }
    }

    @Override
    public void removed() {
        PacketDistributor.sendToServer(new Payloads.MapRequest(false));
        MapTiles.save();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------- Проекция ----------

    @Override
    public double x(double worldX) {
        return width / 2.0 + (worldX - cx) * zoom;
    }

    @Override
    public double y(double worldZ) {
        return height / 2.0 + (worldZ - cz) * zoom;
    }

    private double worldX(double sx) {
        return cx + (sx - width / 2.0) / zoom;
    }

    private double worldZ(double sy) {
        return cz + (sy - height / 2.0) / zoom;
    }

    private boolean overworld() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension() == Level.OVERWORLD;
    }

    // ---------- Отрисовка ----------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xFF0D1016);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        hits.clear();
        Minecraft mc = Minecraft.getInstance();
        double phase = (System.currentTimeMillis() % 100000) / 60.0;
        boolean ow = overworld();

        if (ow) drawBiomes(g);
        drawTerrain(g);
        if (ow) {
            drawOutlines(g, phase);
            drawSquads(g, phase);
            drawCaravans(g, phase);
            drawMarkers(g, mouseX, mouseY);
        }
        drawPlayers(g, mc);
        if (mode == ROUTE) drawRouteDraft(g, mouseX, mouseY);

        // Заголовок и координаты под курсором.
        g.fill(0, 0, width, 14, 0xC0101620);
        g.drawString(font, title, 6, 3, Ui.ACCENT, false);
        String coords = (int) Math.floor(worldX(mouseX)) + ", " + (int) Math.floor(worldZ(mouseY)) + "   ×" + (zoom >= 1 ? String.valueOf((int) zoom) : "1/" + (int) Math.round(1 / zoom));
        g.drawString(font, coords, width - font.width(coords) - 6, 3, Ui.MUTED, false);
        if (!ow) g.drawCenteredString(font, Component.translatable("lordsoutpost.map.overworld_only"), width / 2, 20, Ui.WARN);

        if (mode != NONE) drawModeBar(g, mouseX, mouseY);
        else {
            drawSelection(g, mouseX, mouseY);
            drawSquadStrip(g, mouseX, mouseY);
        }
        Component help = Component.translatable(mode == NONE ? "lordsoutpost.map.help" : mode == ROUTE ? "lordsoutpost.map.help_route" : "lordsoutpost.map.help_hold");
        g.fill(0, height - 12, width, height, 0xC0101620);
        g.drawString(font, help, 6, height - 10, Ui.MUTED, false);
        Component tip = hits.tooltip(mouseX, mouseY);
        if (tip != null) g.renderTooltip(font, tip, mouseX, mouseY);
    }

    private void drawBiomes(GuiGraphics g) {
        int t = BiomeLayer.TILE_BLOCKS;
        int rx0 = Math.floorDiv((int) Math.floor(worldX(0)), t), rx1 = Math.floorDiv((int) Math.floor(worldX(width)), t);
        int rz0 = Math.floorDiv((int) Math.floor(worldZ(0)), t), rz1 = Math.floorDiv((int) Math.floor(worldZ(height)), t);
        if ((rx1 - rx0 + 1) * (rz1 - rz0 + 1) > 36) return;
        for (int rx = rx0; rx <= rx1; rx++)
            for (int rz = rz0; rz <= rz1; rz++) {
                ResourceLocation id = BiomeLayer.tile(rx, rz);
                if (id == null) continue;
                int x0 = (int) Math.floor(x(rx * (double) t)), y0 = (int) Math.floor(y(rz * (double) t));
                int x1 = (int) Math.floor(x((rx + 1) * (double) t)), y1 = (int) Math.floor(y((rz + 1) * (double) t));
                g.blit(id, x0, y0, x1 - x0, y1 - y0, 0, 0, 64, 64, 64, 64);
            }
    }

    private void drawTerrain(GuiGraphics g) {
        int t = MapTiles.REGION;
        int rx0 = Math.floorDiv((int) Math.floor(worldX(0)), t), rx1 = Math.floorDiv((int) Math.floor(worldX(width)), t);
        int rz0 = Math.floorDiv((int) Math.floor(worldZ(0)), t), rz1 = Math.floorDiv((int) Math.floor(worldZ(height)), t);
        if ((rx1 - rx0 + 1) * (rz1 - rz0 + 1) > 40) return;
        for (int rx = rx0; rx <= rx1; rx++)
            for (int rz = rz0; rz <= rz1; rz++) {
                MapTiles.Region region = MapTiles.region(rx, rz, false);
                if (region == null) continue;
                region.upload();
                int x0 = (int) Math.floor(x(rx * (double) t)), y0 = (int) Math.floor(y(rz * (double) t));
                int x1 = (int) Math.floor(x((rx + 1) * (double) t)), y1 = (int) Math.floor(y((rz + 1) * (double) t));
                g.blit(region.id, x0, y0, x1 - x0, y1 - y0, 0, 0, t, t, t, t);
            }
    }

    private static int color(ClientState.Marker m) {
        if (m.id == ClientState.own) return Ui.ACCENT;
        return switch (m.state) {
            case WILD -> m.pact > 0 ? 0xFFE8A04C : Ui.BAD;
            case CONQUERED -> 0xFF8C96AA;
            case OWNED -> m.pact > 0 || m.access.atLeast(AccessLevel.ALLY) ? Ui.GOOD : m.access == AccessLevel.HOSTILE ? 0xFFFF7A45 : 0xFF5AA9FF;
        };
    }

    private void drawOutlines(GuiGraphics g, double phase) {
        if (zoom < 0.5) return;
        for (ClientState.Marker m : ClientState.MARKERS) {
            if (!m.known || m.outline.length < 6 || !onScreen(m.x, m.z, 80)) continue;
            Ui.polygon(g, m.outline, this, zoom >= 2 ? 2f : 1f, color(m), false, 0);
        }
        // Пунктир — периметр следующего тира, который сейчас строится.
        if (ClientState.nextOutline.length >= 6) Ui.polygon(g, ClientState.nextOutline, this, 1f, 0xFFE2B44C, true, phase);
    }

    private void drawSquads(GuiGraphics g, double phase) {
        for (ClientState.SquadMark s : ClientState.SQUADS) {
            int color = SQUAD_COLORS[Math.max(0, Math.min(2, s.id))];
            if (s.route.length >= 2) {
                for (int i = 0; i < s.route.length; i++) {
                    BlockPos a = BlockPos.of(s.route[i]), b = BlockPos.of(s.route[(i + 1) % s.route.length]);
                    Ui.dashed(g, x(a.getX() + 0.5), y(a.getZ() + 0.5), x(b.getX() + 0.5), y(b.getZ() + 0.5), 1f, color, 4, phase);
                    dot(g, x(a.getX() + 0.5), y(a.getZ() + 0.5), 2, color);
                }
            }
            if (s.hold != null) {
                double hx = x(s.hold.getX() + 0.5), hy = y(s.hold.getZ() + 0.5);
                g.renderOutline((int) hx - 4, (int) hy - 4, 9, 9, color);
                dot(g, hx, hy, 1, color);
            }
            if (s.members > 0 && zoom >= 0.5) {
                double sx = x(s.x), sy = y(s.z);
                // Значок отряда: ромб с номером.
                g.pose().pushPose();
                g.pose().translate(sx, sy, 0);
                g.pose().mulPose(Axis.ZP.rotationDegrees(45));
                g.fill(-4, -4, 4, 4, 0xFF10141C);
                g.fill(-3, -3, 3, 3, color);
                g.pose().popPose();
                g.drawString(font, String.valueOf(s.id + 1), (int) sx - 2, (int) sy - 4, 0xFF10141C, false);
            }
        }
    }

    private void drawCaravans(GuiGraphics g, double phase) {
        long since = System.currentTimeMillis() - ClientState.mapStamp;
        for (ClientState.CaravanMark c : ClientState.CARAVANS) {
            Ui.dashed(g, x(c.fx + 0.5), y(c.fz + 0.5), x(c.tx + 0.5), y(c.tz + 0.5), 1f, 0x80E2B44C, 3, 0);
            // Между пакетами сервера значок плавно продолжает путь.
            double wx = c.x, wz = c.z;
            if (c.phase != 1) {
                double tx = c.phase == 2 ? c.fx : c.tx, tz = c.phase == 2 ? c.fz : c.tz;
                double dx = tx - wx, dz = tz - wz, len = Math.sqrt(dx * dx + dz * dz);
                double move = Math.min(len, 2.6 * Math.min(since, 3000) / 1000.0);
                if (len > 0.01) {
                    wx += dx / len * move;
                    wz += dz / len * move;
                }
            }
            double sx = x(wx), sy = y(wz) + Math.sin(phase / 3.0) * 1.2;
            g.fill((int) sx - 4, (int) sy - 3, (int) sx + 4, (int) sy + 3, 0xFF10141C);
            g.fill((int) sx - 3, (int) sy - 2, (int) sx + 3, (int) sy + 2, c.physical ? 0xFFF2D27A : 0xFFB8904A);
            g.fill((int) sx + 2, (int) sy - 5, (int) sx + 4, (int) sy - 2, 0xFFB8904A);
        }
    }

    private void drawMarkers(GuiGraphics g, int mouseX, int mouseY) {
        for (ClientState.Marker m : ClientState.MARKERS) {
            if (!onScreen(m.x, m.z, 12)) continue;
            int sx = (int) Math.round(x(m.x + 0.5)), sy = (int) Math.round(y(m.z + 0.5));
            boolean hover = Math.abs(mouseX - sx) <= 7 && Math.abs(mouseY - sy) <= 7;
            boolean sel = m.id == selected;
            if (!m.known) {
                g.fill(sx - 6, sy - 6, sx + 6, sy + 6, 0xFF10141C);
                g.renderOutline(sx - 6, sy - 6, 12, 12, hover || sel ? Ui.TEXT : 0xFF8C96AA);
                g.drawString(font, "?", sx - 2, sy - 4, hover || sel ? Ui.TEXT : 0xFFC8D0DC, false);
            } else {
                int color = color(m);
                // Значок форта: стена с зубцами и воротами.
                g.fill(sx - 7, sy - 6, sx + 7, sy + 7, 0xFF10141C);
                g.fill(sx - 6, sy - 2, sx + 6, sy + 6, color);
                g.fill(sx - 6, sy - 5, sx - 3, sy - 2, color);
                g.fill(sx - 1, sy - 5, sx + 2, sy - 2, color);
                g.fill(sx + 3, sy - 5, sx + 6, sy - 2, color);
                g.fill(sx - 1, sy + 2, sx + 2, sy + 6, 0xFF10141C);
                if (hover || sel) g.renderOutline(sx - 8, sy - 7, 16, 15, Ui.TEXT);
                if (m.state == OutpostState.WILD) {
                    int pw = m.skulls * 4 - 1;
                    for (int i = 0; i < m.skulls; i++) g.fill(sx - pw / 2 + i * 4, sy + 9, sx - pw / 2 + i * 4 + 3, sy + 12, 0xFFE8ECF4);
                } else if (zoom >= 0.35 && !m.name.isEmpty()) {
                    g.drawCenteredString(font, m.name, sx, sy + 9, color);
                }
            }
            hits.add(sx - 7, sy - 7, 15, 15, () -> selected = m.id, null);
        }
    }

    private void drawPlayers(GuiGraphics g, Minecraft mc) {
        if (mc.level == null || mc.player == null) return;
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p.isSpectator() && p != mc.player) continue;
            double sx = x(p.getX()), sy = y(p.getZ());
            boolean me = p == mc.player;
            g.pose().pushPose();
            g.pose().translate(sx, sy, 0);
            g.pose().mulPose(Axis.ZP.rotationDegrees(Mth.wrapDegrees(p.getYRot()) + 180));
            int c = me ? 0xFFFFFFFF : 0xFF9AD0FF;
            g.fill(-1, -5, 1, -3, c);
            g.fill(-2, -3, 2, -1, c);
            g.fill(-3, -1, 3, 2, c);
            g.fill(-3, 2, -1, 4, c);
            g.fill(1, 2, 3, 4, c);
            g.pose().popPose();
            if (!me && zoom >= 0.5) g.drawCenteredString(font, p.getName(), (int) sx, (int) sy + 6, c);
        }
    }

    private void drawRouteDraft(GuiGraphics g, int mouseX, int mouseY) {
        int color = SQUAD_COLORS[Math.max(0, Math.min(2, modeSquad))];
        for (int i = 0; i < route.size(); i++) {
            BlockPos a = route.get(i);
            double ax = x(a.getX() + 0.5), ay = y(a.getZ() + 0.5);
            if (i + 1 < route.size()) {
                BlockPos b = route.get(i + 1);
                Ui.line(g, ax, ay, x(b.getX() + 0.5), y(b.getZ() + 0.5), 1.5f, color);
            } else {
                Ui.dashed(g, ax, ay, mouseX, mouseY, 1f, color, 3, 0);
                if (route.size() >= 2) {
                    BlockPos first = route.get(0);
                    Ui.dashed(g, ax, ay, x(first.getX() + 0.5), y(first.getZ() + 0.5), 1f, 0x80FFFFFF, 3, 0);
                }
            }
            dot(g, ax, ay, 3, color);
            g.drawString(font, String.valueOf(i + 1), (int) ax + 5, (int) ay - 4, Ui.TEXT, true);
        }
    }

    private void dot(GuiGraphics g, double x, double y, int r, int color) {
        g.fill((int) Math.round(x) - r, (int) Math.round(y) - r, (int) Math.round(x) + r + 1, (int) Math.round(y) + r + 1, color);
    }

    private boolean onScreen(double wx, double wz, int margin) {
        double sx = x(wx), sy = y(wz);
        return sx > -margin && sy > -margin && sx < width + margin && sy < height + margin;
    }

    // ---------- Панели ----------

    @Nullable
    private ClientState.Marker selectedMarker() {
        for (ClientState.Marker m : ClientState.MARKERS) if (m.id == selected) return m;
        return null;
    }

    private void drawSelection(GuiGraphics g, int mouseX, int mouseY) {
        ClientState.Marker m = selectedMarker();
        if (m == null) return;
        Minecraft mc = Minecraft.getInstance();
        int w = 138, x = width - w - 6, y = 20;
        ClientState.Marker own = ClientState.ownMarker();
        boolean mine = m.id == ClientState.own;
        boolean canDeal = own != null && !mine && m.known && m.state != OutpostState.CONQUERED;
        int h = 58 + (mine ? 18 : 0) + (canDeal ? (m.pact > 0 ? 54 : 18) : 0) + (m.known ? 0 : 12);
        Ui.panel(g, x, y, w, h);
        hits.add(x, y, w, h, () -> { }, null);
        int ty = y + 6;
        Component title = !m.known ? Component.translatable("lordsoutpost.map.unknown")
                : m.state == OutpostState.OWNED && !m.name.isEmpty() ? Component.literal(m.name) : Difficulty.bySkulls(m.skulls).label();
        Ui.text(g, font, title, x + 6, ty, w - 12, m.known ? color(m) : Ui.TEXT);
        ty += 12;
        if (m.known) {
            Component state = switch (m.state) {
                case WILD -> Component.translatable("lordsoutpost.map.state.wild", "★".repeat(m.skulls));
                case CONQUERED -> Component.translatable("lordsoutpost.map.state.conquered");
                case OWNED -> Component.translatable("lordsoutpost.map.state.owned", m.owner);
            };
            Ui.text(g, font, state, x + 6, ty, w - 12, Ui.MUTED);
            ty += 10;
            Ui.text(g, font, Component.translatable("lordsoutpost.map.tier", m.tier, Math.round(m.progress * 100)), x + 6, ty, w - 12, Ui.MUTED);
            ty += 10;
        } else {
            Ui.text(g, font, Component.translatable("lordsoutpost.map.unknown.hint"), x + 6, ty, w - 12, Ui.MUTED);
            ty += 10;
        }
        if (mc.player != null) {
            int dist = (int) Math.sqrt((mc.player.getX() - m.x) * (mc.player.getX() - m.x) + (mc.player.getZ() - m.z) * (mc.player.getZ() - m.z));
            Ui.text(g, font, Component.translatable("lordsoutpost.map.distance", dist, m.x, m.z), x + 6, ty, w - 12, Ui.MUTED);
            ty += 14;
        }
        if (mine) {
            Ui.button(g, font, hits, x + 6, ty, w - 12, 14, Component.translatable("lordsoutpost.map.manage"), true, false, mouseX, mouseY,
                    () -> PacketDistributor.sendToServer(new Payloads.OpenRequest(m.id, 0)), null);
        } else if (canDeal) {
            long ownId = own.id;
            if (m.pact == 0) {
                int cost = m.state == OutpostState.WILD ? 20 * m.skulls : 0;
                Ui.button(g, font, hits, x + 6, ty, w - 12, 14, Component.translatable("lordsoutpost.map.pact_trade", cost), true, false, mouseX, mouseY,
                        () -> Net.action(ownId, Action.PACT, t -> { t.putLong("target", m.id); t.putInt("pact", 1); }),
                        Component.translatable("lordsoutpost.map.pact_trade.tip"));
            } else {
                if (m.pact == 1) {
                    int cost = m.state == OutpostState.WILD ? 50 * m.skulls : 0;
                    Ui.button(g, font, hits, x + 6, ty, w - 12, 14, Component.translatable("lordsoutpost.map.pact_military", cost), true, false, mouseX, mouseY,
                            () -> Net.action(ownId, Action.PACT, t -> { t.putLong("target", m.id); t.putInt("pact", 2); }),
                            Component.translatable("lordsoutpost.map.pact_military.tip"));
                } else {
                    Ui.text(g, font, Component.translatable("lordsoutpost.map.allied"), x + 6, ty + 3, w - 12, Ui.GOOD);
                }
                ty += 18;
                Ui.button(g, font, hits, x + 6, ty, w - 12, 14, Component.translatable("lordsoutpost.map.caravan"), true, false, mouseX, mouseY,
                        () -> Net.action(ownId, Action.CARAVAN, t -> t.putLong("target", m.id)), Component.translatable("lordsoutpost.map.caravan.tip"));
                ty += 18;
                Ui.button(g, font, hits, x + 6, ty, w - 12, 14, Component.translatable("lordsoutpost.map.break"), true, false, mouseX, mouseY,
                        () -> Net.action(ownId, Action.BREAK_PACT, t -> t.putLong("target", m.id)), null);
            }
        }
    }

    /** Полоса отрядов: приказы прямо с карты. */
    private void drawSquadStrip(GuiGraphics g, int mouseX, int mouseY) {
        ClientState.Marker own = ClientState.ownMarker();
        if (own == null || ClientState.SQUADS.isEmpty()) return;
        int x = 6, y = height - 16 - ClientState.SQUADS.size() * 16 - 4, w = 232;
        Ui.panel(g, x, y, w, ClientState.SQUADS.size() * 16 + 4);
        hits.add(x, y, w, ClientState.SQUADS.size() * 16 + 4, () -> { }, null);
        int row = y + 3;
        for (ClientState.SquadMark s : ClientState.SQUADS) {
            int color = SQUAD_COLORS[Math.max(0, Math.min(2, s.id))];
            g.fill(x + 4, row + 3, x + 10, row + 9, color);
            Squad.Order order = Squad.Order.VALUES[Math.max(0, Math.min(3, s.order))];
            Ui.text(g, font, Component.translatable("lordsoutpost.squad." + s.id).append(" (" + s.members + ") · ").append(order.label()),
                    x + 14, row + 2, 108, Ui.TEXT);
            int sid = s.id;
            Ui.button(g, font, hits, x + 124, row, 50, 13, Component.translatable("lordsoutpost.map.route"), true, false, mouseX, mouseY,
                    () -> startRoute(sid), Component.translatable("lordsoutpost.map.route.tip"));
            Ui.button(g, font, hits, x + 177, row, 50, 13, Component.translatable("lordsoutpost.map.hold"), true, order == Squad.Order.HOLD, mouseX, mouseY,
                    () -> {
                        if (order == Squad.Order.HOLD) Net.action(own.id, Action.SQUAD_HOLD, t -> t.putInt("squad", sid));
                        else {
                            mode = HOLD;
                            modeSquad = sid;
                        }
                    }, Component.translatable("lordsoutpost.map.hold.tip"));
            row += 16;
        }
    }

    private void drawModeBar(GuiGraphics g, int mouseX, int mouseY) {
        int w = 300, x = (width - w) / 2, y = 18;
        Ui.panel(g, x, y, w, 20);
        hits.add(x, y, w, 20, () -> { }, null);
        Component label = Component.translatable(mode == ROUTE ? "lordsoutpost.map.mode.route" : "lordsoutpost.map.mode.hold",
                Component.translatable("lordsoutpost.squad." + modeSquad), route.size(), Squad.MAX_WAYPOINTS);
        Ui.text(g, font, label, x + 6, y + 6, 150, SQUAD_COLORS[Math.max(0, Math.min(2, modeSquad))]);
        if (mode == ROUTE) {
            Ui.button(g, font, hits, x + 160, y + 3, 44, 14, Component.translatable("lordsoutpost.map.done"), route.size() >= 2 || route.isEmpty(), false,
                    mouseX, mouseY, this::saveRoute, null);
            Ui.button(g, font, hits, x + 207, y + 3, 44, 14, Component.translatable("lordsoutpost.map.clear"), !route.isEmpty(), false,
                    mouseX, mouseY, route::clear, null);
        }
        Ui.button(g, font, hits, x + 254, y + 3, 42, 14, Component.translatable("lordsoutpost.map.cancel"), true, false, mouseX, mouseY,
                () -> { mode = NONE; route.clear(); }, null);
    }

    private void startRoute(int squad) {
        mode = ROUTE;
        modeSquad = squad;
        route.clear();
        for (ClientState.SquadMark s : ClientState.SQUADS) {
            if (s.id == squad) for (long l : s.route) route.add(BlockPos.of(l));
        }
    }

    private void saveRoute() {
        ClientState.Marker own = ClientState.ownMarker();
        if (own != null) {
            long[] pts = new long[route.size()];
            for (int i = 0; i < pts.length; i++) pts[i] = route.get(i).asLong();
            Net.action(own.id, Action.SQUAD_ROUTE, t -> { t.putInt("squad", modeSquad); t.putLongArray("route", pts); });
        }
        mode = NONE;
        route.clear();
    }

    // ---------- Ввод ----------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        dragged = false;
        if (button == 0 && hits.click(mouseX, mouseY)) return true;
        if (mode == ROUTE) {
            if (button == 0 && route.size() < Squad.MAX_WAYPOINTS) route.add(new BlockPos((int) Math.floor(worldX(mouseX)), 0, (int) Math.floor(worldZ(mouseY))));
            else if (button == 1 && !route.isEmpty()) route.remove(route.size() - 1);
            return true;
        }
        if (mode == HOLD && button == 0) {
            ClientState.Marker own = ClientState.ownMarker();
            long pos = new BlockPos((int) Math.floor(worldX(mouseX)), 0, (int) Math.floor(worldZ(mouseY))).asLong();
            if (own != null) Net.action(own.id, Action.SQUAD_HOLD, t -> { t.putInt("squad", modeSquad); t.putLong("pos", pos); });
            mode = NONE;
            return true;
        }
        if (button == 1) selected = 0;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 || button == 2) {
            cx -= dragX / zoom;
            cz -= dragY / zoom;
            dragged = true;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double wx = worldX(mouseX), wz = worldZ(mouseY);
        double next = Mth.clamp(zoom * (scrollY > 0 ? 2.0 : 0.5), 0.125, 8.0);
        if (next != zoom) {
            zoom = next;
            // Точка под курсором остаётся на месте.
            cx = wx - (mouseX - width / 2.0) / zoom;
            cz = wz - (mouseY - height / 2.0) / zoom;
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        Minecraft mc = Minecraft.getInstance();
        if (ClientSetup.MAP_KEY.matches(keyCode, scanCode) && mode == NONE) {
            onClose();
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE && mc.player != null) {
            cx = mc.player.getX();
            cz = mc.player.getZ();
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_HOME) {
            ClientState.Marker own = ClientState.ownMarker();
            if (own != null) {
                cx = own.x;
                cz = own.z;
            }
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && mode != NONE) {
            mode = NONE;
            route.clear();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
