/*
 * BellMarket - Public API
 *
 * The ONLY class external plugins (BellVIP, BellDiscord, BellMounts,
 * BellMarket-Pro) should touch. Everything behind this facade is internal
 * implementation that may change between versions — the API here is the
 * stable contract.
 *
 * Usage from another plugin:
 *
 *     public void onEnable() {
 *         Plugin bm = getServer().getPluginManager().getPlugin("BellMarket");
 *         if (bm == null || !bm.isEnabled()) {
 *             getLogger().warning("BellMarket not present — features disabled");
 *             return;
 *         }
 *         BellMarketAPI.get().getProviderRegistry().register(new MyProvider());
 *         BellMarketAPI.get().giveVipTokens(playerId, 1, "monthly VIP renewal");
 *     }
 */
package pl.bellmarket.api;

import org.bukkit.entity.Player;
import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.currency.CurrencyManager;
import pl.bellmarket.currency.VipTokenManager;
import pl.bellmarket.provider.ProductProviderRegistry;

import java.util.UUID;

public final class BellMarketAPI {

    private static BellMarketAPI instance;

    private final BellMarket plugin;
    private final ProductProviderRegistry providerRegistry;

    private BellMarketAPI(BellMarket plugin, ProductProviderRegistry registry) {
        this.plugin = plugin;
        this.providerRegistry = registry;
    }

    /** Initialised once by BellMarket.onEnable(). */
    public static void init(BellMarket plugin, ProductProviderRegistry registry) {
        instance = new BellMarketAPI(plugin, registry);
    }

    /** Returns the singleton. Throws if BellMarket hasn't finished onEnable yet. */
    public static BellMarketAPI get() {
        if (instance == null) {
            throw new IllegalStateException(
                "BellMarketAPI not initialised — wait for BellMarket to enable first.");
        }
        return instance;
    }

    /** Whether BellMarket is fully initialised. Safe to call from any plugin load order. */
    public static boolean isReady() {
        return instance != null;
    }

    // ─── Provider Registry ─────────────────────────────────────────────────
    public ProductProviderRegistry getProviderRegistry() {
        return providerRegistry;
    }

    // ─── BellCoins ─────────────────────────────────────────────────────────
    public long getCoins(UUID player) {
        return plugin.getCurrency().getBalance(player);
    }

    public long giveCoins(Player player, long amount, String reason) {
        return plugin.getCurrency().addCoins(player, amount);
    }

    public boolean takeCoins(Player player, long amount, String reason) {
        return plugin.getCurrency().takeCoins(player, amount);
    }

    // ─── VIP Tokens ────────────────────────────────────────────────────────
    public long getVipTokens(UUID player) {
        return plugin.getVipTokens().getBalance(player);
    }

    public long giveVipTokens(UUID player, long amount, String reason) {
        return plugin.getVipTokens().addCoins(player, amount, reason);
    }

    public boolean takeVipTokens(UUID player, long amount, String reason) {
        return plugin.getVipTokens().takeCoins(player, amount, reason);
    }

    public void setVipTokens(UUID player, long amount, String reason) {
        plugin.getVipTokens().setBalance(player, amount, reason);
    }

    // ─── Generic ───────────────────────────────────────────────────────────
    /**
     * Get balance of any supported currency. Useful for plugins that don't want
     * to switch on Currency enum themselves.
     */
    public long getBalance(UUID player, Currency currency) {
        return switch (currency) {
            case BELLCOINS -> getCoins(player);
            case VIPTOKEN -> getVipTokens(player);
        };
    }

    public CurrencyManager getCurrencyManager() { return plugin.getCurrency(); }
    public VipTokenManager getVipTokenManager() { return plugin.getVipTokens(); }
}
