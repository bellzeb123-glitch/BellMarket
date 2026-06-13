package pl.bellmarket.api;

import org.bukkit.entity.Player;
import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.currency.CurrencyManager;
import pl.bellmarket.currency.VipTokenManager;
import pl.bellmarket.provider.ProductProviderRegistry;

public class BellMarketAPI {

    private static BellMarketAPI instance;
    private final BellMarket plugin;
    private final ProductProviderRegistry providerRegistry;

    private BellMarketAPI(BellMarket plugin, ProductProviderRegistry registry) {
        this.plugin = plugin;
        this.providerRegistry = registry;
    }

    public static void init(BellMarket plugin, ProductProviderRegistry registry) {
        instance = new BellMarketAPI(plugin, registry);
    }

    public static BellMarketAPI get() {
        if (instance == null) throw new IllegalStateException(
            "BellMarketAPI not initialised — wait for BellMarket to enable first.");
        return instance;
    }

    public static boolean isReady() { return instance != null; }

    public long getCoins(Player player) { return plugin.getCurrency().getBalance(player); }

    public void giveCoins(Player player, long amount, String reason) {
        plugin.getCurrency().addCoins(player, amount, reason);
    }

    public void takeCoins(Player player, long amount, String reason) {
        plugin.getCurrency().takeCoins(player, amount, reason);
    }

    public void setVipTokens(Player player, long amount) {
        plugin.getVipTokens().setBalance(player, amount);
    }

    public void giveVipTokens(Player player, long amount, String reason) {
        plugin.getVipTokens().addTokens(player, amount, reason);
    }

    public void takeVipTokens(Player player, long amount, String reason) {
        plugin.getVipTokens().takeTokens(player, amount, reason);
    }

    public long getBalance(Player player, Currency currency) {
        return switch (currency) {
            case BELLCOINS -> plugin.getCurrency().getBalance(player);
            case VIPTOKEN  -> plugin.getVipTokens().getBalance(player);
        };
    }

    public CurrencyManager getCurrencyManager()      { return plugin.getCurrency(); }
    public VipTokenManager getVipTokenManager()      { return plugin.getVipTokens(); }
    public ProductProviderRegistry getProviderRegistry() { return providerRegistry; }
}
