package pl.bellmarket.provider;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.model.Category;
import pl.bellmarket.model.Product;

import java.util.*;
import java.util.logging.Level;

public class MythicMobsProvider implements ProductProvider {

    private final BellMarket plugin;

    public MythicMobsProvider(BellMarket plugin) { this.plugin = plugin; }

    @Override public String getProviderId() { return "mythicmobs"; }

    @Override
    public boolean isAvailable() {
        var p = Bukkit.getPluginManager().getPlugin("MythicMobs");
        return p != null && p.isEnabled();
    }

    @Override
    public List<Category> generateCategories(long defaultPrice) {
        if (!isAvailable()) return Collections.emptyList();

        FileConfiguration cfg = plugin.getProviderRegistry().loadOrCreateProviderConfig("mythicmobs");
        if (!cfg.getBoolean("enabled", true)) return Collections.emptyList();

        long providerDefaultPrice = cfg.getLong("default-price", defaultPrice);
        List<String> includePrefixes = cfg.getStringList("include-prefixes");
        List<String> excludedItems   = cfg.getStringList("excluded-items");
        int baseOrder = cfg.getInt("base-order", 30);
        ConfigurationSection itemPrices = cfg.getConfigurationSection("item-prices");

        List<String> itemNames = getMythicItemNames();
        if (itemNames.isEmpty()) {
            plugin.getLogger().info("[MythicMobs] No items found.");
            return Collections.emptyList();
        }

        // Filter by include-prefixes
        if (!includePrefixes.isEmpty()) {
            itemNames = itemNames.stream()
                    .filter(n -> includePrefixes.stream().anyMatch(p -> n.toLowerCase().startsWith(p.toLowerCase())))
                    .toList();
        }

        // Group by prefix (categoryOf)
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String itemName : itemNames) {
            if (excludedItems.contains(itemName)) continue;
            String cat = categoryOf(itemName, cfg);
            grouped.computeIfAbsent(cat, k -> new ArrayList<>()).add(itemName);
        }

        List<Category> result = new ArrayList<>();
        int order = baseOrder;

        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            String catKey = entry.getKey();
            ConfigurationSection catCfg = cfg.isConfigurationSection("categories." + catKey)
                    ? cfg.getConfigurationSection("categories." + catKey) : null;

            String displayName = catCfg != null ? catCfg.getString("display-name",
                    ProductProviderRegistry.capitalize(catKey)) : ProductProviderRegistry.capitalize(catKey);
            String color = catCfg != null ? catCfg.getString("color", "&a") : "&a";
            Material icon = catCfg != null
                    ? ProductProviderRegistry.parseMaterial(catCfg.getString("icon"), Material.EMERALD)
                    : Material.EMERALD;

            List<Product> products = new ArrayList<>();
            for (String itemName : entry.getValue()) {
                long price = itemPrices != null ? itemPrices.getLong(itemName, providerDefaultPrice) : providerDefaultPrice;
                Product p = buildProduct(itemName, price, color, catKey);
                if (p != null) products.add(p);
            }

            if (products.isEmpty()) continue;

            result.add(new Category(
                    "mythicmobs_" + catKey,
                    "mythicmobs_" + catKey,
                    color + displayName,
                    order++,
                    true,
                    icon,
                    color + displayName,
                    List.of("&7MythicMobs Items",
                            "&7Items: &f" + products.size(),
                            "",
                            "&eClick to open"),
                    products
            ));
        }

        plugin.getLogger().info("[MythicMobs] Loaded " + result.size() + " categories, "
                + result.stream().mapToInt(c -> c.getProducts().size()).sum() + " items.");
        return result;
    }

    // ─── reflection ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> getMythicItemNames() {
        try {
            var mm = Bukkit.getPluginManager().getPlugin("MythicMobs");
            if (mm == null) return List.of();
            Object inst = mm.getClass().getMethod("inst").invoke(null);
            Object itemManager = inst.getClass().getMethod("getItemManager").invoke(inst);
            Object items = itemManager.getClass().getMethod("getItems").invoke(itemManager);
            List<String> names = new ArrayList<>();
            for (Object item : (Iterable<?>) items) {
                try {
                    String internalName = (String) item.getClass().getMethod("getInternalName").invoke(item);
                    names.add(internalName);
                } catch (Exception ignored) {}
            }
            return names;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[MythicMobs] Could not load items via reflection: " + e.getMessage());
            return List.of();
        }
    }

    private String categoryOf(String itemName, FileConfiguration cfg) {
        if (!cfg.getBoolean("group-by-prefix", true)) return "items";
        int idx = itemName.indexOf('_');
        return idx > 0 ? itemName.substring(0, idx).toLowerCase() : "misc";
    }

    private Product buildProduct(org.bukkit.plugin.Plugin mmPlugin, String itemName, long price, String color, String catKey) {
        return buildProduct(itemName, price, color, catKey);
    }

    private Product buildProduct(String itemName, long price, String color, String catKey) {
        // Try to get a real ItemStack from MM for the icon
        ItemStack icon = tryGetMMItemStack(itemName);
        Material iconMat = icon != null ? icon.getType() : Material.PAPER;
        if (iconMat == Material.AIR) iconMat = Material.PAPER;

        // Display name from ItemStack meta
        String displayName = color + ProductProviderRegistry.capitalize(itemName);
        if (icon != null) {
            ItemMeta meta = icon.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                // Convert Adventure component to legacy string
                try {
                    displayName = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacyAmpersand().serialize(meta.displayName());
                } catch (Exception ignored) {}
            }
        }

        return new Product.Builder()
                .id("mm_" + itemName)
                .type(Product.Type.COMMAND)
                .name(displayName)
                .lore(List.of("&7Source: &fMythicMobs",
                              "&6Price: &e" + price + " &7" + plugin.getLang().getCurrencyName(),
                              "",
                              "&aLeft-click &7to purchase"))
                .price(price)
                .iconMaterial(iconMat)
                .commands(List.of("mmoitems give {player} " + itemName))
                .currency(Currency.BELLCOINS)
                .providerSource("mythicmobs")
                .build();
    }

    @SuppressWarnings("unchecked")
    private ItemStack tryGetMMItemStack(String itemName) {
        try {
            var mm = Bukkit.getPluginManager().getPlugin("MythicMobs");
            if (mm == null) return null;
            Object inst = mm.getClass().getMethod("inst").invoke(null);
            Object itemManager = inst.getClass().getMethod("getItemManager").invoke(inst);
            Object optional = itemManager.getClass().getMethod("getItem", String.class)
                    .invoke(itemManager, itemName);
            // Optional<MythicItem>
            if (optional instanceof Optional<?> opt && opt.isPresent()) {
                Object mythicItem = opt.get();
                ItemStack generated = (ItemStack) mythicItem.getClass()
                        .getMethod("generateItemStack", int.class).invoke(mythicItem, 1);
                return generated;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
