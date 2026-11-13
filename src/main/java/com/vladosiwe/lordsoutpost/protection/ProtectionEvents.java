package com.vladosiwe.lordsoutpost.protection;

import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.blueprint.Marker;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.outpost.AccessLevel;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.registry.ModBlocks;
import com.vladosiwe.lordsoutpost.server.OutpostManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * Гарнизонный Устав в действии: кто может ломать, ставить и открывать на территории аванпоста.
 *
 * Лорд — всё. Союзник — сундуки и станки, но не ломать. Гость — только ходить по двору.
 * Враг — ничего. Переключатель «защита территории» решает, могут ли чужаки (гости и враги) ломать блоки;
 * союзники не ломают никогда. Дикие и брошенные форты не защищены: их штурмуют.
 */
@EventBusSubscriber(modid = LordsOutpost.MODID)
public final class ProtectionEvents {
    private ProtectionEvents() {}

    /** Можно ли игроку менять блоки в этой точке. */
    public static boolean canBuild(OutpostSavedData data, Outpost o, Player player) {
        if (o.state != OutpostState.OWNED) return true;
        if (player.isCreative() && player.hasPermissions(2)) return true;
        AccessLevel level = Access.level(data, o, player.getUUID());
        if (level == AccessLevel.LORD) return true;
        if (level == AccessLevel.ALLY) return false;
        return !o.access.protectBlocks;
    }

    /** Можно ли игроку открыть контейнер или станок. */
    public static boolean canUse(OutpostSavedData data, Outpost o, Player player, BlockPos pos) {
        if (o.state != OutpostState.OWNED) return true;
        if (player.isCreative() && player.hasPermissions(2)) return true;
        AccessLevel level = Access.level(data, o, player.getUUID());
        if (level == AccessLevel.LORD) return true;
        if (level != AccessLevel.ALLY) return false;
        return o.access.alliesTribute || !pos.equals(o.marker(Marker.TRIBUTE));
    }

    private static void deny(Player player, String key) {
        if (player instanceof ServerPlayer sp) sp.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.RED), true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = data.findAt(event.getPos());
        if (o == null) return;
        Player player = event.getPlayer();
        boolean standard = event.getState().is(ModBlocks.LORD_STANDARD.get()) && event.getPos().equals(o.standard);
        if (standard) {
            // Штандарт снимает только Лорд, и только осознанно — крадучись.
            if (!o.isLord(player.getUUID())) {
                event.setCanceled(true);
                deny(player, "lordsoutpost.msg.protected");
            } else if (!player.isShiftKeyDown()) {
                event.setCanceled(true);
                deny(player, "lordsoutpost.msg.standard_sneak");
            } else {
                OutpostManager.abandon(data, o, level.getGameTime());
                deny(player, "lordsoutpost.msg.abandoned");
                if (player instanceof ServerPlayer sp) {
                    sp.refreshDisplayName();
                    sp.refreshTabListName();
                }
            }
            return;
        }
        if (!canBuild(data, o, player)) {
            event.setCanceled(true);
            deny(player, "lordsoutpost.msg.protected");
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!(event.getEntity() instanceof Player player)) return;
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = data.findAt(event.getPos());
        if (o != null && !canBuild(data, o, player)) {
            event.setCanceled(true);
            deny(player, "lordsoutpost.msg.protected");
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        OutpostSavedData data = OutpostSavedData.get(level);
        BlockPos pos = event.getPos();
        Outpost o = data.findAt(pos);
        if (o == null || o.state != OutpostState.OWNED) return;
        Player player = event.getEntity();
        BlockState state = level.getBlockState(pos);
        // Доска и Штандарт сами проверяют доступ и объясняют отказ.
        if (state.is(ModBlocks.QUEST_BOARD.get()) || state.is(ModBlocks.LORD_STANDARD.get())) return;
        boolean interactive = state.hasBlockEntity() || state.getMenuProvider(level, pos) != null;
        if (interactive && !canUse(data, o, player, pos)) {
            event.setUseBlock(TriState.FALSE);
            deny(player, "lordsoutpost.msg.locked");
        }
        // Вёдра, огниво и прочие предметы, меняющие мир, подчиняются тем же правилам, что и блоки.
        if (!canBuild(data, o, player) && !event.getItemStack().isEmpty() && !event.getItemStack().has(net.minecraft.core.component.DataComponents.FOOD)) {
            event.setUseItem(TriState.FALSE);
        }
    }

    /** Взрывы не разрушают защищённый форт. */
    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (event.getAffectedBlocks().isEmpty()) return;
        OutpostSavedData data = OutpostSavedData.get(level);
        event.getAffectedBlocks().removeIf(pos -> {
            Outpost o = data.findAt(pos);
            return o != null && o.state == OutpostState.OWNED && o.access.protectBlocks;
        });
    }
}
