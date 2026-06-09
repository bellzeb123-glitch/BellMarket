/*
 * BellMarket - EliteMobsProvider (SESJA-3)
 *
 * Reads custom items from EliteMobs (plugins/EliteMobs/customitems/*.yml)
 * and exposes them as BellMarket products.
 *
 * EliteMobs item format:
 *   itemType: DIAMOND_SWORD
 *   name: '&5Legendary Sword'
 *   lore: [...]
 *   weight: 100
 *   scalability: scalable
 *   itemTier: 5
 *
 * Grouping: by itemTier value → category "tier_5", "tier_3", etc.
 * Admin can override category names/icons in providers/elitemobs.yml.
 */
package pl.bellmarket.provider;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.model.Category;
import pl.bellmarket.model.Product;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

public class EliteMobsProvider implements ProductProvider {

    private static final Map<String, Material> MATERIAL_HINTS = Map.of(
        "sword", Material.DIAMOND_SWORD, "axe", Material.DIAMOND_AXE,
        "bow", Material.BOW, "crossbow", Material.CROSSBOW,
        "helmet", Material.DIAMOND_HELMET, "chest", Material.DIAMOND_CHESTPLATE,
        "leggings", Material.DIAMOND_LEGGINGS, "boots", Material.DIAMOND_BOOTS,
        "trident", Material.TRIDENT, "staff", Material.STICK
    );

    private final BellMarket plugin;

    public EliteMobsProvider(BellMarket plugin) { this.plugin = plugin; }

    @Override public String getProviderId() { return "elitemobs"; }

    @Override
    public boolean isAvailable() {
        Plugin em = plugin.getServer().getPluginManager().getPlugin("EliteMobs");
        return em != null && em.isEnabled();
    }

    @Override
    public List<Category> generateCategories(long defaultPrice) {
        Plugin em = plugin.getServer().getPluginManager().getPlugin("EliteMobs");
        if (em == null) return Collections.emptyList();

        FileConfiguration cfg = loadOrCreateProviderConfig(defaultPrice);
        if (!cfg.getBoolean("enabled", true)) return Collections.emptyList();

        long globalDefault = cfg.getLong("default-price", defaultPrice);
        List<String> excluded = cfg.getStringList("excluded-items");
        int baseOrder = cfg.getInt("base-order", 300);
        var itemPrices = cfg.getConfigurationSection("item-prices");
        var cats = cfg.getConfigurationSection("categories");

        // Read all .yml files from EliteMobs/customitems/
        File customItemsDir = new File(em.getDataFolder(), "customitems");
        if (!customItemsDir.exists() || !customItemsDir.isDirectory()) {
            plugin.getLogger().info("[EliteMobsProvider] No customitems directory found.");
            return Collections.emptyList();
        }

        File[] files = customItemsDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null || files.length == 0) return Collections.emptyList();

        // Group items by tier
        Map<String, List<EMItem>> byTier = new TreeMap<>();
        for (File file : files) {
            String itemId = file.getName().replace(".yml", "");
            if (excluded.contains(itemId)) continue;

            YamlConfiguration itemCfg = YamlConfiguration.loadConfiguration(file);
            if (!itemCfg.getBoolean("isEnabled", true)) continue;

            String itemType = itemCfg.getString("itemType", "STONE");
            String name     = itemCfg.getString("name", itemId);
            int tier        = itemCfg.getInt("itemTier", 0);
            String itemModel= null;

            // Try to get item-model if set
            if (itemCfg.contains("customModelData"))
                itemModel = itemCfg.getString("customModelData", null);

            String tierKey = tier > 0 ? "tier_" + tier : "misc";
            Material mat = parseMaterialFromId(itemType);

            byTier.computeIfAbsent(tierKey, k -> new ArrayList<>())
                  .add(new EMItem(itemId, name, mat, tier, itemModel));
        }

        List<Category> out = new ArrayList<>();
        int orderCursor = baseOrder;

        for (Map.Entry<String, List<EMItem>> entry : byTier.entrySet()) {
            String tierKey = entry.getKey();
            List<EMItem> items = entry.getValue();

            var catCfg = cats != null ? cats.getConfigurationSection(tierKey) : null;
            String display = catCfg != null ? catCfg.getString("display-name", formatTier(tierKey)) : formatTier(tierKey);
            String color   = catCfg != null ? catCfg.getString("color", tierColor(tierKey)) : tierColor(tierKey);
            Material icon  = parseMaterial(catCfg != null ? catCfg.getString("icon") : null,
                tierIcon(tierKey));
            long catDefault = catCfg != null ? catCfg.getLong("default-price", globalDefault) : globalDefault;

            List<Product> products = new ArrayList<>();
            for (EMItem item : items) {
                long price = (itemPrices != null && itemPrices.contains(item.id))
                    ? itemPrices.getLong(item.id) : catDefault;

                Product p = new Product.Builder()
                    .id("elitemobs_" + item.id)
                    .type(Product.Type.COMMAND)
                    .name(item.displayName)
                    .lore(List.of(
                        "&7Source: &fEliteMobs",
                        color + display + " &7tier",
                        "",
                        "&6Price: &e" + price + " BellCoins",
                        "",
                        "&aLeft-click &7to purchase"
                    ))
                    .price(price)
                    .enabled(true)
                    .iconMaterial(item.material)
                    .iconItemModel(item.itemModel)
                    .commands(List.of("em giveitem " + item.id + " {player} 1"))
                    .currency(Currency.BELLCOINS)
                    .providerSource("elitemobs")
                    .build();
                products.add(p);
            }
            if (products.isEmpty()) continue;

            String catName = color + "⚔ " + display;
            out.add(new Category(
                "elitemobs_" + tierKey, catName, display,
                orderCursor, true, icon, catName,
                List.of("&7EliteMobs Gear", "&7Tier: " + color + display,
                    "&7Items: &f" + products.size(), "", "&eClick to open"),
                products
            ));
            orderCursor += 10;
        }

        plugin.getLogger().info("[EliteMobsProvider] Generated " + out.size() + " tier categories.");
        return out;
    }

    private record EMItem(String id, String displayName, Material material,
                          int tier, String itemModel) {}

    private FileConfiguration loadOrCreateProviderConfig(long defaultPrice) {
        File dir = new File(plugin.getDataFolder(), "providers");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "elitemobs.yml");
        if (!f.exists()) {
            try { Files.writeString(f.toPath(), buildTemplate(defaultPrice)); }
            catch (IOException e) { plugin.getLogger().warning("[EliteMobsProvider] Config write failed: " + e.getMessage()); }
        }
        return YamlConfiguration.loadConfiguration(f);
    }

    private String buildTemplate(long defaultPrice) {
        return """
# ============================================================
#  BellMarket - EliteMobs Provider Configuration
# ============================================================
# Reads custom items from plugins/EliteMobs/customitems/*.yml
# Items are grouped by their itemTier value.
# ============================================================

enabled: true

base-order: 300

default-price: """ + defaultPrice + """


# Items to never show in shop
excluded-items: []

# Category overrides per tier group
# Auto-detected tiers: tier_1 through tier_X, misc (no tier set)
# categories:
#   tier_5:
#     display-name: "Legendary"
#     color: "&5"
#     icon: NETHERITE_SWORD
#     default-price: 5000
categories: {}

# Per-item price overrides
# item-prices:
#   legendary_sword: 5000
item-prices: {}
""";
    }

    private static String formatTier(String tierKey) {
        if (tierKey.startsWith("tier_")) return "Tier " + tierKey.substring(5);
        return capitalize(tierKey);
    }

    private static String tierColor(String tierKey) {
        if (!tierKey.startsWith("tier_")) return "&7";
        int tier = Integer.parseInt(tierKey.substring(5));
        return switch (tier) {
            case 1 -> "&7"; case 2 -> "&a"; case 3 -> "&9";
            case 4 -> "&5"; case 5 -> "&6"; case 6 -> "&c";
            default -> tier > 6 ? "&d" : "&7";
        };
    }

    private static Material tierIcon(String tierKey) {
        if (!tierKey.startsWith("tier_")) return Material.PAPER;
        int tier = Integer.parseInt(tierKey.substring(5));
        return switch (tier) {
            case 1 -> Material.STONE_SWORD; case 2 -> Material.IRON_SWORD;
            case 3 -> Material.GOLDEN_SWORD;  case 4 -> Material.DIAMOND_SWORD;
            case 5 -> Material.DIAMOND_SWORD; case 6 -> Material.NETHERITE_SWORD;
            default -> Material.NETHERITE_SWORD;
        };
    }

    private static Material parseMaterialFromId(String id) {
        // Check material hints from item type name
        String lower = id.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Material> e : MATERIAL_HINTS.entrySet()) {
            if (lower.contains(e.getKey())) return e.getValue();
        }
        try { return Material.valueOf(id.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return Material.DIAMOND_SWORD; }
    }

    private static Material parseMaterial(String name, Material fallback) {
        if (name == null || name.isEmpty()) return fallback;
        try { return Material.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return fallback; }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
