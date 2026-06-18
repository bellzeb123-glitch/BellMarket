/*
 * BellMarket - CategoryManager (SESJA-2)
 *
 * Sesja 2 additions:
 *   + Permission map: tracks `category.required-permission` from YAML per category id
 *   + canSee(Player, Category) helper used by ShopGUI to hide gated categories
 *   + getVisibleCategories(Player) convenience method
 *
 * Existing behaviour preserved unchanged.
 */
package pl.bellmarket.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.model.Category;
import pl.bellmarket.model.Product;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class CategoryManager {

    private final BellMarket plugin;
    private final List<Category> categories = new ArrayList<>();
    /** SESJA-2: maps category id → required permission (null = visible to all). */
    private final Map<String, String> categoryPermissions = new HashMap<>();
    private File categoriesDir;

    public CategoryManager(BellMarket plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        categories.clear();
        categoryPermissions.clear();
        loadManualCategories();
    }

    /** Replaces in-memory provider categories (SkinStudio etc.) without touching YAML categories. */
    public void removeProviderCategories() {
        categories.removeIf(this::isProviderCategory);
    }

    public void addProviderCategories(List<Category> providerCategories) {
        if (providerCategories == null || providerCategories.isEmpty()) return;
        categories.addAll(providerCategories);
        categories.sort(Comparator.comparingInt(Category::getOrder));
    }

    private void loadManualCategories() {
        categoriesDir = new File(plugin.getDataFolder(), "categories");
        if (!categoriesDir.exists()) {
            categoriesDir.mkdirs();
        }

        File[] files = categoriesDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            plugin.getLogger().warning("No category files found in categories/");
            return;
        }
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
        categories.sort(Comparator.comparingInt(Category::getOrder));
        plugin.getLogger().info("Manual categories loaded: " + categories.size());
    }

    private boolean isProviderCategory(Category c) {
        if (c.getId().startsWith("skinstudio_")) return true;
        return c.getProducts().stream()
            .anyMatch(p -> p.getProviderSource() != null && !"manual".equals(p.getProviderSource()));
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

        // SESJA-2: track required-permission per category id
        String requiredPerm = cat.getString("required-permission", null);
        if (requiredPerm != null && !requiredPerm.isEmpty()) {
            categoryPermissions.put(id, requiredPerm);
        }

        Material iconMat = Material.PAPER;
        String iconName  = null;
        List<String> iconLore = new ArrayList<>();
        ConfigurationSection iconSec = cat.getConfigurationSection("icon");
        if (iconSec != null) {
            String matName = iconSec.getString("material", "PAPER");
            try { iconMat = Material.valueOf(matName.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid material: " + matName);
            }
            iconName = iconSec.getString("name");
            iconLore = iconSec.getStringList("lore");
        }

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
        try { type = Product.Type.valueOf(typeName.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown product type: " + typeName + " for product: " + productId);
            return null;
        }

        long price = sec.getLong("price", 0L);
        String name = sec.getString("name", productId);
        List<String> lore = sec.getStringList("lore");
        boolean enabled = sec.getBoolean("enabled", true);

        Material iconMat   = Material.PAPER;
        String iconModel   = null;
        String iconNameStr = null;
        ConfigurationSection iconSec = sec.getConfigurationSection("icon");
        if (iconSec != null) {
            String matName = iconSec.getString("material", "PAPER");
            try { iconMat = Material.valueOf(matName.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid icon material: " + matName);
            }
            iconModel   = iconSec.getString("item-model");
            iconNameStr = iconSec.getString("name");
        }

        Product.Builder builder = new Product.Builder()
            .id(productId).type(type).name(name).lore(lore).price(price).enabled(enabled)
            .iconMaterial(iconMat).iconItemModel(iconModel).iconName(iconNameStr);

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

        String currencyRaw = sec.getString("currency", null);
        builder.currency(Currency.parse(currencyRaw));

        String perm = sec.getString("required-permission", null);
        if (perm != null && !perm.isEmpty()) builder.requiredPermission(perm);

        builder.providerSource("manual");
        return builder.build();
    }

    public List<Category> getCategories() { return categories; }

    public Category getCategory(String id) {
        return categories.stream()
            .filter(c -> c.getId().equals(id)).findFirst().orElse(null);
    }

    // ─── SESJA-2: permission helpers ──────────────────────────────────────

    /** Returns the required permission for a category (null = open to all). */
    public String getRequiredPermission(String categoryId) {
        return categoryPermissions.get(categoryId);
    }

    /** Whether the player can see this category in the shop UI. */
    public boolean canSee(Player player, Category category) {
        if (category == null) return false;
        String perm = categoryPermissions.get(category.getId());
        return perm == null || player.hasPermission(perm);
    }

    /** Returns categories the player has permission to see, in display order. */
    public List<Category> getVisibleCategories(Player player) {
        return categories.stream()
            .filter(c -> canSee(player, c))
            .collect(Collectors.toList());
    }

    /**
     * Records a permission requirement for a category at runtime.
     * Used by external providers that supply gated categories programmatically.
     */
    public void setRequiredPermission(String categoryId, String permission) {
        if (permission == null || permission.isEmpty()) {
            categoryPermissions.remove(categoryId);
        } else {
            categoryPermissions.put(categoryId, permission);
        }
    }
}
