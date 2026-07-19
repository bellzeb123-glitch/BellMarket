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
import org.bukkit.inventory.ItemStack;
import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.event.BellMarketPurchaseEvent;
import pl.bellmarket.model.Product;

import java.util.Locale;

public class PurchaseProcessor {

    public enum Result {
        SUCCESS,
        NOT_ENOUGH_COINS,    // original — used by ShopGUI directly
        PRODUCT_DISABLED,    // original — used by ShopGUI directly
        DELIVERY_FAILED,     // original
        NO_PERMISSION,       // SESJA-1 addition
        CANCELLED,           // SESJA-1 addition
        PURCHASES_DISABLED   // shop.purchases-enabled: false
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

        if (!plugin.getConfig().getBoolean("shop.purchases-enabled", true)) {
            playSound(player, "purchase-fail", Sound.ENTITY_VILLAGER_NO);
            return Result.PURCHASES_DISABLED;
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
        // FMM VIP: zawsze /fmm giveitem — ta sama ścieżka co ręczny give (Lua + nazwa + lore).
        // Własny stack z bridge bywał pusty/bez lore (ModelItemFactory nie istnieje w FMM).
        if ("fmm".equals(product.getProviderSource())) {
            String id = product.getId();
            if (id != null && id.startsWith("fmmvip_")) {
                String fmmId = id.substring("fmmvip_".length());
                boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "fmm giveitem " + player.getName() + " " + fmmId);
                if (!dispatched) {
                    plugin.getLogger().warning("[FMM-VIP] Komenda giveitem nie wykonana dla " + fmmId
                        + " (player=" + player.getName() + ")");
                    return false;
                }
                return true;
            }
        }

        ItemStack stack = resolveGiveItem(product);
        if (stack == null) {
            plugin.getLogger().warning("No give-item defined for product: " + product.getId());
            return false;
        }
        var leftover = player.getInventory().addItem(stack);
        leftover.values().forEach(s ->
            player.getWorld().dropItemNaturally(player.getLocation(), s));
        return true;
    }

    /**
     * BellItems: przebuduj stack przy zakupie (świeży PDC).
     * FMM VIP dostarczane przez {@link #deliverItem} → {@code /fmm giveitem}.
     */
    private ItemStack resolveGiveItem(Product product) {
        if ("bellitems".equals(product.getProviderSource())) {
            String id = product.getId();
            if (id != null && id.startsWith("bellitems_")) {
                ItemStack fresh = createBellItemsStack(id.substring("bellitems_".length()), 1);
                if (fresh != null) return fresh;
            }
        }
        if (product.getGiveItem() == null) return null;
        return product.getGiveItem().clone();
    }

    private ItemStack createBellItemsStack(String bellItemId, int amount) {
        if (Bukkit.getPluginManager().getPlugin("BellItems") == null) return null;
        try {
            Class<?> apiClass = Class.forName("pl.bell.bellitems.api.BellItemsAPI");
            Object api = apiClass.getMethod("get").invoke(null);
            if (api == null) return null;
            @SuppressWarnings("unchecked")
            var stackOpt = (java.util.Optional<ItemStack>) apiClass
                .getMethod("createItem", String.class, int.class)
                .invoke(api, bellItemId, amount);
            return stackOpt.orElse(null);
        } catch (Throwable t) {
            plugin.getLogger().warning("[BellItems] createItem failed for " + bellItemId + ": " + t.getMessage());
            return null;
        }
    }

    private boolean deliverCommands(Player player, Product product) {
        var cmds = product.getCommands();
        if (cmds == null || cmds.isEmpty()) {
            plugin.getLogger().warning("No commands defined for product: " + product.getId());
            return false;
        }
        boolean anyOk = false;
        for (String raw : cmds) {
            if (raw == null || raw.isBlank()) continue;
            String filled = raw
                .replace("{player}", player.getName())
                .replace("{uuid}", player.getUniqueId().toString());

            // FMM giveitem: tylko SenderType.PLAYER, bez argumentu nicku.
            // Z konsoli NIGDY nie działa — dajemy stack bezpośrednio.
            String fmmId = extractFmmGiveitemId(filled);
            if (fmmId != null) {
                if (giveFmmItem(player, fmmId)) {
                    anyOk = true;
                } else {
                    plugin.getLogger().warning("[Purchase] FMM give failed for id=" + fmmId
                        + " product=" + product.getId());
                }
                continue;
            }

            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), filled);
            if (!ok) {
                plugin.getLogger().warning("[Purchase] Command failed: " + filled);
            } else {
                anyOk = true;
            }
        }
        return anyOk;
    }

    private boolean giveFmmItem(Player player, String fmmId) {
        if (fmmId == null || fmmId.isBlank()) return false;
        fmmId = fmmId.trim();

        var bridge = plugin.getFmmScriptedItemBridge();
        ItemStack stack = null;
        if (bridge != null && bridge.isAvailable()) {
            stack = bridge.createScriptedItem(fmmId).orElse(null);
        }

        if (stack != null && !stack.getType().isAir()) {
            player.closeInventory();
            var leftover = player.getInventory().addItem(stack);
            leftover.values().forEach(s ->
                player.getWorld().dropItemNaturally(player.getLocation(), s));
            plugin.getLogger().info("[Purchase] FMM stack -> " + player.getName() + " id=" + fmmId
                + " mat=" + stack.getType());
            return true;
        }

        // Fallback: FMM giveitem wymaga sender=PLAYER (konsola nie działa)
        plugin.getLogger().warning("[Purchase] Bridge nie zbudował stacka dla " + fmmId
            + " — próbuję player.performCommand(fmm giveitem)");
        player.closeInventory();
        org.bukkit.permissions.PermissionAttachment att = null;
        try {
            if (!player.hasPermission("freeminecraftmodels.admin")) {
                att = player.addAttachment(plugin);
                att.setPermission("freeminecraftmodels.admin", true);
                player.recalculatePermissions();
            }
            boolean ran = player.performCommand("fmm giveitem " + fmmId);
            if (!ran) {
                plugin.getLogger().warning("[Purchase] performCommand=false dla fmm giveitem " + fmmId);
                return false;
            }
            plugin.getLogger().info("[Purchase] FMM via performCommand -> " + player.getName() + " id=" + fmmId);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("[Purchase] performCommand exception: " + t.getMessage());
            return false;
        } finally {
            if (att != null) {
                player.removeAttachment(att);
                player.recalculatePermissions();
            }
        }
    }

    private static String extractFmmGiveitemId(String cmd) {
        if (cmd == null) return null;
        String t = cmd.trim();
        if (t.startsWith("/")) t = t.substring(1).trim();
        String lower = t.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("fmm giveitem")) return null;
        String rest = t.substring("fmm giveitem".length()).trim();
        if (rest.isEmpty()) return null;
        String[] parts = rest.split("\\s+");
        if (parts.length == 1) return parts[0];
        // fmm giveitem <player> <itemId> [amount]
        return parts[1];
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
