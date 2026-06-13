/*
 * BellMarket - FreeMinecraftModelsProvider (SESJA-3 FIX4)
 *
 * FIX: Filesystem scan now finds BOTH:
 *   - .bbmodel files directly in models/ → model ID = filename
 *   - directories containing .bbmodel → model ID = dirname
 *   - subdirectories recursively
 *
 * FMM API fix: getConvertedFileModels() is NOT static — needs
 * instance lookup via FMM plugin class.
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
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;

public class FreeMinecraftModelsProvider implements ProductProvider {

    private final BellMarket plugin;

    public FreeMinecraftModelsProvider(BellMarket plugin) { this.plugin = plugin; }

    @Override public String getProviderId() { return "fmm"; }

    @Override
    public boolean isAvailable() {
        Plugin fmm = plugin.getServer().getPluginManager().getPlugin("FreeMinecraftModels");
        return fmm != null && fmm.isEnabled();
    }

    @Override
    public List<Category> generateCategories(long defaultPrice) {
        Plugin fmm = plugin.getServer().getPluginManager().getPlugin("FreeMinecraftModels");
        if (fmm == null) return Collections.emptyList();

        // Get model IDs - try API first, fall back to filesystem
        Set<String> modelIds = getModelIds(fmm);
        if (modelIds.isEmpty()) {
            plugin.getLogger().info("[FMMProvider] No models found.");
            return Collections.emptyList();
        }
        plugin.getLogger().info("[FMMProvider] Found " + modelIds.size() + " models.");

        FileConfiguration cfg = loadOrCreateProviderConfig(fmm, modelIds, defaultPrice);
        if (!cfg.getBoolean("enabled", true)) return Collections.emptyList();

        long globalDefault    = cfg.getLong("default-price", defaultPrice);
        int  baseOrder        = cfg.getInt("base-order", 400);
        List<String> excluded = cfg.getStringList("excluded-models");
        List<String> prefixes = cfg.getStringList("include-prefixes");
        var modelsCfg         = cfg.getConfigurationSection("models");
        var modelPrices       = cfg.getConfigurationSection("model-prices");
        var cats              = cfg.getConfigurationSection("categories");

        List<String> filtered = modelIds.stream()
            .filter(id -> !excluded.contains(id))
            .filter(id -> prefixes.isEmpty() || prefixes.stream()
                .anyMatch(p -> id.toLowerCase().startsWith(p.toLowerCase())))
            .sorted().toList();

        Map<String, List<String>> byCategory = new LinkedHashMap<>();
        for (String id : filtered) {
            byCategory.computeIfAbsent(categoryOf(id), k -> new ArrayList<>()).add(id);
        }

        List<Category> out = new ArrayList<>();
        int orderCursor = baseOrder;

        for (Map.Entry<String, List<String>> entry : byCategory.entrySet()) {
            String catKey = entry.getKey();
            List<String> models = entry.getValue();
            var catCfg = cats != null ? cats.getConfigurationSection(catKey) : null;

            String display  = catCfg != null ? catCfg.getString("display-name", capitalize(catKey)) : capitalize(catKey);
            String color    = catCfg != null ? catCfg.getString("color", "&b") : "&b";
            Material icon   = parseMaterial(catCfg != null ? catCfg.getString("icon") : null, Material.PLAYER_HEAD);
            long catDefault = catCfg != null ? catCfg.getLong("default-price", globalDefault) : globalDefault;

            List<Product> products = new ArrayList<>();
            for (String modelId : models) {
                var mCfg = modelsCfg != null ? modelsCfg.getConfigurationSection(modelId) : null;
                if (mCfg != null && !mCfg.getBoolean("show-in-shop", true)) continue;

                long price = (modelPrices != null && modelPrices.contains(modelId))
                    ? modelPrices.getLong(modelId) : catDefault;

                String displayName = mCfg != null
                    ? mCfg.getString("display-name", humanize(modelId))
                    : humanize(modelId);
                Material iconMat = parseMaterial(mCfg != null ? mCfg.getString("icon") : null, Material.PLAYER_HEAD);
                String itemModel = "freeminecraftmodels:display/" + modelId.toLowerCase();

                List<String> commands = (mCfg != null && mCfg.contains("commands"))
                    ? mCfg.getStringList("commands")
                    : List.of("fmm spawn {player} " + modelId);

                products.add(new Product.Builder()
                    .id("fmm_" + modelId)
                    .type(Product.Type.COMMAND)
                    .name(color + displayName)
                    .lore(List.of(
                        "&7Source: &fFreeMinecraftModels",
                        "&7Model: &8" + modelId,
                        "",
                        "&6Price: &e" + price + " BellCoins",
                        "",
                        "&aLeft-click &7to purchase"
                    ))
                    .price(price).enabled(true)
                    .iconMaterial(iconMat).iconItemModel(itemModel)
                    .commands(commands)
                    .currency(Currency.BELLCOINS).providerSource("fmm")
                    .build());
            }
            if (products.isEmpty()) continue;

            String catName = color + "✦ " + display;
            out.add(new Category("fmm_" + catKey, catName, display,
                orderCursor, true, icon, catName,
                List.of("&7FMM Models", "&7Models: &f" + products.size(), "", "&eClick to open"),
                products));
            orderCursor += 10;
        }

        plugin.getLogger().info("[FMMProvider] Generated " + out.size() + " categories.");
        return out;
    }

    /**
     * Tries FMM API (instance method on ModeledEntityManager),
     * falls back to comprehensive filesystem scan.
     */
    @SuppressWarnings("unchecked")
    private Set<String> getModelIds(Plugin fmm) {
        // Try API — getConvertedFileModels() on ModeledEntityManager instance
        try {
            Class<?> cls = Class.forName("com.magmaguy.freeminecraftmodels.api.ModeledEntityManager");
            // Try as static method first
            Method m = cls.getMethod("getConvertedFileModels");
            Object result = m.invoke(null);
            if (result instanceof Map<?,?> map && !map.isEmpty()) {
                Set<String> ids = new HashSet<>();
                map.keySet().forEach(k -> ids.add(String.valueOf(k)));
                plugin.getLogger().info("[FMMProvider] API (static) returned " + ids.size() + " models.");
                return ids;
            }
        } catch (Throwable ignored) {}

        // Try as instance method via plugin
        try {
            Class<?> cls = Class.forName("com.magmaguy.freeminecraftmodels.api.ModeledEntityManager");
            Object instance = fmm; // try plugin as instance
            Method m = cls.getMethod("getConvertedFileModels");
            Object result = m.invoke(instance);
            if (result instanceof Map<?,?> map && !map.isEmpty()) {
                Set<String> ids = new HashSet<>();
                map.keySet().forEach(k -> ids.add(String.valueOf(k)));
                return ids;
            }
        } catch (Throwable ignored) {}

        // Filesystem scan — comprehensive
        plugin.getLogger().info("[FMMProvider] Using filesystem scan for models.");
        return scanModels(fmm);
    }

    /**
     * Comprehensive scan: finds model IDs from both file and directory layouts.
     *
     * Supports:
     *   models/chair.bbmodel          → id "chair"
     *   models/furniture/chair/       → id "furniture_chair" (dir with bbmodel inside)
     *   models/furniture_chair.bbmodel → id "furniture_chair"
     */
    private Set<String> scanModels(Plugin fmm) {
        Set<String> ids = new LinkedHashSet<>();
        File modelsDir = new File(fmm.getDataFolder(), "models");
        if (!modelsDir.exists()) return ids;
        scanDir(modelsDir, modelsDir, ids);
        return ids;
    }

    private void scanDir(File root, File current, Set<String> results) {
        File[] children = current.listFiles();
        if (children == null) return;

        for (File child : children) {
            if (child.isFile() && child.getName().endsWith(".bbmodel")) {
                // .bbmodel file → model ID = path from root, no extension
                String relPath = root.toPath().relativize(child.toPath())
                    .toString().replace(File.separatorChar, '_')
                    .replaceAll("\\.bbmodel$", "");
                results.add(relPath);

            } else if (child.isDirectory()) {
                // Check if this directory contains a .bbmodel at its root
                File[] bmFiles = child.listFiles(f -> f.getName().endsWith(".bbmodel"));
                boolean isModelDir = bmFiles != null && bmFiles.length > 0;

                if (isModelDir) {
                    // Directory is a model
                    String relPath = root.toPath().relativize(child.toPath())
                        .toString().replace(File.separatorChar, '_');
                    results.add(relPath);
                } else {
                    // Recurse into subdirectory (might contain models)
                    scanDir(root, child, results);
                }
            }
        }
    }

    private static final String TEMPLATE = """
# ============================================================
#  BellMarket - FreeMinecraftModels Provider Configuration
# ============================================================
enabled: true
base-order: 400
default-price: ${DEFAULT_PRICE}

# Only include models starting with these prefixes (empty = all)
include-prefixes: []
excluded-models: []
categories: {}
model-prices: {}

# Configure per model:
# models:
#   furniture_chair:
#     display-name: "Wooden Chair"
#     icon: OAK_SLAB
#     show-in-shop: true
#     commands:
#       - "fmm spawn {player} furniture_chair"
models: {}
${DETECTED}""";

    private FileConfiguration loadOrCreateProviderConfig(Plugin fmm, Set<String> modelIds, long defaultPrice) {
        File dir = new File(plugin.getDataFolder(), "providers");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "fmm.yml");
        if (!f.exists()) {
            try {
                StringBuilder sb = new StringBuilder();
                if (!modelIds.isEmpty()) {
                    sb.append("\n# ─── Detected models (").append(modelIds.size()).append(") ───\n");
                    new TreeSet<>(modelIds).forEach(id ->
                        sb.append("# model-prices:\n#   ").append(id)
                          .append(": ").append(defaultPrice).append("\n"));
                }
                Files.writeString(f.toPath(), TEMPLATE
                    .replace("${DEFAULT_PRICE}", String.valueOf(defaultPrice))
                    .replace("${DETECTED}", sb.toString()));
            } catch (IOException e) {
                plugin.getLogger().warning("[FMMProvider] " + e.getMessage());
            }
        }
        return YamlConfiguration.loadConfiguration(f);
    }

    private static String categoryOf(String id) {
        int idx = id.indexOf('_');
        return idx > 0 ? id.substring(0, idx).toLowerCase() : "misc";
    }

    private static String humanize(String id) {
        return Arrays.stream(id.split("_"))
            .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
            .reduce((a, b) -> a + " " + b).orElse(id);
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
