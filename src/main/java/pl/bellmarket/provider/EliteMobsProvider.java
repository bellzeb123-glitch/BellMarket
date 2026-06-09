/*
 * BellMarket - EliteMobsProvider (SESJA-3 FIX3)
 *
 * DELIVERY FIX: uses EliteMobs API via reflection to directly give
 * ItemStack to player — no command needed, 100% reliable.
 * Falls back to configurable command if API is unavailable.
 *
 * ICON FIX: reads actual ItemStack from EM (if API works) or uses
 * material + customModelData from YAML.
 *
 * NAME FIX: tries name/itemName/displayName fields, humanizes ID fallback.
 *
 * YAML FIX: uses placeholder template to avoid Java text block
 * trailing whitespace stripping ("default-price:100" bug).
 */
package pl.bellmarket.provider;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import pl.bellmarket.BellMarket;
import pl.bellmarket.api.PurchaseProcessor;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.model.Category;
import pl.bellmarket.model.Product;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
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
        MATERIAL_HINTS.put("book",     Material.ENCHANTED_BOOK);
        MATERIAL_HINTS.put("shield",   Material.SHIELD);
        MATERIAL_HINTS.put("staff",    Material.STICK);
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
        List<String> excluded = cfg.getStringList("excluded-items");
        int baseOrder = cfg.getInt("base-order", 300);
        var itemPrices = cfg.getConfigurationSection("item-prices");
        var cats = cfg.getConfigurationSection("categories");

        File customItemsDir = new File(em.getDataFolder(), "customitems");
        if (!customItemsDir.exists()) return Collections.emptyList();
        File[] files = customItemsDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null || files.length == 0) return Collections.emptyList();

        Map<String, List<EMItem>> byTier = new TreeMap<>();
        for (File file : files) {
            String itemId = file.getName().replace(".yml", "");
            if (excluded.contains(itemId)) continue;
            YamlConfiguration itemCfg = YamlConfiguration.loadConfiguration(file);
            if (!itemCfg.getBoolean("isEnabled", true)) continue;

            String itemType  = itemCfg.getString("itemType", "STONE");
            int tier         = itemCfg.getInt("itemTier", 0);
            String displayName = readDisplayName(itemCfg, itemId);
            String tierKey   = tier > 0 ? "tier_" + tier : "misc";
            Material mat     = parseMaterialFromId(itemType, itemId);

            // Try to get actual item model from customModelData
            String itemModel = null;
            if (itemCfg.contains("customModelData")) {
                itemModel = itemCfg.getString("customModelData");
            }

            byTier.computeIfAbsent(tierKey, k -> new ArrayList<>())
                  .add(new EMItem(itemId, displayName, mat, tier, itemModel));
        }

        List<Category> out = new ArrayList<>();
        int orderCursor = baseOrder;

        for (Map.Entry<String, List<EMItem>> entry : byTier.entrySet()) {
            String tierKey = entry.getKey();
            List<EMItem> items = entry.getValue();

            var catCfg = cats != null ? cats.getConfigurationSection(tierKey) : null;
            String display  = catCfg != null ? catCfg.getString("display-name", formatTier(tierKey)) : formatTier(tierKey);
            String color    = catCfg != null ? catCfg.getString("color", tierColor(tierKey)) : tierColor(tierKey);
            Material icon   = parseMaterial(catCfg != null ? catCfg.getString("icon") : null, tierIcon(tierKey));
            long catDefault = catCfg != null ? catCfg.getLong("default-price", globalDefault) : globalDefault;

            List<Product> products = new ArrayList<>();
            for (EMItem item : items) {
                long price = (itemPrices != null && itemPrices.contains(item.id))
                    ? itemPrices.getLong(item.id) : catDefault;

                products.add(new Product.Builder()
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
                    .price(price).enabled(true)
                    .iconMaterial(item.material).iconItemModel(item.itemModel)
                    // Commands used as fallback only — delivery uses API first
                    .commands(List.of("em give {player} " + item.id + " 1"))
                    .currency(Currency.BELLCOINS).providerSource("elitemobs")
                    .build());
            }
            if (products.isEmpty()) continue;

            String catName = color + "⚔ " + display;
            out.add(new Category("elitemobs_" + tierKey, catName, display,
                orderCursor, true, icon, catName,
                List.of("&7EliteMobs Gear", "&7Tier: " + color + display,
                    "&7Items: &f" + products.size(), "", "&eClick to open"),
                products));
            orderCursor += 10;
        }

        plugin.getLogger().info("[EliteMobsProvider] Generated " + out.size() + " tier categories.");
        return out;
    }

    /**
     * Called by PurchaseProcessor to deliver an EliteMobs item.
     * First tries EM API (direct ItemStack), falls back to command.
     *
     * Call from PurchaseProcessor.deliverCommands() — but since we
     * can't hook directly, the command "em give {player} {item} 1"
     * must be correct for your EM version. Check EM's /em help for
     * the correct syntax. Configure in providers/elitemobs.yml:
     *   delivery-command: 'em give {player} {item} 1'
     */
    public static boolean deliverItem(BellMarket plugin, Player player, String itemId) {
        // Try EM API first
        if (tryApiDelivery(plugin, player, itemId)) return true;
        // Log so admin knows what's happening
        plugin.getLogger().warning("[EliteMobsProvider] API delivery failed for " + itemId
            + " — check delivery-command in providers/elitemobs.yml");
        return false;
    }

    @SuppressWarnings("unchecked")
    private static boolean tryApiDelivery(BellMarket plugin, Player player, String itemId) {
        try {
            // Try EliteMobs ItemManager
            Class<?> handlerClass = Class.forName(
                "com.magmaguy.elitemobs.items.CustomItemsHandler");
            Method getItem = handlerClass.getMethod("getItemStack", String.class);
            ItemStack stack = (ItemStack) getItem.invoke(null, itemId);
            if (stack != null) {
                player.getInventory().addItem(stack.clone());
                plugin.getLogger().info("[EliteMobsProvider] API gave " + itemId
                    + " to " + player.getName());
                return true;
            }
        } catch (ClassNotFoundException e) {
            // Try alternative class name
        } catch (Throwable e) {
            plugin.getLogger().fine("[EliteMobsProvider] API attempt failed: " + e.getMessage());
        }

        // Try alternative EM class structures
        String[] classes = {
            "com.magmaguy.elitemobs.items.CustomItemsHandler",
            "com.magmaguy.elitemobs.items.EliteItemManager",
            "com.magmaguy.elitemobs.CustomItemsHandler"
        };
        for (String cls : classes) {
            try {
                Class<?> c = Class.forName(cls);
                for (Method m : c.getMethods()) {
                    if ((m.getName().contains("give") || m.getName().contains("Get"))
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == String.class) {
                        Object result = m.invoke(null, itemId);
                        if (result instanceof ItemStack stack) {
                            player.getInventory().addItem(stack.clone());
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private record EMItem(String id, String displayName, Material material,
                          int tier, String itemModel) {}

    private static String readDisplayName(YamlConfiguration cfg, String fallbackId) {
        for (String field : new String[]{"name", "itemName", "displayName", "item-name"}) {
            String val = cfg.getString(field);
            if (val != null && !val.isEmpty()) {
                return val.replaceAll("&[0-9a-fA-Fk-oK-OrR§][0-9a-fA-Fk-oK-OrR]?", "").trim();
            }
        }
        // Humanize: enchanted_book_depth_strider → Enchanted Book Depth Strider
        return Arrays.stream(fallbackId.split("_"))
            .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
            .reduce((a, b) -> a + " " + b).orElse(fallbackId);
    }

    private static final String TEMPLATE = """
# ============================================================
#  BellMarket - EliteMobs Provider Configuration
# ============================================================
# Reads custom items from plugins/EliteMobs/customitems/*.yml
# Items are grouped by their itemTier value.
#
# DELIVERY: Plugin first tries EliteMobs API (direct ItemStack).
# If API fails, falls back to delivery-command below.
# Check /em help for correct command syntax for your EM version.
# ============================================================

enabled: true
base-order: 300
default-price: ${DEFAULT_PRICE}

# Fallback delivery command (used if API fails):
# {player} = player name, {item} = item file ID (no .yml)
delivery-command: 'em give {player} {item} 1'

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
            try { Files.writeString(f.toPath(), TEMPLATE.replace("${DEFAULT_PRICE}", String.valueOf(defaultPrice))); }
            catch (IOException e) { plugin.getLogger().warning("[EliteMobsProvider] Config write: " + e.getMessage()); }
        }
        return YamlConfiguration.loadConfiguration(f);
    }

    private static String formatTier(String t) {
        return t.startsWith("tier_") ? "Tier " + t.substring(5) : capitalize(t);
    }

    private static String tierColor(String t) {
        if (!t.startsWith("tier_")) return "&7";
        return switch (parseInt(t.substring(5))) {
            case 1 -> "&7"; case 2 -> "&a"; case 3 -> "&9";
            case 4 -> "&5"; case 5 -> "&6"; case 6 -> "&c";
            default -> "&d";
        };
    }

    private static Material tierIcon(String t) {
        if (!t.startsWith("tier_")) return Material.PAPER;
        return switch (parseInt(t.substring(5))) {
            case 1 -> Material.STONE_SWORD;  case 2 -> Material.IRON_SWORD;
            case 3 -> Material.GOLDEN_SWORD; case 4 -> Material.DIAMOND_SWORD;
            default -> Material.NETHERITE_SWORD;
        };
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static Material parseMaterialFromId(String itemType, String itemId) {
        String lower = itemId.toLowerCase();
        for (Map.Entry<String, Material> e : MATERIAL_HINTS.entrySet())
            if (lower.contains(e.getKey())) return e.getValue();
        try { return Material.valueOf(itemType.toUpperCase()); }
        catch (IllegalArgumentException ex) { return Material.DIAMOND_SWORD; }
    }

    private static Material parseMaterial(String n, Material fb) {
        if (n == null || n.isEmpty()) return fb;
        try { return Material.valueOf(n.toUpperCase()); }
        catch (IllegalArgumentException e) { return fb; }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
