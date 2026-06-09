/*
 * BellMarket - MythicMobsProvider (SESJA-3)
 *
 * Reads custom items defined in MythicMobs (plugins/MythicMobs/Items/*.yml)
 * and exposes them as BellMarket products.
 *
 * Primary source for BellMarket on servers using MythicMobs for custom gear.
 * BetterModel item-model keys are automatically detected from the generated
 * ItemStack's item model data.
 *
 * Grouping convention (same as SkinStudio):
 *   prefix_type  →  category "prefix"
 *   e.g. shop_sword, shop_axe  →  category "shop"
 *        tier1_helmet, tier1_chestplate  →  category "tier1"
 *
 * Admin configures which items to expose in providers/mythicmobs.yml.
 * Items can be filtered by prefix, excluded by ID, or fully manually listed.
 *
 * IMPORTANT: Uses MythicMobs API (soft-depend). If MM is not installed,
 * isAvailable() returns false and this provider is silently skipped.
 */
package pl.bellmarket.provider;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.model.Category;
import pl.bellmarket.model.Product;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

public class MythicMobsProvider implements ProductProvider {

    private final BellMarket plugin;

    public MythicMobsProvider(BellMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getProviderId() { return "mythicmobs"; }

    @Override
    public boolean isAvailable() {
        Plugin mm = plugin.getServer().getPluginManager().getPlugin("MythicMobs");
        return mm != null && mm.isEnabled();
    }

    @Override
    public List<Category> generateCategories(long defaultPrice) {
        Plugin mm = plugin.getServer().getPluginManager().getPlugin("MythicMobs");
        if (mm == null) return Collections.emptyList();

        FileConfiguration cfg = loadOrCreateProviderConfig(defaultPrice);
        if (!cfg.getBoolean("enabled", true)) return Collections.emptyList();

        long globalDefault = cfg.getLong("default-price", defaultPrice);
        List<String> prefixFilter = cfg.getStringList("include-prefixes"); // empty = all
        List<String> excluded     = cfg.getStringList("excluded-items");
        ConfigurationSection cats = cfg.getConfigurationSection("categories");
        ConfigurationSection itemPrices = cfg.getConfigurationSection("item-prices");

        // Collect all MythicMobs item names via API
        List<String> allItemNames = getMythicItemNames(mm);
        if (allItemNames.isEmpty()) {
            plugin.getLogger().info("[MythicMobsProvider] No items found in MythicMobs.");
            return Collections.emptyList();
        }

        // Filter by prefix
        if (!prefixFilter.isEmpty()) {
            allItemNames = allItemNames.stream()
                .filter(name -> prefixFilter.stream()
                    .anyMatch(p -> name.toLowerCase(Locale.ROOT).startsWith(p.toLowerCase(Locale.ROOT))))
                .toList();
        }
        // Remove excluded
        allItemNames = allItemNames.stream()
            .filter(name -> !excluded.contains(name))
            .toList();

        // Group by prefix (first segment before _)
        Map<String, List<String>> byCategory = new LinkedHashMap<>();
        for (String itemName : allItemNames) {
            String cat = categoryOf(itemName);
            byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(itemName);
        }

        // Build categories
        List<Category> out = new ArrayList<>();
        int orderBase = cfg.getInt("base-order", 200);
        int orderCursor = orderBase;

        for (Map.Entry<String, List<String>> entry : byCategory.entrySet()) {
            String catKey = entry.getKey();
            List<String> items = entry.getValue();

            // Category config override
            ConfigurationSection catCfg = cats != null ? cats.getConfigurationSection(catKey) : null;
            String catDisplay = catCfg != null ? catCfg.getString("display-name", capitalize(catKey)) : capitalize(catKey);
            String catColor   = catCfg != null ? catCfg.getString("color", "&a") : "&a";
            Material catIcon  = parseMaterial(catCfg != null ? catCfg.getString("icon") : null, Material.EMERALD);
            long catDefault   = catCfg != null ? catCfg.getLong("default-price", globalDefault) : globalDefault;

            List<Product> products = new ArrayList<>();
            for (String itemName : items) {
                long price = (itemPrices != null && itemPrices.contains(itemName))
                    ? itemPrices.getLong(itemName) : catDefault;

                Product p = buildProduct(mm, itemName, price, catColor, catDisplay);
                if (p != null) products.add(p);
            }
            if (products.isEmpty()) continue;

            String catName = catColor + "⚔ " + catDisplay;
            Category cat = new Category(
                "mythicmobs_" + catKey, catName, catDisplay,
                orderCursor, true, catIcon, catName,
                List.of("&7MythicMobs Items", "&7Items: &f" + products.size(), "", "&eClick to open"),
                products
            );
            out.add(cat);
            orderCursor += 10;
        }

        plugin.getLogger().info("[MythicMobsProvider] Generated " + out.size() + " categories, "
            + out.stream().mapToInt(c -> c.getProducts().size()).sum() + " products total.");
        return out;
    }

    /**
     * Builds a Product from a MythicMobs item via the API.
     * Uses the generated ItemStack for material and item-model detection.
     */
    @SuppressWarnings("unchecked")
    private Product buildProduct(Plugin mm, String itemName, long price,
                                  String catColor, String catDisplay) {
        try {
            // Use MythicMobs API via reflection to avoid hard compile dependency
            Object mmApi = mm.getClass().getMethod("getItemManager")
                .invoke(mm.getClass().getMethod("inst").invoke(null));

            Optional<?> itemOpt = (Optional<?>) mmApi.getClass()
                .getMethod("getItem", String.class).invoke(mmApi, itemName);

            if (itemOpt.isEmpty()) return null;
            Object mythicItem = itemOpt.get();

            // Generate the actual ItemStack
            ItemStack stack = (ItemStack) mythicItem.getClass()
                .getMethod("generateItemStack", int.class).invoke(mythicItem, 1);

            if (stack == null || stack.getType().isAir()) return null;

            Material material  = stack.getType();
            String displayName = itemName; // fallback
            String itemModel   = null;

            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                if (meta.hasDisplayName()) {
                    displayName = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacyAmpersand().serialize(meta.displayName());
                }
                // Extract item-model key (BetterModel / resource pack)
                try {
                    NamespacedKey modelKey = (NamespacedKey) meta.getClass()
                        .getMethod("getItemModel").invoke(meta);
                    if (modelKey != null) itemModel = modelKey.toString();
                } catch (Throwable ignored) {}
            }

            return new Product.Builder()
                .id("mythicmobs_" + itemName)
                .type(Product.Type.COMMAND)
                .name(displayName)
                .lore(List.of(
                    "&7Source: &fMythicMobs",
                    "&7Tier: " + catColor + catDisplay,
                    "",
                    "&6Price: &e" + price + " BellCoins",
                    "",
                    "&aLeft-click &7to purchase"
                ))
                .price(price)
                .enabled(true)
                .iconMaterial(material)
                .iconItemModel(itemModel)
                .commands(List.of("mmoitems give " + itemName + " {player}"))
                .currency(Currency.BELLCOINS)
                .providerSource("mythicmobs")
                .build();

        } catch (Throwable t) {
            plugin.getLogger().warning("[MythicMobsProvider] Could not build product '" + itemName
                + "': " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> getMythicItemNames(Plugin mm) {
        try {
            Object inst = mm.getClass().getMethod("inst").invoke(null);
            Object itemManager = inst.getClass().getMethod("getItemManager").invoke(inst);
            Collection<String> names = (Collection<String>)
                itemManager.getClass().getMethod("getItemNames").invoke(itemManager);
            return new ArrayList<>(names);
        } catch (Throwable t) {
            plugin.getLogger().warning("[MythicMobsProvider] Could not fetch item names: " + t.getMessage());
            return Collections.emptyList();
        }
    }

    private FileConfiguration loadOrCreateProviderConfig(long defaultPrice) {
        File dir = new File(plugin.getDataFolder(), "providers");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "mythicmobs.yml");
        if (!f.exists()) {
            try {
                Files.writeString(f.toPath(), buildTemplate(defaultPrice));
                plugin.getLogger().info("[MythicMobsProvider] Created providers/mythicmobs.yml");
            } catch (IOException e) {
                plugin.getLogger().warning("[MythicMobsProvider] Could not write config: " + e.getMessage());
            }
        }
        return YamlConfiguration.loadConfiguration(f);
    }

    private String buildTemplate(long defaultPrice) {
        return """
# ============================================================
#  BellMarket - MythicMobs Provider Configuration
# ============================================================
# Reads items from MythicMobs and adds them to the shop.
# Changes apply on /bm reload.
#
# Item naming convention (same as SkinStudio):
#   prefix_type → category "prefix"
#   e.g. shop_sword, shop_axe → category "shop"
#        tier1_helmet, tier1_chest → category "tier1"
# ============================================================

enabled: true

# Starting order for MM categories in shop (SkinStudio uses 10-90)
base-order: 200

# Global default price for any MM item without a specific price
default-price: """ + defaultPrice + """


# ─── Include filter ──────────────────────────────────────────
# Only expose items whose name STARTS with one of these prefixes.
# Leave empty [] to expose ALL MythicMobs items.
# Recommended: set a "shop_" prefix on items you want to sell.
#
# Example:
# include-prefixes:
#   - shop_
#   - market_
include-prefixes: []

# ─── Excluded items ──────────────────────────────────────────
# Never show these specific items, even if they match include-prefixes.
excluded-items: []

# ─── Category customisation ──────────────────────────────────
# Override auto-detected category metadata per prefix-group.
# If not listed, plugin uses: Capitalize(prefix), green color, EMERALD icon.
#
# categories:
#   shop:
#     display-name: "Shop Items"
#     color: "&a"
#     icon: EMERALD
#     default-price: 1000
categories: {}

# ─── Per-item price overrides (highest priority) ─────────────
# item-prices:
#   shop_legendary_sword: 5000
#   shop_basic_axe: 200
item-prices: {}
""";
    }

    private static String categoryOf(String itemName) {
        int idx = itemName.indexOf('_');
        return idx > 0 ? itemName.substring(0, idx).toLowerCase(Locale.ROOT) : "misc";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static Material parseMaterial(String name, Material fallback) {
        if (name == null || name.isEmpty()) return fallback;
        try { return Material.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return fallback; }
    }
}
