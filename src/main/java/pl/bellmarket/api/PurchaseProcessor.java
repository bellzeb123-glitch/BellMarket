/*
 * BellMarket - PurchaseProcessor
 *
 * SESJA-1 CHANGES vs upstream:
 *   ⚠ FIX #1: deliverSkinToken() now respects product.includeChangeToken().
 *            Previously ran BOTH `skintoken give` AND `skintoken giveremove`
 *            unconditionally — causing the "buying a skin gives a change
 *            token" bug. Now only `skintoken give` runs by default; the
 *            change token only goes out if the YAML explicitly sets
 *            `include-change-token: true`.
 *   ⚠ FIX #2: PurchaseProcessor now picks correct CurrencyManager / VipTokenManager
 *            based on product.getCurrency(). Old code only ever used BellCoins.
 *   + Permission check (product.requiredPermission) before delivery.
 *   + BellMarketPurchaseEvent fired AFTER successful purchase, cancellable
 *     (cancellation rolls back the currency).
 *
 * The rest of the file mirrors the upstream method signatures so callers
 * (ShopGUI etc.) compile unchanged.
 */
package pl.bellmarket.api;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.event.BellMarketPurchaseEvent;
import pl.bellmarket.model.Product;

public class PurchaseProcessor {

    public enum Result {
        SUCCESS,
        NOT_ENOUGH_COINS,    // original — used by ShopGUI directly
        PRODUCT_DISABLED,    // original — used by ShopGUI directly
        DELIVERY_FAILED,     // original
        NO_PERMISSION,       // SESJA-1 addition
        CANCELLED            // SESJA-1 addition
    }

    private final BellMarket plugin;

    public PurchaseProcessor(BellMarket plugin) {
        this.plugin = plugin;
    }

    public Result process(Player player, Product product) {
        if (product == null || !product.isEnabled()) {
            playSound(player, "purchase-fail", Sound.ENTITY_VILLAGER_NO);
            return Result.PRODUCT_DISABLED;
        }

        // SESJA-1: permission gate (covers VIP_EXCLUSIVE + any product with required-permission)
        String perm = product.getRequiredPermission();
        if (perm == null && product.getType() == Product.Type.VIP_EXCLUSIVE) {
            perm = "bellmarket.vip";  // sensible default for VIP_EXCLUSIVE
        }
        if (perm != null && !player.hasPermission(perm)) {
            playSound(player, "purchase-fail", Sound.ENTITY_VILLAGER_NO);
            return Result.NO_PERMISSION;
        }

        Currency currency = product.getCurrency() != null ? product.getCurrency() : Currency.BELLCOINS;
        long price = plugin.getEffectivePrice(product);

        boolean hasEnough = switch (currency) {
            case BELLCOINS -> plugin.getCurrency().hasEnough(player, price);
            case VIPTOKEN  -> plugin.getVipTokens().hasEnough(player, price);
        };
        if (!hasEnough) {
            playSound(player, "purchase-fail", Sound.ENTITY_VILLAGER_NO);
            return Result.NOT_ENOUGH_COINS;
        }

        // Withdraw before delivery so listeners see consistent state
        boolean withdrawn = switch (currency) {
            case BELLCOINS -> plugin.getCurrency().takeCoins(player, price);
            case VIPTOKEN  -> plugin.getVipTokens().takeCoins(player, price,
                                "purchase: " + product.getId());
        };
        if (!withdrawn) {
            playSound(player, "purchase-fail", Sound.ENTITY_VILLAGER_NO);
            return Result.NOT_ENOUGH_COINS;
        }

        // Fire purchase event — listeners may cancel
        BellMarketPurchaseEvent ev = new BellMarketPurchaseEvent(player, product, currency, price);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) {
            // refund
            refund(player, currency, price, "cancelled by listener");
            playSound(player, "purchase-fail", Sound.ENTITY_VILLAGER_NO);
            return Result.CANCELLED;
        }

        // Deliver
        boolean delivered = deliver(player, product);
        if (!delivered) {
            refund(player, currency, price, "delivery failed");
            playSound(player, "purchase-fail", Sound.ENTITY_VILLAGER_NO);
            return Result.DELIVERY_FAILED;
        }

        playSound(player, "purchase-success", Sound.ENTITY_PLAYER_LEVELUP);

        if (plugin.getConfig().getBoolean("admin.log-purchases", true)) {
            plugin.getLogger().info(String.format(
                "[Purchase] %s bought '%s' for %d %s (provider=%s)",
                player.getName(), product.getId(), price, currency.getDisplayName(),
                product.getProviderSource()));
        }

        if (plugin.getProFeatures() != null) {
            plugin.getProFeatures().recordPurchase(
                new PurchaseRecord(player, product, price, currency));
        }
        return Result.SUCCESS;
    }

    private boolean deliver(Player player, Product product) {
        return switch (product.getType()) {
            case SKIN_TOKEN -> deliverSkinToken(player, product);
            case ITEM       -> deliverItem(player, product);
            case COMMAND, MOUNT, VIP_EXCLUSIVE -> deliverCommands(player, product);
        };
    }

    /**
     * FIX: change token only goes out when product.includeChangeToken() is true.
     * Previously both commands ran unconditionally.
     */
    private boolean deliverSkinToken(Player player, Product product) {
        String skinId = product.getSkinId();
        if (skinId == null || skinId.isEmpty()) {
            plugin.getLogger().warning("No skin-id defined for product: " + product.getId());
            return false;
        }
        if (plugin.getServer().getPluginManager().getPlugin("SkinStudio") == null) {
            plugin.getLogger().warning("SkinStudio not found! Cannot deliver skin token for: " + product.getId());
            return false;
        }

        // Always: give the actual skin token
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
            "skintoken give " + player.getName() + " " + skinId);

        // Only if explicitly requested: give a change/remove token alongside.
        // The 00_tokens.yml change_token product sets this to true on purpose
        // (because that product IS the change token). All skin products from
        // SkinStudioProvider have it explicitly false.
        if (product.includeChangeToken()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "skintoken giveremove " + player.getName() + " 1");
        }
        return true;
    }

    private boolean deliverItem(Player player, Product product) {
        if (product.getGiveItem() == null) {
            plugin.getLogger().warning("No give-item defined for product: " + product.getId());
            return false;
        }
        var leftover = player.getInventory().addItem(product.getGiveItem().clone());
        // overflow drops at feet
        leftover.values().forEach(stack ->
            player.getWorld().dropItemNaturally(player.getLocation(), stack));
        return true;
    }

    private boolean deliverCommands(Player player, Product product) {
        var cmds = product.getCommands();
        if (cmds == null || cmds.isEmpty()) {
            plugin.getLogger().warning("No commands defined for product: " + product.getId());
            return false;
        }
        for (String raw : cmds) {
            String filled = raw
                .replace("{player}", player.getName())
                .replace("{uuid}", player.getUniqueId().toString());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), filled);
        }
        return true;
    }

    private void refund(Player player, Currency currency, long amount, String reason) {
        switch (currency) {
            case BELLCOINS -> plugin.getCurrency().addCoins(player, amount);
            case VIPTOKEN  -> plugin.getVipTokens().addCoins(player, amount, "refund: " + reason);
        }
    }

    private void playSound(Player player, String key, Sound fallback) {
        try {
            String soundName = plugin.getConfig().getString("sounds." + key);
            Sound sound = soundName != null ? Sound.valueOf(soundName) : fallback;
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (Throwable ignored) {
            player.playSound(player.getLocation(), fallback, 1.0f, 1.0f);
        }
    }
}
