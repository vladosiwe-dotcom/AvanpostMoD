package com.vladosiwe.lordsoutpost.blueprint;

import com.vladosiwe.lordsoutpost.outpost.Project;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Чертежи построек, которые Лорд заказывает сверх плана тиров: дом, сторожевая башня, участок стены.
 * Канонически вход смотрит на юг (+Z), y = 0 — пол на уровне земли; поворот делает {@link Project}.
 * Материалы — из палитры форта, поэтому пристройки выглядят частью поселения.
 */
public final class ProjectDesigner {
    /** Шаг канонического чертежа и признак пола (под полом досыпается фундамент). */
    public record Cell(BlockPos pos, BlockState state, boolean chain, boolean floor) {}

    private ProjectDesigner() {}

    public static List<Cell> design(Project.Type type, Palette p) {
        return design(type, p, 1);
    }

    public static List<Cell> design(Project.Type type, Palette p, int level) {
        List<Cell> out = new ArrayList<>();
        switch (type) {
            // Дом: 5×5 на 2 кровати → 7×5 на 4 → 7×7 на 8.
            case HOUSE -> dorm(out, p, level >= 2 ? 3 : 2, level >= 3 ? 3 : 2, false);
            // Казарма: каменная, 7×7 на 8 коек → 9×7 на 12.
            case BARRACKS -> dorm(out, p, level >= 2 ? 4 : 3, 3, true);
            case WATCHTOWER -> tower(out, p);
            case WALL -> wall(out, p);
            case PEN -> pen(out, p);
            case SMITHY -> smithy(out, p);
            case MINE -> mine(out, p);
            case FARM -> farm(out, p);
            case WOODYARD -> woodyard(out, p);
        }
        return sorted(out);
    }

    /**
     * Улучшение с уровня from на to: только то, что меняется. Новые и изменённые клетки ставятся, клетки старого
     * уровня, которых нет в новом, разбираются (воздух). Совпадающее остаётся как есть и не стоит ресурсов.
     */
    public static List<Cell> upgrade(Project.Type type, Palette p, int from, int to) {
        java.util.Map<BlockPos, BlockState> old = new java.util.HashMap<>();
        for (Cell c : design(type, p, from)) old.put(c.pos(), c.state());
        List<Cell> neu = design(type, p, to);
        java.util.Set<BlockPos> covered = new java.util.HashSet<>();
        List<Cell> out = new ArrayList<>();
        boolean prevKept = false;
        for (Cell c : neu) {
            covered.add(c.pos());
            BlockState was = old.get(c.pos());
            boolean same = was != null ? was.equals(c.state()) : c.state().isAir();
            if (same) {
                prevKept = false;
                continue;
            }
            out.add(new Cell(c.pos(), c.state(), c.chain() && prevKept, c.floor()));
            prevKept = true;
        }
        for (var e : old.entrySet()) {
            if (!covered.contains(e.getKey()) && !e.getValue().isAir()) out.add(new Cell(e.getKey(), Blocks.AIR.defaultBlockState(), false, false));
        }
        return sorted(out);
    }

    private static List<Cell> sorted(List<Cell> in) {
        // Одна клетка — один шаг: из повторов остаётся последний (кровать поверх расчищенного воздуха и т.п.).
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        List<Cell> out = new ArrayList<>(in.size());
        for (int i = in.size() - 1; i >= 0; i--) if (seen.add(in.get(i).pos())) out.add(in.get(i));
        java.util.Collections.reverse(out);
        // Снизу вверх, чтобы ничего не висело в воздухе; связанные половины (дверь, кровать) остаются рядом.
        List<List<Cell>> groups = new ArrayList<>();
        for (Cell c : out) {
            if (c.chain() && !groups.isEmpty()) groups.get(groups.size() - 1).add(c);
            else groups.add(new ArrayList<>(List.of(c)));
        }
        groups.sort(Comparator.comparingInt(g -> g.get(0).pos().getY()));
        List<Cell> sorted = new ArrayList<>();
        for (List<Cell> g : groups) sorted.addAll(g);
        return sorted;
    }

    private static void put(List<Cell> out, int x, int y, int z, BlockState s) {
        out.add(new Cell(new BlockPos(x, y, z), s, false, y == 0));
    }

    private static BlockState log(Palette p) {
        BlockState s = p.log.defaultBlockState();
        return s.hasProperty(RotatedPillarBlock.AXIS) ? s.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y) : s;
    }

    /** Дверь той же породы, что и доски палитры. */
    private static Block door(Palette p) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(p.planks);
        Block d = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), id.getPath().replace("_planks", "_door")));
        return d instanceof DoorBlock ? d : Blocks.OAK_DOOR;
    }

    private static void door(List<Cell> out, Palette p, int x, int y, int z) {
        BlockState d = door(p).defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        out.add(new Cell(new BlockPos(x, y, z), d.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), false, false));
        out.add(new Cell(new BlockPos(x, y + 1, z), d.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), true, false));
    }

    /**
     * Жилой дом или казарма: пол, бревенчатые углы, стены (у казармы снизу камень), окна, дверь на юг, плоская
     * крыша; внутри проход по оси x = 0 и ряды кроватей изголовьем на север.
     */
    private static void dorm(List<Cell> out, Palette p, int hx, int hz, boolean barracks) {
        for (int x = -hx; x <= hx; x++) for (int z = -hz; z <= hz; z++) put(out, x, 0, z, (barracks ? p.stone : p.planks).defaultBlockState());
        for (int y = 1; y <= 3; y++)
            for (int x = -hx; x <= hx; x++)
                for (int z = -hz; z <= hz; z++) {
                    boolean edgeX = Math.abs(x) == hx, edgeZ = Math.abs(z) == hz;
                    if (!edgeX && !edgeZ) {
                        put(out, x, y, z, Blocks.AIR.defaultBlockState());
                        continue;
                    }
                    if (x == 0 && z == hz && y <= 2) continue;
                    boolean window = y == 2 && ((edgeX && Math.floorMod(z, 2) == 0 && !edgeZ) || (edgeZ && z == -hz && Math.floorMod(x, 2) == 0 && !edgeX));
                    BlockState wall = barracks && y == 1 ? p.stone.defaultBlockState() : p.planks.defaultBlockState();
                    put(out, x, y, z, edgeX && edgeZ ? log(p) : window ? Blocks.GLASS_PANE.defaultBlockState() : wall);
                }
        door(out, p, 0, 1, hz);
        for (int x = -hx; x <= hx; x++) for (int z = -hz; z <= hz; z++) put(out, x, 4, z, p.roof.defaultBlockState());
        BlockState bed = p.bed.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        // Ряды кроватей: изножье на z, изголовье на z-1; первый ряд у задней стены, второй — у входа (если дом глубокий).
        int[] rows = hz >= 3 ? new int[]{-hz + 2, hz - 1} : new int[]{0};
        for (int row : rows)
            for (int bx = -(hx - 1); bx <= hx - 1; bx++) {
                if (bx == 0) continue;
                out.add(new Cell(new BlockPos(bx, 1, row), bed.setValue(BedBlock.PART, BedPart.FOOT), false, false));
                out.add(new Cell(new BlockPos(bx, 1, row - 1), bed.setValue(BedBlock.PART, BedPart.HEAD), true, false));
            }
        put(out, 0, 2, -hz + 1, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.SOUTH));
    }

    /** Сторожевая башня: сруб 3×3 с лестницей внутри, площадка 5×5 на высоте 6, ограждение. */
    private static void tower(List<Cell> out, Palette p) {
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) put(out, x, 0, z, p.stone.defaultBlockState());
        for (int y = 1; y <= 5; y++)
            for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && z == 0) {
                        put(out, 0, y, 0, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
                        continue;
                    }
                    if (x == 0 && z == 1 && y <= 2) continue;
                    put(out, x, y, z, Math.abs(x) == 1 && Math.abs(z) == 1 ? log(p) : p.planks.defaultBlockState());
                }
        door(out, p, 0, 1, 1);
        for (int x = -2; x <= 2; x++)
            for (int z = -2; z <= 2; z++) {
                if (x == 0 && z == 0) put(out, 0, 6, 0, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
                else put(out, x, 6, z, p.planks.defaultBlockState());
            }
        for (int x = -2; x <= 2; x++)
            for (int z = -2; z <= 2; z++) if (Math.abs(x) == 2 || Math.abs(z) == 2) put(out, x, 7, z, p.fence.defaultBlockState());
        put(out, 1, 7, 1, Blocks.AIR.defaultBlockState());
    }

    /** Участок стены длиной 11 блоков: фундамент, кладка в 3 блока, зубцы. */
    private static void wall(List<Cell> out, Palette p) {
        for (int x = -5; x <= 5; x++) {
            put(out, x, 0, 0, p.stone.defaultBlockState());
            for (int y = 1; y <= 3; y++) put(out, x, y, 0, (x & 1) == 0 && y == 2 ? p.stoneAlt.defaultBlockState() : p.stone.defaultBlockState());
            put(out, x, 4, 0, p.stoneWall.defaultBlockState());
        }
    }

    /** Загон 7×7: изгородь, калитка на юг, внутри расчищено (5×5 для скота). */
    private static void pen(List<Cell> out, Palette p) {
        for (int x = -3; x <= 3; x++)
            for (int z = -3; z <= 3; z++) {
                boolean ring = Math.abs(x) == 3 || Math.abs(z) == 3;
                if (!ring) {
                    put(out, x, 1, z, Blocks.AIR.defaultBlockState());
                    put(out, x, 2, z, Blocks.AIR.defaultBlockState());
                } else if (x == 0 && z == 3) {
                    put(out, x, 1, z, p.fenceGate.defaultBlockState().setValue(net.minecraft.world.level.block.FenceGateBlock.FACING, Direction.SOUTH));
                } else {
                    put(out, x, 1, z, p.fence.defaultBlockState());
                }
            }
    }

    /** Открытая кузница 5×5: каменный пол, столбы, навес; доменная печь, печь, наковальня, кузнечный стол. */
    private static void smithy(List<Cell> out, Palette p) {
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) put(out, x, 0, z, p.stone.defaultBlockState());
        for (int y = 1; y <= 3; y++)
            for (int x = -2; x <= 2; x++)
                for (int z = -2; z <= 2; z++) {
                    if (Math.abs(x) == 2 && Math.abs(z) == 2) put(out, x, y, z, log(p));
                    else put(out, x, y, z, Blocks.AIR.defaultBlockState());
                }
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) put(out, x, 4, z, p.slab.defaultBlockState());
        put(out, -1, 1, -1, Blocks.BLAST_FURNACE.defaultBlockState().setValue(net.minecraft.world.level.block.AbstractFurnaceBlock.FACING, Direction.SOUTH));
        put(out, 0, 1, -1, Blocks.FURNACE.defaultBlockState().setValue(net.minecraft.world.level.block.AbstractFurnaceBlock.FACING, Direction.SOUTH));
        put(out, 1, 1, 1, Blocks.ANVIL.defaultBlockState().setValue(net.minecraft.world.level.block.AnvilBlock.FACING, Direction.EAST));
        put(out, -1, 1, 1, Blocks.SMITHING_TABLE.defaultBlockState());
        put(out, 1, 2, -2, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.SOUTH));
    }

    /**
     * Шахтный копёр: каменный оголовок 3×3 вокруг устья, столбы и навес. Сам ствол вниз (6 блоков) с лестницами
     * прокапывает шахтёр: это его работа, а не строителя.
     */
    private static void mine(List<Cell> out, Palette p) {
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) put(out, 0, 0, 0, Blocks.AIR.defaultBlockState());
                else put(out, x, 0, z, p.stone.defaultBlockState());
            }
        for (int y = 1; y <= 2; y++)
            for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++) {
                    if (Math.abs(x) == 1 && Math.abs(z) == 1) put(out, x, y, z, log(p));
                    else put(out, x, y, z, Blocks.AIR.defaultBlockState());
                }
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) put(out, x, 3, z, p.slab.defaultBlockState());
    }

    /** Поле 7×7 под пашню с водой в центре (увлажняет всё поле), по углам — фонари на столбиках, компостер у края. */
    private static void farm(List<Cell> out, Palette p) {
        for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++) {
                boolean field = Math.abs(x) <= 3 && Math.abs(z) <= 3;
                put(out, x, 0, z, field ? (x == 0 && z == 0 ? Blocks.WATER.defaultBlockState() : Blocks.DIRT.defaultBlockState()) : p.path.defaultBlockState());
                put(out, x, 1, z, Blocks.AIR.defaultBlockState());
                put(out, x, 2, z, Blocks.AIR.defaultBlockState());
            }
        out.removeIf(c -> c.pos().equals(new BlockPos(0, 1, 0)));
        out.add(new Cell(new BlockPos(0, 1, 0), Blocks.LILY_PAD.defaultBlockState(), false, false));
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2) {
                replace(out, sx * 4, 1, sz * 4, p.fence.defaultBlockState());
                replace(out, sx * 4, 2, sz * 4, Blocks.LANTERN.defaultBlockState());
            }
        replace(out, 1, 1, 4, Blocks.COMPOSTER.defaultBlockState());
    }

    /** Дровяной двор 5×5: настил, колода для рубки, поленница вдоль задней стороны, бочка под щепу. */
    private static void woodyard(List<Cell> out, Palette p) {
        for (int x = -2; x <= 2; x++)
            for (int z = -2; z <= 2; z++) {
                put(out, x, 0, z, p.path.defaultBlockState());
                put(out, x, 1, z, Blocks.AIR.defaultBlockState());
                put(out, x, 2, z, Blocks.AIR.defaultBlockState());
            }
        BlockState lying = p.log.defaultBlockState().hasProperty(RotatedPillarBlock.AXIS)
                ? p.log.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X) : p.log.defaultBlockState();
        for (int x = -2; x <= 2; x++) {
            replace(out, x, 1, -2, lying);
            if (Math.abs(x) <= 1) replace(out, x, 2, -2, lying);
        }
        replace(out, 1, 1, 0, log(p));
        replace(out, -2, 1, 1, Blocks.BARREL.defaultBlockState());
        replace(out, 2, 1, 2, p.fence.defaultBlockState());
        replace(out, 2, 2, 2, Blocks.LANTERN.defaultBlockState());
    }

    private static void replace(List<Cell> out, int x, int y, int z, BlockState s) {
        BlockPos at = new BlockPos(x, y, z);
        out.removeIf(c -> c.pos().equals(at));
        put(out, x, y, z, s);
    }

    /** Якорь зоны в каноне: центр загона, место кузнеца, точка входа в шахту (у оголовка со стороны входа). */
    public static BlockPos anchor(Project.Type type) {
        return switch (type) {
            case MINE -> new BlockPos(0, 1, 2);
            default -> new BlockPos(0, 1, 0);
        };
    }

    /** Точка, где стоит дозорный на башне (в каноне). */
    public static BlockPos towerPost() {
        return new BlockPos(1, 7, 1);
    }

    /** Глубина постройки вперёд от входа: насколько отступить от игрока, чтобы не строить на нём. */
    public static int depth(Project.Type type) {
        return switch (type) {
            case HOUSE -> 5;
            case BARRACKS -> 6;
            case FARM -> 6;
            case WOODYARD -> 4;
            case WATCHTOWER -> 4;
            case WALL -> 3;
            case PEN -> 5;
            case SMITHY -> 4;
            case MINE -> 3;
        };
    }
}
