/*
 * BellMarket — Main plugin class (FREE, addon-ready)
 *
 * Addon architecture:
 *   - This is the FREE standalone plugin (name: BellMarket).
 *   - BellMarketPro is a SEPARATE plugin (name: BellMarketPro, depend: [BellMarket])
 *     that hooks in via the public methods below.
 *
 * Pro hooks exposed:
 *   + setTitleTransformer(Function<String,Component>) — Pro registers custom GUI titles
 *   + buildTitle(String) — used by ShopGUI everywhere; Free falls back to colorize
 *   + setProActive(String version) — Pro announces itself; banner reflects it
 *   + isProActive() / getProVersion() — state queries
 */
package pl.bellmarket;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
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
import pl.bellmarket.gui.ShopGUI;
import pl.bellmarket.listener.AdminChatListener;
import pl.bellmarket.listener.PlayerListener;
import pl.bellmarket.model.Category;
import pl.bellmarket.provider.*;

import java.util.function.Function;

public class BellMarket extends JavaPlugin {

    private static BellMarket instance;

    private LangManager langManager;
    private CurrencyManager currencyManager;
    private CategoryManager categoryManager;
    private ShopGUI shopGUI;
    private VipTokenManager vipTokens;
    private ProductProviderRegistry providerRegistry;
    private BellMarketCommand bmCommand;

    // ── Pro hooks ─────────────────────────────────────────────────────────
    // titleTransformer is null in Free. The Pro addon registers a transformer
    // that wraps inventory titles with the custom background texture character.
    private Function<String, Component> titleTransformer = null;
    private boolean proActive = false;
    private String proVersion = null;

    /** Called by the BellMarketPro addon to install its custom GUI title builder. */
    public void setTitleTransformer(Function<String, Component> transformer) {
        this.titleTransformer = transformer;
        getLogger().info("[BellMarket] Pro GUI title transformer registered.");
    }

    /** Called by the BellMarketPro addon to announce activation (updates banner). */
    public void setProActive(String version) {
        this.proActive = true;
        this.proVersion = version;
        getLogger().info("[BellMarket] Pro addon v" + version + " activated.");
        printProBanner();
    }

    public boolean isProActive()   { return proActive; }
    public String  getProVersion() { return proVersion; }

    /**
     * Builds an inventory title Component.
     * Pro intercepts this to add the custom background; Free returns colorized text.
     */
    public Component buildTitle(String raw) {
        if (titleTransformer != null) {
            try {
                return titleTransformer.apply(raw);
            } catch (Throwable t) {
                getLogger().warning("[BellMarket] Title transformer error, using fallback: " + t.getMessage());
            }
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
    }

    // ────────────────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        instance = this;
        printBanner();

        saveDefaultConfig();
        saveResource("lang/en.yml", false);
        saveResource("lang/pl.yml", false);
        ensureDefaultCategories();

        this.langManager      = new LangManager(this);
        this.currencyManager  = new CurrencyManager(this);
        this.categoryManager  = new CategoryManager(this);
        this.shopGUI          = new ShopGUI(this);
        this.vipTokens        = new VipTokenManager(this);
        this.providerRegistry = new ProductProviderRegistry(this);

        // Free ships with the SkinStudio provider only.
        // MM / EM / FMM providers are registered by the BellMarketPro addon.
        providerRegistry.register(new SkinStudioProvider(this));

        BellMarketAPI.init(this, providerRegistry);

        for (Category c : providerRegistry.generateAll()) {
            categoryManager.getCategories().add(c);
        }

        this.bmCommand = new BellMarketCommand(this);
        registerCmd("bellmarket", bmCommand);
        registerCmd("bellcoins", new BellCoinsCommand(this));
        registerCmd("vt", new VipTokenCommand(this));

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerListener(this), this);
        pm.registerEvents(new AdminChatListener(this, bmCommand.getAdminGUI()), this);
        pm.registerEvents(bmCommand.getPriceEditor(), this);

        getLogger().info("BellMarket v" + getDescription().getVersion() + " enabled.");
        getLogger().info("Categories loaded: " + categoryManager.getCategories().size());
    }

    private void ensureDefaultCategories() {
        // Ship a couple of starter category files so the shop isn't empty on first run
        String[] defaults = {"categories/00_tokens.yml", "categories/08_custom.yml", "categories/09_vip.yml"};
        for (String path : defaults) {
            try { saveResource(path, false); } catch (IllegalArgumentException ignored) {}
        }
    }

    private void registerCmd(String name, org.bukkit.command.CommandExecutor executor) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) return;
        cmd.setExecutor(executor);
        if (executor instanceof org.bukkit.command.TabCompleter tc) {
            cmd.setTabCompleter(tc);
        }
    }

    @Override
    public void onDisable() {
        if (currencyManager != null) currencyManager.saveAll();
        if (vipTokens != null) vipTokens.saveAll();
        getLogger().info("BellMarket disabled. Data saved.");
    }

    public void reload() {
        reloadConfig();
        langManager.reload();
        currencyManager.reload();
        vipTokens.reload();
        Bukkit.getPluginManager().callEvent(
            new BellMarketReloadEvent(BellMarketReloadEvent.Phase.PRE_PROVIDERS));
        categoryManager.reload();
        for (Category c : providerRegistry.generateAll()) {
            categoryManager.getCategories().add(c);
        }
        Bukkit.getPluginManager().callEvent(
            new BellMarketReloadEvent(BellMarketReloadEvent.Phase.POST_PROVIDERS));
    }

    private void printBanner() {
        var c = Bukkit.getConsoleSender();
        c.sendMessage("§r");
        c.sendMessage("§6  ██████╗ ███████╗██╗     ██╗");
        c.sendMessage("§6  ██╔══██╗██╔════╝██║     ██║");
        c.sendMessage("§6  ██████╔╝█████╗  ██║     ██║");
        c.sendMessage("§6  ██╔══██╗██╔══╝  ██║     ██║");
        c.sendMessage("§6  ██████╔╝███████╗███████╗███████╗§r§f Market");
        c.sendMessage("§6  ╚═════╝ ╚══════╝╚══════╝╚══════╝");
        c.sendMessage("§r");
        c.sendMessage("§7  Version §f" + getDescription().getVersion() + "  §7│  Author §bBellzeb");
        c.sendMessage("§7  Edition §aFree §7│  Pro §8Not installed");
        c.sendMessage("§r");
    }

    /** Re-prints the banner line showing Pro is now active (called by the addon). */
    private void printProBanner() {
        var c = Bukkit.getConsoleSender();
        c.sendMessage("§r");
        c.sendMessage("§6  ██████╗ ███████╗██╗     ██╗");
        c.sendMessage("§6  ██╔══██╗██╔════╝██║     ██║");
        c.sendMessage("§6  ██████╔╝█████╗  ██║     ██║");
        c.sendMessage("§6  ██╔══██╗██╔══╝  ██║     ██║");
        c.sendMessage("§6  ██████╔╝███████╗███████╗███████╗§r§f Market §d§lPRO");
        c.sendMessage("§6  ╚═════╝ ╚══════╝╚══════╝╚══════╝");
        c.sendMessage("§r");
        c.sendMessage("§7  Version §f" + getDescription().getVersion() + "  §7│  Author §bBellzeb");
        c.sendMessage("§7  Edition §d§lPRO §7v" + proVersion + " §a✓ active");
        c.sendMessage("§r");
    }

    public static BellMarket getInstance()               { return instance; }
    public LangManager  getLang()                        { return langManager; }
    public CurrencyManager getCurrency()                 { return currencyManager; }
    public CategoryManager getCategories()               { return categoryManager; }
    public ShopGUI getShopGUI()                          { return shopGUI; }
    public VipTokenManager getVipTokens()                { return vipTokens; }
    public ProductProviderRegistry getProviderRegistry() { return providerRegistry; }
    public BellMarketCommand getBellMarketCommand()      { return bmCommand; }
}
