package com.vladosiwe.lordsoutpost.blueprint;

import com.vladosiwe.lordsoutpost.registry.ModBlocks;
import com.vladosiwe.lordsoutpost.block.OutpostGateBlock;
import com.vladosiwe.lordsoutpost.block.QuestBoardBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;

import java.util.ArrayList;
import java.util.List;

import static com.vladosiwe.lordsoutpost.blueprint.Canvas.facing;
import static com.vladosiwe.lordsoutpost.blueprint.Canvas.log;
import static com.vladosiwe.lordsoutpost.blueprint.Canvas.stairs;

/**
 * Процедурный архитектор: строит чертёж форта для планировки, палитры и тира.
 *
 * Координаты канонические: центр форта (0, 0), ворота на юге (+Z), y = 0 — слой земли, y = 1 — первый блок над землёй.
 * Один и тот же чертёж используют генерация мира, строители и офлайн-симуляция, поэтому форт,
 * достроенный без игрока, совпадает блок в блок с тем, что построили бы на его глазах.
 */
public final class FortDesigner {
    // Этапы стройки внутри тира.
    public static final int ST_GROUND = 0, ST_WALLS = 1, ST_GATE = 2, ST_KEEP = 3, ST_MAGE = 4, ST_WORKS = 5, ST_HOUSING = 6, ST_DECOR = 7;
    /** Снос старого идёт после того, как новые стены и ворота замкнули периметр. */
    public static final int DEMOLISH_AFTER_STAGE = ST_GATE;

    private static final Rotation FACE_S = Rotation.NONE, FACE_W = Rotation.CLOCKWISE_90, FACE_N = Rotation.CLOCKWISE_180, FACE_E = Rotation.COUNTERCLOCKWISE_90;

    private final Canvas c = new Canvas();
    private final Layout layout;
    private final Palette p;
    private final int tier;
    private final int r;
    private final int version;
    /** Центр поля (версия 2) в канонических координатах; null — поле не нашло места (старая лужа с грунтом). */
    @org.jetbrains.annotations.Nullable private final int[] field;
    /** Половина стороны поля: 7×7 вспаханной земли без ограды, фонари по углам. */
    public static final int FIELD_HALF = 3;

    private FortDesigner(Layout layout, Palette palette, int tier, int version, @org.jetbrains.annotations.Nullable int[] field) {
        this.layout = layout;
        this.p = palette;
        this.tier = tier;
        this.version = version;
        this.field = field;
        this.r = Layout.radius(version, tier);
    }

    /** Первая планировка (форты старых миров): без изменений, блок в блок. */
    public static Design design(Layout layout, Palette palette, int tier) {
        return design(layout, palette, tier, 1, null);
    }

    public static Design design(Layout layout, Palette palette, int tier, int version, @org.jetbrains.annotations.Nullable int[] field) {
        FortDesigner d = new FortDesigner(layout, palette, tier, version, field);
        d.ground();
        d.perimeter();
        d.gate();
        d.interior();
        if (version >= 2 && field != null) d.field(field[0], field[1]);
        d.posts();
        d.lamps();
        return new Design(tier, d.r, d.c.cells(), d.c.markers());
    }

    /**
     * Место для поля в просторной планировке: квадрат с оградой, свободный во всех трёх тирах (поле не переезжает
     * при росте форта) и внутри стен первого тира. Ближайшее к восточной половине двора, детерминированно.
     */
    /**
     * Место поля в просторной планировке: одно на все три тира (поле не переезжает при росте форта).
     * Квадратный форт — юго-западная четверть двора, восьмиугольный — к востоку от донжона.
     */
    public static int[] findField(Layout layout, Palette palette) {
        return layout == Layout.SQUARE ? new int[]{-9, 8} : new int[]{0, -11};
    }

    /** Наложения зданий друг на друга в чертеже (для проверки планировки; должно быть 0). */
    public static List<String> collisions(Layout layout, Palette palette, int tier, int version) {
        FortDesigner d = new FortDesigner(layout, palette, tier, version, version >= 2 ? findField(layout, palette) : null);
        d.ground();
        d.perimeter();
        d.gate();
        d.interior();
        if (version >= 2) d.field(d.field[0], d.field[1]);
        List<String> out = new ArrayList<>(d.c.collisionSamples());
        if (d.c.collisions() > out.size()) out.add("… всего " + d.c.collisions());
        return out;
    }

    /** Поле: земля под пашню 7×7, вода в центре (увлажняет всё поле), без ограды; по углам — фонари на столбиках. */
    private void field(int fx, int fz) {
        c.stage(ST_WORKS);
        int h = FIELD_HALF;
        for (int x = -h; x <= h; x++)
            for (int z = -h; z <= h; z++) {
                c.set(fx + x, 0, fz + z, Blocks.DIRT);
                c.set(fx + x, -1, fz + z, Blocks.DIRT);
                c.clear(fx + x, 1, fz + z, fx + x, 2, fz + z);
            }
        c.set(fx, 0, fz, Blocks.WATER);
        c.set(fx, 1, fz, Blocks.LILY_PAD);
        int s = h + 1;
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2) {
                c.set(fx + sx * s, 1, fz + sz * s, p.fence);
                c.set(fx + sx * s, 2, fz + sz * s, Blocks.LANTERN);
            }
        c.set(fx + 1, 1, fz + s, Blocks.COMPOSTER);
        c.mark(Marker.FARM, fx, 1, fz);
    }

    // ============================== Земля и дороги ==============================

    private int keepZ() {
        if (layout == Layout.OCTAGON) return 0;
        if (version >= 2) return switch (tier) { case 1 -> -11; case 2 -> -15; default -> -19; };
        return switch (tier) { case 1 -> -8; case 2 -> -11; default -> -14; };
    }

    private int keepHalf() {
        return switch (tier) { case 1 -> 3; case 2 -> 4; default -> 5; };
    }

    private void ground() {
        c.stage(ST_GROUND);
        int doorZ = keepZ() + keepHalf() + 1;
        // Главная дорога от донжона через ворота наружу.
        int w = tier == 1 ? 0 : 1;
        for (int z = doorZ; z <= r + 5; z++)
            for (int x = -w; x <= w; x++) c.set(x, 0, z, p.path);
        if (tier >= 2) {
            // Поперечная дорога к службам.
            int crossZ = layout == Layout.OCTAGON ? keepHalf() + 3 : 2;
            int reach = r - 5;
            for (int x = -reach; x <= reach; x++) c.set(x, 0, crossZ, p.path);
        }
    }

    // ============================== Стены ==============================

    private boolean gateGap(int x, int z) {
        return z > 0 && Math.abs(x) <= 1;
    }

    private void perimeter() {
        c.stage(ST_WALLS);
        int ext = r + 1;
        if (tier == 1) {
            for (int x = -ext; x <= ext; x++)
                for (int z = -ext; z <= ext; z++) {
                    if (!layout.ring(x, z, r, 1) || gateGap(x, z)) continue;
                    int h = 4 + ((x + z) & 1);
                    c.fill(x, 1, z, x, h, z, p.log);
                }
            return;
        }
        int t = tier == 2 ? 1 : 2;
        int h = tier == 2 ? 5 : 7;
        for (int x = -ext; x <= ext; x++)
            for (int z = -ext; z <= ext; z++) {
                if (!layout.ring(x, z, r, t)) continue;
                boolean outer = layout.ring(x, z, r, 1);
                c.set(x, 0, z, p.foundation);
                if (gateGap(x, z)) continue;
                for (int y = 1; y <= h; y++) c.set(x, y, z, wallBlock(x, y, z));
                if (outer && ((x + z) & 1) == 0) c.set(x, h + 1, z, p.stoneAlt);
            }
        if (tier == 2) {
            // Боевой ход: деревянный настил на столбах вдоль стены.
            for (int x = -ext; x <= ext; x++)
                for (int z = -ext; z <= ext; z++) {
                    if (!layout.ring(x, z, r - 1, 1)) continue;
                    c.set(x, 4, z, p.planks);
                    boolean overGate = z > 0 && Math.abs(x) <= 2;
                    if (!overGate && Math.floorMod(x + z, 4) == 0) c.fill(x, 1, z, x, 3, z, p.fence);
                }
        }
        // Две лестницы на стену по бокам от ворот.
        int top = tier == 2 ? 4 : 7;
        int rz = r - (tier == 2 ? 2 : 2);
        for (int side = -1; side <= 1; side += 2) {
            for (int i = 0; i < top; i++) {
                int x = side * (4 + i);
                c.set(x, 1 + i, rz, stairs(p.stoneStairs, side > 0 ? Direction.EAST : Direction.WEST));
                if (i > 0) c.fill(x, 1, rz, x, i, rz, p.stone);
            }
        }
    }

    private BlockState wallBlock(int x, int y, int z) {
        int hash = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791);
        return (Math.floorMod(hash, 5) == 0 ? p.stoneAlt : p.stone).defaultBlockState();
    }

    // ============================== Ворота и башни ==============================

    private BlockState gateBlock(boolean inner, Direction.Axis axis) {
        return ModBlocks.OUTPOST_GATE.get().defaultBlockState()
                .setValue(OutpostGateBlock.INNER, inner)
                .setValue(BlockStateProperties.HORIZONTAL_AXIS, axis);
    }

    private void gate() {
        c.stage(ST_GATE);
        c.mark(Marker.GATE, 0, 1, r);
        c.mark(Marker.CARAVAN, 0, 1, r + 8);
        c.mark(Marker.YARD, 0, 1, r - 6);
        c.mark(Marker.YARD, 3, 1, r - 7);
        // Одно постоянное поле на каждый форт. Координаты не пересекаются с модулями всех трёх тиров.
        if (version < 2 || field == null) {
            int farmX = layout == Layout.SQUARE ? 3 : 0;
            int farmZ = layout == Layout.SQUARE ? -2 : 11;
            // Землю не кладём чертежом: фермер сам вручную вспашет естественный грунт вокруг воды.
            c.set(farmX, 0, farmZ, Blocks.WATER);
            c.mark(Marker.FARM, farmX, 1, farmZ);
        }
        if (tier == 1) {
            for (int side = -1; side <= 1; side += 2) {
                c.fill(side * 2, 1, r, side * 2, 5, r, p.log);
                c.set(side * 2, 6, r, Blocks.LANTERN);
            }
            c.fill(-1, 4, r, 1, 4, r, log(p.log, Direction.Axis.X));
            c.fill(-1, 1, r, 1, 3, r, gateBlock(false, Direction.Axis.X));
            watchtower();
            return;
        }
        int h = tier == 2 ? 5 : 7;
        int depth = tier == 2 ? 0 : 1;
        // Надвратная перемычка и решётка.
        for (int dz = 0; dz <= depth; dz++) {
            c.fill(-1, 4, r - dz, 1, h, r - dz, p.stoneAlt);
            c.clear(-1, 1, r - dz, 1, 3, r - dz);
        }
        c.fill(-1, 1, r, 1, 3, r, gateBlock(false, Direction.Axis.X));
        c.set(-1, h + 1, r, p.stoneAlt);
        c.set(1, h + 1, r, p.stoneAlt);
        // Привратные башенки.
        for (int side = -1; side <= 1; side += 2) {
            int cx = side * 3;
            c.fill(cx - 1, 0, r - 1, cx + 1, h + 2, r + 1, p.stone);
            c.fill(cx, 0, r + 1, cx, h + 2, r + 1, p.stoneAlt);
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    if (((dx + dz) & 1) == 0) c.set(cx + dx, h + 3, r + dz, p.stoneAlt);
            c.set(cx, h + 3, r, Blocks.LANTERN);
        }
        // Угловые башни.
        int half = tier == 2 ? 2 : 3;
        int th = tier == 2 ? 8 : 11;
        for (int[] t : layout.towers(r)) tower(t[0], t[1], half, th, tier == 3);
    }

    private void tower(int cx, int cz, int half, int height, boolean roofed) {
        for (int x = -half; x <= half; x++)
            for (int z = -half; z <= half; z++) {
                boolean shell = Math.abs(x) == half || Math.abs(z) == half;
                boolean corner = Math.abs(x) == half && Math.abs(z) == half;
                c.set(cx + x, 0, cz + z, p.foundation);
                if (shell) {
                    for (int y = 1; y <= height; y++) c.set(cx + x, y, cz + z, corner ? p.stoneAlt.defaultBlockState() : wallBlock(cx + x, y, cz + z));
                    if (corner || ((x + z) & 1) == 0) c.set(cx + x, height + 1, cz + z, p.stoneAlt);
                } else {
                    c.clear(cx + x, 1, cz + z, cx + x, height - 1, cz + z);
                    c.set(cx + x, height, cz + z, p.planks);
                }
            }
        // Вход смотрит на центр форта.
        boolean alongX = Math.abs(cx) >= Math.abs(cz);
        int dx = alongX ? -Integer.signum(cx) * half : 0;
        int dz = alongX ? 0 : -Integer.signum(cz) * half;
        c.clear(cx + dx, 1, cz + dz, cx + dx, 2, cz + dz);
        // Лестница на площадку у стены, противоположной входу.
        Direction ladderFacing = alongX ? (cx > 0 ? Direction.WEST : Direction.EAST) : (cz > 0 ? Direction.NORTH : Direction.SOUTH);
        int lx = cx - (alongX ? Integer.signum(dx) * (half - 1) : 0);
        int lz = cz - (alongX ? 0 : Integer.signum(dz) * (half - 1));
        c.fill(lx, 1, lz, lx, height, lz, facing(Blocks.LADDER, ladderFacing));
        c.mark(Marker.GUARD_POST, cx, height + 1, cz);
        if (roofed) {
            for (int sx = -1; sx <= 1; sx += 2)
                for (int sz = -1; sz <= 1; sz += 2) c.fill(cx + sx * half, height + 2, cz + sz * half, cx + sx * half, height + 3, cz + sz * half, p.fence);
            pyramidRoof(cx, height + 4, cz, half + 1, p.roofStairs, p.roofSlab, p.roof);
        }
    }

    private void pyramidRoof(int cx, int y, int cz, int radius, Block roofStairs, Block roofSlab, Block roofBlock) {
        for (int k = 0; radius - k >= 0; k++) {
            int q = radius - k;
            if (q == 0) {
                c.set(cx, y + k, cz, roofSlab);
                break;
            }
            for (int i = -q; i <= q; i++) {
                c.set(cx + i, y + k, cz - q, stairs(roofStairs, Direction.SOUTH));
                c.set(cx + i, y + k, cz + q, stairs(roofStairs, Direction.NORTH));
                c.set(cx - q, y + k, cz + i, stairs(roofStairs, Direction.EAST));
                c.set(cx + q, y + k, cz + i, stairs(roofStairs, Direction.WEST));
            }
            // Углы — целые блоки, иначе ступени смотрят в разные стороны.
            for (int sx = -1; sx <= 1; sx += 2)
                for (int sz = -1; sz <= 1; sz += 2) c.set(cx + sx * q, y + k, cz + sz * q, slabOrBlock(roofSlab));
        }
    }

    private BlockState slabOrBlock(Block slab) {
        return slab.defaultBlockState();
    }

    private void watchtower() {
        int cx = version >= 2 ? (layout == Layout.SQUARE ? 12 : 10) : layout == Layout.SQUARE ? 9 : 7;
        int cz = version >= 2 ? (layout == Layout.SQUARE ? -12 : -10) : layout == Layout.SQUARE ? -9 : -8;
        c.at(cx, cz, FACE_S, () -> {
            for (int sx = -1; sx <= 1; sx += 2)
                for (int sz = -1; sz <= 1; sz += 2) c.fill(sx, 1, sz, sx, 6, sz, p.log);
            c.fill(0, 1, 1, 0, 5, 1, p.log);
            c.fill(-1, 6, -1, 1, 6, 1, p.planks);
            c.fill(0, 1, 2, 0, 6, 2, facing(Blocks.LADDER, Direction.SOUTH));
            for (int i = -1; i <= 1; i++) {
                c.set(i, 7, -1, p.fence);
                c.set(-1, 7, i, p.fence);
                c.set(1, 7, i, p.fence);
            }
            c.set(-1, 7, 1, p.fence);
            c.set(1, 7, 1, p.fence);
            for (int sx = -1; sx <= 1; sx += 2)
                for (int sz = -1; sz <= 1; sz += 2) c.set(sx, 8, sz, p.fence);
            c.fill(-2, 9, -2, 2, 9, 2, p.roofSlab);
            c.mark(Marker.GUARD_POST, 0, 7, 0);
        });
    }

    // ============================== Расстановка зданий ==============================

    private void interior() {
        if (version >= 2) {
            if (layout == Layout.SQUARE) interiorSquareV2();
            else interiorOctagonV2();
        } else if (layout == Layout.SQUARE) interiorSquare();
        else interiorOctagon();
    }

    /**
     * Просторный квадратный форт. Юго-западная четверть двора (x −14…−4, z 3…13 и проход вокруг) во всех тирах
     * отдана под поле; донжон у северной стены, службы вдоль восточной, жильё вдоль западной, загон у ворот.
     */
    private void interiorSquareV2() {
        switch (tier) {
            case 1 -> {
                module(ST_KEEP, 0, -11, FACE_S, this::hall);
                module(ST_WORKS, 11, -5, FACE_W, this::storeroomSmall);
                module(ST_WORKS, 11, 3, FACE_W, this::woodyard);
                module(ST_WORKS, 12, 10, FACE_W, this::minePile);
                module(ST_WORKS, 6, 9, FACE_S, () -> pen(2));
                module(ST_HOUSING, -11, -11, FACE_E, this::shelter);
                module(ST_HOUSING, -11, -5, FACE_E, this::shelter);
                module(ST_DECOR, 0, 2, FACE_S, this::campfire);
                module(ST_DECOR, 2, 12, FACE_N, this::questBoard);
            }
            case 2 -> {
                module(ST_KEEP, 0, -15, FACE_S, () -> keep(4, 2));
                module(ST_WORKS, 16, -8, FACE_W, this::storehouse);
                module(ST_WORKS, 16, 4, FACE_W, this::smithy);
                module(ST_WORKS, 16, 14, FACE_W, this::woodyard);
                module(ST_WORKS, 15, -18, FACE_S, this::mineHead);
                module(ST_WORKS, 7, 13, FACE_S, () -> pen(3));
                module(ST_HOUSING, -16, -10, FACE_E, () -> barracks(3));
                module(ST_HOUSING, -16, -2, FACE_E, this::infirmary);
                module(ST_DECOR, 6, 5, FACE_S, this::well);
                module(ST_DECOR, 2, 18, FACE_N, this::questBoard);
            }
            default -> {
                module(ST_KEEP, 0, -19, FACE_S, () -> keep(5, 3));
                module(ST_MAGE, 19, -21, FACE_S, this::mageTower);
                module(ST_WORKS, 22, -8, FACE_W, this::storehouse);
                module(ST_WORKS, 22, 4, FACE_W, this::smithy);
                module(ST_WORKS, 21, 15, FACE_W, this::woodyard);
                module(ST_WORKS, 22, 23, FACE_W, this::mineHead);
                module(ST_WORKS, 8, 15, FACE_S, () -> pen(3));
                module(ST_HOUSING, -22, -15, FACE_E, () -> barracks(4));
                module(ST_HOUSING, -22, -5, FACE_E, () -> barracks(4));
                module(ST_HOUSING, -22, 6, FACE_E, this::infirmary);
                module(ST_DECOR, -9, 21, FACE_S, this::marketStall);
                module(ST_DECOR, -14, 21, FACE_S, this::marketStall);
                module(ST_DECOR, 7, 6, FACE_S, this::well);
                module(ST_DECOR, 2, 24, FACE_N, this::questBoard);
            }
        }
    }

    /**
     * Просторный восьмиугольный форт: донжон в центре, поле к востоку от него (x 5…15, z −5…5) во всех тирах,
     * склад и шахта к северу, жильё и загон к западу, лазарет и рынок к югу.
     */
    private void interiorOctagonV2() {
        switch (tier) {
            case 1 -> {
                module(ST_KEEP, 0, 0, FACE_S, this::hall);
                module(ST_WORKS, 11, -4, FACE_W, this::storeroomSmall);
                module(ST_WORKS, 11, 6, FACE_W, this::woodyard);
                module(ST_WORKS, -11, 7, FACE_E, this::minePile);
                module(ST_WORKS, -6, 12, FACE_S, () -> pen(2));
                module(ST_HOUSING, -11, -4, FACE_E, this::shelter);
                module(ST_HOUSING, -11, 2, FACE_E, this::shelter);
                module(ST_DECOR, 5, 8, FACE_S, this::campfire);
                module(ST_DECOR, 2, 12, FACE_N, this::questBoard);
            }
            case 2 -> {
                module(ST_KEEP, 0, 0, FACE_S, () -> keep(4, 2));
                module(ST_WORKS, 14, -7, FACE_W, this::storehouse);
                module(ST_WORKS, 14, 5, FACE_W, this::smithy);
                module(ST_WORKS, -9, 15, FACE_E, this::woodyard);
                module(ST_WORKS, 10, -17, FACE_S, this::mineHead);
                module(ST_WORKS, -15, 4, FACE_S, () -> pen(3));
                module(ST_HOUSING, -14, -8, FACE_E, () -> barracks(3));
                module(ST_HOUSING, 13, 12, FACE_W, this::infirmary);
                module(ST_DECOR, 5, 12, FACE_S, this::well);
                module(ST_DECOR, 2, 18, FACE_N, this::questBoard);
            }
            default -> {
                module(ST_KEEP, 0, 0, FACE_S, () -> keep(5, 3));
                module(ST_MAGE, -14, -17, FACE_S, this::mageTower);
                module(ST_WORKS, 19, -8, FACE_W, this::storehouse);
                module(ST_WORKS, 19, 5, FACE_W, this::smithy);
                module(ST_WORKS, -12, 20, FACE_E, this::woodyard);
                module(ST_WORKS, 12, -22, FACE_S, this::mineHead);
                module(ST_WORKS, -20, 5, FACE_S, () -> pen(3));
                module(ST_HOUSING, -21, -5, FACE_E, () -> barracks(4));
                module(ST_HOUSING, 19, 13, FACE_W, () -> barracks(4));
                module(ST_HOUSING, 12, 22, FACE_W, this::infirmary);
                module(ST_DECOR, -6, 14, FACE_S, this::marketStall);
                module(ST_DECOR, 7, 15, FACE_S, this::marketStall);
                module(ST_DECOR, 5, 10, FACE_S, this::well);
                module(ST_DECOR, 2, 24, FACE_N, this::questBoard);
            }
        }
    }

    private void interiorSquare() {
        switch (tier) {
            case 1 -> {
                module(ST_KEEP, 0, -8, FACE_S, this::hall);
                module(ST_WORKS, 8, -4, FACE_W, this::storeroomSmall);
                module(ST_WORKS, 8, 3, FACE_W, this::woodyard);
                module(ST_WORKS, 8, 9, FACE_W, this::minePile);
                module(ST_WORKS, -7, 8, FACE_E, () -> pen(2));
                module(ST_HOUSING, -9, -6, FACE_E, this::shelter);
                module(ST_HOUSING, -9, 0, FACE_E, this::shelter);
                module(ST_DECOR, 0, 2, FACE_S, this::campfire);
                module(ST_DECOR, 4, 11, FACE_N, this::questBoard);
            }
            case 2 -> {
                module(ST_KEEP, 0, -11, FACE_S, () -> keep(4, 2));
                module(ST_WORKS, 12, -6, FACE_W, this::storehouse);
                module(ST_WORKS, 12, 2, FACE_W, this::smithy);
                module(ST_WORKS, 11, 9, FACE_W, this::woodyard);
                module(ST_WORKS, 11, -13, FACE_S, this::mineHead);
                module(ST_WORKS, -11, 11, FACE_E, () -> pen(3));
                module(ST_HOUSING, -12, -7, FACE_E, () -> barracks(3));
                module(ST_HOUSING, -12, 2, FACE_E, this::infirmary);
                module(ST_DECOR, 4, 5, FACE_S, this::well);
                module(ST_DECOR, 4, 14, FACE_N, this::questBoard);
            }
            default -> {
                module(ST_KEEP, 0, -14, FACE_S, () -> keep(5, 3));
                module(ST_MAGE, 14, -15, FACE_S, this::mageTower);
                module(ST_WORKS, 16, -5, FACE_W, this::storehouse);
                module(ST_WORKS, 16, 4, FACE_W, this::smithy);
                module(ST_WORKS, 15, 12, FACE_W, this::woodyard);
                module(ST_WORKS, 16, 18, FACE_W, this::mineHead);
                module(ST_WORKS, -14, 17, FACE_E, () -> pen(3));
                module(ST_HOUSING, -16, -11, FACE_E, () -> barracks(4));
                module(ST_HOUSING, -16, -3, FACE_E, () -> barracks(4));
                module(ST_HOUSING, -16, 6, FACE_E, this::infirmary);
                module(ST_DECOR, -6, 12, FACE_S, this::marketStall);
                module(ST_DECOR, 6, 12, FACE_S, this::marketStall);
                module(ST_DECOR, 5, 5, FACE_S, this::well);
                module(ST_DECOR, 4, 19, FACE_N, this::questBoard);
            }
        }
    }

    private void interiorOctagon() {
        switch (tier) {
            case 1 -> {
                module(ST_KEEP, 0, 0, FACE_S, this::hall);
                module(ST_WORKS, 0, -9, FACE_S, this::storeroomSmall);
                module(ST_WORKS, 6, 8, FACE_W, this::woodyard);
                module(ST_WORKS, -7, -8, FACE_E, this::minePile);
                module(ST_WORKS, -6, 7, FACE_E, () -> pen(2));
                module(ST_HOUSING, -9, -1, FACE_E, this::shelter);
                module(ST_HOUSING, 9, -1, FACE_W, this::shelter);
                module(ST_DECOR, 3, 7, FACE_S, this::campfire);
                module(ST_DECOR, 4, 11, FACE_N, this::questBoard);
            }
            case 2 -> {
                module(ST_KEEP, 0, 0, FACE_S, () -> keep(4, 2));
                module(ST_WORKS, 12, -3, FACE_W, this::storehouse);
                module(ST_WORKS, 11, 6, FACE_W, this::smithy);
                module(ST_WORKS, -6, 13, FACE_E, this::woodyard);
                module(ST_WORKS, 7, -12, FACE_S, this::mineHead);
                module(ST_WORKS, -6, -12, FACE_S, () -> pen(3));
                module(ST_HOUSING, -12, -3, FACE_E, () -> barracks(3));
                module(ST_HOUSING, -11, 6, FACE_E, this::infirmary);
                module(ST_DECOR, 5, 10, FACE_S, this::well);
                module(ST_DECOR, 4, 14, FACE_N, this::questBoard);
            }
            default -> {
                module(ST_KEEP, 0, 0, FACE_S, () -> keep(5, 3));
                module(ST_MAGE, 0, -15, FACE_S, this::mageTower);
                module(ST_WORKS, 15, -4, FACE_W, this::storehouse);
                module(ST_WORKS, 15, 5, FACE_W, this::smithy);
                module(ST_WORKS, 9, 14, FACE_W, this::woodyard);
                module(ST_WORKS, 11, -14, FACE_S, this::mineHead);
                module(ST_WORKS, -11, -14, FACE_S, () -> pen(3));
                module(ST_HOUSING, -15, -5, FACE_E, () -> barracks(4));
                module(ST_HOUSING, -15, 4, FACE_E, () -> barracks(4));
                module(ST_HOUSING, -9, 14, FACE_E, this::infirmary);
                module(ST_DECOR, -5, 9, FACE_S, this::marketStall);
                module(ST_DECOR, 6, 9, FACE_S, this::well);
                module(ST_DECOR, 4, 19, FACE_N, this::questBoard);
            }
        }
    }

    private void module(int stage, int x, int z, Rotation rot, Runnable draw) {
        c.stage(stage);
        c.at(x, z, rot, draw);
    }

    // ============================== Модули (вход смотрит на +Z) ==============================

    /** Дом x∈[-w,w], z∈[-d,d] с двускатной крышей и проёмом по центру южной стены. */
    private void house(int w, int d, int h, Block wall, boolean windows) {
        c.fill(-w, 0, -d, w, 0, d, p.planks);
        c.walls(-w, 1, -d, w, h, d, wall.defaultBlockState());
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2) c.fill(sx * w, 1, sz * d, sx * w, h, sz * d, p.log);
        if (windows) {
            for (int z = -d + 2; z <= d - 2; z += 2) {
                c.set(-w, 2, z, Blocks.GLASS_PANE);
                c.set(w, 2, z, Blocks.GLASS_PANE);
            }
            for (int x = -w + 2; x <= w - 2; x += 2) c.set(x, 2, -d, Blocks.GLASS_PANE);
        }
        c.clear(0, 1, d, 0, 2, d);
        gableRoof(w, d, h + 1, wall);
        c.set(0, h, 0, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        c.set(0, h + 1, 0, p.fence);
    }

    private void gableRoof(int w, int d, int top, Block wall) {
        if (w >= d) {
            for (int i = 0; ; i++) {
                int za = -d - 1 + i, zb = d + 1 - i, y = top + i;
                if (za > zb) break;
                if (za == zb) {
                    c.fill(-w - 1, y, za, w + 1, y, za, p.roofSlab);
                    break;
                }
                c.fill(-w - 1, y, za, w + 1, y, za, stairs(p.roofStairs, Direction.SOUTH));
                c.fill(-w - 1, y, zb, w + 1, y, zb, stairs(p.roofStairs, Direction.NORTH));
                if (za + 1 <= zb - 1) {
                    c.fill(-w, y, za + 1, -w, y, zb - 1, wall);
                    c.fill(w, y, za + 1, w, y, zb - 1, wall);
                }
            }
        } else {
            for (int i = 0; ; i++) {
                int xa = -w - 1 + i, xb = w + 1 - i, y = top + i;
                if (xa > xb) break;
                if (xa == xb) {
                    c.fill(xa, y, -d - 1, xa, y, d + 1, p.roofSlab);
                    break;
                }
                c.fill(xa, y, -d - 1, xa, y, d + 1, stairs(p.roofStairs, Direction.EAST));
                c.fill(xb, y, -d - 1, xb, y, d + 1, stairs(p.roofStairs, Direction.WEST));
                if (xa + 1 <= xb - 1) {
                    c.fill(xa + 1, y, -d, xb - 1, y, -d, wall);
                    c.fill(xa + 1, y, d, xb - 1, y, d, wall);
                }
            }
        }
    }

    private void bed(int x, int y, int z, Direction facing) {
        BlockState foot = p.bed.defaultBlockState().setValue(BedBlock.FACING, facing).setValue(BedBlock.PART, BedPart.FOOT);
        c.set(x, y, z, foot);
        c.chained(x + facing.getStepX(), y, z + facing.getStepZ(), foot.setValue(BedBlock.PART, BedPart.HEAD));
    }

    private void throne(int z) {
        c.set(0, 1, z, stairs(p.stairs, Direction.NORTH));
        c.set(-1, 1, z, p.accent);
        c.set(1, 1, z, p.accent);
        c.set(0, 0, z + 1, p.accent);
        c.set(0, 0, z + 2, p.accent);
        c.mark(Marker.THRONE, 0, 1, z + 1);
    }

    /** Тир 1: деревянный зал вождя. */
    private void hall() {
        house(3, 3, 4, p.planks, true);
        throne(-2);
        c.set(-2, 1, -2, facing(Blocks.CHEST, Direction.SOUTH));
        c.mark(Marker.TREASURE, -2, 1, -2);
        c.set(2, 1, -2, facing(Blocks.CHEST, Direction.SOUTH));
        c.mark(Marker.TRIBUTE, 2, 1, -2);
        c.set(-2, 1, 1, Blocks.CRAFTING_TABLE);
        c.set(2, 1, 1, Blocks.BARREL);
        c.mark(Marker.KEEP_DOOR, 0, 1, 4);
    }

    /** Каменный донжон: half — половина стороны, floors — число этажей по 5 блоков. */
    private void keep(int hw, int floors) {
        int top = floors * 5;
        c.fill(-hw, 0, -hw, hw, 0, hw, p.stoneAlt);
        for (int x = -hw; x <= hw; x++)
            for (int z = -hw; z <= hw; z++) {
                boolean shell = Math.abs(x) == hw || Math.abs(z) == hw;
                boolean corner = Math.abs(x) == hw && Math.abs(z) == hw;
                if (!shell) continue;
                for (int y = 1; y <= top; y++) c.set(x, y, z, corner ? p.stoneAlt.defaultBlockState() : wallBlock(x + 31, y, z + 17));
                if (corner || ((x + z) & 1) == 0) c.set(x, top + 1, z, p.stoneAlt);
            }
        for (int f = 1; f <= floors; f++) c.fill(-hw + 1, f * 5, -hw + 1, hw - 1, f * 5, hw - 1, f == floors ? p.stoneAlt : p.planks);
        // Бойницы на каждом этаже.
        for (int f = 0; f < floors; f++) {
            int y = f * 5 + 3;
            c.set(-hw, y, 0, Blocks.IRON_BARS);
            c.set(hw, y, 0, Blocks.IRON_BARS);
            c.set(0, y, -hw, Blocks.IRON_BARS);
            if (f > 0) c.set(0, y, hw, Blocks.IRON_BARS);
            c.set(-hw + 2, y + 1, -hw + 1, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
            c.set(hw - 2, y + 1, hw - 1, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
        // Лестницы между этажами: по западной стене, затем по восточной, и так далее до крыши.
        for (int f = 0; f < floors; f++) {
            int x = (f & 1) == 0 ? -hw + 1 : hw - 1;
            int base = f * 5;
            for (int i = 0; i < 5; i++) {
                int z = 2 - i;
                c.set(x, base + 1 + i, z, stairs(p.stoneStairs, Direction.NORTH));
                if (i > 0) c.fill(x, base + 1, z, x, base + i, z, p.stone);
                if (i < 4) c.clear(x, base + 5, z, x, base + 5, z);
            }
        }
        // Вход: внутренняя решётка цитадели.
        c.fill(0, 1, hw, 0, 2, hw, gateBlock(true, Direction.Axis.X));
        c.mark(Marker.KEEP_DOOR, 0, 1, hw + 1);
        throne(-hw + 2);
        c.set(-hw + 1, 1, -hw + 1, facing(Blocks.CHEST, Direction.SOUTH));
        c.mark(Marker.TREASURE, -hw + 1, 1, -hw + 1);
        c.set(hw - 1, 1, -hw + 1, facing(Blocks.CHEST, Direction.SOUTH));
        c.mark(Marker.TRIBUTE, hw - 1, 1, -hw + 1);
        c.set(hw - 1, 1, hw - 1, Blocks.CRAFTING_TABLE);
        // Покои на втором этаже.
        if (floors >= 2) {
            bed(1, 6, -hw + 3, Direction.NORTH);
            c.set(2, 6, -hw + 1, Blocks.BOOKSHELF);
            c.set(3, 6, -hw + 1, Blocks.BOOKSHELF);
            c.set(-1, 6, -hw + 1, facing(Blocks.CHEST, Direction.SOUTH));
            c.mark(Marker.LOOT_CHEST, -1, 6, -hw + 1);
        }
        c.mark(Marker.GUARD_POST, 0, top + 1, 0);
    }

    /** Тир 3: башня Придворного Мага. */
    /** Блок Iron's Spells 'n Spellbooks (если мод установлен) или замена из ванили. */
    private static BlockState iss(String id, BlockState fallback) {
        var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("irons_spellbooks", id));
        return block.map(Block::defaultBlockState).orElse(fallback);
    }

    private static BlockState issFacing(String id, BlockState fallback, Direction dir) {
        BlockState st = iss(id, fallback);
        return st.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? st.setValue(BlockStateProperties.HORIZONTAL_FACING, dir) : st;
    }

    /**
     * Башня мага: три этажа под одной лестницей. Первый — зал зачарований (стол зачарований, стол начертания
     * заклинаний Iron's Spells, кафедра, книги), второй — мастерская (кузня свитков, тайная наковальня, котёл
     * алхимика), третий — обсерватория с пьедесталами и жаровнями. Без Iron's Spells — ванильные замены.
     */
    private void mageTower() {
        int half = 3, height = 19;
        for (int x = -half; x <= half; x++)
            for (int z = -half; z <= half; z++) {
                int ax = Math.abs(x), az = Math.abs(z);
                if (ax + az > 5) continue;
                boolean shell = ax == half || az == half || ax + az == 5;
                c.set(x, 0, z, Blocks.DEEPSLATE_TILES);
                if (shell) {
                    for (int y = 1; y <= height; y++) {
                        Block b = (y == 6 || y == 12 || y == 18) ? Blocks.AMETHYST_BLOCK : (y % 6 == 0 ? Blocks.CHISELED_DEEPSLATE : y % 3 == 0 ? Blocks.POLISHED_DEEPSLATE : Blocks.DEEPSLATE_BRICKS);
                        c.set(x, y, z, b);
                    }
                } else {
                    c.set(x, 7, z, Blocks.DARK_OAK_PLANKS);
                    c.set(x, 13, z, Blocks.DARK_OAK_PLANKS);
                    c.set(x, height, z, Blocks.DARK_OAK_PLANKS);
                }
            }
        // Вход и окна по этажам.
        c.clear(0, 1, half, 0, 2, half);
        for (int y : new int[]{3, 9, 10, 15, 16}) {
            c.set(-half, y, 0, Blocks.PURPLE_STAINED_GLASS_PANE);
            c.set(half, y, 0, Blocks.PURPLE_STAINED_GLASS_PANE);
            c.set(0, y, -half, Blocks.PURPLE_STAINED_GLASS_PANE);
        }
        c.set(0, 9, half, Blocks.PURPLE_STAINED_GLASS_PANE);
        c.set(0, 15, half, Blocks.PURPLE_STAINED_GLASS_PANE);
        pyramidRoof(0, height + 1, 0, half + 1, Blocks.PURPUR_STAIRS, Blocks.PURPUR_SLAB, Blocks.PURPUR_BLOCK);
        c.set(0, height + 6, 0, Blocks.END_ROD);
        // Первый этаж — зал зачарований.
        c.set(0, 1, -1, Blocks.ENCHANTING_TABLE);
        for (int y = 1; y <= 2; y++) {
            c.set(-2, y, -1, Blocks.BOOKSHELF);
            c.set(-1, y, -2, Blocks.BOOKSHELF);
            c.set(1, y, -2, Blocks.BOOKSHELF);
        }
        c.set(-2, 1, 1, facing(Blocks.LECTERN, Direction.EAST));
        c.set(1, 1, -1, issFacing("inscription_table", Blocks.CARTOGRAPHY_TABLE.defaultBlockState(), Direction.SOUTH));
        c.set(-1, 3, -2, issFacing("book_stack", Blocks.CANDLE.defaultBlockState(), Direction.SOUTH));
        c.set(0, 6, 0, Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        // Второй этаж — мастерская: свитки, наковальня, алхимия.
        c.set(0, 8, -2, issFacing("scroll_forge", Blocks.SMITHING_TABLE.defaultBlockState(), Direction.SOUTH));
        c.set(-2, 8, 0, issFacing("arcane_anvil", Blocks.ANVIL.defaultBlockState(), Direction.EAST));
        c.set(-1, 8, 1, iss("alchemist_cauldron", Blocks.CAULDRON.defaultBlockState()));
        c.set(1, 8, -1, Blocks.BREWING_STAND);
        c.set(-1, 8, -2, Blocks.BOOKSHELF);
        c.set(1, 8, -2, Blocks.AMETHYST_CLUSTER);
        c.set(0, 12, 0, Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        // Третий этаж — обсерватория: пьедесталы, жаровни, книги.
        c.set(0, 14, -1, iss("pedestal", Blocks.CHISELED_DEEPSLATE.defaultBlockState()));
        c.set(-1, 14, 1, iss("pedestal", Blocks.CHISELED_DEEPSLATE.defaultBlockState()));
        c.set(-2, 14, 0, iss("brazier_soul", Blocks.SOUL_CAMPFIRE.defaultBlockState()));
        c.set(1, 14, -2, iss("wisewood_bookshelf", Blocks.BOOKSHELF.defaultBlockState()));
        c.set(-1, 14, -2, iss("wisewood_bookshelf", Blocks.BOOKSHELF.defaultBlockState()));
        c.set(0, 15, -1, Blocks.AMETHYST_CLUSTER);
        c.set(0, 18, 0, Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        // Лестница насквозь на все этажи (у восточной стены).
        c.fill(2, 1, 0, 2, 14, 0, facing(Blocks.LADDER, Direction.WEST));
        c.mark(Marker.MAGE, 0, 1, 1);
    }

    /**
     * Закрытый склад: дверь, которую открывают только свои, бочки с запасами вдоль задней стены,
     * сундук с добычей и посты стражи снаружи у двери.
     */
    private void storeroom(int w, int d, int h, Block wall, boolean stacked) {
        house(w, d, h, wall, false);
        BlockState door = ModBlocks.STOREHOUSE_DOOR.get().defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.NORTH).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        c.set(0, 1, d, door);
        c.chained(0, 2, d, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        BlockState barrel = Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.SOUTH);
        for (int x = -w + 1; x <= w - 1; x++) {
            c.set(x, 1, -d + 1, barrel);
            c.mark(Marker.STOCKPILE, x, 1, -d + 1);
            if (stacked) {
                c.set(x, 2, -d + 1, barrel);
                c.mark(Marker.STOCKPILE, x, 2, -d + 1);
            }
        }
        // Первая бочка — «бочка снабжения» из подсказок: всё, что в неё кладут, идёт в запасы (как и в остальные).
        c.mark(Marker.SUPPLY, -w + 1, 1, -d + 1);
        c.set(w - 1, 1, d - 1, facing(Blocks.CHEST, Direction.WEST));
        c.mark(Marker.LOOT_CHEST, w - 1, 1, d - 1);
        c.set(-w + 1, 1, d - 1, Blocks.HAY_BLOCK);
        c.mark(Marker.STORE_INSIDE, 0, 1, d - 1);
        c.mark(Marker.STORAGE, 0, 1, d + 2);
        c.mark(Marker.STORE_GUARD, -2, 1, d + 2);
        c.mark(Marker.STORE_GUARD, 2, 1, d + 2);
    }

    /** Тир 1: бревенчатый склад. */
    private void storeroomSmall() {
        storeroom(2, 2, 3, p.planks, false);
    }

    /** Тир 2+: каменный склад, бочки в два яруса. */
    private void storehouse() {
        storeroom(3, 2, 4, p.stone, true);
    }

    /** Шахтный ствол под открытым люком: лестница вниз на 6 блоков, штрек ведётся на юг (в локальных координатах). */
    private void shaft() {
        c.fill(0, -6, -1, 0, -1, -1, Blocks.COBBLESTONE);
        c.fill(0, -6, 0, 0, -1, 0, facing(Blocks.LADDER, Direction.SOUTH));
        c.set(0, 0, 0, Blocks.SPRUCE_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.HALF, Half.TOP)
                .setValue(BlockStateProperties.OPEN, true).setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        c.mark(Marker.MINE_SHAFT, 0, -6, 0);
        c.mark(Marker.MINE_DIR, 0, -6, 1);
    }

    private void smithy() {
        c.fill(-2, 0, -2, 2, 0, 2, p.stoneAlt);
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2) c.fill(sx * 2, 1, sz * 2, sx * 2, 3, sz * 2, p.log);
        c.fill(-1, 1, -2, 1, 3, -2, p.stone);
        c.fill(-3, 4, -3, 3, 4, 3, p.roofSlab);
        c.set(-1, 1, -1, facing(Blocks.BLAST_FURNACE, Direction.SOUTH));
        c.set(0, 1, -1, facing(Blocks.FURNACE, Direction.SOUTH));
        c.set(1, 1, -1, Blocks.LAVA_CAULDRON);
        c.set(-1, 1, 1, Blocks.SMITHING_TABLE);
        c.set(1, 1, 1, facing(Blocks.ANVIL, Direction.EAST));
        c.set(2, 1, 0, Blocks.GRINDSTONE);
        c.mark(Marker.SMITHY, 0, 1, 0);
    }

    private void pen(int s) {
        for (int i = -s; i <= s; i++) {
            c.set(i, 1, -s, p.fence);
            c.set(i, 1, s, p.fence);
            c.set(-s, 1, i, p.fence);
            c.set(s, 1, i, p.fence);
        }
        c.set(0, 1, s, facing(p.fenceGate, Direction.SOUTH));
        // Сено и поилка — снаружи у калитки: внутри у ограды по ним скот запрыгивает на неё и сбегает.
        c.set(-2, 1, s + 1, Blocks.HAY_BLOCK);
        c.set(2, 1, s + 1, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
        c.set(-s, 2, -s, Blocks.LANTERN);
        c.mark(Marker.PEN, 0, 1, 0);
    }

    private void woodyard() {
        c.fill(-1, 1, -1, 1, 1, -1, log(p.log, Direction.Axis.X));
        c.fill(-1, 2, -1, 0, 2, -1, log(p.log, Direction.Axis.X));
        c.fill(2, 1, 0, 2, 1, 1, log(p.log, Direction.Axis.Z));
        c.set(0, 1, 1, p.log);
        c.mark(Marker.WOODYARD, -1, 1, 1);
    }

    private void minePile() {
        c.set(1, 1, 0, Blocks.COBBLESTONE);
        c.set(1, 1, -1, Blocks.STONE);
        c.set(0, 1, -1, Blocks.COAL_ORE);
        c.set(1, 2, -1, Blocks.COBBLESTONE);
        c.set(-1, 1, -1, Blocks.IRON_ORE);
        c.set(-1, 0, 0, Blocks.COBBLESTONE);
        c.set(1, 0, 0, Blocks.COBBLESTONE);
        shaft();
        c.mark(Marker.MINE, 0, 1, 1);
    }

    private void mineHead() {
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2) {
                c.set(sx, 0, sz, Blocks.COBBLESTONE);
                c.fill(sx, 1, sz, sx, 3, sz, p.log);
            }
        c.fill(-1, 4, -1, 1, 4, 1, p.slab);
        c.set(0, 0, -1, Blocks.COBBLESTONE);
        c.set(-1, 0, 0, Blocks.COBBLESTONE);
        c.set(1, 0, 0, Blocks.COBBLESTONE);
        c.set(0, 0, 1, Blocks.COBBLESTONE);
        // Ствол шахты под люком.
        shaft();
        c.set(0, 3, 0, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        c.set(1, 1, 0, Blocks.IRON_ORE);
        c.set(-1, 1, 0, Blocks.COAL_ORE);
        c.mark(Marker.MINE, 0, 1, 2);
    }

    private void well() {
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++) {
                boolean center = x == 0 && z == 0;
                c.set(x, 0, z, center ? Blocks.WATER.defaultBlockState() : p.foundation.defaultBlockState());
                c.set(x, -1, z, p.foundation);
                if (!center) c.set(x, 1, z, p.stoneWall);
            }
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2) c.fill(sx, 2, sz, sx, 3, sz, p.fence);
        c.fill(-1, 4, -1, 1, 4, 1, p.roofSlab);
    }

    private void campfire() {
        c.set(0, 1, 0, Blocks.CAMPFIRE);
        c.set(0, 1, 2, log(p.log, Direction.Axis.X));
        c.set(2, 1, 0, log(p.log, Direction.Axis.Z));
        c.set(-2, 1, 0, log(p.log, Direction.Axis.Z));
    }

    private void shelter() {
        c.fill(-2, 0, -1, 2, 0, 1, p.planks);
        c.fill(-2, 1, -1, 2, 2, -1, p.planks);
        c.fill(-2, 1, 1, -2, 2, 1, p.fence);
        c.fill(2, 1, 1, 2, 2, 1, p.fence);
        c.fill(-2, 3, -1, 2, 3, 1, p.slab);
        bed(-1, 1, 1, Direction.NORTH);
        bed(1, 1, 1, Direction.NORTH);
        c.mark(Marker.HOUSING, 0, 1, 1);
        c.mark(Marker.HOUSING, 0, 1, 0);
    }

    private void barracks(int d) {
        house(2, d, 3, p.planks, true);
        for (int z = -d + 1; z + 1 <= d - 1; z += 2) {
            bed(-1, 1, z + 1, Direction.NORTH);
            bed(1, 1, z + 1, Direction.NORTH);
            c.mark(Marker.HOUSING, 0, 1, z);
            c.mark(Marker.HOUSING, 0, 1, z + 1);
        }
    }

    private void infirmary() {
        house(2, 3, 3, p.planks, true);
        BlockState foot = Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT);
        for (int x = -1; x <= 1; x += 2) {
            c.set(x, 1, -1, foot);
            c.chained(x, 1, -2, foot.setValue(BedBlock.PART, BedPart.HEAD));
        }
        c.set(1, 1, 2, Blocks.BREWING_STAND);
        c.set(-1, 1, 2, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
        c.mark(Marker.INFIRMARY, 0, 1, -1);
        c.mark(Marker.INFIRMARY, 0, 1, -2);
        c.mark(Marker.INFIRMARY, 0, 1, 0);
    }

    private void marketStall() {
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2) c.fill(sx, 1, sz, sx, 2, sz, p.fence);
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++) c.set(x, 3, z, ((x + z) & 1) == 0 ? p.accent : p.cloth);
        c.set(0, 1, -1, Blocks.BARREL);
        c.set(0, 1, 0, Blocks.COMPOSTER);
    }

    private void questBoard() {
        c.set(0, 1, 0, ModBlocks.QUEST_BOARD.get().defaultBlockState().setValue(QuestBoardBlock.FACING, Direction.SOUTH));
        c.mark(Marker.BOARD, 0, 1, 1);
    }

    // ============================== Посты и освещение ==============================

    /** Пост стражи; в просторной планировке, если клетку занял дом или поле, — ближайшая свободная. */
    private void post(int x, int y, int z) {
        if (version >= 2 && (c.has(x, y, z) || c.has(x, y + 1, z))) {
            for (int d = 1; d <= 4; d++)
                for (int dx = -d; dx <= d; dx++)
                    for (int dz = -d; dz <= d; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                        if (!c.has(x + dx, y, z + dz) && !c.has(x + dx, y + 1, z + dz) && layout.inside(x + dx, z + dz, r - 1)) {
                            c.mark(Marker.GUARD_POST, x + dx, y, z + dz);
                            return;
                        }
                    }
        }
        c.mark(Marker.GUARD_POST, x, y, z);
    }

    private void posts() {
        int q = r - 4;
        int k = layout == Layout.SQUARE ? q - 1 : q * 7 / 10;
        int[][] loop = {{0, -q}, {k, -k}, {q, 0}, {k, k}, {0, q}, {-k, k}, {-q, 0}, {-k, -k}};
        for (int[] pt : loop) c.mark(Marker.PATROL, pt[0], 1, pt[1]);
        int o = r + 7;
        int ok = layout == Layout.SQUARE ? o : o * 7 / 10;
        int[][] outer = {{0, -o}, {ok, -ok}, {o, 0}, {ok, ok}, {0, o}, {-ok, ok}, {-o, 0}, {-ok, -ok}};
        for (int[] pt : outer) c.mark(Marker.OUTER_PATROL, pt[0], 1, pt[1]);

        // Привратная стража.
        post(-3, 1, r - 3);
        post(3, 1, r - 3);
        if (tier == 1) {
            post(-(r - 2), 1, 0);
            post(r - 2, 1, 0);
            post(0, 1, -(r - 1));
            return;
        }
        // Стража на боевом ходу.
        int wy = tier == 2 ? 5 : 8;
        int w = r - 1;
        post(0, wy, -w);
        post(-w, wy, 0);
        post(w, wy, 0);
        post(-6, wy, w);
        post(6, wy, w);
        post(0, 1, r - 8);
    }

    private void lamps() {
        c.stage(ST_DECOR);
        int q = r - (tier == 1 ? 3 : 5);
        int k = layout == Layout.SQUARE ? q : q * 6 / 10;
        int[][] spots = {{-4, r - 4}, {4, r - 4}, {-k, -k}, {k, -k}, {-k, k}, {k, k}, {-q, 0}, {q, 0}, {-3, 3}, {3, -3}};
        for (int[] s : spots) lamp(s[0], s[1]);
    }

    private void lamp(int x, int z) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int y = 0; y <= 3; y++) if (c.has(x + dx, y, z + dz)) return;
        c.fill(x, 1, z, x, 2, z, p.fence);
        c.set(x, 3, z, Blocks.LANTERN);
    }
}
