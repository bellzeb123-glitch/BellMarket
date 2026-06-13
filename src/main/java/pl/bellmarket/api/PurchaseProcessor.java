package pl.bellmarket.api;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.event.BellMarketPurchaseEvent;
import pl.bellmarket.model.Product;

import java.util.HashMap;
import java.util.logging.Level;

public class PurchaseProcessor {

    public enum Result {
        SUCCESS,
        NOT_ENOUGH_COINS,
        PRODUCT_DISABLED,
        DELIVERY_FAILED,
        NO_PERMISSION,
        CANCELLED
    }

    private final BellMarket plugin;

    public PurchaseProcessor(BellMarket plugin) {
        this.plugin = plugin;
    }

    /**
     * Full purchase flow:
     * 1. Check enabled
     * 2. Check permission
     * 3. Check balance
     * 4. Take coins
     * 5. Fire event (cancellable)
     * 6. Deliver
     * 7. Log
     */
    public Result process(Player player, Product product) {
        // 1. Enabled check
        if (!product.isEnabled()) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return Result.PRODUCT_DISABLED;
        }

        // 2. Permission check
        String reqPerm = product.getRequiredPermission();
        if (reqPerm != null && !reqPerm.isEmpty()) {
            if (!player.hasPermission(reqPerm)) {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                return Result.NO_PERMISSION;
            }
        }
        if (product.getType() == Product.Type.VIP_EXCLUSIVE) {
            if (!player.hasPermission("bellmarket.vip")) {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                return Result.NO_PERMISSION;
            }
        }

        // 3. Balance check
        Currency currency = product.getCurrency();
        long price = product.getPrice();

        boolean hasEnough = switch (currency) {
            case BELLCOINS -> plugin.getCurrency().hasEnough(player.getUniqueId(), price);
            case VIPTOKEN  -> plugin.getVipTokens().hasEnough(player.getUniqueId(), price);
        };

        if (!hasEnough) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return Result.NOT_ENOUGH_COINS;
        }

        // 4. Take coins
        switch (currency) {
            case BELLCOINS -> plugin.getCurrency().takeCoins(player.getUniqueId(), price);
            case VIPTOKEN  -> plugin.getVipTokens().takeCoins(player.getUniqueId(), price, product.getId());
        }

        // 5. Fire event
        BellMarketPurchaseEvent event = new BellMarketPurchaseEvent(player, product, currency, price);
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            // Refund
            refund(player, currency, price, "cancelled by listener");
            return Result.CANCELLED;
        }

        // 6. Deliver
        boolean delivered = deliver(player, product);
        if (!delivered) {
            refund(player, currency, price, "delivery failed");
            return Result.DELIVERY_FAILED;
        }

        // 7. Success
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);

        // Log
        if (plugin.getConfig().getBoolean("admin.log-purchases", true)) {
            plugin.getLogger().info(String.format(
                    "[Purchase] %s bought %s for %d %s (provider: %s)",
                    player.getName(), product.getName(), price,
                    currency.getDisplayName(), product.getProviderSource()));
        }

        return Result.SUCCESS;
    }

    // ─── delivery ────────────────────────────────────────────────────────

    private boolean deliver(Player player, Product product) {
        try {
            return switch (product.getType()) {
                case SKIN_TOKEN -> deliverSkinToken(player, product);
                case ITEM       -> deliverItem(player, product);
                case COMMAND, VIP_EXCLUSIVE -> deliverCommands(player, product);
            };
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Delivery failed for " + product.getId(), e);
            return false;
        }
    }

    private boolean deliverSkinToken(Player player, Product product) {
        String skinId = product.getSkinId();
        if (skinId == null || skinId.isEmpty()) {
            plugin.getLogger().warning("SkinToken product " + product.getId() + " has no skinId!");
            return false;
        }

        // Check SkinStudio is available
        if (Bukkit.getPluginManager().getPlugin("SkinStudio") == null) {
            plugin.getLogger().warning("SkinStudio not found — cannot deliver skin token!");
            return false;
        }

        // Dispatch SkinStudio command
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "skinstudio give " + player.getName() + " " + skinId);

        // Include change token if configured
        if (product.isIncludeChangeToken()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "skinstudio givetoken " + player.getName());
        }

        return true;
    }

    private boolean deliverItem(Player player, Product product) {
        ItemStack giveItem = product.getGiveItem();
        if (giveItem != null) {
            ItemStack clone = giveItem.clone();
            HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(clone);
            leftover.values().forEach(item ->
                    player.getWorld().dropItemNaturally(player.getLocation(), item));
            return true;
        }
        // Fallback to commands
        return deliverCommands(player, product);
    }

    private boolean deliverCommands(Player player, Product product) {
        for (String cmd : product.getCommands()) {
            String resolved = cmd.replace("{player}", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
        }
        return true;
    }

    // ─── refund ──────────────────────────────────────────────────────────

    private void refund(Player player, Currency currency, long amount, String reason) {
        switch (currency) {
            case BELLCOINS -> plugin.getCurrency().addCoins(player.getUniqueId(), amount);
            case VIPTOKEN  -> plugin.getVipTokens().addCoins(player.getUniqueId(), amount, reason);
        }
    }
}
