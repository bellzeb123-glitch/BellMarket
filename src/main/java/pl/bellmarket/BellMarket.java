package pl.bellmarket;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import pl.bellmarket.api.BellMarketAPI;
import pl.bellmarket.command.BellCoinsCommand;
import pl.bellmarket.command.BellMarketCommand;
import pl.bellmarket.command.VipTokenCommand;
import pl.bellmarket.config.CategoryManager;
import pl.bellmarket.config.LangManager;
import pl.bellmarket.currency.CurrencyManager;
import pl.bellmarket.currency.VipTokenManager;
import pl.bellmarket.event.BellMarketReloadEvent;
import pl.bellmarket.gui.AdminGUI;
import pl.bellmarket.gui.PriceEditorGUI;
import pl.bellmarket.gui.ShopGUI;
import pl.bellmarket.listener.AdminChatListener;
import pl.bellmarket.listener.PlayerListener;
import pl.bellmarket.provider.*;

import java.util.List;

public class BellMarket extends JavaPlugin {

    private static BellMarket instance;

    // Managers
    private LangManager     langManager;
    private CurrencyManager currencyManager;
    private VipTokenManager vipTokens;
    private CategoryManager categoryManager;
    private ProductProviderRegistry providerRegistry;

    // GUI
    private ShopGUI          shopGUI;
    private AdminGUI         adminGUI;
    private PriceEditorGUI   priceEditor;

    @Override
    public void onEnable() {
        instance = this;
        printBanner();

        // Config
        saveDefaultConfig();
        saveResource("lang/en.yml", false);
        saveResource("lang/pl.yml", false);
        saveResource("categories/00_tokens.yml", false);
        saveResource("categories/07_mounts.yml", false);
        saveResource("categories/08_custom.yml", false);

        // Managers — LangManager first (uses MERGE for language fix)
        langManager     = new LangManager(this);
        currencyManager = new CurrencyManager(this);
        vipTokens       = new VipTokenManager(this);

        // Providers
        providerRegistry = new ProductProviderRegistry(this);
        providerRegistry.register(new SkinStudioProvider(this));
        providerRegistry.register(new MythicMobsProvider(this));
        providerRegistry.register(new EliteMobsProvider(this));
        providerRegistry.register(new FreeMinecraftModelsProvider(this));

        // Categories (loads from files + providers)
        categoryManager = new CategoryManager(this);

        // GUI
        shopGUI     = new ShopGUI(this);
        adminGUI    = new AdminGUI(this);
        priceEditor = new PriceEditorGUI(this);

        // API
        BellMarketAPI.init(this);

        // Commands
        BellMarketCommand bmCmd = new BellMarketCommand(this);
        PluginCommand bm = getCommand("bellmarket");
        if (bm != null) { bm.setExecutor(bmCmd); bm.setTabCompleter(bmCmd); }

        BellCoinsCommand bcCmd = new BellCoinsCommand(this);
        PluginCommand bc = getCommand("bellcoins");
        if (bc != null) { bc.setExecutor(bcCmd); bc.setTabCompleter(bcCmd); }

        VipTokenCommand vtCmd = new VipTokenCommand(this);
        PluginCommand vt = getCommand("vt");
        if (vt != null) { vt.setExecutor(vtCmd); vt.setTabCompleter(vtCmd); }

        // Listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new AdminChatListener(this, adminGUI, priceEditor), this);

        // Log
        String currName = getConfig().getString("currency.name", "BellCoins");
        List<?> cats = categoryManager.getCategories();
        getLogger().info("BellMarket " + getDescription().getVersion()
                + " enabled. " + cats.size() + " categories loaded. Currency: " + currName);
    }

    @Override
    public void onDisable() {
        if (currencyManager != null) currencyManager.saveAll();
        if (vipTokens != null) vipTokens.saveAll();
        BellMarketAPI.shutdown();
        getLogger().info("BellMarket disabled.");
    }

    /**
     * Globalny reload — przeładowuje WSZYSTKIE managery.
     * FIX: langManager.reload() zmienia język wszędzie (GUI, komendy, opisy, lore).
     */
    public void reload() {
        Bukkit.getPluginManager().callEvent(
                new BellMarketReloadEvent(BellMarketReloadEvent.Phase.PRE_PROVIDERS));

        reloadConfig();               // 1. config z dysku (language: pl/en)
        langManager.reload();         // 2. KLUCZOWE — zmiana języka globalna
        currencyManager.reload();     // 3. waluty
        vipTokens.reload();
        categoryManager.reload();     // 4. kategorie + providerzy

        Bukkit.getPluginManager().callEvent(
                new BellMarketReloadEvent(BellMarketReloadEvent.Phase.POST_PROVIDERS));
    }

    // ─── gettery (oryginalne nazwy) ──────────────────────────────────────

    public static BellMarket getInstance()          { return instance; }
    public LangManager     getLang()                { return langManager; }
    public CurrencyManager getCurrency()            { return currencyManager; }
    public VipTokenManager getVipTokens()           { return vipTokens; }
    public CategoryManager getCategories()          { return categoryManager; }
    public ProductProviderRegistry getProviderRegistry() { return providerRegistry; }
    public ShopGUI         getShopGUI()             { return shopGUI; }
    public AdminGUI        getAdminGUI()            { return adminGUI; }
    public PriceEditorGUI  getPriceEditor()         { return priceEditor; }

    // ─── baner ───────────────────────────────────────────────────────────

    private void printBanner() {
        var c = Bukkit.getConsoleSender();
        c.sendMessage("§r");
        c.sendMessage("§6  ██████╗ ███████╗██╗     ██╗          ");
        c.sendMessage("§6  ██╔══██╗██╔════╝██║     ██║          ");
        c.sendMessage("§6  ██████╔╝█████╗  ██║     ██║          ");
        c.sendMessage("§6  ██╔══██╗██╔══╝  ██║     ██║          ");
        c.sendMessage("§6  ██████╔╝███████╗███████╗███████╗§r§f Market");
        c.sendMessage("§6  ╚═════╝ ╚══════╝╚══════╝╚══════╝     ");
        c.sendMessage("§r");
        c.sendMessage("§7  Version §f" + getDescription().getVersion()
                + "  §7│  Author §bBellzeb");
        c.sendMessage("§7  Status  §aFree §7│ §7Pro §5Coming Soon");
        c.sendMessage("§r");
    }
}
