package com.vladosiwe.lordsoutpost.network;

import com.mojang.authlib.GameProfile;
import com.vladosiwe.lordsoutpost.data.Caravans;
import com.vladosiwe.lordsoutpost.data.Diplomacy;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.data.QuestBoard;
import com.vladosiwe.lordsoutpost.data.ResourceItems;
import com.vladosiwe.lordsoutpost.data.Simulation;
import com.vladosiwe.lordsoutpost.outpost.AccessLevel;
import com.vladosiwe.lordsoutpost.outpost.AccessRules;
import com.vladosiwe.lordsoutpost.outpost.Law;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Pact;
import com.vladosiwe.lordsoutpost.outpost.Quest;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Squad;
import com.vladosiwe.lordsoutpost.protection.Access;
import com.vladosiwe.lordsoutpost.registry.ModBlocks;
import com.vladosiwe.lordsoutpost.registry.ModItems;
import com.vladosiwe.lordsoutpost.server.Captures;
import com.vladosiwe.lordsoutpost.server.Notify;
import com.vladosiwe.lordsoutpost.server.OutpostManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Обработка запросов клиента. Клиенту не доверяем ничему: проверяются владение, уровень доступа,
 * допустимость значений и расстояния. Неверный запрос молча отбрасывается.
 */
public final class ServerActions {
    private static final double COMMAND_RANGE = 200;

    private ServerActions() {}

    public static void handle(ServerPlayer player, Payloads.OutpostAction payload) {
        if (payload.action() < 0 || payload.action() >= Action.VALUES.length) return;
        Action action = Action.VALUES[payload.action()];
        if (action == Action.CLOSE) {
            ModNetwork.closed(player);
            return;
        }
        ServerLevel level = player.server.overworld();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = data.byId(payload.outpost());
        if (o == null || o.state != OutpostState.OWNED) return;
        AccessLevel access = Access.level(data, o, player.getUUID());
        CompoundTag a = payload.args();
        long now = level.getGameTime();

        if (action == Action.DELIVER) {
            if (access.atLeast(AccessLevel.ALLY)) deliver(player, o, a.getInt("quest"), now);
        } else if (access == AccessLevel.LORD) {
            lordAction(player, level, data, o, action, a, now);
        } else {
            return;
        }
        o.dirtyForClients = true;
        data.setDirty();
        if (o.state == OutpostState.OWNED) ModNetwork.refresh(player, data, o);
    }

    private static void lordAction(ServerPlayer player, ServerLevel level, OutpostSavedData data, Outpost o, Action action, CompoundTag a, long now) {
        switch (action) {
            case RENAME -> {
                String name = a.getString("name").trim();
                if (OutpostManager.validName(name)) o.name = name;
            }
            case SET_LAW -> {
                Law law = Law.byId(a.getString("law"));
                if (law == o.law) return;
                if (now - o.lawChangedAt < Simulation.DAY) {
                    feedback(player, "lordsoutpost.msg.law_cooldown", ChatFormatting.YELLOW);
                    return;
                }
                o.law = law;
                o.lawChangedAt = now;
                o.log(now, "lordsoutpost.event.law", law.id);
            }
            case SET_ACCESS -> {
                AccessLevel lvl = AccessLevel.byId(a.getString("level"));
                if (lvl == AccessLevel.LORD) return;
                UUID id;
                String name;
                if (a.hasUUID("id")) {
                    id = a.getUUID("id");
                    AccessRules.Entry old = o.access.players.get(id);
                    if (old == null) return;
                    name = old.name();
                } else {
                    String typed = a.getString("name").trim();
                    if (typed.isEmpty() || typed.length() > 16) return;
                    ServerPlayer online = player.server.getPlayerList().getPlayerByName(typed);
                    Optional<GameProfile> profile = online != null ? Optional.of(online.getGameProfile())
                            : player.server.getProfileCache() == null ? Optional.empty() : player.server.getProfileCache().get(typed);
                    if (profile.isEmpty()) {
                        feedback(player, "lordsoutpost.msg.unknown_player", ChatFormatting.RED);
                        return;
                    }
                    id = profile.get().getId();
                    name = profile.get().getName();
                }
                if (id.equals(o.owner) || (o.access.players.size() >= 64 && !o.access.players.containsKey(id))) return;
                o.access.players.put(id, new AccessRules.Entry(name, lvl));
            }
            case REMOVE_ACCESS -> {
                if (a.hasUUID("id")) o.access.players.remove(a.getUUID("id"));
            }
            case SET_DEFAULT_ACCESS -> {
                AccessLevel lvl = AccessLevel.byId(a.getString("level"));
                if (lvl == AccessLevel.GUEST || lvl == AccessLevel.HOSTILE) o.access.defaultLevel = lvl;
            }
            case TOGGLE -> {
                boolean value = a.getBoolean("value");
                switch (a.getInt("flag")) {
                    case 0 -> o.access.autoAllyPacts = value;
                    case 1 -> o.access.alliesTribute = value;
                    case 2 -> o.access.protectBlocks = value;
                    case 3 -> o.autoCaravans = value;
                    default -> { }
                }
            }
            case SQUAD_ORDER -> {
                Squad s = squad(o, a);
                int order = a.getInt("order");
                if (s == null || order < 0 || order >= Squad.Order.VALUES.length) return;
                Squad.Order ord = Squad.Order.VALUES[order];
                // Сопровождать Лорда может только Свита.
                if (ord == Squad.Order.RETINUE && s.id != Squad.RETINUE) return;
                s.order = ord;
            }
            case SQUAD_ROUTE -> {
                Squad s = squad(o, a);
                if (s == null) return;
                List<BlockPos> route = new ArrayList<>();
                for (long l : a.getLongArray("route")) {
                    BlockPos p = BlockPos.of(l);
                    if (route.size() < Squad.MAX_WAYPOINTS && o.distanceSqr(p.getX(), p.getZ()) <= COMMAND_RANGE * COMMAND_RANGE) route.add(p);
                }
                s.route.clear();
                s.route.addAll(route);
                if (route.size() >= 2) s.order = Squad.Order.PATROL;
            }
            case SQUAD_HOLD -> {
                Squad s = squad(o, a);
                if (s == null) return;
                if (!a.contains("pos")) {
                    s.hold = null;
                    if (s.order == Squad.Order.HOLD) s.order = Squad.Order.POSTS;
                    return;
                }
                BlockPos p = BlockPos.of(a.getLong("pos"));
                if (o.distanceSqr(p.getX(), p.getZ()) > COMMAND_RANGE * COMMAND_RANGE) return;
                s.hold = p;
                s.order = Squad.Order.HOLD;
            }
            case ASSIGN -> {
                int squad = a.getInt("squad");
                if (!a.hasUUID("resident") || squad < 0 || squad >= Squad.COUNT) return;
                Resident r = o.resident(a.getUUID("resident"));
                if (r != null && r.role.combat) r.squad = squad;
            }
            case PACT -> {
                Outpost target = data.byId(a.getLong("target"));
                int pact = a.getInt("pact");
                if (target == null || pact <= 0 || pact >= Pact.VALUES.length) return;
                if (!data.knowledge(player.getUUID()).discovered.contains(target.id)) return;
                Diplomacy.Result result = Diplomacy.propose(data, o, target, Pact.VALUES[pact], now);
                feedback(player, "lordsoutpost.msg.pact." + result.name().toLowerCase(Locale.ROOT),
                        result == Diplomacy.Result.OK || result == Diplomacy.Result.OFFERED ? ChatFormatting.GREEN : ChatFormatting.RED);
                if (result == Diplomacy.Result.OFFERED) {
                    Notify.lord(player.server, target, Component.translatable("lordsoutpost.msg.pact_incoming", o.name, Pact.VALUES[pact].label()));
                }
            }
            case BREAK_PACT -> {
                Outpost target = data.byId(a.getLong("target"));
                if (target != null) Diplomacy.breakPact(data, o, target, now);
            }
            case CARAVAN -> {
                Outpost target = data.byId(a.getLong("target"));
                if (target == null) return;
                Caravans.Result result = Caravans.dispatch(data, o, target, now);
                feedback(player, "lordsoutpost.msg.caravan." + result.name().toLowerCase(Locale.ROOT),
                        result == Caravans.Result.OK ? ChatFormatting.GREEN : ChatFormatting.RED);
            }
            case ABANDON -> {
                BlockPos standard = o.standard;
                OutpostManager.abandon(data, o, now);
                if (standard != null && level.isLoaded(standard) && level.getBlockState(standard).is(ModBlocks.LORD_STANDARD.get())) {
                    level.setBlockAndUpdate(standard, Blocks.AIR.defaultBlockState());
                    ItemStack item = new ItemStack(ModItems.LORD_STANDARD.get());
                    if (!player.getInventory().add(item)) player.drop(item, false);
                }
                feedback(player, "lordsoutpost.msg.abandoned", ChatFormatting.YELLOW);
                player.refreshDisplayName();
                player.refreshTabListName();
                ModNetwork.closed(player);
            }
            default -> { }
        }
    }

    private static Squad squad(Outpost o, CompoundTag a) {
        int id = a.getInt("squad");
        return id < 0 || id >= Squad.COUNT ? null : o.squads[id];
    }

    private static void feedback(ServerPlayer player, String key, ChatFormatting color) {
        player.displayClientMessage(Component.translatable(key).withStyle(color), true);
    }

    /** Игрок сдаёт ресурс по заданию Доски из своего инвентаря. */
    private static void deliver(ServerPlayer player, Outpost o, int questId, long now) {
        if (!o.contains(player.blockPosition())) {
            feedback(player, "lordsoutpost.msg.deliver_far", ChatFormatting.YELLOW);
            return;
        }
        Quest quest = null;
        for (Quest q : o.quests) if (q.id == questId) quest = q;
        if (quest == null || quest.done()) return;
        double before = quest.delivered;
        Quest done = null;
        var inv = player.getInventory();
        for (int slot = 0; slot < inv.items.size() && done == null; slot++) {
            ItemStack stack = inv.items.get(slot);
            ResourceItems.Value v = ResourceItems.of(stack);
            if (v == null || v.resource() != quest.resource) continue;
            double remaining = quest.needed - quest.delivered;
            int take = (int) Math.min(stack.getCount(), Math.ceil(remaining / v.perItem()));
            if (take <= 0) break;
            stack.shrink(take);
            done = QuestBoard.deliver(o, quest.resource, take * v.perItem(), now);
        }
        if (done != null) {
            Captures.questReward(player, o, done);
        } else if (quest.delivered > before) {
            player.displayClientMessage(Component.translatable("lordsoutpost.msg.delivered", (int) quest.delivered, quest.needed).withStyle(ChatFormatting.GREEN), true);
        } else {
            feedback(player, "lordsoutpost.msg.nothing_to_deliver", ChatFormatting.YELLOW);
        }
    }
}
