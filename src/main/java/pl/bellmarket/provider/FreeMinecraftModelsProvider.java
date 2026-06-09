/*
 * BellMarket - FreeMinecraftModelsProvider (SESJA-3 FIX3)
 *
 * REWRITE — uses FMM API instead of directory scanning:
 *   ModeledEntityManager.getConvertedFileModels().keySet() = real loaded model IDs
 *   This is the source of truth — not the filesystem structure.
 *
 * Model naming: "furniture_chair", "furniture_table" etc. → category "furniture"
 * Delivery: /fmm spawn {player} {modelId} at player location
 */
package pl.bellmarket.provider;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
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

        // Get ALL loaded model IDs via FMM API
        Set<String> modelIds = getLoadedModelIds();
        if (modelIds.isEmpty()) {
            plugin.getLogger().info("[FMMProvider] No models found via API. Check FMM is loaded properly.");
            return Collections.emptyList();
        }
        plugin.getLogger().info("[FMMProvider] Found " + modelIds.size() + " loaded models via API.");

        FileConfiguration cfg = loadOrCreateProviderConfig(modelIds, defaultPrice);
        if (!cfg.getBoolean("enabled", true)) return Collections.emptyList();

        long globalDefault    = cfg.getLong("default-price", defaultPrice);
        int  baseOrder        = cfg.getInt("base-order", 400);
        List<String> excluded = cfg.getStringList("excluded-models");
        List<String> prefixes = cfg.getStringList("include-prefixes");
        var modelsCfg         = cfg.getConfigurationSection("models");
        var modelPrices       = cfg.getConfigurationSection("model-prices");
        var cats              = cfg.getConfigurationSection("categories");

        // Filter
        List<String> filteredIds = modelIds.stream()
            .filter(id -> !excluded.contains(id))
            .filter(id -> prefixes.isEmpty() || prefixes.stream()
                .anyMatch(p -> id.toLowerCase().startsWith(p.toLowerCase())))
            .sorted()
            .toList();

        // Group by prefix
        Map<String, List<String>> byCategory = new LinkedHashMap<>();
        for (String id : filteredIds) {
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
                boolean show = mCfg == null || mCfg.getBoolean("show-in-shop", true);
                if (!show) continue;

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
     * Uses FMM API via reflection to get all loaded model IDs.
     * ModeledEntityManager.getConvertedFileModels() returns Map<String, ?>.
     * Keys = model IDs (e.g. "furniture_chair", "weapon_sword").
     */
    @SuppressWarnings("unchecked")
    private Set<String> getLoadedModelIds() {
        try {
            Class<?> managerClass = Class.forName(
                "com.magmaguy.freeminecraftmodels.api.ModeledEntityManager");
            Method getModels = managerClass.getMethod("getConvertedFileModels");
            Map<String, ?> models = (Map<String, ?>) getModels.invoke(null);
            return new HashSet<>(models.keySet());
        } catch (Throwable e) {
            // fallback: scan filesystem
            plugin.getLogger().warning("[FMMProvider] API not available (" + e.getMessage()
                + "), falling back to filesystem scan.");
            return scanModelDirectories();
        }
    }

    /** Filesystem fallback: recursively find directories containing .bbmodel files. */
    private Set<String> scanModelDirectories() {
        Set<String> ids = new LinkedHashSet<>();
        Plugin fmm = plugin.getServer().getPluginManager().getPlugin("FreeMinecraftModels");
        if (fmm == null) return ids;
        File modelsDir = new File(fmm.getDataFolder(), "models");
        if (!modelsDir.exists()) return ids;
        scanRecursive(modelsDir, modelsDir, ids);
        return ids;
    }

    private void scanRecursive(File root, File dir, Set<String> result) {
        File[] children = dir.listFiles();
        if (children == null) return;
        // If this directory contains a .bbmodel file → it's a model
        boolean isModel = Arrays.stream(children).anyMatch(f -> f.getName().endsWith(".bbmodel"));
        if (isModel && !dir.equals(root)) {
            // Use path relative to root as model ID (e.g. "furniture/chair" or "chair")
            String relPath = root.toPath().relativize(dir.toPath())
                .toString().replace(File.separatorChar, '_');
            result.add(relPath);
            return;
        }
        // Otherwise recurse into subdirectories
        for (File child : children) {
            if (child.isDirectory()) scanRecursive(root, child, result);
        }
    }

    private static final String TEMPLATE = """
# ============================================================
#  BellMarket - FreeMinecraftModels Provider Configuration
# ============================================================
# Uses FMM API to list all loaded models — not filesystem scan.
# Model IDs come from ModeledEntityManager.getConvertedFileModels()
#
# Delivery command: /fmm spawn {player} {model}
# {player} = player name, {model} = model ID
# ============================================================

enabled: true
base-order: 400
default-price: ${DEFAULT_PRICE}

# Only include models starting with these prefixes (empty = all)
include-prefixes: []
excluded-models: []
categories: {}
model-prices: {}

# Configure per-model: icon, display name, custom commands
# models:
#   furniture_chair:
#     display-name: "Wooden Chair"
#     icon: OAK_SLAB
#     show-in-shop: true
#     commands:
#       - "fmm spawn {player} furniture_chair"
models: {}

${DETECTED_MODELS}""";

    private FileConfiguration loadOrCreateProviderConfig(Set<String> modelIds, long defaultPrice) {
        File dir = new File(plugin.getDataFolder(), "providers");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "fmm.yml");
        if (!f.exists()) {
            try {
                StringBuilder detected = new StringBuilder();
                if (!modelIds.isEmpty()) {
                    detected.append("\n# ─── Detected models (").append(modelIds.size()).append(") ───\n");
                    detected.append("# Move entries to models: section above to configure them:\n");
                    new TreeSet<>(modelIds).forEach(id ->
                        detected.append("#   ").append(id).append(": ").append(defaultPrice).append("\n"));
                }
                Files.writeString(f.toPath(), TEMPLATE
                    .replace("${DEFAULT_PRICE}", String.valueOf(defaultPrice))
                    .replace("${DETECTED_MODELS}", detected.toString()));
            } catch (IOException e) {
                plugin.getLogger().warning("[FMMProvider] Config write failed: " + e.getMessage());
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
