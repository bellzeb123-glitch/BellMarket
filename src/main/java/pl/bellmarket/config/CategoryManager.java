/*
 * BellMarket - CategoryManager
 *
 * Loads category YAML files from plugins/BellMarket/categories/ and parses
 * each into a Category object containing Products.
 *
 * SESJA-1 INCLUDED:
 *   + Parses new YAML field `currency` (default: bellcoins)
 *   + Parses new YAML field `required-permission` (default: null)
 *   + Sets Product.providerSource = "manual" for file-loaded products
 *   + Handles new Product.Type.VIP_EXCLUSIVE (delivers via commands list)
 *
 * This file is a complete drop-in replacement.
 */
package pl.bellmarket.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.model.Category;
import pl.bellmarket.model.Product;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class CategoryManager {

    private final BellMarket plugin;
    private final List<Category> categories = new ArrayList<>();
    private File categoriesDir;

    public CategoryManager(BellMarket plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        categories.clear();
        categoriesDir = new File(plugin.getDataFolder(), "categories");
        if (!categoriesDir.exists()) {
            categoriesDir.mkdirs();
        }

        File[] files = categoriesDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            plugin.getLogger().warning("No category files found in categories/");
            return;
        }

        // Stable order by filename
        Arrays.sort(files, Comparator.comparing(File::getName));

        for (File file : files) {
            try {
                Category cat = loadCategory(file);
                if (cat != null && cat.isEnabled()) {
                    categories.add(cat);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error loading category " + file.getName() + ": " + e.getMessage());
            }
        }

        // Sort categories by their `order` field (low to high)
        categories.sort(Comparator.comparingInt(Category::getOrder));

        plugin.getLogger().info("Categories loaded: " + categories.size());
    }

    private Category loadCategory(File file) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection cat = config.getConfigurationSection("category");
        if (cat == null) {
            plugin.getLogger().warning("No 'category' section in " + file.getName());
            return null;
        }

        String id          = file.getName().replace(".yml", "");
        String name        = cat.getString("name", id);
        String displayName = cat.getString("display-name", name);
        int    order       = cat.getInt("order", 0);
        boolean enabled    = cat.getBoolean("enabled", true);

        // Icon
        Material iconMat = Material.PAPER;
        String iconName  = null;
        List<String> iconLore = new ArrayList<>();
        ConfigurationSection iconSec = cat.getConfigurationSection("icon");
        if (iconSec != null) {
            String matName = iconSec.getString("material", "PAPER");
            try {
                iconMat = Material.valueOf(matName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid material: " + matName);
            }
            iconName = iconSec.getString("name");
            iconLore = iconSec.getStringList("lore");
        }

        // Products
        List<Product> ps = new ArrayList<>();
        ConfigurationSection productsSec = config.getConfigurationSection("products");
        if (productsSec != null) {
            for (String productId : productsSec.getKeys(false)) {
                ConfigurationSection sec = productsSec.getConfigurationSection(productId);
                if (sec == null) continue;
                Product product = loadProduct(productId, sec);
                if (product != null) ps.add(product);
            }
        }

        return new Category(id, name, displayName, order, enabled, iconMat, iconName, iconLore, ps);
    }

    private Product loadProduct(String productId, ConfigurationSection sec) {
        String typeName = sec.getString("type", "ITEM");
        Product.Type type;
        try {
            type = Product.Type.valueOf(typeName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown product type: " + typeName + " for product: " + productId);
            return null;
        }

        long price = sec.getLong("price", 0L);
        String name = sec.getString("name", productId);
        List<String> lore = sec.getStringList("lore");
        boolean enabled = sec.getBoolean("enabled", true);

        // Icon (per-product, overrides category icon in the slot)
        Material iconMat   = Material.PAPER;
        String iconModel   = null;
        String iconNameStr = null;
        ConfigurationSection iconSec = sec.getConfigurationSection("icon");
        if (iconSec != null) {
            String matName = iconSec.getString("material", "PAPER");
            try {
                iconMat = Material.valueOf(matName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid icon material: " + matName);
            }
            iconModel  = iconSec.getString("item-model");
            iconNameStr = iconSec.getString("name");
        }

        Product.Builder builder = new Product.Builder()
            .id(productId)
            .type(type)
            .name(name)
            .lore(lore)
            .price(price)
            .enabled(enabled)
            .iconMaterial(iconMat)
            .iconItemModel(iconModel)
            .iconName(iconNameStr);

        // Type-specific fields
        switch (type) {
            case SKIN_TOKEN -> {
                builder.skinId(sec.getString("skin-id"));
                builder.includeChangeToken(sec.getBoolean("include-change-token", false));
            }
            case COMMAND, MOUNT, VIP_EXCLUSIVE -> {
                builder.commands(sec.getStringList("commands"));
            }
            case ITEM -> {
                ConfigurationSection itemSec = sec.getConfigurationSection("item");
                if (itemSec != null) {
                    String matName = itemSec.getString("material", "STONE");
                    int amount = itemSec.getInt("amount", 1);
                    try {
                        Material mat = Material.valueOf(matName.toUpperCase(Locale.ROOT));
                        builder.giveItem(new ItemStack(mat, amount));
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Invalid item material: " + matName);
                    }
                }
            }
        }

        // ── SESJA-1: parse new fields ─────────────────────────────────────
        // currency: bellcoins | viptoken (default: bellcoins)
        String currencyRaw = sec.getString("currency", null);
        builder.currency(Currency.parse(currencyRaw));

        // required-permission: gate purchase (default: null = open to all)
        String perm = sec.getString("required-permission", null);
        if (perm != null && !perm.isEmpty()) {
            builder.requiredPermission(perm);
        }

        // Mark this product as file-loaded (vs provider-generated)
        builder.providerSource("manual");
        // ──────────────────────────────────────────────────────────────────

        return builder.build();
    }

    public List<Category> getCategories() {
        return categories;
    }

    public Category getCategory(String id) {
        return categories.stream()
            .filter(c -> c.getId().equals(id))
            .findFirst()
            .orElse(null);
    }
}
