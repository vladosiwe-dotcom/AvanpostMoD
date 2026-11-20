package com.vladosiwe.lordsoutpost.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vladosiwe.lordsoutpost.data.OutpostDirector;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostTask;
import com.vladosiwe.lordsoutpost.outpost.Role;
import com.vladosiwe.lordsoutpost.outpost.WorkZone;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/**
 * Команды Лорда (без прав администратора): что сейчас делает поселение и перенос рабочих зон.
 * /settlement tasks — задачи жителей, приоритеты планировщика, проблемы зон;
 * /settlement zone &lt;зона&gt; here | reset — перенести зону к себе или вернуть её на место по чертежу.
 */
public final class SettlementCommands {
    private SettlementCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("settlement")
                .then(Commands.literal("tasks").executes(SettlementCommands::tasks))
                .then(Commands.literal("zone")
                        .then(Commands.argument("zone", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(WorkZone.VALUES).filter(z -> z.relocatable).map(z -> z.id), b))
                                .then(Commands.literal("here").executes(c -> zone(c, true)))
                                .then(Commands.literal("reset").executes(c -> zone(c, false))))));
    }

    /** Аванпост, Лордом которого является игрок. */
    private static Outpost own(CommandContext<CommandSourceStack> c) {
        if (!(c.getSource().getEntity() instanceof ServerPlayer player)) {
            c.getSource().sendFailure(Component.translatable("lordsoutpost.msg.not_lord"));
            return null;
        }
        Outpost o = OutpostSavedData.get(player.server).lordOutpost(player.getUUID());
        if (o == null) c.getSource().sendFailure(Component.translatable("lordsoutpost.msg.not_lord"));
        return o;
    }

    private static int tasks(CommandContext<CommandSourceStack> c) {
        Outpost o = own(c);
        if (o == null) return 0;
        CommandSourceStack src = c.getSource();
        src.sendSuccess(() -> Component.translatable("lordsoutpost.settlement.header", String.format("%.1f", OutpostDirector.foodDays(o)),
                o.scarce == null ? "—" : o.scarce.id, o.foodCritical ? "!" : "").withStyle(ChatFormatting.GOLD), false);
        StringBuilder pr = new StringBuilder();
        for (Role r : Role.VALUES) {
            int p = o.priorities.getOrDefault(r, OutpostDirector.BASE);
            if (p != OutpostDirector.BASE) pr.append(r.id).append('=').append(p).append(' ');
        }
        if (!pr.isEmpty()) src.sendSuccess(() -> Component.translatable("lordsoutpost.settlement.priorities", pr.toString().trim()), false);
        int n = 0;
        for (OutpostTask t : o.tasks.all()) {
            if (!t.active()) continue;
            n++;
            var who = t.executor == null ? null : o.resident(t.executor);
            var subject = t.subject == null ? null : o.resident(t.subject);
            String line = "#" + t.id + " " + t.type.name().toLowerCase(java.util.Locale.ROOT) + " [" + t.state.name().toLowerCase(java.util.Locale.ROOT) + "]"
                    + (who == null ? "" : " " + who.name) + (subject == null ? "" : " → " + subject.name)
                    + (t.reserved.isEmpty() ? "" : " reserved " + t.reserved.values().stream().mapToInt(Integer::intValue).sum())
                    + (t.reason.isEmpty() ? "" : " (" + t.reason + ")");
            src.sendSuccess(() -> Component.literal(line), false);
        }
        if (n == 0) src.sendSuccess(() -> Component.translatable("lordsoutpost.settlement.no_tasks"), false);
        for (WorkZone z : WorkZone.VALUES) {
            BlockPos moved = o.zones.override(z);
            String problem = o.zones.problem(z);
            if (moved == null && problem == null) continue;
            String line = z.id + (moved == null ? "" : " @" + moved.toShortString()) + (problem == null ? "" : " — " + problem);
            src.sendSuccess(() -> Component.literal(line).withStyle(problem == null ? ChatFormatting.GRAY : ChatFormatting.YELLOW), false);
        }
        return n;
    }

    private static int zone(CommandContext<CommandSourceStack> c, boolean here) {
        Outpost o = own(c);
        if (o == null) return 0;
        WorkZone zone = WorkZone.byId(StringArgumentType.getString(c, "zone"));
        if (zone == null || !zone.relocatable) {
            c.getSource().sendFailure(Component.translatable("lordsoutpost.settlement.zone_fixed"));
            return 0;
        }
        ServerPlayer player = (ServerPlayer) c.getSource().getEntity();
        String error = relocate(player, o, zone, here);
        if (error != null) {
            c.getSource().sendFailure(Component.translatable(error));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.translatable("lordsoutpost.settlement.zone_done", zone.id), false);
        return 1;
    }

    /**
     * Переносит зону туда, где стоит Лорд (или возвращает её на место по чертежу). Общий путь для команды и меню.
     * Возвращает ключ ошибки или null.
     */
    public static String relocate(ServerPlayer player, Outpost o, WorkZone zone, boolean here) {
        if (!zone.relocatable) return "lordsoutpost.settlement.zone_fixed";
        ServerLevel level = player.server.overworld();
        long now = level.getGameTime();
        if (!here) {
            o.zones.relocate(zone, null);
            o.log(now, "lordsoutpost.event.zone_reset", zone.id);
        } else {
            BlockPos at = player.blockPosition();
            if (player.level().dimension() != Level.OVERWORLD || !o.insideWalls(at)) return "lordsoutpost.settlement.zone_outside";
            if (zone == WorkZone.FARM && !tillable(level, at)) return "lordsoutpost.settlement.zone_no_soil";
            o.zones.relocate(zone, at);
            o.log(now, "lordsoutpost.event.zone_moved", zone.id, at.toShortString());
        }
        o.dirtyForClients = true;
        OutpostSavedData.get(level).setDirty();
        return null;
    }

    /** Под новой грядкой должна быть земля: хотя бы половина клеток 5×5 вспахивается или уже вспахана. */
    private static boolean tillable(ServerLevel level, BlockPos center) {
        int good = 0;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockState soil = level.getBlockState(center.offset(dx, -1, dz));
                BlockState above = level.getBlockState(center.offset(dx, 0, dz));
                if ((soil.is(Blocks.DIRT) || soil.is(Blocks.GRASS_BLOCK) || soil.is(Blocks.FARMLAND)) && (above.isAir() || above.is(Blocks.WHEAT))) good++;
            }
        return good >= 12;
    }
}
