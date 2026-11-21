package com.vladosiwe.lordsoutpost.server;

import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.blueprint.Marker;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.entity.OutpostNpc;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Role;
import com.vladosiwe.lordsoutpost.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Зеркало Лорда и его портал.
 *
 * На тире 3 Управляющий сам подходит к Лорду и вручает зеркало. Зеркало открывает перед Лордом малый портал
 * на 30 секунд: войти могут любые два игрока, после второго портал гаснет. Портал ведёт к рамке на верхнем
 * этаже донжона. Каждый пришедший может один раз вернуться обратно — ПКМ по рамке, — после чего обратный путь
 * для него закрыт. Перезарядка зеркала — 3 минуты.
 *
 * Зеркало одно на аванпост: у него номер, и при выдаче нового старые рассыпаются. Оно рассыпается и если
 * аванпост потерян, сменил Лорда или опустился ниже тира 3. Его нельзя выбросить, передать или положить
 * в чужой контейнер: только инвентарь Лорда и личный сундук Лорда этого аванпоста.
 */
@EventBusSubscriber(modid = LordsOutpost.MODID)
public final class LordMirror {
    public static final int TIER = 3;
    public static final int COOLDOWN = 3 * 60 * 20;
    public static final int PORTAL_TICKS = 30 * 20;
    public static final int PORTAL_CAPACITY = 2;

    private record Portal(ServerLevel level, Vec3 pos, float yaw, long outpost, long expires, Set<UUID> used) {}

    private record Back(ResourceKey<Level> dim, Vec3 pos, float yaw, long outpost) {}

    private static final List<Portal> PORTALS = new ArrayList<>();
    private static final Map<UUID, Back> RETURNS = new HashMap<>();
    private static final Map<UUID, List<ItemStack>> KEPT_ON_DEATH = new HashMap<>();

    private LordMirror() {}

    // ============================== Предмет ==============================

    public static boolean isMirror(ItemStack s) {
        return !s.isEmpty() && s.is(ModItems.LORD_MIRROR.get());
    }

    private static CompoundTag tag(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d == null ? new CompoundTag() : d.copyTag();
    }

    public static long outpostOf(ItemStack s) {
        return tag(s).getLong("outpost");
    }

    public static String outpostName(ItemStack s) {
        return tag(s).getString("outpostName");
    }

    public static ItemStack create(Outpost o) {
        ItemStack s = new ItemStack(ModItems.LORD_MIRROR.get());
        CompoundTag t = new CompoundTag();
        t.putLong("outpost", o.id);
        t.putInt("serial", o.mirrorSerial);
        t.putString("outpostName", o.name);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        return s;
    }

    /** Действующее ли зеркало (и, если holder задан, держит ли его законный Лорд). */
    public static boolean valid(OutpostSavedData data, ItemStack s, Player holder) {
        CompoundTag t = tag(s);
        Outpost o = data.byId(t.getLong("outpost"));
        if (o == null || o.state != OutpostState.OWNED || o.tier < TIER || t.getInt("serial") != o.mirrorSerial) return false;
        return holder == null || o.isLord(holder.getUUID());
    }

    // ============================== Портал ==============================

    private static ServerLevel overworld(MinecraftServer server) {
        return server.overworld();
    }

    /** Рамка на верхнем этаже стоит (построена и не сломана). */
    public static BlockPos frame(ServerLevel level, Outpost o) {
        if (o.design().markers(Marker.PORTAL_FRAME).isEmpty()) return null;
        BlockPos f = o.marker(Marker.PORTAL_FRAME);
        return level.isLoaded(f) && !level.getBlockState(f).isAir() ? f : null;
    }

    /** Куда встаёт пришедший: два блока от рамки в сторону входа в донжон. */
    static Vec3 arrival(Outpost o, BlockPos frame) {
        BlockPos door = o.marker(Marker.KEEP_DOOR);
        int dx = door.getX() - frame.getX(), dz = door.getZ() - frame.getZ();
        int sx = Math.abs(dx) >= Math.abs(dz) ? Integer.signum(dx) : 0, sz = sx == 0 ? Integer.signum(dz) : 0;
        return new Vec3(frame.getX() + 0.5 + sx * 2, frame.getY(), frame.getZ() + 0.5 + sz * 2);
    }

    public static boolean use(ServerPlayer player, ItemStack stack) {
        OutpostSavedData data = OutpostSavedData.get(player.server);
        if (!valid(data, stack, player)) {
            stack.setCount(0);
            player.displayClientMessage(Component.translatable("lordsoutpost.msg.mirror.vanished").withStyle(ChatFormatting.RED), false);
            return false;
        }
        if (player.getCooldowns().isOnCooldown(stack.getItem())) return false;
        Outpost o = data.byId(outpostOf(stack));
        ServerLevel ow = overworld(player.server);
        if (frame(ow, o) == null) {
            player.displayClientMessage(Component.translatable("lordsoutpost.msg.mirror.no_frame", o.name).withStyle(ChatFormatting.YELLOW), true);
            return false;
        }
        Vec3 look = Vec3.directionFromRotation(0, player.getYRot());
        Vec3 pos = player.position().add(look.scale(2));
        PORTALS.removeIf(p -> p.outpost == o.id);
        PORTALS.add(new Portal(player.serverLevel(), pos, player.getYRot(), o.id, player.serverLevel().getGameTime() + PORTAL_TICKS, new HashSet<>()));
        player.getCooldowns().addCooldown(stack.getItem(), COOLDOWN);
        player.serverLevel().playSound(null, pos.x, pos.y, pos.z, SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, 0.6f, 1.4f);
        player.displayClientMessage(Component.translatable("lordsoutpost.msg.mirror.opened", PORTAL_TICKS / 20, PORTAL_CAPACITY).withStyle(ChatFormatting.LIGHT_PURPLE), true);
        return true;
    }

    private static void teleport(ServerPlayer p, ServerLevel level, Vec3 to, float yaw) {
        p.teleportTo(level, to.x, to.y, to.z, yaw, p.getXRot());
        p.resetFallDistance();
        level.playSound(null, to.x, to.y, to.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.7f, 1.2f);
    }

    private static void tickPortals(MinecraftServer server) {
        OutpostSavedData data = OutpostSavedData.get(server);
        ServerLevel ow = overworld(server);
        for (Iterator<Portal> it = PORTALS.iterator(); it.hasNext(); ) {
            Portal p = it.next();
            long now = p.level.getGameTime();
            Outpost o = data.byId(p.outpost);
            BlockPos frame = o == null ? null : frame(ow, o);
            if (now >= p.expires || o == null || frame == null || p.used.size() >= PORTAL_CAPACITY || server.getLevel(p.level.dimension()) != p.level) {
                p.level.playSound(null, p.pos.x, p.pos.y, p.pos.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.6f, 1.6f);
                it.remove();
                continue;
            }
            if (now % 2 == 0) particles(p);
            AABB box = new AABB(p.pos.x - 0.7, p.pos.y, p.pos.z - 0.7, p.pos.x + 0.7, p.pos.y + 2.2, p.pos.z + 0.7);
            for (ServerPlayer pl : p.level.getEntitiesOfClass(ServerPlayer.class, box)) {
                if (p.used.contains(pl.getUUID()) || p.used.size() >= PORTAL_CAPACITY || pl.isSpectator()) continue;
                p.used.add(pl.getUUID());
                RETURNS.put(pl.getUUID(), new Back(pl.level().dimension(), pl.position().subtract(Vec3.directionFromRotation(0, pl.getYRot()).scale(1.5)), pl.getYRot() + 180f, o.id));
                Vec3 to = arrival(o, frame);
                float yaw = (float) Math.toDegrees(Math.atan2(-(frame.getX() + 0.5 - to.x), frame.getZ() + 0.5 - to.z));
                teleport(pl, ow, to, yaw);
                pl.displayClientMessage(Component.translatable("lordsoutpost.msg.mirror.arrived").withStyle(ChatFormatting.LIGHT_PURPLE), false);
            }
        }
    }

    private static void particles(Portal p) {
        double rad = Math.toRadians(p.yaw);
        double rx = Math.cos(rad), rz = Math.sin(rad);
        for (int i = 0; i < 20; i++) {
            double a = i / 20.0 * Math.PI * 2;
            double side = Math.cos(a) * 0.65, up = 1.05 + Math.sin(a) * 1.05;
            p.level.sendParticles(ParticleTypes.REVERSE_PORTAL, p.pos.x + rx * side, p.pos.y + up, p.pos.z + rz * side, 1, 0, 0, 0, 0);
        }
        p.level.sendParticles(ParticleTypes.PORTAL, p.pos.x, p.pos.y + 1.0, p.pos.z, 12, 0.25, 0.7, 0.25, 0.4);
    }

    /** ПКМ по рамке: пришедший через зеркало возвращается туда, откуда вошёл — один раз. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onFrameUse(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !(p.level() instanceof ServerLevel level)) return;
        Back back = RETURNS.get(p.getUUID());
        if (back == null) return;
        Outpost o = OutpostSavedData.get(level).byId(back.outpost);
        if (o == null || !event.getPos().equals(frame(level, o))) return;
        event.setCanceled(true);
        RETURNS.remove(p.getUUID());
        ServerLevel to = p.server.getLevel(back.dim);
        if (to == null) return;
        teleport(p, to, back.pos, back.yaw);
        p.displayClientMessage(Component.translatable("lordsoutpost.msg.mirror.returned").withStyle(ChatFormatting.LIGHT_PURPLE), true);
    }

    // ============================== Выдача и надзор ==============================

    private static boolean lordChestHolds(ServerLevel level, Outpost o) {
        for (BlockPos pos : o.markers(Marker.LORD_CHEST)) {
            if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (isMirror(s) && outpostOf(s) == o.id && tag(s).getInt("serial") == o.mirrorSerial) return true;
            }
        }
        return false;
    }

    private static boolean carries(ServerPlayer p, Outpost o) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (isMirror(s) && outpostOf(s) == o.id && tag(s).getInt("serial") == o.mirrorSerial) return true;
        }
        return isMirror(p.containerMenu.getCarried()) && outpostOf(p.containerMenu.getCarried()) == o.id;
    }

    /** Управляющий дошёл до Лорда: вручает новое зеркало (старые рассыпаются). */
    public static void handOver(OutpostNpc npc, ServerPlayer lord) {
        OutpostSavedData data = OutpostSavedData.get(lord.server);
        Outpost o = npc.outpost();
        if (o == null || !o.isLord(lord.getUUID()) || o.tier < TIER || frame(overworld(lord.server), o) == null) return;
        if (carries(lord, o) || lordChestHolds(overworld(lord.server), o)) return;
        o.mirrorSerial++;
        data.setDirty();
        ItemStack mirror = create(o);
        if (!lord.getInventory().add(mirror)) {
            o.mirrorSerial--;
            lord.displayClientMessage(Component.translatable("lordsoutpost.msg.mirror.full").withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        Resident r = npc.resident();
        lord.sendSystemMessage(Component.translatable("lordsoutpost.msg.mirror.given", r == null ? "" : r.name, o.name).withStyle(ChatFormatting.GOLD));
        lord.serverLevel().playSound(null, lord.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 1f);
    }

    private static void supervise(MinecraftServer server) {
        OutpostSavedData data = OutpostSavedData.get(server);
        // Зеркала в руках не того игрока или от потерянного аванпоста рассыпаются.
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            var inv = p.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (isMirror(s) && !valid(data, s, p)) {
                    inv.setItem(i, ItemStack.EMPTY);
                    p.displayClientMessage(Component.translatable("lordsoutpost.msg.mirror.vanished").withStyle(ChatFormatting.RED), false);
                }
            }
        }
        // Тир 3 и рамка стоит, а зеркала у Лорда нет — Управляющий идёт к нему.
        ServerLevel ow = overworld(server);
        for (Outpost o : data.all()) {
            if (o.state != OutpostState.OWNED || o.tier < TIER || o.owner == null) continue;
            ServerPlayer lord = server.getPlayerList().getPlayer(o.owner);
            if (lord == null || lord.level() != ow || o.distanceSqr(lord.getX(), lord.getZ()) > 56 * 56) continue;
            if (frame(ow, o) == null || carries(lord, o) || lordChestHolds(ow, o)) continue;
            for (OutpostNpc npc : ow.getEntitiesOfClass(OutpostNpc.class, lord.getBoundingBox().inflate(64), n -> n.outpostId() == o.id)) {
                Resident r = npc.resident();
                if (r == null || r.role != Role.STEWARD) continue;
                if (npc.courierTarget == null) {
                    npc.courierTarget = lord.getUUID();
                    npc.courierUntil = ow.getGameTime() + 1200;
                }
                break;
            }
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (!PORTALS.isEmpty()) tickPortals(server);
        if (server.getTickCount() % 40 == 0) supervise(server);
    }

    // ============================== Запреты ==============================

    @SubscribeEvent
    public static void onToss(ItemTossEvent event) {
        ItemStack s = event.getEntity().getItem();
        if (!isMirror(s)) return;
        event.setCanceled(true);
        Player p = event.getPlayer();
        if (!p.getInventory().add(s.copy())) p.getInventory().placeItemBackInInventory(s.copy());
        p.displayClientMessage(Component.translatable("lordsoutpost.msg.mirror.bound").withStyle(ChatFormatting.RED), true);
    }

    /** Зеркало не лежит на земле: любой «выпавший» экземпляр исчезает (смерть обрабатывается отдельно). */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof ItemEntity item && isMirror(item.getItem())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onDeathDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        List<ItemStack> kept = new ArrayList<>();
        event.getDrops().removeIf(e -> {
            if (!isMirror(e.getItem())) return false;
            kept.add(e.getItem().copy());
            return true;
        });
        if (!kept.isEmpty()) KEPT_ON_DEATH.put(p.getUUID(), kept);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        List<ItemStack> kept = KEPT_ON_DEATH.remove(event.getEntity().getUUID());
        if (kept != null) for (ItemStack s : kept) event.getEntity().getInventory().add(s);
    }

    /** Закрыли контейнер: зеркало может остаться только в личном сундуке Лорда своего аванпоста. */
    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        OutpostSavedData data = OutpostSavedData.get(p.server);
        for (Slot slot : event.getContainer().slots) {
            if (slot.container == p.getInventory() || !isMirror(slot.getItem())) continue;
            ItemStack s = slot.getItem();
            if (slot.container instanceof BlockEntity be && be.getLevel() instanceof ServerLevel level) {
                Outpost o = data.byId(outpostOf(s));
                if (o != null && o.markers(Marker.LORD_CHEST).contains(be.getBlockPos()) && level.dimension() == Level.OVERWORLD) continue;
            }
            slot.set(ItemStack.EMPTY);
            if (!p.getInventory().add(s)) p.getInventory().placeItemBackInInventory(s);
            p.displayClientMessage(Component.translatable("lordsoutpost.msg.mirror.bound").withStyle(ChatFormatting.RED), true);
        }
    }

    public static void reset() {
        PORTALS.clear();
        RETURNS.clear();
        KEPT_ON_DEATH.clear();
    }
}
