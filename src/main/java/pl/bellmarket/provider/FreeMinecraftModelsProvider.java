/*
 * BellMarket - FreeMinecraftModelsProvider (SESJA-3 FIX5)
 *
 * FMM models are world entities (furniture, props, mounts).
 * Buying = receiving a PAPER item with FMM's PDC tags.
 * When the item is placed/used, FMM spawns the entity.
 * This mirrors what /fmm craftify produces as output.
 *
 * Icon fix: model key = "freeminecraftmodels:display/<modelId>"
 * (NO _idle suffix for CraftifyCommand output items)
 *
 * Model detection: FileModelConverter.getConvertedFileModels() via reflection
 */
package pl.bellmarket.provider;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
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

        Set<String> modelIds = getModelIds(fmm);
        if (modelIds.isEmpty()) {
            plugin.getLogger().info("[FMMProvider] No models found.");
            return Collections.emptyList();
        }
        plugin.getLogger().info("[FMMProvider] Found " + modelIds.size() + " models: " + modelIds);

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
            Material icon   = parseMaterial(catCfg != null ? catCfg.getString("icon") : null, Material.PAPER);
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
                Material iconMat = parseMaterial(mCfg != null ? mCfg.getString("icon") : null, Material.PAPER);

                // Item-model key for shop icon (no _idle suffix for craftify items)
                String itemModel = "freeminecraftmodels:display/" + modelId;

                // Delivery: give PAPER with FMM PDC data (same as craftify output)
                ItemStack giveItem = buildFmmItem(fmm, modelId, displayName);

                products.add(new Product.Builder()
                    .id("fmm_" + modelId)
                    .type(Product.Type.ITEM)
                    .name(color + displayName)
                    .lore(List.of(
                        "&7Source: &fFreeMinecraftModels",
                        "&7Model: &8" + modelId,
                        "&7Type: &bProp / Furniture",
                        "",
                        "&6Price: &e" + price + " BellCoins",
                        "",
                        "&aLeft-click &7to purchase"
                    ))
                    .price(price).enabled(true)
                    .iconMaterial(iconMat).iconItemModel(itemModel)
                    .giveItem(giveItem)
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
     * Creates a PAPER item with FMM's PDC tags (mirrors CraftifyCommand output).
     * When given to player, they can place it to spawn the FMM model.
     * Uses "model_id" PDC key from FMM's MetadataHandler.PLUGIN namespace.
     */
    private ItemStack buildFmmItem(Plugin fmm, String modelId, String displayName) {
        ItemStack item = new ItemStack(Material.PAPER, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        // Display name
        meta.displayName(net.kyori.adventure.text.Component.text(
            net.kyori.adventure.text.format.NamedTextColor.GOLD + "✦ " + displayName)
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));

        meta.lore(List.of(
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().deserialize("&7FMM Model: &f" + modelId),
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().deserialize("&7Place to spawn in world")
        ));

        // Set item model key (makes icon show 3D in GUI)
        try {
            NamespacedKey modelKey = NamespacedKey.fromString("freeminecraftmodels:display/" + modelId);
            if (modelKey != null) meta.setItemModel(modelKey);
        } catch (Throwable ignored) {}

        // Set FMM PDC tag: model_id = modelId
        // Uses FMM plugin's namespace (same as MetadataHandler.PLUGIN)
        try {
            NamespacedKey fmmKey = new NamespacedKey(fmm, "model_id");
            meta.getPersistentDataContainer().set(fmmKey, PersistentDataType.STRING, modelId);
        } catch (Throwable ignored) {}

        // Set craftify_output PDC flag
        try {
            NamespacedKey craftifyKey = new NamespacedKey(fmm, "craftify_output");
            meta.getPersistentDataContainer().set(craftifyKey, PersistentDataType.BYTE, (byte) 1);
        } catch (Throwable ignored) {}

        item.setItemMeta(meta);
        return item;
    }

    @SuppressWarnings("unchecked")
    private Set<String> getModelIds(Plugin fmm) {
        // FileModelConverter.getConvertedFileModels() — used by CraftifyCommand
        try {
            Class<?> cls = Class.forName(
                "com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter");
            Method m = cls.getMethod("getConvertedFileModels");
            Map<String, ?> map = (Map<String, ?>) m.invoke(null);
            if (map != null && !map.isEmpty()) {
                Set<String> ids = new HashSet<>(map.keySet());
                plugin.getLogger().info("[FMMProvider] API found " + ids.size() + " models.");
                return ids;
            }
        } catch (Throwable e) {
            plugin.getLogger().fine("[FMMProvider] API unavailable: " + e.getMessage());
        }

        // Filesystem fallback
        plugin.getLogger().info("[FMMProvider] Falling back to filesystem scan.");
        return scanModels(fmm);
    }

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
                String relPath = root.toPath().relativize(child.toPath())
                    .toString().replace(File.separatorChar, '_')
                    .replaceAll("\\.bbmodel$", "");
                results.add(relPath);
            } else if (child.isDirectory()) {
                File[] bbFiles = child.listFiles(f -> f.getName().endsWith(".bbmodel"));
                if (bbFiles != null && bbFiles.length > 0) {
                    String relPath = root.toPath().relativize(child.toPath())
                        .toString().replace(File.separatorChar, '_');
                    results.add(relPath);
                } else {
                    scanDir(root, child, results);
                }
            }
        }
    }

    private static final String TEMPLATE = """
# ============================================================
#  BellMarket - FreeMinecraftModels Provider Configuration
# ============================================================
# FMM models are sold as craftify-output items (PAPER with PDC).
# Players receive a physical item they can place in the world
# to spawn the 3D model (furniture, props, etc.)
#
# Model IDs from FileModelConverter.getConvertedFileModels()
# ============================================================

enabled: true
base-order: 400
default-price: ${DEFAULT_PRICE}

include-prefixes: []
excluded-models: []
categories: {}
model-prices: {}
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
                        sb.append("# ").append(id).append(": ").append(defaultPrice).append("\n"));
                }
                Files.writeString(f.toPath(), TEMPLATE
                    .replace("${DEFAULT_PRICE}", String.valueOf(defaultPrice))
                    .replace("${DETECTED}", sb.toString()));
            } catch (IOException e) { plugin.getLogger().warning("[FMMProvider] " + e.getMessage()); }
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
