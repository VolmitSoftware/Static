package com.volmit.staticstats.tracking;

import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.stats.StatsService;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.Cancellable;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerStatisticIncrementEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantInventory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class DetailListenerTest {
    private static final UUID PLAYER_ID = UUID.fromString("8b12d134-6353-4da7-acef-b6115c97d384");

    private final StatsService stats = mock(StatsService.class);
    private final DetailListener listener = new DetailListener(stats);
    private final Player player = player();

    @Test
    void countsMinedMaterialOnlyForAcceptedBreaks() {
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getPlayer()).thenReturn(player);
        doReturn(block(Material.DIAMOND_ORE)).when(event).getBlock();
        listener.onBlockBroken(event);
        when(event.isCancelled()).thenReturn(true);
        listener.onBlockBroken(event);
        verify(stats).addDetail(PLAYER_ID, DetailKey.MINED_DIAMOND_ORE, 1);
    }

    @Test
    void countsEachNewPlacedMaterialInsteadOfReplacedStateMaterial() {
        BlockMultiPlaceEvent event = mock(BlockMultiPlaceEvent.class);
        BlockState first = mock(BlockState.class);
        BlockState second = mock(BlockState.class);
        when(first.getType()).thenReturn(Material.AIR);
        when(second.getType()).thenReturn(Material.AIR);
        doReturn(block(Material.OAK_PLANKS)).when(first).getBlock();
        doReturn(block(Material.GLASS)).when(second).getBlock();
        when(event.getPlayer()).thenReturn(player);
        when(event.canBuild()).thenReturn(true);
        when(event.getReplacedBlockStates()).thenReturn(List.of(first, second));
        listener.onBlockPlaced(event);
        verify(stats).addDetail(PLAYER_ID, DetailKey.PLACED_OAK_PLANKS, 1);
        verify(stats).addDetail(PLAYER_ID, DetailKey.PLACED_GLASS, 1);

        clearInvocations(stats);
        when(event.canBuild()).thenReturn(false);
        listener.onBlockPlaced(event);
        when(event.canBuild()).thenReturn(true);
        when(event.isCancelled()).thenReturn(true);
        listener.onBlockPlaced(event);
        verifyNoInteractions(stats);
    }

    @Test
    void countsSinglePlacementAndOnlyPlayerAttributedMobDeaths() {
        BlockPlaceEvent placement = mock(BlockPlaceEvent.class);
        when(placement.getPlayer()).thenReturn(player);
        when(placement.canBuild()).thenReturn(true);
        doReturn(block(Material.OAK_PLANKS)).when(placement).getBlockPlaced();
        listener.onBlockPlaced(placement);
        verify(stats).addDetail(PLAYER_ID, DetailKey.PLACED_OAK_PLANKS, 1);

        EntityDeathEvent death = mock(EntityDeathEvent.class, withSettings().extraInterfaces(Cancellable.class));
        LivingEntity zombie = mock(LivingEntity.class);
        when(death.getEntity()).thenReturn(zombie);
        when(death.getEntityType()).thenReturn(EntityType.ZOMBIE);
        when(zombie.getKiller()).thenReturn(player);
        listener.onMobDeath(death);
        when(((Cancellable) death).isCancelled()).thenReturn(true);
        listener.onMobDeath(death);
        when(((Cancellable) death).isCancelled()).thenReturn(false);
        when(zombie.getKiller()).thenReturn(null);
        listener.onMobDeath(death);
        verify(stats).addDetail(PLAYER_ID, DetailKey.KILLED_ZOMBIE, 1);
    }

    @Test
    void fishingCountsAcceptedOutputStacksIncludingGenericTreasure() {
        PlayerFishEvent event = mock(PlayerFishEvent.class);
        Item item = mock(Item.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getState()).thenReturn(PlayerFishEvent.State.CAUGHT_FISH);
        when(event.getCaught()).thenReturn(item);
        doReturn(stack(Material.COD, 3)).when(item).getItemStack();
        listener.onFishing(event);
        verify(stats).addDetail(PLAYER_ID, DetailKey.CAUGHT_ITEMS, 3);
        verify(stats).addDetail(PLAYER_ID, DetailKey.CAUGHT_COD, 3);

        doReturn(stack(Material.SADDLE, 1)).when(item).getItemStack();
        listener.onFishing(event);
        verify(stats).addDetail(PLAYER_ID, DetailKey.CAUGHT_ITEMS, 1);
        clearInvocations(stats);
        when(event.isCancelled()).thenReturn(true);
        listener.onFishing(event);
        when(event.isCancelled()).thenReturn(false);
        when(event.getState()).thenReturn(PlayerFishEvent.State.CAUGHT_ENTITY);
        listener.onFishing(event);
        when(event.getState()).thenReturn(PlayerFishEvent.State.CAUGHT_FISH);
        when(event.getCaught()).thenReturn(null);
        listener.onFishing(event);
        verifyNoInteractions(stats);
    }

    @Test
    void breedingCountsOneAcceptedChildWithPlayerBreeder() {
        EntityBreedEvent event = mock(EntityBreedEvent.class);
        when(event.getBreeder()).thenReturn(player);
        when(event.getEntityType()).thenReturn(EntityType.COW);
        listener.onBreeding(event);
        verify(stats).addDetail(PLAYER_ID, DetailKey.BRED_ANIMALS, 1);
        verify(stats).addDetail(PLAYER_ID, DetailKey.BRED_COW, 1);
        clearInvocations(stats);
        when(event.isCancelled()).thenReturn(true);
        listener.onBreeding(event);
        when(event.isCancelled()).thenReturn(false);
        when(event.getBreeder()).thenReturn(null);
        listener.onBreeding(event);
        verifyNoInteractions(stats);
    }

    @Test
    void craftingUsesActualPositiveDeltaIncludingBulkOutput() {
        inventory(mock(CraftingInventory.class));
        listener.onStatistic(statistic(Statistic.CRAFT_ITEM, Material.OAK_PLANKS, 20, 32));
        verify(stats).addDetail(PLAYER_ID, DetailKey.CRAFTED_ITEMS, 12);
        verify(stats).addDetail(PLAYER_ID, DetailKey.CRAFTED_OAK_PLANKS, 12);
        clearInvocations(stats);
        PlayerStatisticIncrementEvent cancelled = statistic(Statistic.CRAFT_ITEM, Material.OAK_PLANKS, 32, 40);
        when(cancelled.isCancelled()).thenReturn(true);
        listener.onStatistic(cancelled);
        listener.onStatistic(statistic(Statistic.CRAFT_ITEM, Material.OAK_PLANKS, 32, 32));
        listener.onStatistic(statistic(Statistic.CRAFT_ITEM, Material.OAK_PLANKS, 32, 20));
        listener.onStatistic(statistic(Statistic.CRAFT_ITEM, Material.AIR, 0, 1));
        listener.onStatistic(statistic(Statistic.CRAFT_ITEM, null, 0, 1));
        listener.onStatistic(statistic(Statistic.PICKUP, Material.OAK_PLANKS, 0, 64));
        verifyNoInteractions(stats);
    }

    @Test
    void repeatedShiftTradesConsumeEachObservedOutputOnceAndNeverCountAsCrafts() {
        merchant(listener, true);
        for (int trade = 0; trade < 3; trade++) {
            listener.onStatistic(statistic(Statistic.CRAFT_ITEM, Material.BREAD, trade * 6, (trade + 1) * 6));
            listener.onStatistic(statistic(Statistic.CRAFT_ITEM, Material.BREAD, (trade + 1) * 6, (trade + 1) * 6));
            listener.onStatistic(statistic(Statistic.TRADED_WITH_VILLAGER, null, trade, trade + 1));
        }
        listener.onStatistic(statistic(Statistic.TRADED_WITH_VILLAGER, null, 3, 4));
        verify(stats, times(3)).addDetail(PLAYER_ID, DetailKey.TRADED_ITEMS, 6);
        verify(stats, times(3)).addDetail(PLAYER_ID, DetailKey.TRADED_BREAD, 6);
        verify(stats, never()).addDetail(PLAYER_ID, DetailKey.CRAFTED_ITEMS, 6);
        verify(stats, never()).addDetail(PLAYER_ID, DetailKey.CRAFTED_BREAD, 6);
    }

    @Test
    void failedTradeDoesNotCountAndCannotLeakIntoNextClick() {
        merchant();
        listener.onStatistic(statistic(Statistic.CRAFT_ITEM, Material.BREAD, 0, 6));
        verifyNoInteractions(stats);
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getWhoClicked()).thenReturn(player);
        listener.onInventoryClick(click);
        listener.onStatistic(statistic(Statistic.TRADED_WITH_VILLAGER, null, 0, 1));
        verifyNoInteractions(stats);

        merchant();
        listener.onStatistic(statistic(Statistic.CRAFT_ITEM, Material.BREAD, 6, 12));
        listener.onStatistic(statistic(Statistic.CRAFT_ITEM, Material.EMERALD, 0, 2));
        listener.onStatistic(statistic(Statistic.TRADED_WITH_VILLAGER, null, 1, 2));
        verify(stats).addDetail(PLAYER_ID, DetailKey.TRADED_EMERALD, 2);
        verify(stats, never()).addDetail(PLAYER_ID, DetailKey.TRADED_BREAD, 6);
    }

    @Test
    void cancelledCraftAndTradeStatisticsDiscardPendingOutput() {
        merchant();
        listener.onStatistic(statistic(Statistic.CRAFT_ITEM, Material.BREAD, 0, 6));
        PlayerStatisticIncrementEvent cancelledCraft = statistic(Statistic.CRAFT_ITEM, Material.EMERALD, 0, 2);
        when(cancelledCraft.isCancelled()).thenReturn(true);
        listener.onStatistic(cancelledCraft);
        listener.onStatistic(statistic(Statistic.TRADED_WITH_VILLAGER, null, 0, 1));
        listener.onStatistic(statistic(Statistic.CRAFT_ITEM, Material.BREAD, 6, 12));
        PlayerStatisticIncrementEvent cancelledTrade = statistic(Statistic.TRADED_WITH_VILLAGER, null, 1, 2);
        when(cancelledTrade.isCancelled()).thenReturn(true);
        listener.onStatistic(cancelledTrade);
        listener.onStatistic(statistic(Statistic.TRADED_WITH_VILLAGER, null, 2, 3));
        verifyNoInteractions(stats);
    }

    @Test
    void paperShiftTradesPairConfirmationBeforeTheActualOutput() {
        DetailListener paper = new DetailListener(stats, DetailListener.TradeOrder.CONFIRMATION_FIRST);
        merchant(paper, true);
        for (int trade = 0; trade < 3; trade++) {
            paper.onStatistic(statistic(Statistic.CRAFT_ITEM, Material.BREAD, trade * 6, trade * 6));
            paper.onStatistic(statistic(Statistic.TRADED_WITH_VILLAGER, null, trade, trade + 1));
            paper.onStatistic(statistic(Statistic.CRAFT_ITEM, Material.BREAD, trade * 6, (trade + 1) * 6));
        }
        verify(stats, times(3)).addDetail(PLAYER_ID, DetailKey.TRADED_ITEMS, 6);
        verify(stats, times(3)).addDetail(PLAYER_ID, DetailKey.TRADED_BREAD, 6);
        verify(stats, never()).addDetail(PLAYER_ID, DetailKey.CRAFTED_ITEMS, 6);
    }

    @Test
    void paperFailedAndCancelledShiftIterationsCannotCreditTheNextTrade() {
        DetailListener paper = new DetailListener(stats, DetailListener.TradeOrder.CONFIRMATION_FIRST);
        merchant(paper, true);
        paper.onStatistic(statistic(Statistic.CRAFT_ITEM, Material.BREAD, 0, 6));
        PlayerStatisticIncrementEvent cancelled = statistic(Statistic.TRADED_WITH_VILLAGER, null, 0, 1);
        when(cancelled.isCancelled()).thenReturn(true);
        paper.onStatistic(cancelled);
        paper.onStatistic(statistic(Statistic.CRAFT_ITEM, Material.BREAD, 6, 12));
        paper.onStatistic(statistic(Statistic.TRADED_WITH_VILLAGER, null, 1, 2));
        PlayerStatisticIncrementEvent rejectedOutput = statistic(Statistic.CRAFT_ITEM, Material.BREAD, 12, 18);
        when(rejectedOutput.isCancelled()).thenReturn(true);
        paper.onStatistic(rejectedOutput);
        verifyNoInteractions(stats);
        paper.onStatistic(statistic(Statistic.TRADED_WITH_VILLAGER, null, 2, 3));
        paper.onStatistic(statistic(Statistic.CRAFT_ITEM, Material.EMERALD, 0, 2));
        verify(stats).addDetail(PLAYER_ID, DetailKey.TRADED_EMERALD, 2);
        verify(stats, never()).addDetail(PLAYER_ID, DetailKey.TRADED_BREAD, 6);
    }

    @Test
    void paperNormalTradesStillRequireOutputBeforeConfirmation() {
        DetailListener paper = new DetailListener(stats, DetailListener.TradeOrder.CONFIRMATION_FIRST);
        merchant(paper, false);
        paper.onStatistic(statistic(Statistic.CRAFT_ITEM, Material.EMERALD, 0, 4));
        paper.onStatistic(statistic(Statistic.TRADED_WITH_VILLAGER, null, 0, 1));
        verify(stats).addDetail(PLAYER_ID, DetailKey.TRADED_EMERALD, 4);
    }

    @Test
    void ignoresVirtualMerchantsAndUnrelatedInventoryStatistics() {
        MerchantInventory merchant = mock(MerchantInventory.class);
        when(merchant.getMerchant()).thenReturn(mock(Merchant.class));
        inventory(merchant);
        listener.onStatistic(statistic(Statistic.CRAFT_ITEM, Material.BREAD, 0, 6));
        listener.onStatistic(statistic(Statistic.TRADED_WITH_VILLAGER, null, 0, 1));
        inventory(mock(Inventory.class));
        listener.onStatistic(statistic(Statistic.CRAFT_ITEM, Material.BREAD, 6, 12));
        verifyNoInteractions(stats);
    }

    @Test
    void inventoryAndConnectionLifecycleClearsPendingTradeState() {
        InventoryOpenEvent open = mock(InventoryOpenEvent.class);
        when(open.getPlayer()).thenReturn(player);
        InventoryCloseEvent close = mock(InventoryCloseEvent.class);
        when(close.getPlayer()).thenReturn(player);
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        List<Runnable> clear = List.of(() -> listener.onInventoryOpen(open), () -> listener.onInventoryClose(close),
                () -> listener.onQuit(quit), listener::close);
        for (Runnable action : clear) {
            merchant();
            listener.onStatistic(statistic(Statistic.CRAFT_ITEM, Material.BREAD, 0, 6));
            action.run();
            listener.onStatistic(statistic(Statistic.TRADED_WITH_VILLAGER, null, 0, 1));
        }
        verify(stats, never()).addDetail(any(UUID.class), any(DetailKey.class), anyLong());
    }

    private PlayerStatisticIncrementEvent statistic(Statistic statistic, Material material, int before, int after) {
        PlayerStatisticIncrementEvent event = mock(PlayerStatisticIncrementEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getStatistic()).thenReturn(statistic);
        when(event.getMaterial()).thenReturn(material);
        when(event.getPreviousValue()).thenReturn(before);
        when(event.getNewValue()).thenReturn(after);
        return event;
    }

    private void merchant() {
        merchant(listener, false);
    }

    private void merchant(DetailListener target, boolean shift) {
        MerchantInventory inventory = mock(MerchantInventory.class);
        when(inventory.getMerchant()).thenReturn(mock(Villager.class));
        inventory(inventory);
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getWhoClicked()).thenReturn(player);
        when(click.getInventory()).thenReturn(inventory);
        when(click.getRawSlot()).thenReturn(2);
        when(click.isShiftClick()).thenReturn(shift);
        target.onInventoryClick(click);
    }

    private void inventory(Inventory inventory) {
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory);
        when(player.getOpenInventory()).thenReturn(view);
    }

    private static Player player() {
        Player result = mock(Player.class);
        when(result.getUniqueId()).thenReturn(PLAYER_ID);
        return result;
    }

    private static Block block(Material material) {
        Block result = mock(Block.class);
        when(result.getType()).thenReturn(material);
        return result;
    }

    private static ItemStack stack(Material material, int amount) {
        ItemStack result = mock(ItemStack.class);
        when(result.getType()).thenReturn(material);
        when(result.getAmount()).thenReturn(amount);
        return result;
    }
}
