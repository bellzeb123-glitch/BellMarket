package pl.bellmarket.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import pl.bellmarket.BellMarket;
import pl.bellmarket.model.Category;
import pl.bellmarket.model.Product;

import java.io.File;
import java.util.*;

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
        if (!categoriesDir.exists()) categoriesDir.mkdirs();

        File[] files = categoriesDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            plugin.getLogger().warning("No category files found in categories/");
            return;
        }

        Arrays.sort(files, Comparator.comparing(File::getName));

        for (File file : files) {
            try {
                Category category = loadCategory(file);
                if (category != null && category.isEnabled()) {
                    categories.add(category);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error loading category " + file.getName() + ": " + e.getMessage());
            }
        }

        // Sort by order field
        categories.sort(Comparator.comparingInt(Category::getOrder));
        plugin.getLogger().info("Loaded " + categories.size() + " categories.");
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
        int order          = cat.getInt("order", 99);
        boolean enabled    = cat.getBoolean("enabled", true);

        // Icon
        ConfigurationSection iconSec = cat.getConfigurationSection("icon");
        Material iconMat = Material.CHEST;
        String iconName = name;
        List<String> iconLore = new ArrayList<>();
        if (iconSec != null) {
            String matName = iconSec.getString("material", "CHEST");
            try { iconMat = Material.valueOf(matName.toUpperCase()); }
            catch (Exception e) { plugin.getLogger().warning("Invalid material: " + matName); }
            iconName = iconSec.getString("name", name);
            iconLore = iconSec.getStringList("lore");
        }

        // Products
        List<Product> products = new ArrayList<>();
        ConfigurationSection productsSec = config.getConfigurationSection("products");
        if (productsSec != null) {
            for (String productId : productsSec.getKeys(false)) {
                ConfigurationSection ps = productsSec.getConfigurationSection(productId);
                if (ps == null) continue;
                Product product = loadProduct(productId, ps);
                if (product != null) products.add(product);
            }
        }

        return new Category(id, name, displayName, order, enabled,
            iconMat, iconName, iconLore, products);
    }

    private Product loadProduct(String id, ConfigurationSection ps) {
        String typeName = ps.getString("type", "COMMAND").toUpperCase();
        Product.Type type;
        try { type = Product.Type.valueOf(typeName); }
        catch (Exception e) {
            plugin.getLogger().warning("Unknown product type: " + typeName + " for product: " + id);
            return null;
        }

        String name        = ps.getString("name", id);
        List<String> lore  = ps.getStringList("lore");
        long price         = ps.getLong("price", 0);
        boolean enabled    = ps.getBoolean("enabled", true);

        // Icon
        ConfigurationSection iconSec = ps.getConfigurationSection("icon");
        Material iconMat = Material.PAPER;
        String iconModel = null;
        String iconName = null;
        if (iconSec != null) {
            String matName = iconSec.getString("material", "PAPER");
            try { iconMat = Material.valueOf(matName.toUpperCase()); }
            catch (Exception e) { plugin.getLogger().warning("Invalid icon material: " + matName); }
            iconModel = iconSec.getString("item-model");
            iconName  = iconSec.getString("name");
        }

        Product.Builder builder = new Product.Builder()
            .id(id).type(type).name(name).lore(lore).price(price).enabled(enabled)
            .iconMaterial(iconMat).iconItemModel(iconModel).iconName(iconName);

        switch (type) {
            case SKIN_TOKEN -> {
                builder.skinId(ps.getString("skin-id", ""))
                       .includeChangeToken(ps.getBoolean("include-change-token", false));
            }
            case COMMAND, MOUNT -> {
                builder.commands(ps.getStringList("commands"));
            }
            case ITEM -> {
                ConfigurationSection itemSec = ps.getConfigurationSection("item");
                if (itemSec != null) {
                    String matName = itemSec.getString("material", "STONE");
                    Material mat = Material.STONE;
                    try { mat = Material.valueOf(matName.toUpperCase()); }
                    catch (Exception e) { plugin.getLogger().warning("Invalid item material: " + matName); }
                    int amount = itemSec.getInt("amount", 1);
                    ItemStack item = new ItemStack(mat, amount);
                    builder.giveItem(item);
                }
            }
        }

        return builder.build();
    }

    public List<Category> getCategories() { return categories; }

    public Category getCategory(String id) {
        return categories.stream().filter(c -> c.getId().equals(id)).findFirst().orElse(null);
    }
}
