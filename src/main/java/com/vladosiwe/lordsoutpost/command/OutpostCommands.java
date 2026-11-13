package com.vladosiwe.lordsoutpost.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.vladosiwe.lordsoutpost.blueprint.Layout;
import com.vladosiwe.lordsoutpost.blueprint.Palette;
import com.vladosiwe.lordsoutpost.data.ALifeSimulationManager;
import com.vladosiwe.lordsoutpost.data.Discovery;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.data.Simulation;
import com.vladosiwe.lordsoutpost.outpost.Difficulty;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Resource;
import com.vladosiwe.lordsoutpost.registry.ModItems;
import com.vladosiwe.lordsoutpost.server.OutpostManager;
import com.vladosiwe.lordsoutpost.world.OutpostSites;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Команды администратора (уровень прав 2) для проверки и настройки:
 * /outpost nearest | list | info | create | capture | tier | sim | stock | raid | remove | reveal
 */
public final class OutpostCommands {
    private OutpostCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("outpost").requires(s -> s.hasPermission(2))
                .then(Commands.literal("nearest").executes(OutpostCommands::nearest))
                .then(Commands.literal("list").executes(OutpostCommands::list))
                .then(Commands.literal("info").executes(OutpostCommands::info))
                .then(Commands.literal("reveal").executes(OutpostCommands::reveal))
                .then(Commands.literal("create")
                        .then(Commands.argument("skulls", IntegerArgumentType.integer(1, 5))
                                .executes(c -> create(c, null, null))
                                .then(Commands.argument("layout", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(Layout.VALUES).map(l -> l.id), b))
                                        .executes(c -> create(c, StringArgumentType.getString(c, "layout"), null))
                                        .then(Commands.argument("palette", StringArgumentType.word())
                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(Palette.VALUES).map(p -> p.id), b))
                                                .executes(c -> create(c, StringArgumentType.getString(c, "layout"), StringArgumentType.getString(c, "palette")))))))
                .then(Commands.literal("capture").executes(OutpostCommands::capture))
                .then(Commands.literal("tier").then(Commands.argument("tier", IntegerArgumentType.integer(1, 3)).executes(OutpostCommands::tier)))
                .then(Commands.literal("sim").then(Commands.argument("days", DoubleArgumentType.doubleArg(0.01, 100)).executes(OutpostCommands::sim)))
                .then(Commands.literal("stock")
                        .then(Commands.argument("resource", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(Resource.VALUES).map(r -> r.id), b))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0, 1_000_000)).executes(OutpostCommands::stock))))
                .then(Commands.literal("raid").executes(OutpostCommands::raid))
                .then(Commands.literal("remove").executes(OutpostCommands::remove)));
    }

    private static ServerLevel overworld(CommandContext<CommandSourceStack> c) {
        return c.getSource().getServer().overworld();
    }

    /** Аванпост, в котором стоит источник команды, или его собственный. */
    private static Outpost here(CommandContext<CommandSourceStack> c) {
        OutpostSavedData data = OutpostSavedData.get(overworld(c));
        Outpost o = c.getSource().getLevel().dimension() == Level.OVERWORLD ? data.findAt(BlockPos.containing(c.getSource().getPosition())) : null;
        if (o == null && c.getSource().getEntity() instanceof ServerPlayer p) o = data.lordOutpost(p.getUUID());
        if (o == null) c.getSource().sendFailure(Component.translatable("lordsoutpost.cmd.none_here"));
        return o;
    }

    private static MutableComponent describe(Outpost o) {
        String tp = "/tp @s " + o.center.getX() + " " + (o.center.getY() + 30) + " " + o.center.getZ();
        return Component.literal("#" + Long.toHexString(o.id) + " ")
                .append(o.difficulty.label())
                .append(Component.literal(" " + "★".repeat(o.difficulty.skulls) + " " + o.state + " T" + o.tier + " " + Math.round(o.progress() * 100) + "% "
                        + o.layout.id + "/" + o.palette.id + (o.name.isEmpty() ? "" : " \"" + o.name + "\"") + " "))
                .append(Component.literal("[" + o.center.getX() + " " + o.center.getY() + " " + o.center.getZ() + "]")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, tp))));
    }

    private static int nearest(CommandContext<CommandSourceStack> c) {
        ServerLevel level = overworld(c);
        OutpostSavedData data = OutpostSavedData.get(level);
        BlockPos at = BlockPos.containing(c.getSource().getPosition());
        Discovery.ensureRecords(level, data, at.getX(), at.getZ(), 2600, level.getGameTime());
        Optional<Outpost> best = data.near(at.getX(), at.getZ(), 4000).stream().min(Comparator.comparingDouble(o -> o.distanceSqr(at.getX(), at.getZ())));
        if (best.isEmpty()) {
            c.getSource().sendFailure(Component.translatable("lordsoutpost.cmd.none_near"));
            return 0;
        }
        Outpost o = best.get();
        int dist = (int) Math.sqrt(o.distanceSqr(at.getX(), at.getZ()));
        c.getSource().sendSuccess(() -> describe(o).append(Component.literal(" (" + dist + " m)")), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        OutpostSavedData data = OutpostSavedData.get(overworld(c));
        BlockPos at = BlockPos.containing(c.getSource().getPosition());
        List<Outpost> all = data.all().stream().sorted(Comparator.comparingDouble(o -> o.distanceSqr(at.getX(), at.getZ()))).limit(20).toList();
        c.getSource().sendSuccess(() -> Component.translatable("lordsoutpost.cmd.list", data.all().size()), false);
        for (Outpost o : all) c.getSource().sendSuccess(() -> describe(o), false);
        return all.size();
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        Outpost o = here(c);
        if (o == null) return 0;
        c.getSource().sendSuccess(() -> describe(o), false);
        StringBuilder sb = new StringBuilder();
        for (Resource r : Resource.VALUES) sb.append(r.id).append('=').append((int) o.stock(r)).append(' ');
        sb.append("| pop=").append(o.residents.size()).append('/').append(o.populationCap())
                .append(" fighters=").append(o.fighters()).append(" steps=").append(o.steps).append('/').append(o.nextPlan().size())
                .append(" placed=").append(o.placed).append(" placedTier=").append(o.placedTier).append(" online=").append(o.online)
                .append(" law=").append(o.law.id).append(" caravans=").append(o.caravans.size()).append(" quests=").append(o.quests.size())
                .append(" raid=").append(o.raid != null);
        c.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int reveal(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        ServerLevel level = overworld(c);
        OutpostSavedData data = OutpostSavedData.get(level);
        Discovery.ensureRecords(level, data, player.getBlockX(), player.getBlockZ(), 3000, level.getGameTime());
        int n = 0;
        for (Outpost o : data.near(player.getX(), player.getZ(), 3000)) {
            data.knowledge(player.getUUID()).hinted.add(o.id);
            if (data.knowledge(player.getUUID()).discovered.add(o.id)) n++;
        }
        data.setDirty();
        int count = n;
        c.getSource().sendSuccess(() -> Component.translatable("lordsoutpost.cmd.revealed", count), false);
        return n;
    }

    private static int create(CommandContext<CommandSourceStack> c, String layoutId, String paletteId) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        if (player.level().dimension() != Level.OVERWORLD) {
            c.getSource().sendFailure(Component.translatable("lordsoutpost.standard.outside"));
            return 0;
        }
        ServerLevel level = overworld(c);
        OutpostSavedData data = OutpostSavedData.get(level);
        BlockPos center = player.blockPosition().below();
        for (Outpost other : data.near(center.getX(), center.getZ(), 120)) {
            c.getSource().sendFailure(Component.translatable("lordsoutpost.cmd.too_close").append(describe(other)));
            return 0;
        }
        Layout layout = layoutId == null ? Layout.VALUES[level.random.nextInt(Layout.VALUES.length)] : Layout.byId(layoutId);
        Palette palette = paletteId == null ? OutpostSites.palette(level.getBiome(center)) : Palette.byId(paletteId);
        Rotation rotation = switch (player.getDirection()) {
            // Ворота смотрят на игрока, создавшего форт.
            case NORTH -> Rotation.NONE;
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            default -> Rotation.COUNTERCLOCKWISE_90;
        };
        if (player.getDirection().getAxis() == Direction.Axis.Y) rotation = Rotation.NONE;
        Outpost o = OutpostManager.createManual(data, center, layout, palette, rotation,
                Difficulty.bySkulls(IntegerArgumentType.getInteger(c, "skulls")), level.getGameTime(), level.random);
        data.knowledge(player.getUUID()).discovered.add(o.id);
        c.getSource().sendSuccess(() -> Component.translatable("lordsoutpost.cmd.created").append(describe(o)), true);
        return 1;
    }

    private static int capture(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        Outpost o = here(c);
        if (o == null) return 0;
        if (o.state != OutpostState.WILD) {
            c.getSource().sendFailure(Component.translatable("lordsoutpost.cmd.not_wild"));
            return 0;
        }
        ServerLevel level = overworld(c);
        OutpostManager.conquer(OutpostSavedData.get(level), o, level.getGameTime());
        ItemStack standard = new ItemStack(ModItems.LORD_STANDARD.get());
        if (!player.getInventory().add(standard)) player.drop(standard, false);
        c.getSource().sendSuccess(() -> Component.translatable("lordsoutpost.cmd.captured"), true);
        return 1;
    }

    private static int tier(CommandContext<CommandSourceStack> c) {
        Outpost o = here(c);
        if (o == null) return 0;
        int tier = IntegerArgumentType.getInteger(c, "tier");
        if (tier < o.tier) {
            c.getSource().sendFailure(Component.translatable("lordsoutpost.cmd.tier_down"));
            return 0;
        }
        o.tier = tier;
        o.steps = 0;
        o.dirtyForClients = true;
        OutpostSavedData.get(overworld(c)).setDirty();
        c.getSource().sendSuccess(() -> describe(o), true);
        return 1;
    }

    /** Прокручивает экономику аванпоста на заданное число игровых дней (макро-тиками, как в игре). */
    private static int sim(CommandContext<CommandSourceStack> c) {
        Outpost o = here(c);
        if (o == null) return 0;
        ServerLevel level = overworld(c);
        OutpostSavedData data = OutpostSavedData.get(level);
        long ticks = (long) (DoubleArgumentType.getDouble(c, "days") * Simulation.DAY);
        long now = level.getGameTime();
        // Сдвигаем прошлое назад: все таймеры аванпоста считаются от игрового времени.
        o.lastSim -= ticks;
        o.foundedAt -= ticks;
        o.lawChangedAt -= ticks;
        o.nextRaidAt -= ticks;
        o.nextCaravanAt -= ticks;
        o.conqueredAt -= ticks;
        long savedLast = o.lastSim;
        for (long t = savedLast + ALifeSimulationManager.SIM_INTERVAL; t <= now; t += ALifeSimulationManager.SIM_INTERVAL) {
            o.lastSim = t - ALifeSimulationManager.SIM_INTERVAL;
            Simulation.tick(level, data, o, t);
        }
        o.lastSim = now;
        c.getSource().sendSuccess(() -> describe(o), true);
        return 1;
    }

    private static int stock(CommandContext<CommandSourceStack> c) {
        Outpost o = here(c);
        if (o == null) return 0;
        Resource r = Resource.byId(StringArgumentType.getString(c, "resource"));
        if (r == null) {
            c.getSource().sendFailure(Component.translatable("lordsoutpost.cmd.bad_resource"));
            return 0;
        }
        o.setStock(r, Math.min(o.capacity(r), IntegerArgumentType.getInteger(c, "amount")));
        o.dirtyForClients = true;
        c.getSource().sendSuccess(() -> Component.literal(r.id + " = " + (int) o.stock(r)), true);
        return 1;
    }

    private static int raid(CommandContext<CommandSourceStack> c) {
        Outpost o = here(c);
        if (o == null) return 0;
        if (o.state != OutpostState.OWNED) {
            c.getSource().sendFailure(Component.translatable("lordsoutpost.cmd.not_owned"));
            return 0;
        }
        o.nextRaidAt = 1;
        o.lastSim = Math.min(o.lastSim, overworld(c).getGameTime() - ALifeSimulationManager.SIM_INTERVAL);
        c.getSource().sendSuccess(() -> Component.translatable("lordsoutpost.cmd.raid"), true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> c) {
        Outpost o = here(c);
        if (o == null) return 0;
        OutpostSavedData data = OutpostSavedData.get(overworld(c));
        if (o.state == OutpostState.OWNED) OutpostManager.abandon(data, o, overworld(c).getGameTime());
        o.residents.clear();
        data.remove(o);
        if (!o.manual) data.markRegionEmpty(OutpostSavedData.regionOf(o.center.getX()), OutpostSavedData.regionOf(o.center.getZ()));
        c.getSource().sendSuccess(() -> Component.translatable("lordsoutpost.cmd.removed"), true);
        return 1;
    }
}
