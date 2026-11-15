package com.vladosiwe.lordsoutpost.client.gui;

import com.vladosiwe.lordsoutpost.client.Net;
import com.vladosiwe.lordsoutpost.client.map.MapScreen;
import com.vladosiwe.lordsoutpost.network.Action;
import com.vladosiwe.lordsoutpost.network.ModNetwork;
import com.vladosiwe.lordsoutpost.outpost.AccessLevel;
import com.vladosiwe.lordsoutpost.outpost.AccessRules;
import com.vladosiwe.lordsoutpost.outpost.Law;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Pact;
import com.vladosiwe.lordsoutpost.outpost.Quest;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Resource;
import com.vladosiwe.lordsoutpost.outpost.Role;
import com.vladosiwe.lordsoutpost.outpost.Squad;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Окно управления аванпостом. Пять вкладок: Обзор, Гарнизон, Доступ и Права, Задания, Дипломатия.
 * Окно ничего не решает само: каждая кнопка отправляет запрос, сервер проверяет его и присылает новый снимок.
 */
public class OutpostScreen extends Screen {
    private static final int W = 384, H = 238;
    private static final int TAB_OVERVIEW = 0, TAB_GARRISON = 1, TAB_ACCESS = 2, TAB_QUESTS = 3, TAB_DIPLOMACY = 4;
    private static final String[] TABS = {"overview", "garrison", "access", "quests", "diplomacy"};
    private static final int[] SQUAD_COLORS = {0xFF5AA9FF, 0xFF58D68D, 0xFFE2B44C};

    private OutpostView view;
    private int tab;
    private boolean naming;
    private int left, top;
    private final Ui.Hits hits = new Ui.Hits();
    private EditBox nameBox;
    private EditBox playerBox;
    private int scroll;
    private long abandonArmed;

    public OutpostScreen(OutpostView view, int tab) {
        super(Component.translatable("lordsoutpost.ui.title"));
        this.view = view;
        this.naming = tab == ModNetwork.TAB_NAME;
        this.tab = naming ? TAB_OVERVIEW : Math.max(0, Math.min(4, tab));
    }

    public void update(OutpostView fresh) {
        if (fresh.id != view.id) return;
        if (fresh.state != OutpostState.OWNED) {
            onClose();
            return;
        }
        view = fresh;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        String oldName = nameBox == null ? view.name : nameBox.getValue();
        String oldPlayer = playerBox == null ? "" : playerBox.getValue();
        nameBox = new EditBox(font, left + 110, top + 100, 164, 16, Component.translatable("lordsoutpost.ui.name"));
        nameBox.setMaxLength(32);
        nameBox.setValue(oldName);
        playerBox = new EditBox(font, left + 206, top + 62, 168, 14, Component.translatable("lordsoutpost.ui.player_name"));
        playerBox.setMaxLength(16);
        playerBox.setValue(oldPlayer);
        addRenderableWidget(nameBox);
        addRenderableWidget(playerBox);
        syncWidgets();
        if (naming) setInitialFocus(nameBox);
    }

    private void syncWidgets() {
        nameBox.visible = naming;
        nameBox.active = naming;
        boolean accessTab = !naming && tab == TAB_ACCESS && view.lord();
        playerBox.visible = accessTab;
        playerBox.active = accessTab;
    }

    @Override
    public void removed() {
        Net.action(view.id, Action.CLOSE);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ============================== Отрисовка ==============================

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        hits.clear();
        syncWidgets();
        renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, left, top, W, H);
        g.fill(left + 1, top + 1, left + W - 1, top + 22, 0xFF151B26);

        // Шапка: имя, хозяин, тир, день.
        Component name = Component.literal(view.name.isEmpty() ? "—" : view.name);
        g.drawString(font, name, left + 8, top + 7, Ui.ACCENT, false);
        int nx = left + 12 + font.width(name);
        if (view.lord() && !naming) {
            Ui.button(g, font, hits, nx, top + 5, 12, 12, Component.literal("✎"), true, false, mouseX, mouseY, () -> {
                naming = true;
                nameBox.setValue(view.name);
                setFocused(nameBox);
            }, Component.translatable("lordsoutpost.ui.rename"));
        }
        Component sub = Component.translatable("lordsoutpost.ui.header", view.owner, view.tier, view.day);
        g.drawString(font, sub, left + W - 8 - font.width(sub), top + 7, Ui.MUTED, false);

        // Вкладки.
        int tw = (W - 16) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            int idx = i;
            Component label = Component.translatable("lordsoutpost.ui.tab." + TABS[i]);
            if (i == TAB_QUESTS && !view.quests.isEmpty()) label = label.copy().append(" (" + view.quests.size() + ")");
            Ui.button(g, font, hits, left + 8 + i * tw, top + 26, tw - 2, 14, label, !naming, tab == i && !naming, mouseX, mouseY, () -> {
                tab = idx;
                scroll = 0;
            }, null);
        }
        int cx = left + 8, cy = top + 46;
        if (naming) {
            drawNaming(g, mouseX, mouseY);
        } else {
            switch (tab) {
                case TAB_GARRISON -> drawGarrison(g, cx, cy, mouseX, mouseY);
                case TAB_ACCESS -> drawAccess(g, cx, cy, mouseX, mouseY);
                case TAB_QUESTS -> drawQuests(g, cx, cy, mouseX, mouseY);
                case TAB_DIPLOMACY -> drawDiplomacy(g, cx, cy, mouseX, mouseY);
                default -> drawOverview(g, cx, cy, mouseX, mouseY);
            }
        }
        for (var r : renderables) r.render(g, mouseX, mouseY, partialTick);
        Component tip = hits.tooltip(mouseX, mouseY);
        if (tip != null) g.renderTooltip(font, font.split(tip, 220), mouseX, mouseY);
    }

    private void drawNaming(GuiGraphics g, int mouseX, int mouseY) {
        g.drawCenteredString(font, Component.translatable("lordsoutpost.ui.naming"), left + W / 2, top + 70, Ui.TEXT);
        g.drawCenteredString(font, Component.translatable("lordsoutpost.ui.naming.hint"), left + W / 2, top + 82, Ui.MUTED);
        boolean ok = !nameBox.getValue().trim().isEmpty();
        Ui.button(g, font, hits, left + W / 2 - 62, top + 124, 60, 16, Component.translatable("lordsoutpost.ui.confirm"), ok, false, mouseX, mouseY, this::submitName, null);
        Ui.button(g, font, hits, left + W / 2 + 2, top + 124, 60, 16, Component.translatable("lordsoutpost.ui.later"), true, false, mouseX, mouseY, () -> naming = false, null);
    }

    private void submitName() {
        String name = nameBox.getValue().trim();
        if (!name.isEmpty()) Net.action(view.id, Action.RENAME, t -> t.putString("name", name));
        naming = false;
    }

    // ---------- Обзор ----------

    private void drawOverview(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        // Стройка.
        Component tierLine = view.complete ? Component.translatable("lordsoutpost.ui.tier_done", view.tier)
                : Component.translatable("lordsoutpost.ui.tier_progress", view.tier, view.tier + 1, Math.round(view.progress * 100));
        g.drawString(font, tierLine, x, y, Ui.TEXT, false);
        Ui.bar(g, x, y + 11, 176, 7, view.complete ? 1f : view.progress, view.stalled != null ? Ui.WARN : Ui.GOOD);
        if (view.stalled != null) {
            Ui.text(g, font, Component.translatable("lordsoutpost.ui.stalled", view.stalled.label()), x, y + 21, 176, Ui.WARN);
        } else if (!view.complete) {
            Ui.text(g, font, Component.translatable("lordsoutpost.ui.steps", view.steps, view.planSize), x, y + 21, 176, Ui.MUTED);
        }

        // Население.
        int py = y + 35;
        Component pop = Component.translatable("lordsoutpost.ui.population", view.people.size(), view.popCap);
        g.drawString(font, pop, x, py, view.starving ? Ui.BAD : Ui.TEXT, false);
        if (view.starving) g.drawString(font, Component.translatable("lordsoutpost.ui.starving"), x + font.width(pop) + 6, py, Ui.BAD, false);
        Role[] roles = {Role.BUILDER, Role.LUMBERJACK, Role.MINER, Role.HUNTER, Role.HERDER, Role.SMITH, Role.MAGE, Role.CARAVANEER};
        int col = 0, ry = py + 11;
        for (Role role : roles) {
            int n = view.count(role);
            if (n == 0) continue;
            Ui.text(g, font, role.label().copy().append(": " + n), x + col * 90, ry, 88, Ui.MUTED);
            if (++col == 2) {
                col = 0;
                ry += 9;
            }
        }
        if (col != 0) ry += 9;
        Ui.text(g, font, Component.translatable("lordsoutpost.ui.fighters", view.fighters()), x, ry, 176, Ui.MUTED);
        if (view.raid) Ui.text(g, font, Component.translatable("lordsoutpost.ui.raid"), x + 90, ry, 88, Ui.BAD);

        // Закон.
        int ly = y + 92;
        g.drawString(font, Component.translatable("lordsoutpost.ui.law"), x, ly, Ui.TEXT, false);
        if (view.lawCooldown > 0) {
            Component cd = Component.translatable("lordsoutpost.ui.law_cooldown", view.lawCooldown / 60);
            g.drawString(font, cd, x + 176 - font.width(cd), ly, Ui.MUTED, false);
        }
        Law hovered = null;
        for (int i = 0; i < Law.VALUES.length; i++) {
            Law law = Law.VALUES[i];
            boolean hover = Ui.button(g, font, hits, x + (i % 2) * 89, ly + 11 + (i / 2) * 15, 87, 13, law.label(),
                    view.lord() && (view.lawCooldown == 0 || law == view.law), law == view.law, mouseX, mouseY,
                    () -> Net.action(view.id, Action.SET_LAW, t -> t.putString("law", law.id)), null);
            if (hover) hovered = law;
        }
        Law shown = hovered == null ? view.law : hovered;
        List<net.minecraft.util.FormattedCharSequence> desc = font.split(shown.description(), 176);
        for (int i = 0; i < Math.min(3, desc.size()); i++) g.drawString(font, desc.get(i), x, ly + 44 + i * 9, Ui.MUTED, false);

        // Склад.
        int sx = x + 190, sy = y;
        g.drawString(font, Component.translatable("lordsoutpost.ui.stock"), sx, sy, Ui.TEXT, false);
        Component tribute = Component.translatable("lordsoutpost.ui.pen", view.pen, view.penCap);
        g.drawString(font, tribute, left + W - 8 - font.width(tribute), sy, Ui.MUTED, false);
        sy += 11;
        for (Resource r : Resource.VALUES) {
            double have = view.stock.getOrDefault(r, 0.0);
            int cap = view.cap.getOrDefault(r, 1);
            g.pose().pushPose();
            g.pose().translate(sx, sy - 2, 0);
            g.pose().scale(0.75f, 0.75f, 1f);
            g.renderItem(new ItemStack(r.icon), 0, 0);
            g.pose().popPose();
            boolean boosted = view.boosts.containsKey(r);
            Ui.text(g, font, r.label(), sx + 14, sy + 1, 54, boosted ? Ui.ACCENT : Ui.TEXT);
            if (r == Resource.GOLD) {
                g.drawString(font, String.valueOf((int) have), sx + 72, sy + 1, 0xFF000000 | r.color, false);
            } else {
                Ui.bar(g, sx + 70, sy, 72, 9, (float) (have / cap), 0xFF000000 | r.color);
                String val = (int) have + "/" + cap;
                g.drawString(font, val, sx + 146, sy + 1, Ui.MUTED, false);
            }
            if (boosted) hits.add(sx, sy, 186, 11, null, Component.translatable("lordsoutpost.ui.boosted", view.boosts.get(r) / 60));
            sy += 12;
        }

        // Хроника.
        int hy = y + 150;
        g.fill(x, hy - 4, left + W - 8, hy - 3, Ui.BORDER);
        g.drawString(font, Component.translatable("lordsoutpost.ui.chronicle"), x, hy, Ui.TEXT, false);
        if (view.lord()) {
            boolean armed = System.currentTimeMillis() - abandonArmed < 4000;
            Ui.button(g, font, hits, left + W - 98, hy - 2, 90, 12, Component.translatable(armed ? "lordsoutpost.ui.abandon_confirm" : "lordsoutpost.ui.abandon"),
                    true, armed, mouseX, mouseY, () -> {
                        if (System.currentTimeMillis() - abandonArmed < 4000) {
                            Net.action(view.id, Action.ABANDON);
                            onClose();
                        } else {
                            abandonArmed = System.currentTimeMillis();
                        }
                    }, Component.translatable("lordsoutpost.ui.abandon.tip"));
        }
        for (int i = 0; i < Math.min(3, view.chronicle.size()); i++) {
            OutpostView.Entry e = view.chronicle.get(i);
            Component line = Component.translatable("lordsoutpost.ui.day", e.day()).append(" ").append(OutpostView.describe(e));
            Ui.text(g, font, line, x, hy + 12 + i * 9, W - 16, i == 0 ? Ui.TEXT : Ui.MUTED);
        }
    }

    // ---------- Гарнизон ----------

    private void drawGarrison(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        int colW = (W - 16) / 3;
        boolean lord = view.lord();
        for (int s = 0; s < Squad.COUNT; s++) {
            int sx = x + s * colW, color = SQUAD_COLORS[s];
            OutpostView.SquadInfo info = s < view.squads.size() ? view.squads.get(s) : new OutpostView.SquadInfo(0, 0, false);
            Squad.Order order = Squad.Order.VALUES[Math.max(0, Math.min(3, info.order()))];
            List<OutpostView.Person> members = new ArrayList<>();
            for (OutpostView.Person p : view.people) if (p.role().combat && p.squad() == s) members.add(p);
            g.fill(sx, y, sx + colW - 4, y + 11, 0xFF151B26);
            g.fill(sx, y, sx + 2, y + 11, color);
            Ui.text(g, font, Component.translatable("lordsoutpost.squad." + s).append(" (" + members.size() + ")"), sx + 5, y + 2, colW - 12, color);
            Ui.text(g, font, Component.translatable("lordsoutpost.ui.order", order.label()), sx, y + 14, colW - 6, Ui.MUTED);

            // Приказы.
            int sq = s;
            int bw = (colW - 6) / 2;
            Ui.button(g, font, hits, sx, y + 25, bw, 12, Squad.Order.POSTS.label(), lord, order == Squad.Order.POSTS, mouseX, mouseY,
                    () -> order(sq, Squad.Order.POSTS), Component.translatable("lordsoutpost.order.posts.tip"));
            Ui.button(g, font, hits, sx + bw + 1, y + 25, bw, 12, Squad.Order.PATROL.label(), lord, order == Squad.Order.PATROL, mouseX, mouseY,
                    () -> order(sq, Squad.Order.PATROL), Component.translatable("lordsoutpost.order.patrol.tip"));
            Ui.button(g, font, hits, sx, y + 38, bw, 12, Component.translatable("lordsoutpost.ui.route", info.waypoints()), lord, false, mouseX, mouseY,
                    () -> Minecraft.getInstance().setScreen(new MapScreen(sq)), Component.translatable("lordsoutpost.map.route.tip"));
            if (s == Squad.RETINUE) {
                Ui.button(g, font, hits, sx + bw + 1, y + 38, bw, 12, Squad.Order.RETINUE.label(), lord, order == Squad.Order.RETINUE, mouseX, mouseY,
                        () -> order(sq, order == Squad.Order.RETINUE ? Squad.Order.POSTS : Squad.Order.RETINUE), Component.translatable("lordsoutpost.order.retinue.tip"));
            } else {
                Ui.text(g, font, order == Squad.Order.HOLD ? Squad.Order.HOLD.label() : Component.empty(), sx + bw + 4, y + 40, bw - 4, Ui.WARN);
            }

            // Бойцы: щелчок переводит в следующий отряд.
            int ry = y + 54;
            for (OutpostView.Person p : members) {
                if (ry > top + H - 22) break;
                boolean hover = mouseX >= sx && mouseX < sx + colW - 4 && mouseY >= ry && mouseY < ry + 11;
                if (hover) g.fill(sx, ry, sx + colW - 4, ry + 11, 0xFF232B3B);
                boolean out = p.status() != Resident.Status.ACTIVE;
                Ui.text(g, font, Component.literal(p.name()), sx + 2, ry + 2, 50, out ? Ui.MUTED : Ui.TEXT);
                Component role = p.status() == Resident.Status.WOUNDED ? Component.translatable("lordsoutpost.ui.wounded", p.heal())
                        : p.status() == Resident.Status.ON_CARAVAN ? Component.translatable("lordsoutpost.ui.on_caravan") : p.role().label();
                Ui.text(g, font, role, sx + 54, ry + 2, 38, p.status() == Resident.Status.WOUNDED ? Ui.BAD : Ui.MUTED);
                // Здоровье, броня и уровень снаряжения.
                Ui.bar(g, sx + 94, ry + 2, 16, 3, p.hp(), Ui.BAD);
                Ui.bar(g, sx + 94, ry + 6, 16, 3, p.armor(), 0xFF8FB7E8);
                for (int k = 0; k <= p.gear(); k++) g.fill(sx + 112 + k * 2, ry + 3, sx + 113 + k * 2, ry + 8, Ui.ACCENT);
                Component tip = Component.translatable("lordsoutpost.ui.fighter.tip", p.name(), p.role().label(), Math.round(p.hp() * 100),
                        Math.round(p.armor() * 100), Component.translatable("lordsoutpost.gear." + p.gear()), p.kills());
                UUID id = p.id();
                hits.add(sx, ry, colW - 4, 11, lord ? () -> Net.action(view.id, Action.ASSIGN, t -> { t.putUUID("resident", id); t.putInt("squad", (sq + 1) % Squad.COUNT); }) : null, tip);
                ry += 11;
            }
        }
        Ui.text(g, font, Component.translatable(lord ? "lordsoutpost.ui.garrison.hint" : "lordsoutpost.ui.lord_only"), x, top + H - 13, W - 16, Ui.MUTED);
    }

    private void order(int squad, Squad.Order order) {
        Net.action(view.id, Action.SQUAD_ORDER, t -> { t.putInt("squad", squad); t.putInt("order", order.ordinal()); });
    }

    // ---------- Доступ и Права ----------

    private void drawAccess(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        AccessRules rules = view.rules;
        if (rules == null) {
            g.drawCenteredString(font, Component.translatable("lordsoutpost.ui.lord_only"), left + W / 2, y + 60, Ui.MUTED);
            return;
        }
        // Слева: список игроков.
        g.drawString(font, Component.translatable("lordsoutpost.ui.access.list"), x, y, Ui.TEXT, false);
        int row = y + 12, idx = 0, visible = 14;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rules.players.size() - visible)));
        for (Map.Entry<UUID, AccessRules.Entry> e : rules.players.entrySet()) {
            if (idx++ < scroll) continue;
            if (idx - scroll > visible) break;
            AccessLevel lvl = e.getValue().level();
            Ui.text(g, font, Component.literal(e.getValue().name()), x + 2, row + 2, 84, Ui.TEXT);
            UUID id = e.getKey();
            AccessLevel next = lvl == AccessLevel.ALLY ? AccessLevel.GUEST : lvl == AccessLevel.GUEST ? AccessLevel.HOSTILE : AccessLevel.ALLY;
            g.fill(x + 88, row + 2, x + 91, row + 9, 0xFF000000 | lvl.color);
            Ui.button(g, font, hits, x + 94, row, 74, 11, lvl.label(), true, false, mouseX, mouseY,
                    () -> Net.action(view.id, Action.SET_ACCESS, t -> { t.putUUID("id", id); t.putString("level", next.id); }),
                    Component.translatable("lordsoutpost.access." + lvl.id + ".desc"));
            Ui.button(g, font, hits, x + 170, row, 12, 11, Component.literal("×"), true, false, mouseX, mouseY,
                    () -> Net.action(view.id, Action.REMOVE_ACCESS, t -> t.putUUID("id", id)), Component.translatable("lordsoutpost.ui.access.remove"));
            row += 12;
        }
        if (rules.players.isEmpty()) Ui.text(g, font, Component.translatable("lordsoutpost.ui.access.empty"), x + 2, row + 2, 180, Ui.MUTED);

        // Справа: добавить игрока и переключатели.
        int rx = x + 198;
        g.drawString(font, Component.translatable("lordsoutpost.ui.access.add"), rx, y, Ui.TEXT, false);
        AccessLevel[] levels = {AccessLevel.ALLY, AccessLevel.GUEST, AccessLevel.HOSTILE};
        boolean typed = !playerBox.getValue().trim().isEmpty();
        for (int i = 0; i < levels.length; i++) {
            AccessLevel lvl = levels[i];
            Ui.button(g, font, hits, rx + i * 57, y + 34, 55, 13, lvl.label(), typed, false, mouseX, mouseY, () -> {
                String name = playerBox.getValue().trim();
                Net.action(view.id, Action.SET_ACCESS, t -> { t.putString("name", name); t.putString("level", lvl.id); });
                playerBox.setValue("");
            }, Component.translatable("lordsoutpost.access." + lvl.id + ".desc"));
        }
        int ty = y + 56;
        g.drawString(font, Component.translatable("lordsoutpost.ui.access.default"), rx, ty + 2, Ui.TEXT, false);
        AccessLevel def = rules.defaultLevel;
        Ui.button(g, font, hits, rx + 112, ty, 58, 12, def.label(), true, false, mouseX, mouseY,
                () -> Net.action(view.id, Action.SET_DEFAULT_ACCESS, t -> t.putString("level", def == AccessLevel.GUEST ? "hostile" : "guest")),
                Component.translatable("lordsoutpost.ui.access.default.tip"));
        ty += 20;
        toggle(g, rx, ty, 0, rules.autoAllyPacts, "auto_ally", mouseX, mouseY);
        toggle(g, rx, ty + 16, 1, rules.alliesTribute, "allies_tribute", mouseX, mouseY);
        toggle(g, rx, ty + 32, 2, rules.protectBlocks, "protect", mouseX, mouseY);
        int ky = ty + 54;
        for (AccessLevel lvl : new AccessLevel[]{AccessLevel.ALLY, AccessLevel.GUEST, AccessLevel.HOSTILE}) {
            g.fill(rx, ky + 1, rx + 3, ky + 8, 0xFF000000 | lvl.color);
            Ui.text(g, font, lvl.label().copy().append(" — ").append(Component.translatable("lordsoutpost.access." + lvl.id + ".short")), rx + 6, ky, 166, Ui.MUTED);
            ky += 10;
        }
    }

    private void toggle(GuiGraphics g, int x, int y, int flag, boolean value, String key, int mouseX, int mouseY) {
        Ui.button(g, font, hits, x, y, 22, 12, Component.translatable(value ? "lordsoutpost.ui.on" : "lordsoutpost.ui.off"), true, value, mouseX, mouseY,
                () -> Net.action(view.id, Action.TOGGLE, t -> { t.putInt("flag", flag); t.putBoolean("value", !value); }),
                Component.translatable("lordsoutpost.ui.toggle." + key + ".tip"));
        Ui.text(g, font, Component.translatable("lordsoutpost.ui.toggle." + key), x + 26, y + 2, 146, Ui.TEXT);
    }

    // ---------- Задания ----------

    private void drawQuests(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        g.drawString(font, Component.translatable("lordsoutpost.ui.quests.title"), x, y, Ui.TEXT, false);
        int row = y + 14;
        if (view.quests.isEmpty()) {
            Ui.text(g, font, Component.translatable("lordsoutpost.ui.quests.empty"), x, row, W - 16, Ui.MUTED);
            row += 14;
        }
        for (Quest q : view.quests) {
            g.fill(x, row, left + W - 8, row + 24, 0xFF151B26);
            g.renderItem(new ItemStack(q.resource.icon), x + 4, row + 4);
            Ui.text(g, font, q.title(), x + 26, row + 3, 190, Ui.TEXT);
            Ui.bar(g, x + 26, row + 14, 150, 6, (float) (q.delivered / q.needed), 0xFF000000 | q.resource.color);
            g.drawString(font, (int) q.delivered + "/" + q.needed, x + 180, row + 13, Ui.MUTED, false);
            Component reward = Component.translatable("lordsoutpost.ui.quests.reward", q.rewardGold);
            g.drawString(font, reward, x + 226, row + 3, Ui.ACCENT, false);
            int qid = q.id;
            Ui.button(g, font, hits, left + W - 70, row + 5, 58, 14, Component.translatable("lordsoutpost.ui.quests.deliver"), true, false, mouseX, mouseY,
                    () -> Net.action(view.id, Action.DELIVER, t -> t.putInt("quest", qid)), Component.translatable("lordsoutpost.ui.quests.deliver.tip"));
            row += 27;
        }
        row += 4;
        for (net.minecraft.util.FormattedCharSequence line : font.split(Component.translatable("lordsoutpost.ui.quests.hint"), W - 16)) {
            g.drawString(font, line, x, row, Ui.MUTED, false);
            row += 9;
        }
        row += 4;
        for (Map.Entry<Resource, Integer> e : view.boosts.entrySet()) {
            Ui.text(g, font, Component.translatable("lordsoutpost.ui.quests.boost", e.getKey().label(), e.getValue() / 60), x, row, W - 16, Ui.ACCENT);
            row += 9;
        }
    }

    // ---------- Дипломатия ----------

    private void drawDiplomacy(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        if (!view.lord()) {
            g.drawCenteredString(font, Component.translatable("lordsoutpost.ui.lord_only"), left + W / 2, y + 60, Ui.MUTED);
            return;
        }
        int gold = (int) Math.floor(view.stock.getOrDefault(Resource.GOLD, 0.0));
        g.drawString(font, Component.translatable("lordsoutpost.ui.diplomacy.title"), x, y, Ui.TEXT, false);
        Component treasury = Component.translatable("lordsoutpost.ui.diplomacy.treasury", gold);
        g.drawString(font, treasury, left + W - 8 - font.width(treasury), y, Ui.ACCENT, false);
        int row = y + 13, visible = 6;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, view.neighbors.size() - visible)));
        if (view.neighbors.isEmpty()) Ui.text(g, font, Component.translatable("lordsoutpost.ui.diplomacy.empty"), x, row, W - 16, Ui.MUTED);
        for (int i = scroll; i < Math.min(view.neighbors.size(), scroll + visible); i++) {
            OutpostView.Neighbor n = view.neighbors.get(i);
            boolean incoming = n.incoming() > n.pact();
            g.fill(x, row, left + W - 8, row + 15, incoming ? 0xFF2A3320 : 0xFF151B26);
            int color = n.state() == OutpostState.WILD ? (n.pact() > 0 ? 0xFFE8A04C : Ui.BAD) : n.pact() > 0 ? Ui.GOOD : 0xFF5AA9FF;
            Ui.text(g, font, n.label(), x + 3, row + 4, 92, color);
            g.drawString(font, n.dist() + "m", x + 98, row + 4, Ui.MUTED, false);
            Pact pact = Pact.VALUES[Math.max(0, Math.min(2, n.pact()))];
            Ui.text(g, font, incoming ? Component.translatable("lordsoutpost.ui.diplomacy.incoming") : n.offered() > n.pact()
                    ? Component.translatable("lordsoutpost.ui.diplomacy.offered") : pact.label(), x + 132, row + 4, 62, incoming ? Ui.ACCENT : Ui.MUTED);
            long target = n.id();
            int bx = x + 196;
            if (n.pact() < 2) {
                int want = n.pact() + 1;
                int cost = want == 1 ? n.costTrade() : n.costMilitary();
                Component label = incoming ? Component.translatable("lordsoutpost.ui.diplomacy.accept")
                        : Component.translatable(want == 1 ? "lordsoutpost.ui.diplomacy.trade" : "lordsoutpost.ui.diplomacy.military", cost);
                Ui.button(g, font, hits, bx, row + 1, 74, 13, label, gold >= cost, false, mouseX, mouseY,
                        () -> Net.action(view.id, Action.PACT, t -> { t.putLong("target", target); t.putInt("pact", want); }),
                        Component.translatable(want == 1 ? "lordsoutpost.map.pact_trade.tip" : "lordsoutpost.map.pact_military.tip"));
            }
            if (n.pact() > 0) {
                boolean busy = false;
                for (OutpostView.CaravanInfo c : view.caravans) if (c.target() == target) busy = true;
                Ui.button(g, font, hits, bx + 76, row + 1, 58, 13, Component.translatable("lordsoutpost.ui.diplomacy.caravan"), !busy, false, mouseX, mouseY,
                        () -> Net.action(view.id, Action.CARAVAN, t -> t.putLong("target", target)),
                        Component.translatable("lordsoutpost.ui.diplomacy.caravan.tip", n.caravans(), Math.round(n.road() * 100)));
                Ui.button(g, font, hits, bx + 136, row + 1, 12, 13, Component.literal("×"), true, false, mouseX, mouseY,
                        () -> Net.action(view.id, Action.BREAK_PACT, t -> t.putLong("target", target)), Component.translatable("lordsoutpost.map.break"));
            }
            row += 17;
        }

        // Караваны в пути.
        int cy = top + H - 62;
        g.fill(x, cy - 4, left + W - 8, cy - 3, Ui.BORDER);
        g.drawString(font, Component.translatable("lordsoutpost.ui.caravans", view.caravans.size(), view.count(Role.CARAVANEER)), x, cy, Ui.TEXT, false);
        Ui.button(g, font, hits, left + W - 30, cy - 2, 22, 12, Component.translatable(view.autoCaravans ? "lordsoutpost.ui.on" : "lordsoutpost.ui.off"),
                true, view.autoCaravans, mouseX, mouseY, () -> Net.action(view.id, Action.TOGGLE, t -> { t.putInt("flag", 3); t.putBoolean("value", !view.autoCaravans); }),
                Component.translatable("lordsoutpost.ui.toggle.auto_caravans.tip"));
        Component auto = Component.translatable("lordsoutpost.ui.toggle.auto_caravans");
        g.drawString(font, auto, left + W - 34 - font.width(auto), cy, Ui.MUTED, false);
        int ry = cy + 12;
        if (view.caravans.isEmpty()) {
            Ui.text(g, font, Component.translatable("lordsoutpost.ui.caravans.none", view.surplus), x, ry, W - 16, Ui.MUTED);
        }
        for (int i = 0; i < Math.min(3, view.caravans.size()); i++) {
            OutpostView.CaravanInfo c = view.caravans.get(i);
            OutpostView.Neighbor n = view.neighbor(c.target());
            Component where = n == null ? Component.literal("?") : n.label();
            Ui.text(g, font, Component.translatable("lordsoutpost.ui.caravan.phase." + c.phase(), where), x, ry + 1, 170, Ui.TEXT);
            Ui.bar(g, x + 176, ry + 1, 120, 7, c.phase() == 1 ? 1f : c.progress(), Ui.ACCENT);
            g.drawString(font, Component.translatable("lordsoutpost.ui.caravan.escort", c.escort()), x + 302, ry + 1, Ui.MUTED, false);
            ry += 11;
        }
    }

    // ============================== Ввод ==============================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && hits.click(mouseX, mouseY)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (naming && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            submitName();
            return true;
        }
        if (naming && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            naming = false;
            return true;
        }
        boolean typing = (nameBox.isFocused() && nameBox.visible) || (playerBox.isFocused() && playerBox.visible);
        if (!typing && minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
