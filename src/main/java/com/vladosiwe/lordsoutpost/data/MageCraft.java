package com.vladosiwe.lordsoutpost.data;

import com.vladosiwe.lordsoutpost.compat.MagicLoot;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Рецепты придворного мага. Всё делается из настоящих предметов со склада, через резерв и сумку мага;
 * готовое — тоже настоящие предметы, которые маг относит на склад.
 * <ul>
 *   <li>С Iron's Spells 'n Spellbooks (по правилам мода): свиток огня в кузне свитков — бумага + чернила + стержень
 *       ифрита (фокус школы огня), уровень заклинания случаен внутри редкости чернил; повышение чернил — 4 чернила +
 *       катализатор (медь, железо, золото, аметист) дают 1 чернила следующей редкости и 3 пустых пузырька.</li>
 *   <li>Придумано для поселения: обычные чернила — пузырёк + 2 угля (копоть); магическая эссенция — 3 лазурита +
 *       осколок аметиста.</li>
 *   <li>Без мода: зачарованная книга — книга + 3 лазурита, сила зачарования случайна.</li>
 * </ul>
 */
public final class MageCraft {
    public record Recipe(String id, Map<Item, Integer> inputs, boolean forge, int rarity) {}

    /** Сколько готовых свитков, чернил одной редкости и эссенции держать на складе. */
    private static final int KEEP_SCROLLS = 6, KEEP_INK = 4, KEEP_ESSENCE = 8, KEEP_BOOKS = 4;
    private static final Item[] CATALYSTS = {Items.COPPER_INGOT, Items.IRON_INGOT, Items.GOLD_INGOT, Items.AMETHYST_SHARD};

    private MageCraft() {}

    public static Item iss(String path) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(MagicLoot.IRONS, path));
    }

    /** Сколько штук предмета маг оставляет на складе для своих рецептов (остальное можно выставить на полку). */
    public static int keepInStore(Item item) {
        if (!MagicLoot.ironsLoaded()) return 0;
        for (int r = 0; r < MagicLoot.INK_RARITIES.length; r++) if (item == ink(r)) return KEEP_INK;
        return 0;
    }

    public static Item ink(int rarity) {
        return iss(MagicLoot.INK_RARITIES[rarity] + "_ink");
    }

    private static boolean has(ServerLevel level, Outpost o, Map<Item, Integer> inputs) {
        for (var e : inputs.entrySet()) {
            if (e.getKey() == Items.AIR || Logistics.available(level, o, e.getKey(), null) < e.getValue()) return false;
        }
        return true;
    }

    private static int stored(ServerLevel level, Outpost o, Item item) {
        int n = Storehouse.countItem(level, o, item);
        for (ItemStack st : o.storeItems) if (st.is(item)) n += st.getCount();
        for (ItemStack st : o.shopStock) if (st.is(item)) n += st.getCount();
        return n;
    }

    private static Map<Item, Integer> of(Object... kv) {
        Map<Item, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.merge((Item) kv[i], (Integer) kv[i + 1], Integer::sum);
        return m;
    }

    /** Что сейчас нужнее всего и на что есть ингредиенты (null — делать нечего). */
    @Nullable
    public static Recipe next(ServerLevel level, Outpost o, boolean forgeNearby) {
        if (MagicLoot.ironsLoaded() && iss("scroll") != Items.AIR) {
            // 1. Свитки — главный товар; лучшие чернила идут в дело первыми.
            if (forgeNearby && stored(level, o, iss("scroll")) < KEEP_SCROLLS) {
                for (int r = 4; r >= 0; r--) {
                    Recipe rec = new Recipe("fire_scroll_" + MagicLoot.INK_RARITIES[r], of(Items.PAPER, 1, ink(r), 1, Items.BLAZE_ROD, 1), true, r);
                    if (has(level, o, rec.inputs())) return rec;
                }
            }
            // 2. Повышение чернил, пока следующей редкости мало.
            for (int r = 3; r >= 0; r--) {
                if (stored(level, o, ink(r + 1)) >= KEEP_INK / 2) continue;
                Recipe rec = new Recipe("ink_" + MagicLoot.INK_RARITIES[r + 1], of(ink(r), 4, CATALYSTS[r], 1), false, r + 1);
                if (has(level, o, rec.inputs())) return rec;
            }
            // 3. Обычные чернила из копоти.
            if (stored(level, o, ink(0)) < KEEP_INK) {
                for (Item coal : new Item[]{Items.COAL, Items.CHARCOAL}) {
                    Recipe rec = new Recipe("ink_common", of(Items.GLASS_BOTTLE, 1, coal, 2), false, 0);
                    if (has(level, o, rec.inputs())) return rec;
                }
            }
            // 4. Магическая эссенция.
            if (stored(level, o, iss("arcane_essence")) < KEEP_ESSENCE) {
                Recipe rec = new Recipe("arcane_essence", of(Items.LAPIS_LAZULI, 3, Items.AMETHYST_SHARD, 1), false, 0);
                if (has(level, o, rec.inputs())) return rec;
            }
            return null;
        }
        if (stored(level, o, Items.ENCHANTED_BOOK) < KEEP_BOOKS) {
            Recipe rec = new Recipe("enchanted_book", of(Items.BOOK, 1, Items.LAPIS_LAZULI, 3), false, 0);
            if (has(level, o, rec.inputs())) return rec;
        }
        return null;
    }

    /** Рецепт, все ингредиенты которого уже лежат в сумке (работа, прерванная набегом, продолжается). */
    @Nullable
    public static Recipe resumable(ServerLevel level, Outpost o, boolean forgeNearby) {
        List<Recipe> all = new ArrayList<>();
        if (MagicLoot.ironsLoaded()) {
            for (int r = 4; r >= 0; r--) all.add(new Recipe("fire_scroll_" + MagicLoot.INK_RARITIES[r], of(Items.PAPER, 1, ink(r), 1, Items.BLAZE_ROD, 1), true, r));
            for (int r = 3; r >= 0; r--) all.add(new Recipe("ink_" + MagicLoot.INK_RARITIES[r + 1], of(ink(r), 4, CATALYSTS[r], 1), false, r + 1));
            for (Item coal : new Item[]{Items.COAL, Items.CHARCOAL}) all.add(new Recipe("ink_common", of(Items.GLASS_BOTTLE, 1, coal, 2), false, 0));
            all.add(new Recipe("arcane_essence", of(Items.LAPIS_LAZULI, 3, Items.AMETHYST_SHARD, 1), false, 0));
        } else {
            all.add(new Recipe("enchanted_book", of(Items.BOOK, 1, Items.LAPIS_LAZULI, 3), false, 0));
        }
        for (Recipe rec : all) {
            if (rec.forge() && !forgeNearby) continue;
            boolean ok = true;
            for (var e : rec.inputs().entrySet()) if (e.getKey() == Items.AIR || o.mageSatchel.countItem(e.getKey()) < e.getValue()) ok = false;
            if (ok) return rec;
        }
        return null;
    }

    /** Готовые предметы рецепта (качество свитков и книг — случайное, по правилам их источника). */
    public static List<ItemStack> craft(ServerLevel level, Recipe rec, RandomSource rnd) {
        List<ItemStack> out = new ArrayList<>();
        switch (rec.id()) {
            case "arcane_essence" -> out.add(new ItemStack(iss("arcane_essence")));
            case "ink_common" -> out.add(new ItemStack(ink(0)));
            case "enchanted_book" -> out.addAll(MagicLoot.mageBook(level, rnd));
            default -> {
                if (rec.id().startsWith("fire_scroll_")) out.addAll(MagicLoot.fireScroll(level, rec.rarity(), rnd));
                else if (rec.id().startsWith("ink_")) {
                    out.add(new ItemStack(ink(rec.rarity())));
                    out.add(new ItemStack(Items.GLASS_BOTTLE, 3));
                }
            }
        }
        out.removeIf(ItemStack::isEmpty);
        return out;
    }

    /** Кузня свитков Iron's Spells в 8 блоках от места мага (её ставит Лорд — это станция мода). */
    @Nullable
    public static BlockPos forge(ServerLevel level, BlockPos at) {
        var forgeBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(MagicLoot.IRONS, "scroll_forge"));
        if (forgeBlock == net.minecraft.world.level.block.Blocks.AIR) return null;
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-8, -3, -8), at.offset(8, 10, 8))) {
            if (level.isLoaded(p) && level.getBlockState(p).is(forgeBlock)) return p.immutable();
        }
        return null;
    }
}
