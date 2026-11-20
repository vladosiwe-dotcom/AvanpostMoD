package com.vladosiwe.lordsoutpost.entity;

import com.vladosiwe.lordsoutpost.entity.ai.BuildGoal;
import com.vladosiwe.lordsoutpost.entity.ai.CaravanGoal;
import com.vladosiwe.lordsoutpost.entity.ai.ShelterGoal;
import com.vladosiwe.lordsoutpost.entity.ai.WorkGoal;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Role;
import com.vladosiwe.lordsoutpost.server.Interactions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** Мирный житель: Управляющий, строитель, охотник, скотовод, шахтёр, лесоруб, кузнец, маг, караванщик. */
public class WorkerEntity extends OutpostNpc implements net.minecraft.world.item.trading.Merchant {
    /** Ноша жителя: то, что он добыл или подобрал и ещё не донёс до склада. */
    public final net.minecraft.world.SimpleContainer inventory = new net.minecraft.world.SimpleContainer(12);
    public WorkGoal workGoal;

    public WorkerEntity(EntityType<? extends WorkerEntity> type, Level level) {
        super(type, level);
    }

    /** Взять предмет в ношу; возвращает то, что не поместилось. */
    public ItemStack collect(ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        return inventory.addItem(stack.copy());
    }

    /** Сколько предметов в ноше. */
    public int carried() {
        int n = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) n += inventory.getItem(i).getCount();
        return n;
    }

    /** Количество занятых слотов ноши. Заполнение измеряется слотами, а не числом предметов. */
    public int occupiedSlots() {
        int occupied = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (!inventory.getItem(i).isEmpty()) occupied++;
        }
        return occupied;
    }

    /** Пора возвращаться на склад при заполнении не менее 75% слотов ноши. */
    public boolean cargoReadyToDeposit() {
        int slots = inventory.getContainerSize();
        return slots > 0 && occupiedSlots() * 4 >= slots * 3;
    }

    public boolean inventoryFull() {
        return occupiedSlots() == inventory.getContainerSize();
    }

    /** Уровень инструмента шахтёра ограничен и тиром форта, и личным опытом шахтёра. */
    public static int minerToolTier(Outpost outpost, Resident resident) {
        return Math.max(1, Math.min(3, Math.min(outpost.tier, 1 + (resident.miningLevel() - 1) / 2)));
    }

    /** Строитель сообщает соседним строителям, занят ли уже этот рабочий блок. */
    public boolean reservesBuildPosition(BlockPos pos) {
        for (var wrapped : goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof com.vladosiwe.lordsoutpost.entity.ai.BuildGoal build
                    && build.reservesWorkPosition(pos)) return true;
        }
        return false;
    }

    public java.util.List<String> goalClassNames() {
        java.util.List<String> out = new java.util.ArrayList<>();
        goalSelector.getAvailableGoals().stream().filter(w -> w.isRunning()).forEach(w -> {
            var goal = w.getGoal();
            out.add(goal instanceof com.vladosiwe.lordsoutpost.entity.ai.BuildGoal build ? build.debugStatus() : goal.getClass().getSimpleName());
        });
        return out;
    }

    /** Погибший житель роняет ношу. */
    @Override
    protected void dropEquipment() {
        super.dropEquipment();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack st = inventory.removeItemNoUpdate(i);
            if (!st.isEmpty()) spawnAtLocation(st);
        }
    }

    public static AttributeSupplier.Builder attributes() {
        return createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.ATTACK_DAMAGE, 2)
                .add(Attributes.FOLLOW_RANGE, 32);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.OpenDoorGoal(this, true));
        goalSelector.addGoal(1, new ShelterGoal(this));
        goalSelector.addGoal(2, new CaravanGoal(this));
        goalSelector.addGoal(3, new BuildGoal(this));
        workGoal = new WorkGoal(this);
        goalSelector.addGoal(4, workGoal);
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 6f));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    @Override
    protected void equip(@Nullable Outpost o, Resident r) {
        ItemStack tool = switch (r.role) {
            case BUILDER -> new ItemStack(Items.IRON_AXE);
            case SKOTOVOD -> new ItemStack(Items.BOW);
            case FARMER -> new ItemStack(Items.IRON_HOE);
            // Кирка шахтёра — настоящий предмет со склада (см. WorkGoal): в руке она появляется, когда он её взял.
            case MINER -> ItemStack.EMPTY;
            case LUMBERJACK -> new ItemStack(Items.IRON_AXE);
            case SMITH -> new ItemStack(Items.IRON_INGOT);
            case MAGE -> new ItemStack(Items.BOOK);
            case CARAVANEER -> new ItemStack(Items.LEAD);
            default -> new ItemStack(Items.WRITABLE_BOOK);
        };
        setItemSlot(EquipmentSlot.MAINHAND, tool);
        noDrops();
    }

    @Override
    public boolean isEnemy(LivingEntity other) {
        return false;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp) {
            Outpost o = outpost();
            if (o != null && role() == Role.CARAVANEER && !sp.isShiftKeyDown()) openShop(sp, o);
            else if (o != null) Interactions.talk(sp, o, this);
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    // ============================== Лавка торговца ==============================

    @Nullable private Player tradingPlayer;
    private net.minecraft.world.item.trading.MerchantOffers offers = new net.minecraft.world.item.trading.MerchantOffers();

    /** Открывает ванильное окно торговли с товаром лавки (кроме врагов форта). */
    private void openShop(ServerPlayer player, Outpost o) {
        var data = com.vladosiwe.lordsoutpost.data.OutpostSavedData.get(player.serverLevel());
        if (o.state != com.vladosiwe.lordsoutpost.outpost.OutpostState.OWNED
                || com.vladosiwe.lordsoutpost.protection.Access.level(data, o, player.getUUID()) == com.vladosiwe.lordsoutpost.outpost.AccessLevel.HOSTILE) {
            Interactions.talk(player, o, this);
            return;
        }
        if (tradingPlayer != null && tradingPlayer != player) return;
        offers = com.vladosiwe.lordsoutpost.data.Shop.offers(o);
        if (offers.isEmpty()) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("lordsoutpost.msg.shop_empty", getName()), true);
            return;
        }
        setTradingPlayer(player);
        openTradingScreen(player, getName(), 1);
    }

    @Override
    public void setTradingPlayer(@Nullable Player player) {
        tradingPlayer = player;
    }

    @Nullable
    @Override
    public Player getTradingPlayer() {
        return tradingPlayer;
    }

    public boolean trading() {
        return tradingPlayer != null;
    }

    @Override
    public net.minecraft.world.item.trading.MerchantOffers getOffers() {
        return offers;
    }

    @Override
    public void overrideOffers(net.minecraft.world.item.trading.MerchantOffers offers) {
        this.offers = offers;
    }

    /** Связка куплена: товар уходит из лавки, изумруды — в казну. */
    @Override
    public void notifyTrade(net.minecraft.world.item.trading.MerchantOffer offer) {
        Outpost o = outpost();
        if (o == null) return;
        if (!com.vladosiwe.lordsoutpost.data.Shop.sold(o, offer)) {
            // Товара уже нет (не должно случаться: пока идёт торговля, лавку никто не трогает) — больше не продаём.
            offer.setToOutOfStock();
            com.vladosiwe.lordsoutpost.LordsOutpost.LOGGER.warn("Лавка форта #{}: продана связка, которой нет в лавке", Long.toHexString(o.id));
        }
        playSound(SoundEvents.VILLAGER_YES, 0.6f, 1f);
        if (level() instanceof net.minecraft.server.level.ServerLevel sl) com.vladosiwe.lordsoutpost.data.OutpostSavedData.get(sl).setDirty();
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
        return SoundEvents.VILLAGER_YES;
    }

    @Override
    public boolean isClientSide() {
        return level().isClientSide;
    }

    /** Торговец пропал из мира посреди торговли — окно закрывается, сделка не может пройти без лавки. */
    @Override
    public void onRemovedFromLevel() {
        if (tradingPlayer != null) {
            Player p = tradingPlayer;
            tradingPlayer = null;
            if (p.containerMenu instanceof net.minecraft.world.inventory.MerchantMenu) p.closeContainer();
        }
        super.onRemovedFromLevel();
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.VILLAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.VILLAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.VILLAGER_DEATH;
    }

    @Override
    public int getAmbientSoundInterval() {
        return 400;
    }
}
