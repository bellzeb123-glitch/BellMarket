package pl.bellmarket.provider;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import pl.bellmarket.BellMarket;
import pl.bellmarket.config.LangManager;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.model.Category;
import pl.bellmarket.model.Product;

import java.io.File;
import java.util.*;
import java.util.logging.Level;

public class SkinStudioProvider implements ProductProvider {

    private final BellMarket plugin;

    // Maps §-color code → stained glass Material
    private static final Map<String, Material> COLOR_TO_GLASS = Map.ofEntries(
        Map.entry("&6", Material.ORANGE_STAINED_GLASS_PANE),
        Map.entry("&e", Material.YELLOW_STAINED_GLASS_PANE),
        Map.entry("&b", Material.LIGHT_BLUE_STAINED_GLASS_PANE),
        Map.entry("&a", Material.LIME_STAINED_GLASS_PANE),
        Map.entry("&c", Material.RED_STAINED_GLASS_PANE),
        Map.entry("&d", Material.PINK_STAINED_GLASS_PANE),
        Map.entry("&5", Material.PURPLE_STAINED_GLASS_PANE),
        Map.entry("&9", Material.BLUE_STAINED_GLASS_PANE),
        Map.entry("&f", Material.WHITE_STAINED_GLASS_PANE),
        Map.entry("&7", Material.LIGHT_GRAY_STAINED_GLASS_PANE),
        Map.entry("&8", Material.GRAY_STAINED_GLASS_PANE),
        Map.entry("&0", Material.BLACK_STAINED_GLASS_PANE)
    );

    public SkinStudioProvider(BellMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getProviderId() { return "skinstudio"; }

    @Override
    public boolean isAvailable() {
        var p = Bukkit.getPluginManager().getPlugin("SkinStudio");
        return p != null && p.isEnabled();
    }

    @Override
    public List<Category> generateCategories(long defaultPrice) {
        if (!isAvailable()) return Collections.emptyList();

        // Read SkinStudio config
        File ssConfig = new File(plugin.getServer().getPluginManager()
                .getPlugin("SkinStudio").getDataFolder(), "config.yml");
        if (!ssConfig.exists()) return Collections.emptyList();

        FileConfiguration ssCfg;
        try {
            ssCfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(ssConfig);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[SkinStudio] Could not load config.yml", e);
            return Collections.emptyList();
        }

        ConfigurationSection skinsSection = ssCfg.getConfigurationSection("skins");
        if (skinsSection == null) return Collections.emptyList();

        // Load provider config
        FileConfiguration cfg = plugin.getProviderRegistry().loadOrCreateProviderConfig("skinstudio");
        long providerDefaultPrice = cfg.getLong("default-price", defaultPrice);
        boolean includeChangeToken = plugin.getConfig()
                .getBoolean("providers.skinstudio.include-change-token", false);
        List<String> globalExcluded = cfg.getStringList("excluded-skins");

        // Group skins by tier
        Map<String, List<String>> tierMap = new LinkedHashMap<>();
        for (String skinName : skinsSection.getKeys(false)) {
            String tier = tierOf(skinName);
            tierMap.computeIfAbsent(tier, k -> new ArrayList<>()).add(skinName);
        }

        // Sort tiers
        List<String> sortedTiers = new ArrayList<>(tierMap.keySet());
        sortedTiers.sort(Comparator.naturalOrder());

        List<Category> result = new ArrayList<>();
        int order = 10;

        for (String tier : sortedTiers) {
            ConfigurationSection tierCfg = cfg.isConfigurationSection("tiers." + tier)
                    ? cfg.getConfigurationSection("tiers." + tier) : null;

            // Skip disabled tiers
            if (tierCfg != null && !tierCfg.getBoolean("enabled", true)) continue;

            String color       = detectTierColor(tier, tierCfg);
            String displayName = detectTierDisplayName(tier, tierCfg);
            Material icon      = COLOR_TO_GLASS.getOrDefault(color, Material.LIGHT_GRAY_STAINED_GLASS_PANE);
            if (tierCfg != null && tierCfg.isString("icon")) {
                icon = ProductProviderRegistry.parseMaterial(tierCfg.getString("icon"), icon);
            }

            List<String> tierExcluded = tierCfg != null ? tierCfg.getStringList("excluded-skins") : List.of();
            long tierPrice = tierCfg != null ? tierCfg.getLong("default-price", providerDefaultPrice) : providerDefaultPrice;

            // Build products
            List<Product> products = new ArrayList<>();
            for (String skinName : tierMap.get(tier)) {
                if (globalExcluded.contains(skinName) || tierExcluded.contains(skinName)) continue;

                ConfigurationSection skinSection = skinsSection.getConfigurationSection(skinName);
                long skinPrice = cfg.getLong("skin-prices." + skinName, tierPrice);

                Product p = buildSkinProduct(skinName, skinSection, skinPrice,
                        includeChangeToken, color, tier);
                if (p != null) products.add(p);
            }

            if (products.isEmpty()) continue;

            // Category lore
            LangManager lang = plugin.getLang();
            List<String> catLore = lang.getList("provider.skinstudio.category-lore",
                    "color", color, "tier", displayName, "count", String.valueOf(products.size()));

            result.add(new Category(
                    "skinstudio_" + tier,
                    "skinstudio_" + tier,
                    color + displayName,
                    order++,
                    true,
                    icon,
                    color + displayName,
                    catLore,
                    products
            ));
        }

        plugin.getLogger().info("[SkinStudio] Loaded " + result.size() + " tier categories.");
        return result;
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private String tierOf(String skinName) {
        int idx = skinName.indexOf('_');
        return idx > 0 ? skinName.substring(0, idx) : "misc";
    }

    private String detectTierColor(String tier, ConfigurationSection tierCfg) {
        if (tierCfg != null && tierCfg.isString("color")) return tierCfg.getString("color");
        return switch (tier.toLowerCase(Locale.ROOT)) {
            case "bronze"   -> "&6";
            case "silver"   -> "&7";
            case "gold"     -> "&e";
            case "diamond"  -> "&b";
            case "platinum" -> "&f";
            case "vip"      -> "&5";
            default         -> "&7";
        };
    }

    private String detectTierDisplayName(String tier, ConfigurationSection tierCfg) {
        if (tierCfg != null && tierCfg.isString("display-name"))
            return tierCfg.getString("display-name");
        return ProductProviderRegistry.capitalize(tier);
    }

    private Product buildSkinProduct(String skinName, ConfigurationSection skinSection,
                                     long price, boolean includeChangeToken,
                                     String color, String tier) {
        LangManager lang = plugin.getLang();
        List<String> lore = lang.getList("provider.skinstudio.product-lore",
                "skin-id", skinName, "color", color, "tier",
                ProductProviderRegistry.capitalize(tier),
                "price", String.valueOf(price),
                "currency", lang.getCurrencyName());

        // Try to get item-model from SkinStudio skin config
        String itemModel = null;
        if (skinSection != null) {
            itemModel = skinSection.getString("item-model");
            if (itemModel == null) {
                List<String> itemTypes = skinSection.getStringList("item-types");
                if (!itemTypes.isEmpty()) itemModel = null; // use PAPER fallback
            }
        }

        return new Product.Builder()
                .id("skinstudio_" + skinName)
                .type(Product.Type.SKIN_TOKEN)
                .name(color + ProductProviderRegistry.capitalize(
                        skinName.contains("_") ? skinName.substring(skinName.indexOf('_') + 1) : skinName))
                .lore(lore)
                .price(price)
                .iconMaterial(Material.PAPER)
                .iconItemModel(itemModel)
                .skinId(skinName)
                .includeChangeToken(includeChangeToken)
                .currency(Currency.BELLCOINS)
                .providerSource("skinstudio")
                .build();
    }
}
