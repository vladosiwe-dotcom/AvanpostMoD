package com.vladosiwe.lordsoutpost.client;

import com.vladosiwe.lordsoutpost.blueprint.Layout;
import com.vladosiwe.lordsoutpost.outpost.AccessLevel;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** То, что клиент знает о мире аванпостов: метки карты, права рядом стоящих фортов, баннер находки. */
public final class ClientState {
    /** Метка аванпоста на карте. known = false — слух (знак вопроса), подробностей нет. */
    public static final class Marker {
        public long id;
        public int x, y, z;
        public boolean known;
        public int skulls, tier, reach, rot;
        public float progress;
        public OutpostState state = OutpostState.WILD;
        public String name = "", owner = "";
        public Layout layout = Layout.SQUARE;
        public AccessLevel access = AccessLevel.GUEST;
        public int pact;
        public int[] outline = new int[0];

        /** Та же геометрия, что на сервере: точка на территории форта. */
        public boolean contains(BlockPos pos) {
            if (!known || pos.getY() < y - 16 || pos.getY() > y + 48) return false;
            int r = reach + Layout.MARGIN;
            int dx = pos.getX() - x, dz = pos.getZ() - z;
            if (Math.abs(dx) > r + 1 || Math.abs(dz) > r + 1) return false;
            int cx, cz;
            switch (rot) {
                case 1 -> { cx = dz; cz = -dx; }
                case 2 -> { cx = -dx; cz = -dz; }
                case 3 -> { cx = -dz; cz = dx; }
                default -> { cx = dx; cz = dz; }
            }
            return layout.inside(cx, cz, r);
        }
    }

    public static final class SquadMark {
        public int id, order, members;
        public double x, z;
        public long[] route = new long[0];
        @Nullable public BlockPos hold;
    }

    public static final class CaravanMark {
        public double x, z;
        public int fx, fz, tx, tz, phase;
        public boolean physical;
    }

    public static final List<Marker> MARKERS = new ArrayList<>();
    public static final List<SquadMark> SQUADS = new ArrayList<>();
    public static final List<CaravanMark> CARAVANS = new ArrayList<>();
    public static int[] nextOutline = new int[0];
    public static long own;
    public static long mapStamp;

    // Баннер «Найден Аванпост».
    public static int bannerSkulls;
    public static long bannerStart = -10000;

    private ClientState() {}

    public static void clear() {
        MARKERS.clear();
        SQUADS.clear();
        CARAVANS.clear();
        nextOutline = new int[0];
        own = 0;
        bannerStart = -10000;
    }

    public static void readMap(CompoundTag tag) {
        MARKERS.clear();
        ListTag list = tag.getList("outposts", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            Marker m = new Marker();
            m.id = t.getLong("id");
            m.x = t.getInt("x");
            m.z = t.getInt("z");
            m.known = t.getBoolean("known");
            if (m.known) {
                m.y = t.getInt("y");
                m.skulls = t.getByte("skulls");
                m.state = OutpostState.VALUES[Math.max(0, Math.min(2, t.getByte("state")))];
                m.name = t.getString("name");
                m.owner = t.getString("owner");
                m.tier = t.getByte("tier");
                m.progress = t.getFloat("progress");
                m.layout = Layout.byId(t.getString("layout"));
                m.rot = t.getByte("rot");
                m.reach = t.getInt("reach");
                m.access = AccessLevel.VALUES[Math.max(0, Math.min(3, t.getByte("access")))];
                m.pact = t.getByte("pact");
                m.outline = t.getIntArray("outline");
            }
            MARKERS.add(m);
        }
        own = tag.getLong("own");
        nextOutline = tag.getIntArray("nextOutline");
        SQUADS.clear();
        ListTag squads = tag.getList("squads", Tag.TAG_COMPOUND);
        for (int i = 0; i < squads.size(); i++) {
            CompoundTag t = squads.getCompound(i);
            SquadMark s = new SquadMark();
            s.id = t.getByte("id");
            s.order = t.getByte("order");
            s.members = t.getInt("members");
            s.x = t.getDouble("x");
            s.z = t.getDouble("z");
            s.route = t.getLongArray("route");
            s.hold = t.contains("hold") ? BlockPos.of(t.getLong("hold")) : null;
            SQUADS.add(s);
        }
        CARAVANS.clear();
        ListTag caravans = tag.getList("caravans", Tag.TAG_COMPOUND);
        for (int i = 0; i < caravans.size(); i++) {
            CompoundTag t = caravans.getCompound(i);
            CaravanMark c = new CaravanMark();
            c.x = t.getDouble("x");
            c.z = t.getDouble("z");
            c.fx = t.getInt("fx");
            c.fz = t.getInt("fz");
            c.tx = t.getInt("tx");
            c.tz = t.getInt("tz");
            c.phase = t.getByte("phase");
            c.physical = t.getBoolean("physical");
            CARAVANS.add(c);
        }
        mapStamp = System.currentTimeMillis();
    }

    @Nullable
    public static Marker ownMarker() {
        if (own == 0) return null;
        for (Marker m : MARKERS) if (m.id == own) return m;
        return null;
    }

    /** Пройдёт ли локальный игрок сквозь решётку ворот (то же правило, что на сервере). */
    public static boolean canPassGate(BlockPos pos, boolean inner) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.isCreative() || mc.player.isSpectator()) return true;
        if (mc.level == null || mc.level.dimension() != Level.OVERWORLD) return true;
        for (Marker m : MARKERS) {
            if (!m.contains(pos)) continue;
            if (m.state == OutpostState.CONQUERED) return true;
            if (m.state == OutpostState.WILD) return false;
            return m.access.atLeast(inner ? AccessLevel.ALLY : AccessLevel.GUEST);
        }
        return true;
    }
}
