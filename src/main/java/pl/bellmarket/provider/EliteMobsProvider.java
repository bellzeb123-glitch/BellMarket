/*
 * BellMarket - EliteMobsProvider (SESJA-3 FIX)
 *
 * Fixes:
 *   1. YAML template: "default-price: PRICE" via placeholder replace
 *      (Java text blocks strip trailing whitespace → caused "default-price:100")
 *   2. Display name: reads name/displayName/itemName fields + fallback to
 *      humanized file ID
 *   3. Delivery command: configurable per admin in elitemobs.yml.
 *      Default changed to /em give {player} <item> 1 (EM 10.x syntax)
 *      Admin MUST verify correct command for their EM version.
 */
package pl.bellmarket.provider;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
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

    private static final Map<String, Material> MATERIAL_HINTS = new HashMap<>();
    static {
        MATERIAL_HINTS.put("sword",    Material.DIAMOND_SWORD);
        MATERIAL_HINTS.put("axe",      Material.DIAMOND_AXE);
        MATERIAL_HINTS.put("bow",      Material.BOW);
        MATERIAL_HINTS.put("crossbow", Material.CROSSBOW);
        MATERIAL_HINTS.put("helmet",   Material.DIAMOND_HELMET);
        MATERIAL_HINTS.put("chest",    Material.DIAMOND_CHESTPLATE);
        MATERIAL_HINTS.put("leggings", Material.DIAMOND_LEGGINGS);
        MATERIAL_HINTS.put("boots",    Material.DIAMOND_BOOTS);
        MATERIAL_HINTS.put("trident",  Material.TRIDENT);
        MATERIAL_HINTS.put("staff",    Material.STICK);
        MATERIAL_HINTS.put("book",     Material.ENCHANTED_BOOK);
        MATERIAL_HINTS.put("shield",   Material.SHIELD);
        MATERIAL_HINTS.put("wand",     Material.STICK);
    }

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
        List<String> excluded  = cfg.getStringList("excluded-items");
        int baseOrder          = cfg.getInt("base-order", 300);
        // FIX 3: read delivery-command from config (admin can override)
        String deliveryCmd     = cfg.getString("delivery-command",
            "em give {player} {item} 1");
        var itemPrices         = cfg.getConfigurationSection("item-prices");
        var cats               = cfg.getConfigurationSection("categories");

        File customItemsDir = new File(em.getDataFolder(), "customitems");
        if (!customItemsDir.exists() || !customItemsDir.isDirectory()) return Collections.emptyList();
        File[] files = customItemsDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null || files.length == 0) return Collections.emptyList();

        Map<String, List<EMItem>> byTier = new TreeMap<>();
        for (File file : files) {
            String itemId = file.getName().replace(".yml", "");
            if (excluded.contains(itemId)) continue;
            YamlConfiguration itemCfg = YamlConfiguration.loadConfiguration(file);
            if (!itemCfg.getBoolean("isEnabled", true)) continue;

            String itemType = itemCfg.getString("itemType", "STONE");
            int tier        = itemCfg.getInt("itemTier", 0);
            String itemModel= null;
            if (itemCfg.contains("customModelData"))
                itemModel = itemCfg.getString("customModelData");

            // FIX 2: try multiple name fields, fall back to humanized ID
            String displayName = readDisplayName(itemCfg, itemId);
            String tierKey = tier > 0 ? "tier_" + tier : "misc";
            Material mat = parseMaterialFromId(itemType, itemId);

            byTier.computeIfAbsent(tierKey, k -> new ArrayList<>())
                  .add(new EMItem(itemId, displayName, mat, tier, itemModel));
        }

        List<Category> out = new ArrayList<>();
        int orderCursor = baseOrder;

        for (Map.Entry<String, List<EMItem>> entry : byTier.entrySet()) {
            String tierKey = entry.getKey();
            List<EMItem> items = entry.getValue();

            var catCfg      = cats != null ? cats.getConfigurationSection(tierKey) : null;
            String display  = catCfg != null ? catCfg.getString("display-name", formatTier(tierKey)) : formatTier(tierKey);
            String color    = catCfg != null ? catCfg.getString("color", tierColor(tierKey)) : tierColor(tierKey);
            Material icon   = parseMaterial(catCfg != null ? catCfg.getString("icon") : null, tierIcon(tierKey));
            long catDefault = catCfg != null ? catCfg.getLong("default-price", globalDefault) : globalDefault;

            List<Product> products = new ArrayList<>();
            for (EMItem item : items) {
                long price = (itemPrices != null && itemPrices.contains(item.id))
                    ? itemPrices.getLong(item.id) : catDefault;

                // FIX 3: build command with {item} placeholder
                String cmd = deliveryCmd
                    .replace("{item}", item.id)
                    .replace("{id}",   item.id);

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
                    .commands(List.of(cmd))
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

    // FIX 2: reads name from multiple possible EM fields
    private static String readDisplayName(YamlConfiguration cfg, String fallbackId) {
        // Try common EM name fields
        for (String field : new String[]{"name", "itemName", "displayName", "item-name"}) {
            String val = cfg.getString(field);
            if (val != null && !val.isEmpty()) {
                // Strip color codes for display
                return val.replaceAll("&[0-9a-fA-Fk-oK-OrR]", "").trim();
            }
        }
        // Humanize file ID: enchanted_book_depth_strider → Enchanted Book Depth Strider
        return Arrays.stream(fallbackId.split("_"))
            .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
            .reduce((a, b) -> a + " " + b)
            .orElse(fallbackId);
    }

    private record EMItem(String id, String displayName, Material material,
                          int tier, String itemModel) {}

    // FIX 1: use placeholder replacement instead of string concatenation in text block
    private static final String TEMPLATE = """
# ============================================================
#  BellMarket - EliteMobs Provider Configuration
# ============================================================
# Reads custom items from plugins/EliteMobs/customitems/*.yml
# Items are grouped by their itemTier value.
#
# IMPORTANT: Set delivery-command to match your EM version.
# Test with: /em give <yourname> <itemId> 1
# ============================================================

enabled: true

base-order: 300

default-price: ${DEFAULT_PRICE}

# ─── Delivery command ────────────────────────────────────────
# {player} = player name, {item} = item file ID (without .yml)
# Common formats:
#   EM 10.x:  em give {player} {item} 1
#   EM older: em summon {item} {player}
#   Custom:   mmoitems give PLAYER {player} {item} 1
delivery-command: 'em give {player} {item} 1'

# Items to never show in shop
excluded-items: []

# Category overrides per tier group
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

    private FileConfiguration loadOrCreateProviderConfig(long defaultPrice) {
        File dir = new File(plugin.getDataFolder(), "providers");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "elitemobs.yml");
        if (!f.exists()) {
            try {
                String content = TEMPLATE.replace("${DEFAULT_PRICE}", String.valueOf(defaultPrice));
                Files.writeString(f.toPath(), content);
            } catch (IOException e) {
                plugin.getLogger().warning("[EliteMobsProvider] Config write failed: " + e.getMessage());
            }
        }
        return YamlConfiguration.loadConfiguration(f);
    }

    private static String formatTier(String t) {
        if (t.startsWith("tier_")) return "Tier " + t.substring(5);
        return capitalize(t);
    }

    private static String tierColor(String t) {
        if (!t.startsWith("tier_")) return "&7";
        return switch (Integer.parseInt(t.substring(5))) {
            case 1 -> "&7"; case 2 -> "&a"; case 3 -> "&9";
            case 4 -> "&5"; case 5 -> "&6"; case 6 -> "&c";
            default -> "&d";
        };
    }

    private static Material tierIcon(String t) {
        if (!t.startsWith("tier_")) return Material.PAPER;
        return switch (Integer.parseInt(t.substring(5))) {
            case 1 -> Material.STONE_SWORD;  case 2 -> Material.IRON_SWORD;
            case 3 -> Material.GOLDEN_SWORD; case 4 -> Material.DIAMOND_SWORD;
            case 5 -> Material.DIAMOND_SWORD; default -> Material.NETHERITE_SWORD;
        };
    }

    private static Material parseMaterialFromId(String itemType, String itemId) {
        String lower = itemId.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Material> e : MATERIAL_HINTS.entrySet())
            if (lower.contains(e.getKey())) return e.getValue();
        try { return Material.valueOf(itemType.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return Material.DIAMOND_SWORD; }
    }

    private static Material parseMaterial(String n, Material fb) {
        if (n == null || n.isEmpty()) return fb;
        try { return Material.valueOf(n.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return fb; }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
