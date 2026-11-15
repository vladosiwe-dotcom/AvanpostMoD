package com.vladosiwe.lordsoutpost.blueprint;

import com.vladosiwe.lordsoutpost.outpost.Resource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Полный комплект чертежей одного вида форта (планировка + палитра + поворот): три тира и планы перехода между ними.
 * План тира N — упорядоченный список шагов, который превращает готовый тир N-1 в тир N.
 * Потокобезопасен: его читают и потоки генерации мира, и серверный поток.
 */
public final class FortBlueprint {
    public static final int MAX_TIER = 3;
    private static final Map<String, FortBlueprint> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, FortBlueprint> eldest) {
            return size() > 12;
        }
    };

    public final Layout layout;
    public final Palette palette;
    public final Rotation rotation;
    private final Design[] designs = new Design[MAX_TIER + 1];
    @SuppressWarnings("unchecked")
    private final List<Step>[] plans = new List[MAX_TIER + 1];
    private final Map<Long, Map<BlockPos, BlockState>> snapshots = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Map<BlockPos, BlockState>> eldest) {
            return size() > 3;
        }
    };

    private FortBlueprint(Layout layout, Palette palette, Rotation rotation) {
        this.layout = layout;
        this.palette = palette;
        this.rotation = rotation;
        for (int t = 1; t <= MAX_TIER; t++) designs[t] = FortDesigner.design(layout, palette, t).rotated(rotation);
        for (int t = 1; t <= MAX_TIER; t++) plans[t] = buildPlan(t > 1 ? designs[t - 1] : null, designs[t]);
    }

    public static FortBlueprint get(Layout layout, Palette palette, Rotation rotation) {
        String key = layout.id + '/' + palette.id + '/' + rotation.ordinal();
        synchronized (CACHE) {
            FortBlueprint bp = CACHE.get(key);
            if (bp == null) {
                bp = new FortBlueprint(layout, palette, rotation);
                CACHE.put(key, bp);
            }
            return bp;
        }
    }

    public Design design(int tier) {
        return designs[Math.max(1, Math.min(MAX_TIER, tier))];
    }

    /** Шаги, которые превращают готовый тир tier-1 в tier (для tier = 1 — стройка с нуля). */
    public List<Step> plan(int tier) {
        return plans[Math.max(1, Math.min(MAX_TIER, tier))];
    }

    /**
     * Состояние мира для готового тира плюс первых nextSteps шагов следующего.
     * Используется генерацией мира: «Тир 1 (50%)» — это готовый тир 1 и половина плана тира 2.
     */
    public Map<BlockPos, BlockState> snapshot(int tier, int nextSteps) {
        long key = ((long) tier << 32) | (nextSteps & 0xFFFFFFFFL);
        synchronized (snapshots) {
            Map<BlockPos, BlockState> cached = snapshots.get(key);
            if (cached != null) return cached;
            Map<BlockPos, BlockState> map = new HashMap<>(designs[tier].cells.size() * 2);
            for (var e : designs[tier].cells.entrySet()) map.put(e.getKey(), e.getValue().state());
            if (tier < MAX_TIER) {
                List<Step> plan = plans[tier + 1];
                for (int i = 0; i < Math.min(nextSteps, plan.size()); i++) map.put(plan.get(i).pos(), plan.get(i).state());
            }
            snapshots.put(key, map);
            return map;
        }
    }

    private static List<Step> buildPlan(@Nullable Design prev, Design cur) {
        List<Map.Entry<BlockPos, Canvas.Cell>> adds = new ArrayList<>();
        Map<Integer, Map.Entry<BlockPos, Canvas.Cell>> bySeq = new HashMap<>();
        for (var e : cur.cells.entrySet()) {
            Canvas.Cell old = prev == null ? null : prev.cells.get(e.getKey());
            if (old != null && old.state() == e.getValue().state()) continue;
            adds.add(e);
            bySeq.put(e.getValue().seq(), e);
        }
        // Вторая половина кровати идёт сразу за первой.
        Map<Integer, List<Map.Entry<BlockPos, Canvas.Cell>>> followers = new HashMap<>();
        List<Map.Entry<BlockPos, Canvas.Cell>> heads = new ArrayList<>();
        for (var e : adds) {
            Canvas.Cell cell = e.getValue();
            if (cell.chain() && bySeq.containsKey(cell.seq() - 1)) followers.computeIfAbsent(cell.seq() - 1, k -> new ArrayList<>()).add(e);
            else heads.add(e);
        }
        heads.sort(Comparator.<Map.Entry<BlockPos, Canvas.Cell>>comparingInt(e -> e.getValue().stage())
                .thenComparingInt(e -> e.getValue().group())
                .thenComparingInt(e -> e.getKey().getY())
                .thenComparingInt(e -> e.getValue().seq()));

        List<Step> removals = new ArrayList<>();
        if (prev != null) {
            for (var e : prev.cells.entrySet()) {
                if (cur.cells.containsKey(e.getKey())) continue;
                BlockPos pos = e.getKey();
                if (pos.getY() > 0 && e.getValue().state().isAir()) continue;
                removals.add(new Step(pos, (pos.getY() <= 0 ? Blocks.DIRT : Blocks.AIR).defaultBlockState(), false, null));
            }
            removals.sort(Comparator.<Step>comparingInt(s -> -s.pos().getY()).thenComparingLong(s -> s.pos().asLong()));
        }

        List<Step> plan = new ArrayList<>(adds.size() + removals.size());
        boolean demolished = prev == null;
        for (var e : heads) {
            if (!demolished && e.getValue().stage() > FortDesigner.DEMOLISH_AFTER_STAGE) {
                plan.addAll(removals);
                demolished = true;
            }
            plan.add(step(e, false));
            List<Map.Entry<BlockPos, Canvas.Cell>> f = followers.get(e.getValue().seq());
            if (f != null) for (var fe : f) plan.add(step(fe, true));
        }
        if (!demolished) plan.addAll(removals);
        return List.copyOf(plan);
    }

    private static Step step(Map.Entry<BlockPos, Canvas.Cell> e, boolean chain) {
        return new Step(e.getKey(), e.getValue().state(), chain, costOf(e.getValue().state()));
    }

    /** Какой ресурс тратится на блок. */
    @Nullable
    public static Resource costOf(BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty()) return null;
        SoundType sound = state.getSoundType();
        if (sound == SoundType.METAL || sound == SoundType.ANVIL || sound == SoundType.LANTERN || sound == SoundType.CHAIN) return Resource.IRON;
        NoteBlockInstrument instrument = state.instrument();
        if (instrument == NoteBlockInstrument.BASS) return Resource.WOOD;
        if (instrument == NoteBlockInstrument.BASEDRUM) return Resource.STONE;
        return null;
    }

    /** Блок, чья форма зависит от соседей (забор, стена, ступени): после установки её нужно пересчитать. */
    public static boolean shapeSensitive(BlockState state) {
        return state.getBlock() instanceof CrossCollisionBlock || state.getBlock() instanceof WallBlock
                || state.getBlock() instanceof StairBlock || state.getBlock() instanceof FenceGateBlock;
    }
}
