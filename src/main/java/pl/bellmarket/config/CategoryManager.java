package pl.bellmarket.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.model.Category;
import pl.bellmarket.model.Product;
import pl.bellmarket.provider.ProductProvider;
import pl.bellmarket.provider.ProductProviderRegistry;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

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
        loadFromFiles();
        loadFromProviders();
        categories.sort(Comparator.comparingInt(Category::getOrder));
        plugin.getLogger().info("Loaded " + categories.size() + " categories total.");
    }

    /**
     * Re-generates all provider configs. Called by /bm generate.
     */
    public void generateAll() {
        categories.clear();
        categoryPermissions.clear();
        loadFromFiles();
        loadFromProviders();
        categories.sort(Comparator.comparingInt(Category::getOrder));
    }

    // ─── loading from YAML files ─────────────────────────────────────────

    private void loadFromFiles() {
        File categoriesDir = new File(plugin.getDataFolder(), "categories");
        if (!categoriesDir.exists()) {
            categoriesDir.mkdirs();
        }

        File[] files = categoriesDir.listFiles((d, n) -> n.endsWith(".yml"));
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
    }

    private Category loadCategory(File file) {
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = cfg.getConfigurationSection("category");
        if (section == null) {
            // Try root-level keys
            section = cfg;
        }

        String id          = section.getString("id", file.getName().replace(".yml", ""));
        String name        = section.getString("name", id);
        String displayName = section.getString("display-name", name);
        int order          = section.getInt("order", 50);
        boolean enabled    = section.getBoolean("enabled", true);

        // Permission
        String reqPerm = section.getString("required-permission", "");
        if (reqPerm != null && !reqPerm.isEmpty()) {
            categoryPermissions.put(id, reqPerm);
        }

        // Icon
        String materialStr = section.getString("icon", "PAPER");
        Material material;
        try { material = Material.valueOf(materialStr.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { material = Material.PAPER; }

        List<String> lore = section.getStringList("lore");

        // Products
        List<Product> products = new ArrayList<>();
        ConfigurationSection productsSection = section.getConfigurationSection("products");
        if (productsSection != null) {
            for (String pKey : productsSection.getKeys(false)) {
                try {
                    Product p = loadProduct(productsSection, pKey, id);
                    if (p != null) products.add(p);
                } catch (Exception e) {
                    plugin.getLogger().warning("Error loading product " + pKey + " in " + id);
                }
            }
        }

        return new Category(id, name, displayName, order, enabled,
                material, displayName, lore, products);
    }

    private Product loadProduct(ConfigurationSection productsSection, String pKey, String catId) {
        ConfigurationSection s = productsSection.getConfigurationSection(pKey);
        if (s == null) return null;

        String typeStr = s.getString("type", "COMMAND");
        Product.Type type;
        try { type = Product.Type.valueOf(typeStr.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { type = Product.Type.COMMAND; }

        String currStr = s.getString("currency", "BELLCOINS");
        Currency currency;
        try { currency = Currency.valueOf(currStr.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { currency = Currency.BELLCOINS; }

        String materialStr = s.getString("material", "PAPER");
        Material mat;
        try { mat = Material.valueOf(materialStr.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { mat = Material.PAPER; }

        return new Product.Builder()
                .id(catId + "_" + pKey)
                .type(type)
                .name(s.getString("name", pKey))
                .lore(s.getStringList("lore"))
                .price(s.getLong("price", 100))
                .enabled(s.getBoolean("enabled", true))
                .iconMaterial(mat)
                .iconItemModel(s.getString("item-model"))
                .iconName(s.getString("icon-name"))
                .skinId(s.getString("skin-id"))
                .includeChangeToken(s.getBoolean("include-change-token", false))
                .commands(s.getStringList("commands"))
                .currency(currency)
                .requiredPermission(s.getString("required-permission"))
                .providerSource(s.getString("provider-source", "yaml"))
                .build();
    }

    // ─── loading from providers ──────────────────────────────────────────

    private void loadFromProviders() {
        long defaultPrice = plugin.getConfig().getLong("provider.default-price", 500);
        for (ProductProvider provider : plugin.getProviderRegistry().getProviders()) {
            if (!provider.isAvailable()) continue;
            try {
                List<Category> providerCats = provider.generateCategories(defaultPrice);
                categories.addAll(providerCats);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Error from provider " + provider.getProviderId(), e);
            }
        }
    }

    // ─── price editor support ────────────────────────────────────────────

    /**
     * Sets price for a product in memory + saves to YAML.
     * Used by PriceEditorGUI.
     */
    public void setProductPrice(String categoryId, String productId, long newPrice) {
        // Update in memory
        for (Category cat : categories) {
            if (!cat.getId().equals(categoryId)) continue;
            for (Product p : cat.getProducts()) {
                if (p.getId().equals(productId)) {
                    p.setPrice(newPrice);
                    break;
                }
            }
        }

        // Save to disk (categories dir only)
        File catDir = new File(plugin.getDataFolder(), "categories");
        File[] files = catDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection section = cfg.getConfigurationSection("category");
            if (section == null) section = cfg;
            String id = section.getString("id", file.getName().replace(".yml", ""));
            if (!id.equals(categoryId)) continue;

            ConfigurationSection products = section.getConfigurationSection("products");
            if (products == null) continue;

            String localKey = productId.startsWith(categoryId + "_")
                    ? productId.substring(categoryId.length() + 1) : productId;
            if (products.contains(localKey)) {
                products.set(localKey + ".price", newPrice);
                try { cfg.save(file); }
                catch (IOException e) { plugin.getLogger().log(Level.WARNING, "Could not save category file", e); }
            }
            break;
        }
    }

    // ─── access ──────────────────────────────────────────────────────────

    public List<Category> getCategories() {
        return Collections.unmodifiableList(categories);
    }

    public Optional<Category> getCategory(String id) {
        return categories.stream().filter(c -> c.getId().equals(id)).findFirst();
    }

    public boolean canSee(org.bukkit.entity.Player player, Category category) {
        String perm = categoryPermissions.get(category.getId());
        return perm == null || perm.isEmpty() || player.hasPermission(perm);
    }
}
