package pl.bellmarket.provider;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.model.Category;
import pl.bellmarket.model.Product;

import java.util.*;
import java.util.logging.Level;

public class FreeMinecraftModelsProvider implements ProductProvider {

    private final BellMarket plugin;

    public FreeMinecraftModelsProvider(BellMarket plugin) { this.plugin = plugin; }

    @Override public String getProviderId() { return "fmm"; }

    @Override
    public boolean isAvailable() {
        var p = Bukkit.getPluginManager().getPlugin("FreeMinecraftModels");
        return p != null && p.isEnabled();
    }

    @Override
    public List<Category> generateCategories(long defaultPrice) {
        if (!isAvailable()) return Collections.emptyList();

        Set<String> modelIds = getModelIds();
        if (modelIds.isEmpty()) {
            plugin.getLogger().info("[FMM] No model IDs found.");
            return Collections.emptyList();
        }
        plugin.getLogger().info("[FMM] Found " + modelIds.size() + " models.");

        FileConfiguration cfg = plugin.getProviderRegistry().loadOrCreateProviderConfig("fmm");
        if (!cfg.getBoolean("enabled", true)) return Collections.emptyList();

        long providerDefaultPrice = cfg.getLong("default-price", defaultPrice);
        int baseOrder = cfg.getInt("base-order", 50);
        List<String> excludedModels = cfg.getStringList("excluded-models");
        List<String> includePrefixes = cfg.getStringList("include-prefixes");
        ConfigurationSection modelPrices = cfg.getConfigurationSection("model-prices");
        ConfigurationSection modelsSection = cfg.getConfigurationSection("models");

        // Filter models
        List<String> filtered = modelIds.stream()
                .sorted()
                .filter(id -> !excludedModels.contains(id))
                .filter(id -> includePrefixes.isEmpty() ||
                        includePrefixes.stream().anyMatch(p -> id.toLowerCase().startsWith(p.toLowerCase())))
                .toList();

        // Group by category prefix (categoryOf)
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String modelId : filtered) {
            String cat = categoryOf(modelId);
            grouped.computeIfAbsent(cat, k -> new ArrayList<>()).add(modelId);
        }

        List<Category> result = new ArrayList<>();
        int order = baseOrder;

        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            String catKey = entry.getKey();
            ConfigurationSection catCfg = cfg.isConfigurationSection("categories." + catKey)
                    ? cfg.getConfigurationSection("categories." + catKey) : null;

            String displayName = catCfg != null ? catCfg.getString("display-name",
                    ProductProviderRegistry.capitalize(catKey)) : ProductProviderRegistry.capitalize(catKey);
            String color = catCfg != null ? catCfg.getString("color", "&b") : "&b";
            Material icon = catCfg != null
                    ? ProductProviderRegistry.parseMaterial(catCfg.getString("icon"), Material.PAPER)
                    : Material.PAPER;

            List<Product> products = new ArrayList<>();
            for (String modelId : entry.getValue()) {
                // Check show-in-shop flag from per-model config
                if (modelsSection != null && modelsSection.isConfigurationSection(modelId)) {
                    if (!modelsSection.getBoolean(modelId + ".show-in-shop", true)) continue;
                }
                long price = modelPrices != null ? modelPrices.getLong(modelId, providerDefaultPrice) : providerDefaultPrice;
                products.add(buildFmmItem(modelId, price, color));
            }

            if (products.isEmpty()) continue;

            result.add(new Category(
                    "fmm_" + catKey,
                    "fmm_" + catKey,
                    color + displayName,
                    order++,
                    true,
                    icon,
                    color + displayName,
                    List.of("&7FMM Models",
                            "&7Models: &f" + products.size(),
                            "",
                            "&eClick to open"),
                    products
            ));
        }

        plugin.getLogger().info("[FMM] Loaded " + result.size() + " categories.");
        return result;
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Set<String> getModelIds() {
        try {
            var fmm = Bukkit.getPluginManager().getPlugin("FreeMinecraftModels");
            if (fmm == null) return Set.of();
            // Try to get model registry via reflection
            Object modelManager = fmm.getClass().getMethod("getModelManager").invoke(fmm);
            Object registry = modelManager.getClass().getMethod("getModels").invoke(modelManager);
            if (registry instanceof Map<?,?> map) {
                Set<String> ids = new LinkedHashSet<>();
                for (Object key : map.keySet()) ids.add(key.toString());
                return ids;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[FMM] Could not get model IDs: " + e.getMessage());
        }
        return Set.of();
    }

    private String categoryOf(String modelId) {
        int idx = modelId.indexOf('_');
        return idx > 0 ? modelId.substring(0, idx).toLowerCase() : "misc";
    }

    private String humanize(String modelId) {
        return Arrays.stream(modelId.split("[_-]"))
                .map(ProductProviderRegistry::capitalize)
                .reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);
    }

    private Product buildFmmItem(String modelId, long price, String color) {
        return new Product.Builder()
                .id("fmm_" + modelId)
                .type(Product.Type.ITEM)
                .name(color + humanize(modelId))
                .lore(List.of("&7Source: &fFreeMinecraftModels",
                              "&7Type: &bProp / Furniture",
                              "&6Price: &e" + price + " &7" + plugin.getLang().getCurrencyName(),
                              "",
                              "&aLeft-click &7to purchase"))
                .price(price)
                .iconMaterial(Material.PAPER)
                .iconItemModel(modelId)
                .commands(List.of("fmm spawn {player} " + modelId))
                .currency(Currency.BELLCOINS)
                .providerSource("fmm")
                .giveItem(null)
                .build();
    }
}
