package com.vladosiwe.lordsoutpost.data;

import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.Resource;
import com.vladosiwe.lordsoutpost.outpost.WorkZone;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Лавка торговца. Товар — настоящие предметы в личном инвентаре лавки ({@link Outpost#shopStock}, сохраняется):
 * торговец сам приносит их со склада через логистику, а предлагает игроку ровно то, что лежит в лавке.
 * Продажа идёт через ванильное окно торговли: игрок выбирает предложение и сколько раз его купить.
 * Вырученные изумруды уходят в казну. Товар, который поселению вдруг стал нужен, торговец относит обратно.
 */
public final class Shop {
    /** Сколько стопок держит лавка. */
    public static final int STACKS = 9;
    /** За один поход на склад торговец берёт не больше этого числа стопок. */
    private static final int PER_TRIP = 3;

    private Shop() {}

    /** Цена связки в изумрудах: стоимость ресурса по ценам Доски, с округлением вверх. */
    public static int price(ItemStack bundle) {
        ResourceItems.Value v = ResourceItems.of(bundle);
        if (v == null) return 0;
        double gold = v.perItem() * bundle.getCount() * QuestBoard.goldValue(v.resource());
        return (int) Math.max(1, Math.min(64, Math.ceil(gold)));
    }

    /** Сколько штук продавать за раз, чтобы связка стоила хотя бы изумруд. */
    static int bundle(ItemStack stack) {
        ResourceItems.Value v = ResourceItems.of(stack);
        if (v == null) return 0;
        double each = v.perItem() * QuestBoard.goldValue(v.resource());
        int n = each <= 0 ? stack.getMaxStackSize() : (int) Math.ceil(1.0 / each);
        return Math.max(1, Math.min(stack.getMaxStackSize(), n));
    }

    /** Предложения по содержимому лавки: одна строка на вид товара, число покупок — сколько связок лежит. */
    public static MerchantOffers offers(Outpost o) {
        Map<Item, Integer> totals = new LinkedHashMap<>();
        Map<Item, ItemStack> proto = new LinkedHashMap<>();
        for (ItemStack st : o.shopStock) {
            if (st.isEmpty() || ResourceItems.of(st) == null || !st.getComponentsPatch().isEmpty()) continue;
            totals.merge(st.getItem(), st.getCount(), Integer::sum);
            proto.putIfAbsent(st.getItem(), st);
        }
        MerchantOffers out = new MerchantOffers();
        for (var e : totals.entrySet()) {
            ItemStack p = proto.get(e.getKey());
            int size = bundle(p);
            int uses = e.getValue() / size;
            if (uses <= 0) continue;
            ItemStack result = p.copyWithCount(size);
            out.add(new MerchantOffer(new ItemCost(Items.EMERALD, price(result)), result, uses, 0, 0f));
        }
        return out;
    }

    /**
     * Игрок купил связку: она уходит из лавки, изумруды — в казну. Возвращает false, если товара уже нет
     * (тогда сделка не засчитывается, а окно закрывается вызывающим).
     */
    public static boolean sold(Outpost o, MerchantOffer offer) {
        ItemStack result = offer.getResult();
        int need = result.getCount();
        int have = 0;
        for (ItemStack st : o.shopStock) if (ItemStack.isSameItemSameComponents(st, result)) have += st.getCount();
        if (have < need) return false;
        for (Iterator<ItemStack> it = o.shopStock.iterator(); it.hasNext() && need > 0; ) {
            ItemStack st = it.next();
            if (!ItemStack.isSameItemSameComponents(st, result)) continue;
            int n = Math.min(need, st.getCount());
            st.shrink(n);
            need -= n;
            if (st.isEmpty()) it.remove();
        }
        o.add(Resource.GOLD, offer.getCostA().getCount());
        o.dirtyForClients = true;
        return true;
    }

    /** Кладёт предметы из ноши торговца в лавку (сколько поместится в STACKS стопок). Возвращает остаток. */
    public static ItemStack stock(Outpost o, ItemStack stack) {
        for (ItemStack have : o.shopStock) {
            if (stack.isEmpty()) break;
            if (ItemStack.isSameItemSameComponents(have, stack) && have.getCount() < have.getMaxStackSize()) {
                int n = Math.min(stack.getCount(), have.getMaxStackSize() - have.getCount());
                have.grow(n);
                stack.shrink(n);
            }
        }
        while (!stack.isEmpty() && o.shopStock.size() < STACKS) {
            int n = Math.min(stack.getCount(), stack.getMaxStackSize());
            o.shopStock.add(stack.split(n));
        }
        o.dirtyForClients = true;
        return stack;
    }

    /**
     * Что принести в лавку: излишки склада по тем же правилам, что и для каравана (запас еды, материалы на стройку
     * и отложенное под задачи не трогаются). Берутся предметы, которые реально лежат в бочках.
     */
    public static Map<Item, Integer> restock(ServerLevel level, Outpost o) {
        Map<Item, Integer> want = new LinkedHashMap<>();
        int free = STACKS - o.shopStock.size();
        if (free <= 0) return want;
        EnumMap<Resource, Double> surplus = Caravans.surplus(o);
        for (var e : surplus.entrySet()) {
            if (want.size() >= Math.min(free, PER_TRIP)) break;
            Item item = Storehouse.findItem(level, o, st -> {
                ResourceItems.Value v = ResourceItems.of(st);
                return v != null && v.resource() == e.getKey() && st.getComponentsPatch().isEmpty();
            });
            if (item == null) continue;
            ResourceItems.Value v = ResourceItems.of(new ItemStack(item));
            int count = (int) Math.min(item.getDefaultMaxStackSize(), Math.floor(e.getValue() / v.perItem()));
            count = Math.min(count, Logistics.available(level, o, item, null));
            if (count >= bundle(new ItemStack(item))) want.put(item, count);
        }
        return want;
    }

    /** Ресурс одной из стопок лавки сейчас в дефиците — её надо вернуть на склад. */
    @Nullable
    public static ItemStack takeBackScarce(Outpost o) {
        if (o.scarce == null && !o.foodCritical) return null;
        for (Iterator<ItemStack> it = o.shopStock.iterator(); it.hasNext(); ) {
            ItemStack st = it.next();
            ResourceItems.Value v = ResourceItems.of(st);
            if (v != null && (v.resource() == o.scarce || (o.foodCritical && (v.resource() == Resource.FOOD || v.resource() == Resource.WHEAT)))) {
                it.remove();
                o.dirtyForClients = true;
                return st;
            }
        }
        return null;
    }

    public static boolean hasShop(Outpost o) {
        return o.hasZone(WorkZone.SHOP);
    }
}
