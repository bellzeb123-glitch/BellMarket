/*
 * BellMarket - FreeMinecraftModelsProvider (SESJA-3)
 *
 * Reads models from FreeMinecraftModels (plugins/FreeMinecraftModels/models/)
 * and exposes them as purchasable products (spawn permissions or tokens).
 *
 * FMM models are NOT items — they're 3D entities rendered via BetterModel-like
 * display entities. "Purchasing" an FMM model typically means:
 *   A) Running a command that spawns the entity for the player
 *   B) Giving the player a permission to use it (e.g. as a pet/mount)
 *
 * Admin configures what command to run per model in providers/fmm.yml.
 * Default: runs "/fmm spawn {player} <modelName>" at player location.
 *
 * Model icons: uses PLAYER_HEAD with setItemModel if BetterModel is present,
 * otherwise falls back to configured material per model.
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

        FileConfiguration cfg = loadOrCreateProviderConfig(fmm, defaultPrice);
        if (!cfg.getBoolean("enabled", true)) return Collections.emptyList();

        long globalDefault = cfg.getLong("default-price", defaultPrice);
        int  baseOrder     = cfg.getInt("base-order", 400);
        List<String> excluded = cfg.getStringList("excluded-models");
        ConfigurationSection modelsCfg  = cfg.getConfigurationSection("models");
        ConfigurationSection modelPrices = cfg.getConfigurationSection("model-prices");
        ConfigurationSection cats        = cfg.getConfigurationSection("categories");

        // Scan FMM models directory
        File modelsDir = new File(fmm.getDataFolder(), "models");
        if (!modelsDir.exists()) return Collections.emptyList();

        // Models are subdirectories in FMM
        File[] modelDirs = modelsDir.listFiles(File::isDirectory);
        if (modelDirs == null || modelDirs.length == 0) return Collections.emptyList();

        // Group models by category prefix
        Map<String, List<FMMModel>> byCategory = new LinkedHashMap<>();
        List<String> includePrefixes = cfg.getStringList("include-prefixes");

        for (File modelDir : modelDirs) {
            String modelName = modelDir.getName();
            if (excluded.contains(modelName)) continue;
            if (!includePrefixes.isEmpty() && includePrefixes.stream()
                .noneMatch(p -> modelName.toLowerCase().startsWith(p.toLowerCase()))) continue;

            String catKey = categoryOf(modelName);

            // Get model-specific config if exists
            ConfigurationSection mCfg = modelsCfg != null ? modelsCfg.getConfigurationSection(modelName) : null;
            boolean showInShop = mCfg == null || mCfg.getBoolean("show-in-shop", true);
            if (!showInShop) continue;

            String displayName = mCfg != null ? mCfg.getString("display-name",
                capitalize(modelName.replace('_', ' ')))
                : capitalize(modelName.replace('_', ' '));
            Material icon = parseMaterial(mCfg != null ? mCfg.getString("icon") : null,
                Material.PLAYER_HEAD);
            String itemModel = mCfg != null ? mCfg.getString("item-model", null) : null;
            // Auto-detect: FMM models often have item key "freeminecraftmodels:display/<name>"
            if (itemModel == null) {
                itemModel = "freeminecraftmodels:display/" + modelName.toLowerCase(Locale.ROOT);
            }

            byCategory.computeIfAbsent(catKey, k -> new ArrayList<>())
                      .add(new FMMModel(modelName, displayName, icon, itemModel));
        }

        List<Category> out = new ArrayList<>();
        int orderCursor = baseOrder;

        for (Map.Entry<String, List<FMMModel>> entry : byCategory.entrySet()) {
            String catKey = entry.getKey();
            List<FMMModel> models = entry.getValue();

            var catCfg  = cats != null ? cats.getConfigurationSection(catKey) : null;
            String display  = catCfg != null ? catCfg.getString("display-name", capitalize(catKey)) : capitalize(catKey);
            String color    = catCfg != null ? catCfg.getString("color", "&b") : "&b";
            Material catIcon = parseMaterial(catCfg != null ? catCfg.getString("icon") : null, Material.PLAYER_HEAD);
            long catDefault = catCfg != null ? catCfg.getLong("default-price", globalDefault) : globalDefault;

            List<Product> products = new ArrayList<>();
            for (FMMModel model : models) {
                long price = (modelPrices != null && modelPrices.contains(model.id))
                    ? modelPrices.getLong(model.id) : catDefault;

                // Command to spawn/give the FMM model
                var mCfg = modelsCfg != null ? modelsCfg.getConfigurationSection(model.id) : null;
                List<String> commands = mCfg != null && mCfg.contains("commands")
                    ? mCfg.getStringList("commands")
                    : defaultCommands(model.id);

                Product p = new Product.Builder()
                    .id("fmm_" + model.id)
                    .type(Product.Type.COMMAND)
                    .name(model.displayName)
                    .lore(List.of(
                        "&7Source: &fFreeMinecraftModels",
                        color + display,
                        "",
                        "&6Price: &e" + price + " BellCoins",
                        "",
                        "&aLeft-click &7to purchase"
                    ))
                    .price(price)
                    .enabled(true)
                    .iconMaterial(model.icon)
                    .iconItemModel(model.itemModel)
                    .commands(commands)
                    .currency(Currency.BELLCOINS)
                    .providerSource("fmm")
                    .build();
                products.add(p);
            }
            if (products.isEmpty()) continue;

            String catName = color + "✦ " + display;
            out.add(new Category(
                "fmm_" + catKey, catName, display,
                orderCursor, true, catIcon, catName,
                List.of("&7FMM Models", "&7Models: &f" + products.size(), "", "&eClick to open"),
                products
            ));
            orderCursor += 10;
        }

        plugin.getLogger().info("[FMMProvider] Generated " + out.size() + " categories.");
        return out;
    }

    private List<String> defaultCommands(String modelId) {
        return List.of("fmm spawn {player} " + modelId);
    }

    private record FMMModel(String id, String displayName, Material icon, String itemModel) {}

    private FileConfiguration loadOrCreateProviderConfig(Plugin fmm, long defaultPrice) {
        File dir = new File(plugin.getDataFolder(), "providers");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "fmm.yml");
        if (!f.exists()) {
            try { Files.writeString(f.toPath(), buildTemplate(fmm, defaultPrice)); }
            catch (IOException e) { plugin.getLogger().warning("[FMMProvider] Config write failed: " + e.getMessage()); }
        }
        return YamlConfiguration.loadConfiguration(f);
    }

    private String buildTemplate(Plugin fmm, long defaultPrice) {
        // Auto-detect model names for template
        File modelsDir = new File(fmm.getDataFolder(), "models");
        List<String> detectedModels = new ArrayList<>();
        if (modelsDir.exists()) {
            File[] dirs = modelsDir.listFiles(File::isDirectory);
            if (dirs != null) for (File d : dirs) detectedModels.add(d.getName());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("""
# ============================================================
#  BellMarket - FreeMinecraftModels Provider Configuration
# ============================================================
# Reads 3D models from plugins/FreeMinecraftModels/models/
# and exposes them as purchasable products.
#
# "Purchasing" a model runs a configurable command (default:
#  /fmm spawn {player} <modelName>). Customize per-model
#  in the models: section below.
# ============================================================

enabled: true

base-order: 400

default-price: """).append(defaultPrice).append("""


# Only include models whose name starts with these prefixes.
# Leave empty to include ALL detected models.
include-prefixes: []

excluded-models: []

# Category overrides (models grouped by prefix before _)
categories: {}

# Per-model price overrides
model-prices: {}

# Per-model configuration
# models:
#   my_horse:
#     display-name: "My Horse"
#     show-in-shop: true
#     icon: SADDLE
#     item-model: "freeminecraftmodels:display/my_horse"
#     commands:
#       - "fmm spawn {player} my_horse"
#       - "tell {player} Enjoy your new mount!"
models: {}
""");

        if (!detectedModels.isEmpty()) {
            sb.append("\n# ─── Detected models (").append(detectedModels.size()).append(") ───\n");
            sb.append("# To configure a model, move it to the models: section above.\n");
            for (String m : detectedModels) {
                sb.append("# ").append(m).append(": ").append(defaultPrice).append("\n");
            }
        }
        return sb.toString();
    }

    private static String categoryOf(String name) {
        int idx = name.indexOf('_');
        return idx > 0 ? name.substring(0, idx).toLowerCase(Locale.ROOT) : "models";
    }

    private static Material parseMaterial(String n, Material fb) {
        if (n == null || n.isEmpty()) return fb;
        try { return Material.valueOf(n.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return fb; }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
