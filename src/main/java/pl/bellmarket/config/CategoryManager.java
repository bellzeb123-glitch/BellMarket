package pl.bellmarket.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.model.Category;
import pl.bellmarket.model.Product;

import java.io.File;
import java.util.*;

public class CategoryManager {

    private final BellMarket plugin;
    private final List<Category> categories = new ArrayList<>();
    private final Map<String, String> categoryPermissions = new HashMap<>();

    public CategoryManager(BellMarket plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        categories.clear();
        categoryPermissions.clear();

        File categoriesDir = new File(plugin.getDataFolder(), "categories");
        if (!categoriesDir.exists()) categoriesDir.mkdirs();

        File[] files = categoriesDir.listFiles(f -> f.getName().endsWith(".yml"));
        if (files == null) return;

        List<Category> loaded = new ArrayList<>();
        for (File file : files) {
            try {
                Category cat = loadCategory(file);
                if (cat != null && cat.isEnabled()) {
                    loaded.add(cat);
                    if (cat.getRequiredPermission() != null && !cat.getRequiredPermission().isEmpty()) {
                        categoryPermissions.put(cat.getId(), cat.getRequiredPermission());
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error loading category " + file.getName() + ": " + e.getMessage());
            }
        }

        loaded.sort(Comparator.comparingInt(Category::getOrder));
        categories.addAll(loaded);
        plugin.getLogger().info("Categories loaded: " + categories.size());
    }

    private Category loadCategory(File file) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = config.getConfigurationSection("category");
        if (sec == null) {
            plugin.getLogger().warning("No 'category' section in " + file.getName());
            return null;
        }

        String id          = file.getName().replace(".yml", "");
        String name        = sec.getString("name", id);
        String displayName = sec.getString("display-name", name);
        int order          = sec.getInt("order", 50);
        boolean enabled    = sec.getBoolean("enabled", true);
        String requiredPerm = sec.getString("required-permission", "");

        // Icon
        Material iconMat = Material.PAPER;
        String iconName  = name;
        List<String> iconLore = new ArrayList<>();

        ConfigurationSection iconSec = sec.getConfigurationSection("icon");
        if (iconSec != null) {
            String matName = iconSec.getString("material", "PAPER");
            try { iconMat = Material.valueOf(matName.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { plugin.getLogger().warning("Invalid material: " + matName); }
            iconName  = iconSec.getString("name", name);
            iconLore  = iconSec.getStringList("lore");
        }

        // Products
        List<Product> products = new ArrayList<>();
        ConfigurationSection productsSec = config.getConfigurationSection("products");
        if (productsSec != null) {
            for (String productId : productsSec.getKeys(false)) {
                try {
                    Product p = loadProduct(productId, productsSec.getConfigurationSection(productId));
                    if (p != null) products.add(p);
                } catch (Exception e) {
                    plugin.getLogger().warning("Error loading product " + productId + ": " + e.getMessage());
                }
            }
        }

        Category cat = new Category(id, name, displayName, order, enabled, iconMat, iconName, iconLore, products);
        if (requiredPerm != null && !requiredPerm.isEmpty()) {
            cat.setRequiredPermission(requiredPerm);
        }
        return cat;
    }

    private Product loadProduct(String productId, ConfigurationSection ps) {
        if (ps == null) return null;

        String typeName = ps.getString("type", "COMMAND");
        Product.Type type = Product.Type.COMMAND;
        try { type = Product.Type.valueOf(typeName.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown product type: " + typeName + " for product: " + productId);
        }

        String name        = ps.getString("name", productId);
        long price         = ps.getLong("price", 0);
        boolean enabled    = ps.getBoolean("enabled", true);
        String requiredPerm = ps.getString("required-permission", "");
        String currencyRaw = ps.getString("currency", "bellcoins");
        Currency currency  = Currency.parse(currencyRaw);
        List<String> lore  = ps.getStringList("lore");
        List<String> commands = ps.getStringList("commands");

        // Icon
        Material iconMat  = Material.PAPER;
        String iconModel  = ps.getString("item-model", "");
        String iconNameStr = ps.getString("icon.name", name);

        ConfigurationSection iconSec = ps.getConfigurationSection("icon");
        if (iconSec != null) {
            String matName = iconSec.getString("material", "PAPER");
            try { iconMat = Material.valueOf(matName.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { plugin.getLogger().warning("Invalid icon material: " + matName); }
            iconNameStr = iconSec.getString("name", name);
            if (iconModel.isEmpty()) iconModel = iconSec.getString("item-model", "");
        }

        Product.Builder builder = new Product.Builder()
            .id(productId).type(type).name(name).lore(lore).price(price).enabled(enabled)
            .iconMaterial(iconMat).iconItemModel(iconModel).iconName(iconNameStr)
            .currency(currency).requiredPermission(requiredPerm.isEmpty() ? null : requiredPerm)
            .commands(commands).manual(true).providerSource("manual");

        if (type == Product.Type.SKIN_TOKEN) {
            builder.skinId(ps.getString("skin-id", ""))
                   .includeChangeToken(ps.getBoolean("include-change-token", false));
        }

        if (type == Product.Type.ITEM) {
            ConfigurationSection itemSec = ps.getConfigurationSection("item");
            if (itemSec != null) {
                String matName = itemSec.getString("material", "STONE");
                try {
                    Material mat = Material.valueOf(matName.toUpperCase(Locale.ROOT));
                    int amount = itemSec.getInt("amount", 1);
                    builder.giveItem(new org.bukkit.inventory.ItemStack(mat, amount));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid item material: " + matName);
                }
            }
        }

        return builder.build();
    }

    public List<Category> getCategories()  { return categories; }

    public Category getCategory(String id) {
        return categories.stream().filter(c -> c.getId().equals(id)).findFirst().orElse(null);
    }

    public boolean canSee(Player player, Category cat) {
        if (cat == null) return false;
        String perm = cat.getRequiredPermission();
        if (perm == null || perm.isEmpty()) return true;
        return player.hasPermission(perm);
    }

    public List<Category> getVisibleCategories(Player player) {
        return categories.stream().filter(c -> canSee(player, c)).toList();
    }
}
