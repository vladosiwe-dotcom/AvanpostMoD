package com.vladosiwe.lordsoutpost.entity;

import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.data.Shop;
import com.vladosiwe.lordsoutpost.outpost.AccessLevel;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.protection.Access;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.jetbrains.annotations.Nullable;

/**
 * Торговля придворного мага: ванильное окно торговли, а товар — только {@link Outpost#magicShelf}, то есть
 * настоящие предметы, которые маг сам принёс со склада. Своих сделок пироманта Iron's Spells (они создают товар
 * из ничего) у тела мага нет: взаимодействие перехватывается и открывает это окно.
 * Окно живёт, пока жив продавец и игрок рядом; каждый открытый экземпляр работает с записью аванпоста напрямую,
 * поэтому продать один и тот же предмет дважды нельзя.
 */
public final class MageShelf implements Merchant {
    private final LivingEntity seller;
    private final long outpostId;
    @Nullable private Player trading;
    private MerchantOffers offers;

    private MageShelf(LivingEntity seller, long outpostId, MerchantOffers offers) {
        this.seller = seller;
        this.outpostId = outpostId;
        this.offers = offers;
    }

    /** Игрок заговорил с магом: открыть полку (врагам форта — нет). Возвращает true, если окно открыто. */
    public static boolean open(ServerPlayer player, LivingEntity seller, Outpost o) {
        var data = OutpostSavedData.get(player.serverLevel());
        if (o.state != OutpostState.OWNED || Access.level(data, o, player.getUUID()) == AccessLevel.HOSTILE) return false;
        MerchantOffers offers = Shop.shelfOffers(o);
        if (offers.isEmpty()) {
            player.displayClientMessage(Component.translatable("lordsoutpost.msg.shelf_empty", seller.getName()), true);
            return true;
        }
        MageShelf shelf = new MageShelf(seller, o.id, offers);
        shelf.setTradingPlayer(player);
        shelf.openTradingScreen(player, Component.translatable("lordsoutpost.ui.shelf.title", seller.getName()), 0);
        return true;
    }

    @Nullable
    private Outpost outpost() {
        if (!(seller.level() instanceof ServerLevel level)) return null;
        return OutpostSavedData.get(level).byId(outpostId);
    }

    @Override
    public void setTradingPlayer(@Nullable Player player) {
        trading = player;
    }

    /** Окно торговли валидно, пока это значение — игрок: продавец погиб или игрок ушёл — сервер закроет окно сам. */
    @Nullable
    @Override
    public Player getTradingPlayer() {
        if (trading != null && !seller.level().isClientSide
                && (!seller.isAlive() || seller.isRemoved() || seller.distanceToSqr(trading) > 64)) trading = null;
        return trading;
    }

    @Override
    public MerchantOffers getOffers() {
        return offers;
    }

    @Override
    public void overrideOffers(MerchantOffers offers) {
        this.offers = offers;
    }

    @Override
    public void notifyTrade(MerchantOffer offer) {
        Outpost o = outpost();
        if (o == null || !Shop.soldFromShelf(o, offer)) {
            // Товара уже нет (продан в другом окне) — строка закрывается, сделка не засчитана как продажа полки.
            offer.setToOutOfStock();
            if (o != null) LordsOutpost.LOGGER.warn("Полка мага форта #{}: продан предмет, которого нет на полке", Long.toHexString(o.id));
            return;
        }
        seller.playSound(SoundEvents.EVOKER_CELEBRATE, 0.5f, 1.2f);
        if (seller.level() instanceof ServerLevel sl) OutpostSavedData.get(sl).setDirty();
    }

    @Override
    public void notifyTradeUpdated(ItemStack stack) {
    }

    @Override
    public int getVillagerXp() {
        return 0;
    }

    @Override
    public void overrideXp(int xp) {
    }

    @Override
    public boolean showProgressBar() {
        return false;
    }

    @Override
    public SoundEvent getNotifyTradeSound() {
        return SoundEvents.ENCHANTMENT_TABLE_USE;
    }

    @Override
    public boolean isClientSide() {
        return seller.level().isClientSide;
    }
}
