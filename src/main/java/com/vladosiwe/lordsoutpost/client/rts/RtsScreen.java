package com.vladosiwe.lordsoutpost.client.rts;

import com.mojang.blaze3d.platform.InputConstants;
import com.vladosiwe.lordsoutpost.blueprint.Palette;
import com.vladosiwe.lordsoutpost.blueprint.ProjectDesigner;
import com.vladosiwe.lordsoutpost.client.Net;
import com.vladosiwe.lordsoutpost.client.gui.OutpostView;
import com.vladosiwe.lordsoutpost.client.gui.Ui;
import com.vladosiwe.lordsoutpost.network.Action;
import com.vladosiwe.lordsoutpost.outpost.Project;
import com.vladosiwe.lordsoutpost.outpost.Resource;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Интерфейс RTS-режима строительства поверх стратегической камеры. Каталог — существующие типы построек и их
 * стоимость из снимка аванпоста; место проверяет сервер (теми же правилами, что и заказ), заказ создаётся только на
 * сервере и выполняется обычными строителями. Клиент отвечает за камеру, предпросмотр и отправку запросов.
 */
public class RtsScreen extends Screen {
    private static final int LEFT_W = 124, RIGHT_W = 132;
    /** Нижняя граница правой панели (заказы) в этом кадре: ниже неё — уже мир. */
    private int rightBottom = 40;

    private OutpostView view;
    private final Ui.Hits hits = new Ui.Hits();
    @Nullable private Project.Type selected;
    private int rot;
    /** Наведённая точка (центр постройки) и состояние проверки сервером. */
    @Nullable private BlockPos hover;
    @Nullable private String checkKey, sentKey;
    private int seq;
    private long sentAt;
    @Nullable private String result;
    private final EnumMap<Resource, Integer> missing = new EnumMap<>(Resource.class);
    private boolean dragging;
    private double lastDragX, lastDragY;

    /** Для отрисовки призрака постройки в мире (RtsRender). */
    @Nullable static RtsScreen current;

    public RtsScreen(OutpostView view) {
        super(Component.translatable("lordsoutpost.rts.title"));
        this.view = view;
    }

    @Override
    protected void init() {
        current = this;
        if (!RtsCamera.active() && view.manage != null) {
            RtsCamera.enter(view.manage.cx + 0.5, view.manage.cy + 1, view.manage.cz + 0.5, view.manage.orderLimit);
        }
    }

    public void update(OutpostView v) {
        if (v.id == view.id) view = v;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        RtsCamera.exit();
        if (current == this) current = null;
        Net.action(view.id, Action.CLOSE);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Без затемнения: мир виден целиком.
    }

    // ============================== Управление камерой ==============================

    private boolean key(int code) {
        return InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), code);
    }

    private void moveCamera() {
        double f = 0, r = 0;
        if (key(GLFW.GLFW_KEY_W) || key(GLFW.GLFW_KEY_UP)) f += 1;
        if (key(GLFW.GLFW_KEY_S) || key(GLFW.GLFW_KEY_DOWN)) f -= 1;
        if (key(GLFW.GLFW_KEY_D) || key(GLFW.GLFW_KEY_RIGHT)) r += 1;
        if (key(GLFW.GLFW_KEY_A) || key(GLFW.GLFW_KEY_LEFT)) r -= 1;
        if (f != 0 || r != 0) RtsCamera.pan(f, r);
        if (key(GLFW.GLFW_KEY_Q)) RtsCamera.rotate(-2.5);
        if (key(GLFW.GLFW_KEY_E)) RtsCamera.rotate(2.5);
        if (key(GLFW.GLFW_KEY_R)) RtsCamera.tilt(1.5);
        if (key(GLFW.GLFW_KEY_F)) RtsCamera.tilt(-1.5);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_H) {
            RtsCamera.home();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            RtsCamera.toggleOverview();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && selected != null) {
            cancelSelection();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        // Колесо: при выбранной постройке — поворот (с Ctrl — приближение), иначе — приближение.
        if (selected != null && !hasControlDown()) {
            rot = Math.floorMod(rot + (dy > 0 ? 1 : -1), 4);
            return true;
        }
        RtsCamera.zoom(dy);
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && hits.click(mx, my)) return true;
        if (overUi(mx, my)) return true;
        if (button == 2) {
            dragging = true;
            lastDragX = mx;
            lastDragY = my;
            return true;
        }
        if (button == 1) {
            if (selected != null) cancelSelection();
            return true;
        }
        if (button == 0 && selected != null && hover != null) {
            if ("ok".equals(result) && key().equals(checkKey)) order();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 2) dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging) {
            RtsCamera.rotate((mx - lastDragX) * 0.6);
            RtsCamera.tilt((my - lastDragY) * 0.4);
            lastDragX = mx;
            lastDragY = my;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    private boolean overUi(double mx, double my) {
        return mx < LEFT_W + 8 || (mx > width - RIGHT_W - 8 && my < rightBottom) || my < 22;
    }

    // ============================== Выбор места ==============================

    /** Точка на земле под курсором: луч из камеры через пиксель курсора. */
    @Nullable
    private BlockPos pick(double mx, double my) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        Camera cam = mc.gameRenderer.getMainCamera();
        double fov = Math.toRadians(mc.options.fov().get());
        double aspect = (double) width / Math.max(1, height);
        double nx = mx / width * 2 - 1, ny = 1 - my / height * 2;
        double th = Math.tan(fov / 2);
        Vector3f f = cam.getLookVector(), u = cam.getUpVector(), l = cam.getLeftVector();
        Vec3 dir = new Vec3(f.x() + u.x() * ny * th - l.x() * nx * th * aspect,
                f.y() + u.y() * ny * th - l.y() * nx * th * aspect,
                f.z() + u.z() * ny * th - l.z() * nx * th * aspect).normalize();
        Vec3 from = cam.getPosition();
        BlockHitResult hit = mc.level.clip(new ClipContext(from, from.add(dir.scale(256)), ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, CollisionContext.empty()));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos p = hit.getBlockPos();
        // На клиенте нет карты высот «без листвы»: берём обычную и опускаемся сквозь листву и траву до земли
        // (сервер всё равно определяет высоту сам).
        int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ()) - 1;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos(p.getX(), y, p.getZ());
        while (q.getY() > mc.level.getMinBuildHeight()) {
            var st = mc.level.getBlockState(q);
            if (!(st.isAir() || st.is(net.minecraft.tags.BlockTags.LEAVES) || st.is(net.minecraft.tags.BlockTags.LOGS)
                    || st.is(net.minecraft.tags.BlockTags.SNOW) || (st.canBeReplaced() && st.getFluidState().isEmpty()))) break;
            q.move(0, -1, 0);
        }
        return q.immutable();
    }

    private String key() {
        return selected == null || hover == null ? "" : selected.id + "/" + hover.getX() + "/" + hover.getZ() + "/" + rot;
    }

    private void cancelSelection() {
        selected = null;
        hover = null;
        result = null;
        checkKey = null;
        sentKey = null;
        missing.clear();
    }

    private void select(Project.Type type) {
        selected = type;
        result = null;
        checkKey = null;
        sentKey = null;
        missing.clear();
    }

    /** Проверка места на сервере — не чаще ~6 раз в секунду и только при смене точки, типа или поворота. */
    private void requestCheck() {
        if (selected == null || hover == null) return;
        String k = key();
        if (k.equals(sentKey) || System.currentTimeMillis() - sentAt < 160) return;
        sentKey = k;
        sentAt = System.currentTimeMillis();
        int s = ++seq;
        Project.Type t = selected;
        BlockPos p = hover;
        int r = rot;
        pending.put(s, k);
        Net.action(view.id, Action.CHECK_PROJECT, tag -> {
            tag.putString("type", t.id);
            tag.putInt("x", p.getX());
            tag.putInt("z", p.getZ());
            tag.putInt("rot", r);
            tag.putInt("seq", s);
        });
    }

    private final java.util.Map<Integer, String> pending = new java.util.HashMap<>();

    /** Ответ сервера на проверку или заказ. */
    public void onCheck(CompoundTag t) {
        String k = pending.remove(t.getInt("seq"));
        if (t.getBoolean("ordered")) {
            // Заказ принят: выбор сбрасывается, постройка появится в списке заказов со следующим снимком.
            cancelSelection();
            return;
        }
        if (k == null) return;
        checkKey = k;
        result = t.getString("result");
        missing.clear();
        CompoundTag m = t.getCompound("missing");
        for (String id : m.getAllKeys()) {
            Resource r = Resource.byId(id);
            if (r != null) missing.put(r, m.getInt(id));
        }
    }

    private void order() {
        if (selected == null || hover == null) return;
        int s = ++seq;
        Project.Type t = selected;
        BlockPos p = hover;
        int r = rot;
        Net.action(view.id, Action.ORDER_PROJECT_AT, tag -> {
            tag.putString("type", t.id);
            tag.putInt("x", p.getX());
            tag.putInt("z", p.getZ());
            tag.putInt("rot", r);
            tag.putInt("seq", s);
        });
    }

    // ============================== Данные для призрака постройки ==============================

    /** Клетки выбранной постройки в мире (для предпросмотра), null — нечего показывать. */
    @Nullable
    List<BlockPos> ghostCells() {
        if (selected == null || hover == null || view.manage == null) return null;
        return cells(selected, hover, rot);
    }

    List<BlockPos> cells(Project.Type type, BlockPos origin, int r) {
        List<BlockPos> out = new ArrayList<>();
        Palette palette = Palette.byId(view.manage.palette);
        Rotation rotation = Rotation.values()[Math.floorMod(r, 4)];
        for (ProjectDesigner.Cell c : ProjectDesigner.design(type, palette)) {
            if (c.state().isAir()) continue;
            out.add(origin.offset(c.pos().rotate(rotation)));
        }
        return out;
    }

    /** 1 — можно (сервер подтвердил), 0 — нельзя, -1 — ждём ответа. */
    int ghostState() {
        if (!key().equals(checkKey) || result == null) return -1;
        return "ok".equals(result) ? 1 : 0;
    }

    List<OutpostView.ProjectInfo> activeOrders() {
        List<OutpostView.ProjectInfo> out = new ArrayList<>();
        if (view.manage != null) for (var p : view.manage.projects) if (p.active()) out.add(p);
        return out;
    }

    // ============================== Отрисовка интерфейса ==============================

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        RtsCamera.frame();
        moveCamera();
        hits.clear();
        OutpostView.Manage m = view.manage;
        if (m == null) {
            onClose();
            return;
        }
        if (selected != null && !overUi(mouseX, mouseY)) {
            BlockPos p = pick(mouseX, mouseY);
            if (p != null) hover = p;
            requestCheck();
        }
        // Подсказка по управлению — мелким шрифтом в две строки.
        g.fill(0, 0, width, 20, 0xA0101520);
        g.pose().pushPose();
        g.pose().scale(0.6f, 0.6f, 1f);
        int lw = (int) (width / 0.6f) - 8;
        var lines = font.split(Component.translatable("lordsoutpost.rts.help"), lw);
        for (int i = 0; i < Math.min(2, lines.size()); i++) g.drawCenteredString(font, lines.get(i), (int) (width / 1.2f), 3 + i * 10, Ui.MUTED);
        g.pose().popPose();

        drawCatalog(g, m, mouseX, mouseY);
        drawOrders(g, m, mouseX, mouseY);

        Component tip = hits.tooltip(mouseX, mouseY);
        if (tip != null) g.renderTooltip(font, font.split(tip, 220), mouseX, mouseY);
    }

    private void drawCatalog(GuiGraphics g, OutpostView.Manage m, int mx, int my) {
        int x = 6, y = 24, w = LEFT_W;
        Ui.panel(g, x - 2, y - 2, w + 4, height - y - 4);
        g.drawString(font, Component.translatable("lordsoutpost.rts.catalog"), x + 2, y + 2, Ui.ACCENT, false);
        Ui.button(g, font, hits, x + w - 40, y, 40, 11, Component.translatable("lordsoutpost.rts.exit_short"), true, false, mx, my, this::onClose,
                Component.translatable("lordsoutpost.rts.exit"));
        y += 14;
        for (Project.Type type : Project.Type.VALUES) {
            boolean sel = type == selected;
            Ui.button(g, font, hits, x, y, w, 11, Component.translatable("lordsoutpost.project." + type.id), true, sel, mx, my,
                    () -> select(type), Component.translatable("lordsoutpost.project." + type.id + ".desc"));
            y += 12;
        }
        y += 4;
        // Склад: в наличии и в резерве под заказы.
        g.drawString(font, Component.translatable("lordsoutpost.rts.stock"), x + 2, y, Ui.TEXT, false);
        y += 10;
        for (Resource r : new Resource[]{Resource.WOOD, Resource.STONE, Resource.IRON}) {
            int have = (int) Math.floor(view.stock.getOrDefault(r, 0.0));
            int res = m.reserved.getOrDefault(r, 0);
            renderIcon(g, r, x + 2, y - 2);
            Ui.text(g, font, Component.translatable("lordsoutpost.rts.stock_line", have, res), x + 16, y + 1, w - 18, Ui.MUTED);
            y += 11;
        }
        y += 4;
        if (selected == null) {
            Ui.wrapped(g, font, Component.translatable("lordsoutpost.rts.pick"), x + 2, y, w - 4, Ui.MUTED, 4);
        } else {
            g.drawString(font, Component.translatable("lordsoutpost.project." + selected.id), x + 2, y, Ui.ACCENT, false);
            g.drawString(font, rot * 90 + "°", x + w - 4 - font.width(rot * 90 + "°"), y, Ui.MUTED, false);
            hits.add(x, y, w, 9, null, Component.translatable("lordsoutpost.project." + selected.id + ".desc"));
            y += 11;
            var cost = m.costs.getOrDefault(selected.id, new EnumMap<>(Resource.class));
            for (var e : cost.entrySet()) {
                int have = (int) Math.floor(view.stock.getOrDefault(e.getKey(), 0.0)) - m.reserved.getOrDefault(e.getKey(), 0);
                boolean ok = have >= e.getValue();
                renderIcon(g, e.getKey(), x + 2, y - 2);
                Ui.text(g, font, Component.translatable("lordsoutpost.rts.cost_line", e.getValue(), Math.max(0, have)),
                        x + 16, y + 1, w - 18, ok ? Ui.GOOD : Ui.BAD);
                y += 11;
            }
            y += 2;
            int state = ghostState();
            Component status;
            int color;
            if (hover == null) {
                status = Component.translatable("lordsoutpost.rts.point");
                color = Ui.MUTED;
            } else if (state < 0) {
                status = Component.translatable("lordsoutpost.rts.checking");
                color = Ui.MUTED;
            } else if (state == 1) {
                status = Component.translatable("lordsoutpost.rts.can_place");
                color = Ui.GOOD;
            } else {
                status = Component.translatable("lordsoutpost.msg.project." + result);
                color = Ui.BAD;
            }
            y += Ui.wrapped(g, font, status, x + 2, y, w - 4, color, 2) + 2;
            if (!missing.isEmpty() && "no_resources".equals(result)) {
                StringBuilder sb = new StringBuilder();
                for (var e : missing.entrySet()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(e.getKey().label().getString()).append(" ").append(e.getValue());
                }
                y += Ui.wrapped(g, font, Component.translatable("lordsoutpost.rts.missing", sb.toString()), x + 2, y, w - 4, Ui.BAD, 2) + 2;
            }
            Ui.button(g, font, hits, x, y, w / 2 - 1, 12, Component.translatable("lordsoutpost.rts.confirm"), state == 1, false, mx, my, this::order,
                    Component.translatable("lordsoutpost.rts.confirm.tip"));
            Ui.button(g, font, hits, x + w / 2 + 1, y, w / 2 - 1, 12, Component.translatable("lordsoutpost.rts.cancel_pick"), true, false, mx, my,
                    this::cancelSelection, null);
        }

    }

    private void drawOrders(GuiGraphics g, OutpostView.Manage m, int mx, int my) {
        int w = RIGHT_W, x = width - w - 6, y = 24;
        List<OutpostView.ProjectInfo> orders = activeOrders();
        int ph = orders.isEmpty() ? 34 : 16 + orders.size() * 46;
        Ui.panel(g, x - 2, y - 2, w + 4, Math.min(height - y - 4, ph));
        rightBottom = y + ph + 2;
        g.drawString(font, Component.translatable("lordsoutpost.rts.orders", orders.size(), m.projectLimit), x + 2, y + 2, Ui.ACCENT, false);
        y += 14;
        if (orders.isEmpty()) {
            Ui.wrapped(g, font, Component.translatable("lordsoutpost.rts.no_orders"), x + 2, y, w - 4, Ui.MUTED, 3);
            return;
        }
        for (int i = 0; i < orders.size(); i++) {
            OutpostView.ProjectInfo p = orders.get(i);
            g.fill(x, y, x + w, y + 43, 0xFF151B26);
            g.drawString(font, Component.translatable("lordsoutpost.project." + p.type()), x + 3, y + 3, Ui.TEXT, false);
            int bx = x + w - 48;
            boolean first = i == 0, last = i == orders.size() - 1;
            Ui.button(g, font, hits, bx, y + 2, 14, 10, Component.literal("▲"), !first, false, mx, my,
                    () -> Net.action(view.id, Action.MOVE_PROJECT, t -> { t.putLong("id", p.id()); t.putBoolean("up", true); }),
                    Component.translatable("lordsoutpost.rts.up"));
            Ui.button(g, font, hits, bx + 16, y + 2, 14, 10, Component.literal("▼"), !last, false, mx, my,
                    () -> Net.action(view.id, Action.MOVE_PROJECT, t -> { t.putLong("id", p.id()); t.putBoolean("up", false); }),
                    Component.translatable("lordsoutpost.rts.down"));
            Ui.button(g, font, hits, bx + 32, y + 2, 14, 10, Component.literal("✕"), true, false, mx, my,
                    () -> Net.action(view.id, Action.CANCEL_PROJECT, t -> t.putLong("id", p.id())),
                    Component.translatable("lordsoutpost.rts.cancel_order"));
            Ui.bar(g, x + 3, y + 14, w - 50, 7, p.progress(), Ui.GOOD);
            g.drawString(font, Math.round(p.progress() * 100) + "%", x + w - 44, y + 14, Ui.MUTED, false);
            g.drawString(font, Component.translatable("lordsoutpost.rts.builders", p.builders()), x + 3, y + 24, Ui.MUTED, false);
            Component lack;
            int color;
            if (!p.lack().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (var e : p.lack().keySet()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(e.label().getString());
                }
                lack = Component.translatable("lordsoutpost.rts.lack", sb.toString());
                color = Ui.BAD;
            } else {
                StringBuilder sb = new StringBuilder();
                for (var e : p.remaining().entrySet()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(e.getKey().label().getString()).append(" ").append(e.getValue());
                }
                lack = Component.translatable("lordsoutpost.rts.remaining", sb.length() == 0 ? "—" : sb.toString());
                color = Ui.MUTED;
            }
            Ui.text(g, font, lack, x + 3, y + 33, w - 6, color);
            // Клик по карточке — показать заказ камерой.
            hits.add(x, y + 12, w - 50, 30, () -> RtsCamera.focusOn(p.x() + 0.5, p.z() + 0.5), Component.translatable("lordsoutpost.rts.show"));
            y += 46;
        }
    }

    private void renderIcon(GuiGraphics g, Resource r, int x, int y) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75f, 0.75f, 1f);
        g.renderItem(new ItemStack(r.icon), 0, 0);
        g.pose().popPose();
    }

    @Override
    public void tick() {
        if (!RtsCamera.active()) onClose();
    }

    static float clampAlpha(float a) {
        return Mth.clamp(a, 0, 1);
    }
}
