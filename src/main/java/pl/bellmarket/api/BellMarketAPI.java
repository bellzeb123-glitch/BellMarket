package pl.bellmarket.api;

import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.CurrencyManager;
import pl.bellmarket.currency.VipTokenManager;
import pl.bellmarket.provider.ProductProviderRegistry;

public class BellMarketAPI {

    private static BellMarketAPI instance;
    private final BellMarket plugin;

    private BellMarketAPI(BellMarket plugin) { this.plugin = plugin; }

    public static void init(BellMarket plugin) { instance = new BellMarketAPI(plugin); }
    public static void shutdown() { instance = null; }
    public static boolean isReady() { return instance != null; }
    public static BellMarketAPI get() {
        if (instance == null) throw new IllegalStateException("BellMarketAPI not initialized");
        return instance;
    }

    public CurrencyManager getCurrencyManager()   { return plugin.getCurrency(); }
    public VipTokenManager getVipTokenManager()    { return plugin.getVipTokens(); }
    public ProductProviderRegistry getProviderRegistry() { return plugin.getProviderRegistry(); }

    public void giveVipTokens(java.util.UUID uuid, long amount, String reason) {
        plugin.getVipTokens().addCoins(uuid, amount, reason);
    }
}
