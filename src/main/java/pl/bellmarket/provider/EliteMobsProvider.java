package pl.bellmarket.provider;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.model.Category;
import pl.bellmarket.model.Product;

import java.io.File;
import java.util.*;
import java.util.logging.Level;

public class EliteMobsProvider implements ProductProvider {

    private final BellMarket plugin;
    // Cache of parsed EM items: itemId → EMItem
    private final Map<String, EMItem> emItemCache = new HashMap<>();
    private boolean apiChecked = false;
    private boolean apiAvailable = false;

    public EliteMobsProvider(BellMarket plugin) { this.plugin = plugin; }

    @Override public String getProviderId() { return "elitemobs"; }

    @Override
    public boolean isAvailable() {
        var p = Bukkit.getPluginManager().getPlugin("EliteMobs");
        return p != null && p.isEnabled();
    }

    @Override
    public List<Category> generateCategories(long defaultPrice) {
        if (!isAvailable()) return Collections.emptyList();

        FileConfiguration cfg = plugin.getProviderRegistry().loadOrCreateProviderConfig("elitemobs");
        if (!cfg.getBoolean("enabled", true)) return Collections.emptyList();

        long providerDefaultPrice = cfg.getLong("default-price", defaultPrice);
        List<String> excludedItems = cfg.getStringList("excluded-items");
        int baseOrder = cfg.getInt("base-order", 40);
        ConfigurationSection itemPrices = cfg.getConfigurationSection("item-prices");

        // Scan customitems directory
        File emDataFolder = Bukkit.getPluginManager().getPlugin("EliteMobs").getDataFolder();
        File customItemsDir = new File(emDataFolder, "customitems");
        if (!customItemsDir.exists()) return Collections.emptyList();

        File[] files = customItemsDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null || files.length == 0) return Collections.emptyList();

        // Parse items and group by tier
        Map<String, List<EMItem>> tierMap = new TreeMap<>();

        for (File file : files) {
            try {
                FileConfiguration itemCfg = YamlConfiguration.loadConfiguration(file);
                String itemId = file.getName().replace(".yml", "");
                if (excludedItems.contains(itemId)) continue;

                EMItem emItem = parseEMItem(itemId, itemCfg, cfg);
                emItemCache.put(itemId, emItem);
                tierMap.computeIfAbsent(emItem.tier, k -> new ArrayList<>()).add(emItem);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[EliteMobs] Error parsing " + file.getName(), e);
            }
        }

        List<Category> result = new ArrayList<>();
        int order = baseOrder;

        for (Map.Entry<String, List<EMItem>> entry : tierMap.entrySet()) {
            String tier = entry.getKey();
            ConfigurationSection catCfg = cfg.isConfigurationSection("categories." + tier)
                    ? cfg.getConfigurationSection("categories." + tier) : null;

            String displayName = catCfg != null ? catCfg.getString("display-name", formatTier(tier)) : formatTier(tier);
            String color       = catCfg != null ? catCfg.getString("color", tierColor(tier)) : tierColor(tier);
            Material icon      = catCfg != null
                    ? ProductProviderRegistry.parseMaterial(catCfg.getString("icon"), tierIcon(tier))
                    : tierIcon(tier);

            List<Product> products = new ArrayList<>();
            for (EMItem emItem : entry.getValue()) {
                long price = itemPrices != null ? itemPrices.getLong(emItem.id, providerDefaultPrice) : providerDefaultPrice;
                products.add(buildProduct(emItem, price, color));
            }

            result.add(new Category(
                    "elitemobs_" + tier,
                    "elitemobs_" + tier,
                    color + displayName,
                    order++,
                    true,
                    icon,
                    color + displayName,
                    List.of("&7EliteMobs Items — Tier " + displayName,
                            "&7Items: &f" + products.size(),
                            "",
                            "&eClick to open"),
                    products
            ));
        }

        plugin.getLogger().info("[EliteMobs] Loaded " + result.size() + " tier categories.");
        return result;
    }

    // ─── parsing ─────────────────────────────────────────────────────────

    private EMItem parseEMItem(String id, FileConfiguration cfg, FileConfiguration providerCfg) {
        String materialStr  = cfg.getString("itemType", "STONE");
        String tier         = cfg.getString("itemTier", "misc");
        String displayName  = readDisplayName(cfg, id);
        int customModelData = cfg.getInt("customModelData", 0);

        Material mat = parseMaterialFromId(materialStr);

        return new EMItem(id, mat, tier, displayName, customModelData);
    }

    private String readDisplayName(FileConfiguration cfg, String fallback) {
        // EliteMobs stores display name as a string in 'name' key
        String name = cfg.getString("name");
        if (name != null && !name.isBlank()) return name;
        return ProductProviderRegistry.capitalize(fallback);
    }

    private Material parseMaterialFromId(String materialStr) {
        try {
            return Material.valueOf(materialStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Material.STONE;
        }
    }

    private Product buildProduct(EMItem emItem, long price, String color) {
        return new Product.Builder()
                .id("em_" + emItem.id)
                .type(Product.Type.COMMAND)
                .name(color + emItem.displayName)
                .lore(List.of("&7Source: &fEliteMobs",
                              "&7Tier: " + color + formatTier(emItem.tier),
                              "&6Price: &e" + price + " &7" + plugin.getLang().getCurrencyName(),
                              "",
                              "&aLeft-click &7to purchase"))
                .price(price)
                .iconMaterial(emItem.material)
                .commands(List.of("em giveitem {player} " + emItem.id + " 1"))
                .currency(Currency.BELLCOINS)
                .providerSource("elitemobs")
                .build();
    }

    // ─── tier helpers ────────────────────────────────────────────────────

    private String formatTier(String tier) {
        return ProductProviderRegistry.capitalize(tier);
    }

    private String tierColor(String tier) {
        return switch (tier.toLowerCase()) {
            case "common"    -> "&f";
            case "uncommon"  -> "&a";
            case "rare"      -> "&9";
            case "epic"      -> "&5";
            case "legendary" -> "&6";
            case "mythic"    -> "&c";
            default          -> "&7";
        };
    }

    private Material tierIcon(String tier) {
        return switch (tier.toLowerCase()) {
            case "common"    -> Material.STONE_SWORD;
            case "uncommon"  -> Material.IRON_SWORD;
            case "rare"      -> Material.GOLDEN_SWORD;
            case "epic"      -> Material.DIAMOND_SWORD;
            case "legendary" -> Material.NETHERITE_SWORD;
            default          -> Material.STONE_SWORD;
        };
    }

    // ─── inner classes ───────────────────────────────────────────────────

    private record EMItem(String id, Material material, String tier, String displayName, int customModelData) {}
}
