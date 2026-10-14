package com.volmit.staticstats.tracking;

import art.arcane.volmlib.util.bukkit.BukkitInventoryViews;
import com.volmit.staticstats.detailed.DetailCategory;
import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.stats.StatsService;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.block.BlockState;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DetailListener implements Listener, AutoCloseable {
    private static final Map<DetailCategory, Map<Material, DetailKey>> MATERIAL_KEYS = materialKeys();
    private static final Map<DetailCategory, Map<EntityType, DetailKey>> ENTITY_KEYS = entityKeys();
    private static final Map<DetailCategory, DetailKey> TOTAL_KEYS = Map.of(
            DetailCategory.FISH_CAUGHT, DetailKey.CAUGHT_ITEMS,
            DetailCategory.ANIMALS_BRED, DetailKey.BRED_ANIMALS,
            DetailCategory.ITEMS_CRAFTED, DetailKey.CRAFTED_ITEMS,
            DetailCategory.ITEMS_TRADED, DetailKey.TRADED_ITEMS);

    private final StatsService stats;
    private final TradeOrder shiftOrder;
    private final Map<UUID, TradeTransaction> pendingTrades = new ConcurrentHashMap<>();

    public DetailListener(StatsService stats) {
        this(stats, shiftOrder());
    }

    DetailListener(StatsService stats, TradeOrder shiftOrder) {
        this.stats = Objects.requireNonNull(stats);
        this.shiftOrder = Objects.requireNonNull(shiftOrder);
    }

    @Override
    public void close() {
        pendingTrades.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBroken(BlockBreakEvent event) {
        if (!event.isCancelled()) {
            recordMaterial(event.getPlayer().getUniqueId(), DetailCategory.BLOCKS_MINED, event.getBlock().getType(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlaced(BlockPlaceEvent event) {
        if (event.isCancelled() || !event.canBuild()) {
            return;
        }
        UUID playerId = event.getPlayer().getUniqueId();
        if (event instanceof BlockMultiPlaceEvent multiple) {
            for (BlockState replaced : multiple.getReplacedBlockStates()) {
                recordMaterial(playerId, DetailCategory.BLOCKS_PLACED, replaced.getBlock().getType(), 1);
            }
        } else {
            recordMaterial(playerId, DetailCategory.BLOCKS_PLACED, event.getBlockPlaced().getType(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        if (cancelled(event)
                || event.getEntity() instanceof Player || event.getEntity() instanceof ArmorStand) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            recordEntity(killer.getUniqueId(), DetailCategory.MOBS_KILLED, event.getEntityType());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFishing(PlayerFishEvent event) {
        if (event.isCancelled() || event.getState() != PlayerFishEvent.State.CAUGHT_FISH
                || !(event.getCaught() instanceof Item item)) {
            return;
        }
        ItemStack output = item.getItemStack();
        recordMaterial(event.getPlayer().getUniqueId(), DetailCategory.FISH_CAUGHT, output.getType(), output.getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreeding(EntityBreedEvent event) {
        if (!event.isCancelled() && event.getBreeder() instanceof Player player) {
            recordEntity(player.getUniqueId(), DetailCategory.ANIMALS_BRED, event.getEntityType());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatistic(PlayerStatisticIncrementEvent event) {
        if (event.getStatistic() != Statistic.CRAFT_ITEM && event.getStatistic() != Statistic.TRADED_WITH_VILLAGER) {
            return;
        }
        UUID playerId = event.getPlayer().getUniqueId();
        long amount = (long) event.getNewValue() - event.getPreviousValue();
        Inventory inventory = BukkitInventoryViews.top(event.getPlayer().getOpenInventory());
        if (villagerInventory(inventory)) {
            recordTrade(event, playerId, amount);
            return;
        }
        pendingTrades.remove(playerId);
        if (event.getStatistic() != Statistic.CRAFT_ITEM || amount <= 0) {
            return;
        }
        Material material = event.getMaterial();
        if (event.isCancelled() || material == null || emptyMaterial(material)) {
            return;
        }
        if (inventory instanceof CraftingInventory) {
            recordMaterial(playerId, DetailCategory.ITEMS_CRAFTED, material, amount);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        UUID playerId = event.getWhoClicked().getUniqueId();
        pendingTrades.remove(playerId);
        if (!event.isCancelled() && event.getRawSlot() == 2 && villagerInventory(event.getInventory())) {
            pendingTrades.put(playerId, new TradeTransaction(event.isShiftClick() ? shiftOrder : TradeOrder.OUTPUT_FIRST));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        pendingTrades.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        pendingTrades.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        pendingTrades.remove(event.getPlayer().getUniqueId());
    }

    private static Map<DetailCategory, Map<Material, DetailKey>> materialKeys() {
        Map<DetailCategory, Map<Material, DetailKey>> result = new EnumMap<>(DetailCategory.class);
        for (DetailKey key : DetailKey.values()) {
            if (!key.target().isEmpty() && key.category() != DetailCategory.MOBS_KILLED
                    && key.category() != DetailCategory.ANIMALS_BRED) {
                result.computeIfAbsent(key.category(), ignored -> new EnumMap<>(Material.class))
                        .put(Material.valueOf(key.target()), key);
            }
        }
        return result;
    }

    private static Map<DetailCategory, Map<EntityType, DetailKey>> entityKeys() {
        Map<DetailCategory, Map<EntityType, DetailKey>> result = new EnumMap<>(DetailCategory.class);
        for (DetailKey key : DetailKey.values()) {
            if (!key.target().isEmpty() && (key.category() == DetailCategory.MOBS_KILLED
                    || key.category() == DetailCategory.ANIMALS_BRED)) {
                result.computeIfAbsent(key.category(), ignored -> new EnumMap<>(EntityType.class))
                        .put(EntityType.valueOf(key.target()), key);
            }
        }
        return result;
    }

    private static boolean villagerInventory(Inventory inventory) {
        return inventory instanceof MerchantInventory merchant && merchant.getMerchant() instanceof AbstractVillager;
    }

    private static boolean cancelled(Event event) {
        return event instanceof Cancellable cancellable && cancellable.isCancelled();
    }

    private static boolean emptyMaterial(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

    private static TradeOrder shiftOrder() {
        try {
            Class.forName("io.papermc.paper.event.player.PlayerPurchaseEvent", false, DetailListener.class.getClassLoader());
            return TradeOrder.CONFIRMATION_FIRST;
        } catch (ClassNotFoundException exception) {
            return TradeOrder.OUTPUT_FIRST;
        }
    }

    private void recordTrade(PlayerStatisticIncrementEvent event, UUID playerId, long amount) {
        TradeTransaction transaction = pendingTrades.get(playerId);
        if (transaction == null || amount <= 0) {
            return;
        }
        TradeOutput output;
        if (event.getStatistic() == Statistic.TRADED_WITH_VILLAGER) {
            output = transaction.completed(!event.isCancelled());
        } else {
            Material material = event.getMaterial();
            output = transaction.output(material, amount, !event.isCancelled() && material != null && !emptyMaterial(material));
        }
        if (output != null) {
            recordMaterial(playerId, DetailCategory.ITEMS_TRADED, output.material(), output.amount());
        }
    }

    private void recordMaterial(UUID playerId, DetailCategory category, Material material, long amount) {
        if (amount > 0 && !emptyMaterial(material)) {
            record(playerId, category, MATERIAL_KEYS.get(category).get(material), amount);
        }
    }

    private void recordEntity(UUID playerId, DetailCategory category, EntityType type) {
        record(playerId, category, ENTITY_KEYS.get(category).get(type), 1);
    }

    private void record(UUID playerId, DetailCategory category, DetailKey specific, long amount) {
        stats.activity(playerId);
        DetailKey total = TOTAL_KEYS.get(category);
        if (total != null) {
            stats.addDetail(playerId, total, amount);
        }
        if (specific != null) {
            stats.addDetail(playerId, specific, amount);
        }
    }

    enum TradeOrder {
        OUTPUT_FIRST,
        CONFIRMATION_FIRST
    }

    private record TradeOutput(Material material, long amount, boolean accepted) {
    }

    private static final class TradeTransaction {
        private final TradeOrder order;
        private TradeOutput pending;
        private boolean confirmed;
        private boolean confirmationAccepted;

        private TradeTransaction(TradeOrder order) {
            this.order = order;
        }

        private TradeOutput output(Material material, long amount, boolean accepted) {
            TradeOutput output = new TradeOutput(material, amount, accepted);
            if (order == TradeOrder.OUTPUT_FIRST) {
                pending = output;
                return null;
            }
            boolean completed = confirmed && confirmationAccepted && accepted;
            confirmed = false;
            return completed ? output : null;
        }

        private TradeOutput completed(boolean accepted) {
            if (order == TradeOrder.CONFIRMATION_FIRST) {
                confirmed = true;
                confirmationAccepted = accepted;
                return null;
            }
            TradeOutput output = pending;
            pending = null;
            return accepted && output != null && output.accepted() ? output : null;
        }
    }
}
