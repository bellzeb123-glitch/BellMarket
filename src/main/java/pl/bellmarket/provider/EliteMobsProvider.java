/*
 * BellMarket - EliteMobsProvider (SESJA-3 FIX4)
 *
 * DELIVERY FIX: builds ItemStack directly from EM's YAML file
 * instead of running a command. Bypasses all API/command issues.
 *
 * How it works:
 *   1. Reads plugins/EliteMobs/customitems/<itemId>.yml
 *   2. Constructs Bukkit ItemStack with material, name, lore, enchantments
 *   3. Sets item-model/customModelData if present
 *   4. Adds directly to player.getInventory()
 *
 * Note: item won't have EM's runtime scaling (level-based damage).
 * This is standard behavior for shop-given items on most servers.
 * If you need scaled items, configure a custom delivery-command.
 */
package pl.bellmarket.provider;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
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

public class EliteMobsProvider implements ProductProvider {

    private static final Map<String, Material> MATERIAL_HINTS = new HashMap<>();
    static {
        MATERIAL_HINTS.put("sword",    Material.DIAMOND_SWORD);
        MATERIAL_HINTS.put("axe",      Material.DIAMOND_AXE);
        MATERIAL_HINTS.put("bow",      Material.BOW);
        MATERIAL_HINTS.put("crossbow", Material.CROSSBOW);
        MATERIAL_HINTS.put("helmet",   Material.DIAMOND_HELMET);
        MATERIAL_HINTS.put("chestplate", Material.DIAMOND_CHESTPLATE);
        MATERIAL_HINTS.put("chest",    Material.DIAMOND_CHESTPLATE);
        MATERIAL_HINTS.put("leggings", Material.DIAMOND_LEGGINGS);
        MATERIAL_HINTS.put("boots",    Material.DIAMOND_BOOTS);
        MATERIAL_HINTS.put("trident",  Material.TRIDENT);
        MATERIAL_HINTS.put("book",     Material.ENCHANTED_BOOK);
        MATERIAL_HINTS.put("shield",   Material.SHIELD);
        MATERIAL_HINTS.put("staff",    Material.STICK);
        MATERIAL_HINTS.put("scrap",    Material.IRON_NUGGET);
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
        boolean preferBellItems = cfg.getBoolean("prefer-bellitems-catalog", true);
        var itemPrices = cfg.getConfigurationSection("item-prices");
        var cats = cfg.getConfigurationSection("categories");

        BellItemsCatalogBridge bellBridge = plugin.getBellItemsCatalogBridge();

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

            String itemType   = itemCfg.getString("itemType", "STONE");
            int tier          = itemCfg.getInt("itemTier", 0);
            String displayName = readDisplayName(itemCfg, itemId);
            String tierKey    = tier > 0 ? "tier_" + tier : "misc";
            Material mat      = parseMaterialFromId(itemType, itemId);
            String itemModel  = itemCfg.getString("customModelData", null);

            byTier.computeIfAbsent(tierKey, k -> new ArrayList<>())
                  .add(new EMItem(itemId, displayName, mat, tier, itemModel, file));
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

                ItemStack giveStack = null;
                Material iconMat = item.material;
                String iconModel = item.itemModel;
                String displayName = item.displayName;
                List<String> lore = List.of(
                    "&7Source: &fEliteMobs",
                    color + display + " &7tier",
                    "",
                    "&6Price: &e" + price + " BellCoins",
                    "",
                    "&aLeft-click &7to purchase"
                );

                if (preferBellItems && bellBridge != null && bellBridge.isAvailable()) {
                    var catalogId = bellBridge.resolveCatalogId(item.id);
                    if (catalogId.isPresent()) {
                        var stackOpt = bellBridge.createShopItem(catalogId.get());
                        var metaOpt = bellBridge.readMeta(catalogId.get());
                        if (stackOpt.isPresent()) {
                            giveStack = stackOpt.get();
                            if (metaOpt.isPresent()) {
                                var meta = metaOpt.get();
                                if (meta.displayName() != null && !meta.displayName().isBlank()) {
                                    displayName = meta.displayName();
                                }
                                if (meta.material() != null) iconMat = meta.material();
                                if (meta.itemModel() != null && !meta.itemModel().isBlank()) {
                                    iconModel = meta.itemModel();
                                }
                            }
                            lore = List.of(
                                "&7Source: &fBellItems",
                                "&7EM id: &8" + item.id,
                                color + display + " &7tier",
                                "",
                                "&6Price: &e" + price + " BellCoins",
                                "",
                                "&aLeft-click &7to purchase"
                            );
                        }
                    }
                }

                if (giveStack == null) {
                    giveStack = buildItemStack(item);
                }

                products.add(new Product.Builder()
                    .id("elitemobs_" + item.id)
                    .type(Product.Type.ITEM)
                    .name(displayName)
                    .lore(lore)
                    .price(price).enabled(true)
                    .iconMaterial(iconMat).iconItemModel(iconModel)
                    .giveItem(giveStack)
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
     * Builds a Bukkit ItemStack from EM's YAML definition.
     * Reads: material, display name, lore, enchantments, custom model data.
     */
    private ItemStack buildItemStack(EMItem item) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(item.sourceFile);

        Material mat = item.material;
        ItemStack stack = new ItemStack(mat, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        // Display name
        String rawName = readDisplayName(cfg, item.id);
        meta.displayName(colorize(rawName).decoration(
            net.kyori.adventure.text.format.TextDecoration.ITALIC, false));

        // Lore
        List<String> loreRaw = cfg.getStringList("lore");
        if (!loreRaw.isEmpty()) {
            meta.lore(loreRaw.stream()
                .map(l -> colorize(l).decoration(
                    net.kyori.adventure.text.format.TextDecoration.ITALIC, false))
                .toList());
        }

        // Custom model data (for resource pack models)
        if (item.itemModel != null) {
            try {
                NamespacedKey key = NamespacedKey.fromString(item.itemModel);
                if (key != null) {
                    meta.setItemModel(key);
                } else {
                    int customData = Integer.parseInt(item.itemModel);
                    meta.setCustomModelData(customData);
                }
            } catch (Throwable ignored) {}
        }

        // Enchantments
        List<String> enchants = cfg.getStringList("enchantments");
        for (String enchantEntry : enchants) {
            try {
                String[] parts = enchantEntry.split("_(?=[^_]+$)"); // split on last _
                if (parts.length == 2) {
                    Enchantment ench = Enchantment.getByKey(
                        NamespacedKey.minecraft(parts[0].toLowerCase()));
                    if (ench != null) {
                        int level = Integer.parseInt(parts[1]);
                        meta.addEnchant(ench, level, true);
                        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                    }
                }
            } catch (Throwable ignored) {}
        }

        stack.setItemMeta(meta);
        return stack;
    }

    private record EMItem(String id, String displayName, Material material,
                          int tier, String itemModel, File sourceFile) {}

    private static String readDisplayName(YamlConfiguration cfg, String fallbackId) {
        for (String field : new String[]{"name", "itemName", "displayName", "item-name"}) {
            String val = cfg.getString(field);
            if (val != null && !val.isEmpty()) {
                return val.replaceAll("&[0-9a-fA-Fk-oK-OrR§]", "").trim();
            }
        }
        return Arrays.stream(fallbackId.split("_"))
            .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
            .reduce((a, b) -> a + " " + b).orElse(fallbackId);
    }

    private static final String TEMPLATE = """
# ============================================================
#  BellMarket - EliteMobs Provider Configuration
# ============================================================
# Items are read directly from plugins/EliteMobs/customitems/
# and given as Bukkit ItemStacks — no EM commands needed.
#
# Note: Items won't have EM's runtime level scaling.
# ============================================================

enabled: true
base-order: 300
default-price: ${DEFAULT_PRICE}
excluded-items: []
categories: {}
item-prices: {}
""";

    private FileConfiguration loadOrCreateProviderConfig(long defaultPrice) {
        File dir = new File(plugin.getDataFolder(), "providers");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "elitemobs.yml");
        if (!f.exists()) {
            try { Files.writeString(f.toPath(), TEMPLATE.replace("${DEFAULT_PRICE}", String.valueOf(defaultPrice))); }
            catch (IOException e) { plugin.getLogger().warning("[EliteMobsProvider] " + e.getMessage()); }
        }
        return YamlConfiguration.loadConfiguration(f);
    }

    private static String formatTier(String t) {
        return t.startsWith("tier_") ? "Tier " + t.substring(5) : capitalize(t);
    }

    private static String tierColor(String t) {
        if (!t.startsWith("tier_")) return "&7";
        try {
            return switch (Integer.parseInt(t.substring(5))) {
                case 1 -> "&7"; case 2 -> "&a"; case 3 -> "&9";
                case 4 -> "&5"; case 5 -> "&6"; case 6 -> "&c";
                default -> "&d";
            };
        } catch (NumberFormatException e) { return "&7"; }
    }

    private static Material tierIcon(String t) {
        if (!t.startsWith("tier_")) return Material.PAPER;
        try {
            return switch (Integer.parseInt(t.substring(5))) {
                case 1 -> Material.STONE_SWORD;  case 2 -> Material.IRON_SWORD;
                case 3 -> Material.GOLDEN_SWORD; case 4 -> Material.DIAMOND_SWORD;
                default -> Material.NETHERITE_SWORD;
            };
        } catch (NumberFormatException e) { return Material.PAPER; }
    }

    private static Material parseMaterialFromId(String itemType, String itemId) {
        String lower = itemId.toLowerCase();
        for (Map.Entry<String, Material> e : MATERIAL_HINTS.entrySet())
            if (lower.contains(e.getKey())) return e.getValue();
        try { return Material.valueOf(itemType.toUpperCase()); }
        catch (IllegalArgumentException ex) { return Material.IRON_NUGGET; }
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

    private static Component colorize(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s == null ? "" : s);
    }
}
