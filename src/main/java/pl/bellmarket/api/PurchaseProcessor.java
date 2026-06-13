package pl.bellmarket.api;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.event.BellMarketPurchaseEvent;
import pl.bellmarket.model.Product;

import java.util.HashMap;

public class PurchaseProcessor {

    public enum Result { SUCCESS, NOT_ENOUGH_COINS, PRODUCT_DISABLED, DELIVERY_FAILED, NO_PERMISSION, CANCELLED }

    private final BellMarket plugin;

    public PurchaseProcessor(BellMarket plugin) { this.plugin = plugin; }

    public Result process(Player player, Product product) {
        if (!product.isEnabled()) {
            playSound(player, "purchase-fail", Sound.ENTITY_VILLAGER_NO);
            return Result.PRODUCT_DISABLED;
        }

        String perm = product.getRequiredPermission();
        if (perm != null && !perm.isEmpty() && !player.hasPermission(perm)) {
            playSound(player, "purchase-fail", Sound.ENTITY_VILLAGER_NO);
            return Result.NO_PERMISSION;
        }

        Currency currency = product.getCurrency() != null ? product.getCurrency() : Currency.BELLCOINS;
        long price = product.getPrice();
        boolean withdrawn = false;

        switch (currency) {
            case BELLCOINS -> {
                if (!plugin.getCurrency().hasEnough(player, price)) {
                    playSound(player, "purchase-fail", Sound.ENTITY_VILLAGER_NO);
                    return Result.NOT_ENOUGH_COINS;
                }
                plugin.getCurrency().takeCoins(player, price, "purchase: " + product.getId());
                withdrawn = true;
            }
            case VIPTOKEN -> {
                if (!plugin.getVipTokens().hasEnough(player, price)) {
                    playSound(player, "purchase-fail", Sound.ENTITY_VILLAGER_NO);
                    return Result.NOT_ENOUGH_COINS;
                }
                plugin.getVipTokens().takeTokens(player, price, "purchase: " + product.getId());
                withdrawn = true;
            }
        }

        BellMarketPurchaseEvent ev = new BellMarketPurchaseEvent(player, product);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) {
            if (withdrawn) refund(player, currency, price, "cancelled by listener");
            return Result.CANCELLED;
        }

        boolean delivered = deliver(player, product);
        if (!delivered) {
            if (withdrawn) refund(player, currency, price, "delivery failed");
            return Result.DELIVERY_FAILED;
        }

        logPurchase(player, product, price, currency.getDisplayName());
        return Result.SUCCESS;
    }

    private boolean deliver(Player player, Product product) {
        return switch (product.getType()) {
            case SKIN_TOKEN    -> deliverSkinToken(player, product);
            case ITEM          -> deliverItem(player, product);
            case COMMAND, VIP_EXCLUSIVE -> deliverCommands(player, product);
        };
    }

    private boolean deliverSkinToken(Player player, Product product) {
        String skinId = product.getSkinId();
        if (skinId == null || skinId.isEmpty()) {
            plugin.getLogger().warning("No skin-id defined for product: " + product.getId());
            return false;
        }
        var ss = Bukkit.getServer().getPluginManager().getPlugin("SkinStudio");
        if (ss == null) {
            plugin.getLogger().warning("SkinStudio not found! Cannot deliver skin token for: " + product.getId());
            return false;
        }
        String cmd = product.isIncludeChangeToken()
            ? "skintoken give " + player.getName() + " " + skinId
            : "skintoken give " + player.getName() + " " + skinId;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        return true;
    }

    private boolean deliverItem(Player player, Product product) {
        var giveItem = product.getGiveItem();
        if (giveItem == null) {
            plugin.getLogger().warning("No give-item defined for product: " + product.getId());
            return false;
        }
        var leftover = player.getInventory().addItem(giveItem.clone());
        leftover.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
        return true;
    }

    private boolean deliverCommands(Player player, Product product) {
        var cmds = product.getCommands();
        if (cmds == null || cmds.isEmpty()) {
            plugin.getLogger().warning("No commands defined for product: " + product.getId());
            return false;
        }
        for (String raw : cmds) {
            String filled = raw.replace("{player}", player.getName())
                              .replace("{uuid}", player.getUniqueId().toString());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), filled);
        }
        return true;
    }

    private void refund(Player player, Currency currency, long amount, String reason) {
        switch (currency) {
            case BELLCOINS -> plugin.getCurrency().addCoins(player, amount, "refund: " + reason);
            case VIPTOKEN  -> plugin.getVipTokens().addTokens(player, amount, "refund: " + reason);
        }
    }

    private void logPurchase(Player player, Product product, long price, String currency) {
        if (plugin.getConfig().getBoolean("admin.log-purchases", true)) {
            plugin.getLogger().info(String.format("[Purchase] %s bought '%s' for %d %s (provider=%s)",
                player.getName(), product.getDisplayName(), price, currency, product.getProviderSource()));
        }
    }

    private void playSound(Player player, String key, Sound fallback) {
        try {
            String soundName = plugin.getConfig().getString("sounds." + key);
            Sound s = soundName != null ? Sound.valueOf(soundName) : fallback;
            player.playSound(player, s, 1f, 1f);
        } catch (Exception ignored) {
            player.playSound(player, fallback, 1f, 1f);
        }
    }
}
