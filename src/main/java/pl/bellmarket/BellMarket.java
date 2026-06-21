/*
 * BellMarket - Main plugin class (SESJA-3)
 *
 * Sesja 3 additions:
 *   + Registers MythicMobsProvider
 *   + Registers EliteMobsProvider
 *   + Registers FreeMinecraftModelsProvider
 *   All are soft-depend — skip silently if plugin not installed.
 */
package pl.bellmarket;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import pl.bellmarket.api.BellMarketAPI;
import pl.bellmarket.api.ProFeatures;
import pl.bellmarket.command.BellCoinsCommand;
import pl.bellmarket.command.BellMarketCommand;
import pl.bellmarket.command.VipTokenCommand;
import pl.bellmarket.config.CategoryManager;
import pl.bellmarket.config.LangManager;
import pl.bellmarket.currency.CurrencyManager;
import pl.bellmarket.currency.VipTokenManager;
import pl.bellmarket.event.BellMarketReloadEvent;
import pl.bellmarket.gui.ShopGUI;
import pl.bellmarket.listener.AdminChatListener;
import pl.bellmarket.listener.PlayerListener;
import pl.bellmarket.listener.ProviderSyncListener;
import pl.bellmarket.model.Category;
import pl.bellmarket.provider.ProductProviderRegistry;
import pl.bellmarket.provider.SkinStudioProvider;

import java.util.List;
import java.util.function.Function;

public class BellMarket extends JavaPlugin {

    private static BellMarket instance;

    private LangManager langManager;
    private CurrencyManager currencyManager;
    private CategoryManager categoryManager;
    private ShopGUI shopGUI;
    private VipTokenManager vipTokens;
    private ProductProviderRegistry providerRegistry;
    private pl.bellmarket.gui.AdminGUI adminGUI;
    private Function<String, Component> titleTransformer;
    private ProFeatures proFeatures;

    @Override
    public void onEnable() {
        instance = this;
        printBanner();

        saveDefaultConfig();
        saveResource("lang/en.yml", false);
        saveResource("lang/pl.yml", false);
        saveResource("categories/00_tokens.yml", false);
        saveResource("categories/01_vip.yml", false);
        saveResource("categories/07_mounts.yml", false);
        saveResource("categories/08_custom.yml", false);

        this.langManager     = new LangManager(this);
        this.currencyManager = new CurrencyManager(this);
        this.categoryManager = new CategoryManager(this);
        this.shopGUI         = new ShopGUI(this);
        this.vipTokens       = new VipTokenManager(this);
        this.providerRegistry = new ProductProviderRegistry(this);

        // ── Built-in providers ──────────────────────────────────────────────
        providerRegistry.register(new SkinStudioProvider(this));
        if (Bukkit.getPluginManager().getPlugin("BellItems") != null) {
            providerRegistry.register(new pl.bellmarket.provider.BellItemsProvider(this));
        }

        BellMarketAPI.init(this, providerRegistry);

        refreshProviderCategories();
        // SkinStudio may finish writing config.yml after its onEnable — retry next tick.
        Bukkit.getScheduler().runTask(this, this::refreshProviderCategories);

        // ── Commands ────────────────────────────────────────────────────────
        BellMarketCommand bmCmd = new BellMarketCommand(this);
        this.adminGUI = bmCmd.getAdminGUI();
        PluginCommand bellmarketCmd = getCommand("bellmarket");
        if (bellmarketCmd != null) bellmarketCmd.setExecutor(bmCmd);

        BellCoinsCommand bcCmd = new BellCoinsCommand(this);
        PluginCommand bellcoinsCmd = getCommand("bellcoins");
        if (bellcoinsCmd != null) bellcoinsCmd.setExecutor(bcCmd);

        VipTokenCommand vtCmd = new VipTokenCommand(this);
        PluginCommand vtCommand = getCommand("vt");
        if (vtCommand != null) {
            vtCommand.setExecutor(vtCmd);
            vtCommand.setTabCompleter(vtCmd);
        }

        // ── Listeners ───────────────────────────────────────────────────────
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerListener(this), this);
        pm.registerEvents(new AdminChatListener(this, bmCmd.getAdminGUI()), this);
        pm.registerEvents(bmCmd.getPriceEditor(), this);
        pm.registerEvents(new ProviderSyncListener(this), this);

        getLogger().info("BellMarket v" + getDescription().getVersion() + " enabled!");
        getLogger().info("Currency: " + getConfig().getString("currency.name", "BellCoins"));
        getLogger().info("Categories loaded: " + categoryManager.getCategories().size());
    }

    private void printBanner() {
        var c = org.bukkit.Bukkit.getConsoleSender();
        boolean proActive = org.bukkit.Bukkit.getPluginManager().getPlugin("BellMarketPro") != null;
        c.sendMessage("§r");
        c.sendMessage("§6  ██████╗ ███████╗██╗     ██╗          ");
        c.sendMessage("§6  ██╔══██╗██╔════╝██║     ██║          ");
        c.sendMessage("§6  ██████╔╝█████╗  ██║     ██║          ");
        c.sendMessage("§6  ██╔══██╗██╔══╝  ██║     ██║          ");
        c.sendMessage("§6  ██████╔╝███████╗███████╗███████╗§r§f Market");
        c.sendMessage("§6  ╚═════╝ ╚══════╝╚══════╝╚══════╝     ");
        c.sendMessage("§r");
        c.sendMessage("§7  Version §f" + getDescription().getVersion() + "  §7│  Author §bBellzeb");
        c.sendMessage("§7  Status  §aFree §7│ " + (proActive ? "§5Pro §aActive" : "§7Pro §5Coming Soon"));
        c.sendMessage("§r");
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
        Bukkit.getPluginManager().callEvent(
            new BellMarketReloadEvent(BellMarketReloadEvent.Phase.PRE_PROVIDERS));
        categoryManager.reload();
        refreshProviderCategories();
        Bukkit.getPluginManager().callEvent(
            new BellMarketReloadEvent(BellMarketReloadEvent.Phase.POST_PROVIDERS));
    }

    /**
     * Regenerates in-memory provider categories (SkinStudio tiers etc.)
     * without reloading manual YAML categories from disk.
     */
    public void refreshProviderCategories() {
        List<Category> generated = providerRegistry.generateAll();
        categoryManager.removeProviderCategories();
        categoryManager.addProviderCategories(generated);

        int total = categoryManager.getCategories().size();
        getLogger().info("Shop catalog: " + total + " categories ("
            + generated.size() + " from providers)");

        if (generated.isEmpty() && isSkinStudioInstalled()) {
            getLogger().info("[Providers] SkinStudio is present but no skin categories yet — "
                + "will sync automatically when ready.");
        }
    }

    private boolean isSkinStudioInstalled() {
        var sk = Bukkit.getPluginManager().getPlugin("SkinStudio");
        return sk != null;
    }

    public static BellMarket getInstance()          { return instance; }
    public LangManager  getLang()                   { return langManager; }
    public CurrencyManager getCurrency()            { return currencyManager; }
    public CategoryManager getCategories()          { return categoryManager; }
    public ShopGUI getShopGUI()                     { return shopGUI; }
    public VipTokenManager getVipTokens()           { return vipTokens; }
    public ProductProviderRegistry getProviderRegistry() { return providerRegistry; }
    public pl.bellmarket.gui.AdminGUI getAdminGUI()       { return adminGUI; }

    public void setTitleTransformer(Function<String, Component> transformer) {
        this.titleTransformer = transformer;
    }

    public void setProFeatures(ProFeatures features) {
        this.proFeatures = features;
    }

    public ProFeatures getProFeatures() {
        return proFeatures;
    }

    public long getEffectivePrice(pl.bellmarket.model.Product product) {
        if (proFeatures != null) {
            return proFeatures.resolvePrice(product);
        }
        return product.getPrice();
    }

    public Component buildTitle(String raw) {
        if (titleTransformer != null) {
            return titleTransformer.apply(raw);
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
    }
}
