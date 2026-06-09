/*
 * BellMarket - FreeMinecraftModelsProvider (SESJA-3 FIX)
 *
 * Fix: YAML template uses placeholder replacement instead of string
 * concatenation at text block boundaries (fixes "default-price:100").
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

        long globalDefault     = cfg.getLong("default-price", defaultPrice);
        int baseOrder          = cfg.getInt("base-order", 400);
        List<String> excluded  = cfg.getStringList("excluded-models");
        List<String> prefixes  = cfg.getStringList("include-prefixes");
        var modelsCfg          = cfg.getConfigurationSection("models");
        var modelPrices        = cfg.getConfigurationSection("model-prices");
        var cats               = cfg.getConfigurationSection("categories");

        File modelsDir = new File(fmm.getDataFolder(), "models");
        if (!modelsDir.exists()) return Collections.emptyList();
        File[] modelDirs = modelsDir.listFiles(File::isDirectory);
        if (modelDirs == null || modelDirs.length == 0) return Collections.emptyList();

        Map<String, List<FMMModel>> byCategory = new LinkedHashMap<>();

        for (File modelDir : modelDirs) {
            String modelName = modelDir.getName();
            if (excluded.contains(modelName)) continue;
            if (!prefixes.isEmpty() && prefixes.stream()
                .noneMatch(p -> modelName.toLowerCase().startsWith(p.toLowerCase()))) continue;

            String catKey = categoryOf(modelName);
            var mCfg = modelsCfg != null ? modelsCfg.getConfigurationSection(modelName) : null;
            boolean show = mCfg == null || mCfg.getBoolean("show-in-shop", true);
            if (!show) continue;

            String displayName = mCfg != null
                ? mCfg.getString("display-name", capitalize(modelName.replace('_', ' ')))
                : capitalize(modelName.replace('_', ' '));
            Material icon = parseMaterial(mCfg != null ? mCfg.getString("icon") : null, Material.PLAYER_HEAD);
            String itemModel = mCfg != null
                ? mCfg.getString("item-model", "freeminecraftmodels:display/" + modelName.toLowerCase())
                : "freeminecraftmodels:display/" + modelName.toLowerCase();

            byCategory.computeIfAbsent(catKey, k -> new ArrayList<>())
                      .add(new FMMModel(modelName, displayName, icon, itemModel));
        }

        List<Category> out = new ArrayList<>();
        int orderCursor = baseOrder;

        for (Map.Entry<String, List<FMMModel>> entry : byCategory.entrySet()) {
            String catKey = entry.getKey();
            List<FMMModel> models = entry.getValue();

            var catCfg      = cats != null ? cats.getConfigurationSection(catKey) : null;
            String display  = catCfg != null ? catCfg.getString("display-name", capitalize(catKey)) : capitalize(catKey);
            String color    = catCfg != null ? catCfg.getString("color", "&b") : "&b";
            Material catIcon = parseMaterial(catCfg != null ? catCfg.getString("icon") : null, Material.PLAYER_HEAD);
            long catDefault = catCfg != null ? catCfg.getLong("default-price", globalDefault) : globalDefault;

            List<Product> products = new ArrayList<>();
            for (FMMModel model : models) {
                long price = (modelPrices != null && modelPrices.contains(model.id))
                    ? modelPrices.getLong(model.id) : catDefault;

                var mCfg = modelsCfg != null ? modelsCfg.getConfigurationSection(model.id) : null;
                List<String> commands = (mCfg != null && mCfg.contains("commands"))
                    ? mCfg.getStringList("commands")
                    : List.of("fmm spawn {player} " + model.id);

                products.add(new Product.Builder()
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
                    .price(price).enabled(true)
                    .iconMaterial(model.icon).iconItemModel(model.itemModel)
                    .commands(commands)
                    .currency(Currency.BELLCOINS).providerSource("fmm")
                    .build());
            }
            if (products.isEmpty()) continue;

            String catName = color + "✦ " + display;
            out.add(new Category("fmm_" + catKey, catName, display,
                orderCursor, true, catIcon, catName,
                List.of("&7FMM Models", "&7Models: &f" + products.size(), "", "&eClick to open"),
                products));
            orderCursor += 10;
        }

        plugin.getLogger().info("[FMMProvider] Generated " + out.size() + " categories.");
        return out;
    }

    private record FMMModel(String id, String displayName, Material icon, String itemModel) {}

    // FIX: static template with placeholder to avoid text block whitespace stripping
    private static final String TEMPLATE = """
# ============================================================
#  BellMarket - FreeMinecraftModels Provider Configuration
# ============================================================
# Reads 3D models from plugins/FreeMinecraftModels/models/
# and exposes them as purchasable products.
#
# Default command: /fmm spawn {player} <modelName>
# Configure per-model commands in the models: section.
# ============================================================

enabled: true

base-order: 400

default-price: ${DEFAULT_PRICE}

# Only include models whose name starts with these prefixes.
# Leave empty [] to include ALL detected models.
include-prefixes: []

excluded-models: []

categories: {}

model-prices: {}

# Per-model configuration — configure commands here!
# models:
#   my_horse:
#     display-name: "My Horse"
#     show-in-shop: true
#     icon: SADDLE
#     item-model: "freeminecraftmodels:display/my_horse"
#     commands:
#       - "fmm spawn {player} my_horse"
models: {}
${DETECTED_MODELS}
""";

    private FileConfiguration loadOrCreateProviderConfig(Plugin fmm, long defaultPrice) {
        File dir = new File(plugin.getDataFolder(), "providers");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "fmm.yml");
        if (!f.exists()) {
            try {
                Files.writeString(f.toPath(), buildContent(fmm, defaultPrice));
            } catch (IOException e) {
                plugin.getLogger().warning("[FMMProvider] Config write failed: " + e.getMessage());
            }
        }
        return YamlConfiguration.loadConfiguration(f);
    }

    private String buildContent(Plugin fmm, long defaultPrice) {
        File modelsDir = new File(fmm.getDataFolder(), "models");
        StringBuilder detected = new StringBuilder();
        if (modelsDir.exists()) {
            File[] dirs = modelsDir.listFiles(File::isDirectory);
            if (dirs != null && dirs.length > 0) {
                detected.append("# ─── Detected models (")
                        .append(dirs.length).append(") ───\n");
                detected.append("# Uncomment and move to models: section to configure:\n");
                for (File d : dirs) {
                    detected.append("#   ").append(d.getName())
                            .append(": ").append(defaultPrice).append("\n");
                }
            }
        }
        return TEMPLATE
            .replace("${DEFAULT_PRICE}", String.valueOf(defaultPrice))
            .replace("${DETECTED_MODELS}", detected.toString());
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
