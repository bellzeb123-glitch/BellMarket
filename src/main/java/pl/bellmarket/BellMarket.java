package pl.bellmarket;

import org.bukkit.plugin.java.JavaPlugin;
import pl.bellmarket.command.BellCoinsCommand;
import pl.bellmarket.command.BellMarketCommand;
import pl.bellmarket.config.CategoryManager;
import pl.bellmarket.config.LangManager;
import pl.bellmarket.currency.CurrencyManager;
import pl.bellmarket.gui.AdminGUI;
import pl.bellmarket.gui.ShopGUI;
import pl.bellmarket.listener.AdminChatListener;
import pl.bellmarket.listener.PlayerListener;

import java.util.logging.Logger;

public class BellMarket extends JavaPlugin {

    private static BellMarket instance;
    private LangManager langManager;
    private CurrencyManager currencyManager;
    private CategoryManager categoryManager;
    private ShopGUI shopGUI;

    @Override
    public void onEnable() {
        instance = this;

        // Save default configs
        saveDefaultConfig();
        saveResource("lang/en.yml", false);
        saveResource("lang/pl.yml", false);
        saveResource("categories/01_bronze.yml", false);
        saveResource("categories/06_mounts.yml", false);

        // Initialize managers
        langManager      = new LangManager(this);
        currencyManager  = new CurrencyManager(this);
        categoryManager  = new CategoryManager(this);
        shopGUI          = new ShopGUI(this);

        // Register commands
        BellMarketCommand bmCmd = new BellMarketCommand(this);
        getCommand("bellmarket").setExecutor(bmCmd);
        getCommand("bellcoins").setExecutor(new BellCoinsCommand(this));

        // Register listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new AdminChatListener(this, bmCmd.getAdminGUI()), this);

        getLogger().info("BellMarket v" + getDescription().getVersion() + " enabled!");
        getLogger().info("Language: " + getConfig().getString("language", "en"));
        getLogger().info("Currency: " + getConfig().getString("currency.name", "BellCoins"));
        getLogger().info("Categories loaded: " + categoryManager.getCategories().size());
    }

    @Override
    public void onDisable() {
        if (currencyManager != null) currencyManager.saveAll();
        getLogger().info("BellMarket disabled. All data saved.");
    }

    public void reload() {
        reloadConfig();
        langManager.reload();
        categoryManager.reload();
        currencyManager.reload();
    }

    public static BellMarket getInstance() { return instance; }
    public LangManager getLang()           { return langManager; }
    public CurrencyManager getCurrency()   { return currencyManager; }
    public CategoryManager getCategories() { return categoryManager; }
    public ShopGUI getShopGUI()            { return shopGUI; }
}
