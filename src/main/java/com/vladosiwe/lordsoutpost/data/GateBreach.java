package com.vladosiwe.lordsoutpost.data;

import com.vladosiwe.lordsoutpost.block.OutpostGateBlock;
import com.vladosiwe.lordsoutpost.blueprint.Marker;
import com.vladosiwe.lordsoutpost.entity.FighterEntity;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.registry.ModBlocks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Решётки внешних ворот как преграда, которую можно сорвать. Дикий форт: когда у ворот не осталось ни одного
 * мародёра, а игрок стоит у решётки, она падает — в форт можно войти. Набег: налётчики (особенно разоритель)
 * долбят решётку, пока она не рухнет; после боя строитель ставит её заново (ремонт ворот — в первую очередь).
 */
public final class GateBreach {
    private static final Map<Long, List<BlockPos>> CACHE = new HashMap<>();
    private static final Map<Long, Object> CACHE_DESIGN = new HashMap<>();

    private GateBreach() {}

    /** Клетки внешней решётки ворот (мировые координаты). */
    public static List<BlockPos> outerGate(Outpost o) {
        Object key = o.design();
        if (CACHE_DESIGN.get(o.id) != key) {
            List<BlockPos> out = new ArrayList<>();
            for (var e : o.design().cells.entrySet()) {
                var st = e.getValue().state();
                if (st.is(ModBlocks.OUTPOST_GATE.get()) && !st.getValue(OutpostGateBlock.INNER)) out.add(o.world(e.getKey()));
            }
            CACHE.put(o.id, out);
            CACHE_DESIGN.put(o.id, key);
        }
        return CACHE.get(o.id);
    }

    public static List<BlockPos> standing(ServerLevel level, Outpost o) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : outerGate(o)) if (level.isLoaded(p) && level.getBlockState(p).is(ModBlocks.OUTPOST_GATE.get())) out.add(p);
        return out;
    }

    /** Снаружи у ворот: на 3 блока от центра ворот прочь от центра форта. */
    public static BlockPos outside(Outpost o) {
        BlockPos gate = o.marker(Marker.GATE);
        double dx = gate.getX() - o.center.getX(), dz = gate.getZ() - o.center.getZ();
        double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        return BlockPos.containing(gate.getX() + 0.5 + dx / len * 3, gate.getY(), gate.getZ() + 0.5 + dz / len * 3);
    }

    /** Ломает одну клетку решётки (ближайшую к точке). */
    public static boolean breakOne(ServerLevel level, Outpost o, BlockPos near) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : standing(level, o)) {
            double d = p.distSqr(near);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        if (best == null) return false;
        level.destroyBlock(best, false);
        level.playSound(null, best, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.HOSTILE, 1.2f, 0.7f);
        return true;
    }

    /** Дикий форт: стража ворот перебита, игрок у решётки — она падает целиком. */
    public static void wild(ServerLevel level, Outpost o) {
        List<BlockPos> gate = standing(level, o);
        if (gate.isEmpty()) return;
        BlockPos centre = o.marker(Marker.GATE);
        AABB near = new AABB(centre).inflate(5, 3, 5);
        List<ServerPlayer> players = level.getEntitiesOfClass(ServerPlayer.class, near, p -> p.isAlive() && !p.isSpectator() && !p.isCreative());
        if (players.isEmpty()) return;
        boolean guarded = !level.getEntitiesOfClass(FighterEntity.class, new AABB(centre).inflate(12, 8, 12),
                f -> f.isAlive() && f.hostileFaction() && f.outpostId() == o.id).isEmpty();
        if (guarded) return;
        for (BlockPos p : gate) level.destroyBlock(p, false);
        level.playSound(null, centre, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 1.5f, 0.6f);
        level.playSound(null, centre, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.8f, 0.6f);
        for (ServerPlayer p : players) p.displayClientMessage(Component.translatable("lordsoutpost.msg.gate_breached").withStyle(ChatFormatting.GOLD), true);
    }
}
