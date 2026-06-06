/*
 * BellMarket - Main plugin class
 *
 * SESJA-1 INCLUDED: VipTokenManager + ProductProviderRegistry + SkinStudioProvider
 * + BellMarketAPI initialisation + reload event firing + provider category merge.
 *
 * This file is a complete drop-in replacement.
 */
package pl.bellmarket;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import pl.bellmarket.api.BellMarketAPI;
import pl.bellmarket.command.BellCoinsCommand;
import pl.bellmarket.command.BellMarketCommand;
import pl.bellmarket.config.CategoryManager;
import pl.bellmarket.config.LangManager;
import pl.bellmarket.currency.CurrencyManager;
import pl.bellmarket.currency.VipTokenManager;
import pl.bellmarket.event.BellMarketReloadEvent;
import pl.bellmarket.gui.ShopGUI;
import pl.bellmarket.listener.AdminChatListener;
import pl.bellmarket.listener.PlayerListener;
import pl.bellmarket.model.Category;
import pl.bellmarket.provider.ProductProviderRegistry;
import pl.bellmarket.provider.SkinStudioProvider;

public class BellMarket extends JavaPlugin {

    private static BellMarket instance;

    private LangManager langManager;
    private CurrencyManager currencyManager;
    private CategoryManager categoryManager;
    private ShopGUI shopGUI;

    // ── SESJA-1 additions ─────────────────────────────────────────────────
    private VipTokenManager vipTokens;
    private ProductProviderRegistry providerRegistry;
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        instance = this;

        // Save default config + bundled resources
        saveDefaultConfig();
        saveResource("lang/en.yml", false);
        saveResource("lang/pl.yml", false);
        saveResource("categories/00_tokens.yml", false);
        saveResource("categories/07_mounts.yml", false);
        saveResource("categories/08_custom.yml", false);

        // Core managers
        this.langManager     = new LangManager(this);
        this.currencyManager = new CurrencyManager(this);
        this.categoryManager = new CategoryManager(this);
        this.shopGUI         = new ShopGUI(this);

        // ── SESJA-1: new managers ─────────────────────────────────────────
        this.vipTokens        = new VipTokenManager(this);
        this.providerRegistry = new ProductProviderRegistry(this);

        // Register built-in providers
        providerRegistry.register(new SkinStudioProvider(this));
        // Future: providerRegistry.register(new MythicMobsProvider(this));
        // Future: providerRegistry.register(new EliteMobsProvider(this));
        // Future: providerRegistry.register(new FreeMinecraftModelsProvider(this));

        // Initialise public API (must come AFTER managers + registry are created)
        BellMarketAPI.init(this, providerRegistry);

        // Run providers once at startup to populate provider-driven categories
        for (Category c : providerRegistry.generateAll()) {
            categoryManager.getCategories().add(c);
        }
        // ──────────────────────────────────────────────────────────────────

        // Commands
        BellMarketCommand bmCmd = new BellMarketCommand(this);
        PluginCommand bellmarketCmd = getCommand("bellmarket");
        if (bellmarketCmd != null) bellmarketCmd.setExecutor(bmCmd);

        BellCoinsCommand bcCmd = new BellCoinsCommand(this);
        PluginCommand bellcoinsCmd = getCommand("bellcoins");
        if (bellcoinsCmd != null) bellcoinsCmd.setExecutor(bcCmd);

        // Listeners
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerListener(this), this);
        pm.registerEvents(new AdminChatListener(this, shopGUI.getAdminGUI()), this);

        // Startup banner
        getLogger().info("BellMarket v" + getDescription().getVersion() + " enabled!");
        getLogger().info("Currency: " + getConfig().getString("currency.name", "BellCoins"));
        getLogger().info("Categories loaded: " + categoryManager.getCategories().size());
    }

    @Override
    public void onDisable() {
        if (currencyManager != null) currencyManager.saveAll();
        if (vipTokens != null) vipTokens.saveAll();
        getLogger().info("BellMarket disabled. All data saved.");
    }

    public void reload() {
        reloadConfig();
        langManager.reload();
        currencyManager.reload();
        vipTokens.reload();

        // ── SESJA-1: fire PRE_PROVIDERS so external plugins can re-register ─
        Bukkit.getPluginManager().callEvent(
            new BellMarketReloadEvent(BellMarketReloadEvent.Phase.PRE_PROVIDERS));
        // ────────────────────────────────────────────────────────────────────

        categoryManager.reload();

        // ── SESJA-1: merge provider-generated categories into the catalog ───
        for (Category c : providerRegistry.generateAll()) {
            categoryManager.getCategories().add(c);
        }

        Bukkit.getPluginManager().callEvent(
            new BellMarketReloadEvent(BellMarketReloadEvent.Phase.POST_PROVIDERS));
        // ────────────────────────────────────────────────────────────────────
    }

    public static BellMarket getInstance()         { return instance; }
    public LangManager  getLang()                  { return langManager; }
    public CurrencyManager getCurrency()           { return currencyManager; }
    public CategoryManager getCategories()         { return categoryManager; }
    public ShopGUI getShopGUI()                    { return shopGUI; }

    // ── SESJA-1 getters ───────────────────────────────────────────────────
    public VipTokenManager        getVipTokens()        { return vipTokens; }
    public ProductProviderRegistry getProviderRegistry() { return providerRegistry; }
    // ──────────────────────────────────────────────────────────────────────
}
