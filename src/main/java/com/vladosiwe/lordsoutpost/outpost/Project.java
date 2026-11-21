package com.vladosiwe.lordsoutpost.outpost;

import com.vladosiwe.lordsoutpost.blueprint.FortBlueprint;
import com.vladosiwe.lordsoutpost.blueprint.Palette;
import com.vladosiwe.lordsoutpost.blueprint.ProjectDesigner;
import com.vladosiwe.lordsoutpost.blueprint.Step;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Rotation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Постройка, которую Лорд заказал сверх плана тиров (дом, башня, стена). Живёт в той же экономике A-Life:
 * шаги оплачиваются из того же кредита стройки и ресурсов склада, рядом с игроком их ставят строители руками,
 * вдали — оплаченные шаги встают при возвращении игрока. Блоки проекта считаются постройкой игрока:
 * ремонт и чертежи тиров их не трогают.
 */
public final class Project {
    public enum Type {
        HOUSE("house", null, 3), WATCHTOWER("watchtower", null, 1), WALL("wall", null, 1),
        /** Новые места рабочих объектов: после постройки соответствующая зона переезжает сюда. */
        PEN("pen", WorkZone.PEN, 1), SMITHY("smithy", WorkZone.SMITHY, 1), MINE("mine", WorkZone.MINE, 1),
        /** Казарма: койки гарнизона (население форта), улучшается до второго уровня. */
        BARRACKS("barracks", null, 2),
        /** Поле фермера 7×7 с водой — переносит пашню. */
        FARM("farm", WorkZone.FARM, 1),
        /** Дровяной двор: колода, поленница — сюда лесоруб сносит дрова. */
        WOODYARD("woodyard", WorkZone.WOODYARD, 1);

        public static final Type[] VALUES = values();
        public final String id;
        /** Зона, которую переносит эта постройка (null — обычная постройка). */
        @Nullable public final WorkZone relocates;
        /** Сколько уровней у постройки (1 — не улучшается). */
        public final int maxLevel;

        Type(String id, @Nullable WorkZone relocates, int maxLevel) {
            this.id = id;
            this.relocates = relocates;
            this.maxLevel = maxLevel;
        }

        /** Кроватей в постройке этого уровня (население). */
        public int beds(int level) {
            return switch (this) {
                case HOUSE -> 2 * level;
                case BARRACKS -> 4 * level;
                default -> 0;
            };
        }

        public Component label() {
            return Component.translatable("lordsoutpost.project." + id);
        }

        @Nullable
        public static Type byId(String id) {
            for (Type t : VALUES) if (t.id.equals(id)) return t;
            return null;
        }
    }

    public enum State {
        PLANNED, BUILDING, DONE, CANCELLED;
        public static final State[] VALUES = values();
    }

    public final long id;
    public final Type type;
    /** Пол постройки (y — уровень земли) в мировых координатах. */
    public final BlockPos origin;
    public final Rotation rotation;
    public State state = State.PLANNED;
    /** Сколько шагов оплачено (по порядку). */
    public int paid;
    /** Какие шаги уже стоят в мире. */
    public final BitSet placed = new BitSet();
    public long orderedAt;
    /** Уровень постройки (1 — базовый). */
    public int level = 1;
    /**
     * Улучшение: id прежней постройки, которую эта заменяет (-1 — новая постройка), и её уровень. Пока улучшение
     * строится, шаги — только разница между уровнями (что добавить, заменить или убрать); готовое улучшение
     * становится обычной постройкой нового уровня, прежняя запись удаляется.
     */
    public long replaces = -1;
    public int fromLevel;

    private transient List<Step> steps;
    private transient BitSet floors;
    private transient Palette cachedPalette;

    public Project(long id, Type type, BlockPos origin, Rotation rotation) {
        this.id = id;
        this.type = type;
        this.origin = origin.immutable();
        this.rotation = rotation;
    }

    /** Шаги в координатах относительно центра аванпоста (как у плана тиров), снизу вверх. */
    public List<Step> steps(Outpost o) {
        if (steps == null || cachedPalette != o.palette) {
            List<ProjectDesigner.Cell> cells = fromLevel > 0 ? ProjectDesigner.upgrade(type, o.palette, fromLevel, level)
                    : ProjectDesigner.design(type, o.palette, level);
            steps = new ArrayList<>(cells.size());
            floors = new BitSet();
            for (int i = 0; i < cells.size(); i++) {
                ProjectDesigner.Cell c = cells.get(i);
                BlockPos world = origin.offset(c.pos().rotate(rotation));
                var state = c.state().rotate(rotation);
                steps.add(new Step(world.subtract(o.center), state, c.chain(), FortBlueprint.costOf(state)));
                if (c.floor()) floors.set(i);
            }
            cachedPalette = o.palette;
        }
        return steps;
    }

    /** Сбросить кэш шагов (улучшение достроено — шаги снова весь чертёж уровня). */
    public void resetSteps() {
        steps = null;
        floors = null;
    }

    public boolean floor(Outpost o, int index) {
        steps(o);
        return floors.get(index);
    }

    public int size(Outpost o) {
        return steps(o).size();
    }

    public boolean active() {
        return state == State.PLANNED || state == State.BUILDING;
    }

    /** Оплачен целиком: в экономике постройка уже существует (блоки встанут, как только игрок будет рядом). */
    public boolean built(Outpost o) {
        return state == State.DONE || (state != State.CANCELLED && paid >= size(o));
    }

    public float progress(Outpost o) {
        int n = size(o);
        return n == 0 ? 1f : placed.cardinality() / (float) n;
    }

    /** Якорь рабочей зоны, которую переносит постройка (центр загона, место кузнеца, вход в шахту). */
    public BlockPos zoneAnchor() {
        return origin.offset(ProjectDesigner.anchor(type).rotate(rotation));
    }

    /** Направление штрека новой шахты (единичный вектор по горизонтали). */
    public BlockPos mineDir() {
        return new BlockPos(0, 0, 1).rotate(rotation);
    }

    /** Позиция дозорного на башне в мире. */
    public BlockPos post() {
        return origin.offset(ProjectDesigner.towerPost().rotate(rotation));
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putLong("id", id);
        t.putString("type", type.id);
        t.putLong("origin", origin.asLong());
        t.putByte("rot", (byte) rotation.ordinal());
        t.putByte("state", (byte) state.ordinal());
        t.putInt("paid", paid);
        t.putLongArray("placed", placed.toLongArray());
        t.putLong("ordered", orderedAt);
        t.putByte("level", (byte) level);
        t.putLong("replaces", replaces);
        t.putByte("fromLevel", (byte) fromLevel);
        return t;
    }

    @Nullable
    public static Project load(CompoundTag t) {
        Type type = Type.byId(t.getString("type"));
        if (type == null) return null;
        Project p = new Project(t.getLong("id"), type, BlockPos.of(t.getLong("origin")), Rotation.values()[Math.max(0, Math.min(3, t.getByte("rot")))]);
        p.state = State.VALUES[Math.max(0, Math.min(State.VALUES.length - 1, t.getByte("state")))];
        p.paid = Math.max(0, t.getInt("paid"));
        p.placed.or(BitSet.valueOf(t.getLongArray("placed")));
        p.orderedAt = t.getLong("ordered");
        p.level = Math.max(1, Math.min(type.maxLevel, t.contains("level") ? t.getByte("level") : 1));
        p.replaces = t.contains("replaces") ? t.getLong("replaces") : -1;
        p.fromLevel = Math.max(0, t.getByte("fromLevel"));
        return p;
    }
}
