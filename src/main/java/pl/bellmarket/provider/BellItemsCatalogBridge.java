/*
 * BellMarket - BellItemsCatalogBridge
 *
 * Resolves external IDs (EliteMobs item id, FMM model id, skin id) to BellItems
 * catalog entries so shop products use ItemFactory stacks (correct models + effects)
 * instead of raw EM YAML / guessed item_model keys.
 */
package pl.bellmarket.provider;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import pl.bellmarket.BellMarket;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class BellItemsCatalogBridge {

    private final BellMarket plugin;
    private final Plugin bellItemsPlugin;

    private Map<String, String> byItemId = Map.of();
    private Map<String, String> bySkinId = Map.of();
    private Map<String, String> byItemModel = Map.of();

    public BellItemsCatalogBridge(BellMarket plugin) {
        this.plugin = plugin;
        this.bellItemsPlugin = plugin.getServer().getPluginManager().getPlugin("BellItems");
        refresh();
    }

    public boolean isAvailable() {
        return bellItemsPlugin != null && bellItemsPlugin.isEnabled() && !byItemId.isEmpty();
    }

    public void refresh() {
        byItemId = Map.of();
        bySkinId = Map.of();
        byItemModel = Map.of();
        if (bellItemsPlugin == null || !bellItemsPlugin.isEnabled()) return;

        try {
            Class<?> apiClass = Class.forName("pl.bell.bellitems.api.BellItemsAPI");
            Object api = apiClass.getMethod("get").invoke(null);
            if (api == null) return;

            @SuppressWarnings("unchecked")
            Collection<String> ids = (Collection<String>) apiClass.getMethod("getItemIds").invoke(api);
            var idMap = new HashMap<String, String>();
            var skinMap = new HashMap<String, String>();
            var modelMap = new HashMap<String, String>();

            for (String id : ids) {
                idMap.put(normalizeKey(id), id);

                var defOpt = (Optional<?>) apiClass.getMethod("getDefinition", String.class).invoke(api, id);
                if (defOpt.isEmpty()) continue;
                Object def = defOpt.get();

                Object skin = def.getClass().getMethod("getSkin").invoke(def);
                String skinId = (String) skin.getClass().getMethod("getSkinId").invoke(skin);
                if (skinId != null && !skinId.isBlank()) {
                    skinMap.putIfAbsent(normalizeKey(skinId), id);
                }

                String itemModel = (String) skin.getClass().getMethod("getItemModel").invoke(skin);
                if (itemModel != null && !itemModel.isBlank()) {
                    modelMap.putIfAbsent(normalizeModelKey(itemModel), id);
                }
            }

            byItemId = Map.copyOf(idMap);
            bySkinId = Map.copyOf(skinMap);
            byItemModel = Map.copyOf(modelMap);
            plugin.getLogger().info("[BellItemsBridge] Indexed " + byItemId.size() + " catalog items.");
        } catch (Throwable t) {
            plugin.getLogger().warning("[BellItemsBridge] Index failed: " + t.getMessage());
        }
    }

    /** Resolve EM item id / skin id / alias to BellItems catalog id. */
    public Optional<String> resolveCatalogId(String externalId) {
        if (externalId == null || externalId.isBlank()) return Optional.empty();
        refreshIfEmpty();

        String key = normalizeKey(externalId);
        String hit = byItemId.get(key);
        if (hit != null) return Optional.of(hit);

        hit = bySkinId.get(key);
        if (hit != null) return Optional.of(hit);

        // EM files often use same basename as SkinStudio skin id after import
        int slash = key.lastIndexOf('/');
        if (slash >= 0 && slash < key.length() - 1) {
            hit = bySkinId.get(key.substring(slash + 1));
            if (hit != null) return Optional.of(hit);
        }
        return Optional.empty();
    }

    /** Resolve FMM model id to BellItems catalog id via item_model or skin id. */
    public Optional<String> resolveFmmModel(String modelId) {
        if (modelId == null || modelId.isBlank()) return Optional.empty();
        refreshIfEmpty();

        Optional<String> direct = resolveCatalogId(modelId);
        if (direct.isPresent()) return direct;

        String displayModel = "freeminecraftmodels:display/" + normalizeKey(modelId);
        String hit = byItemModel.get(normalizeModelKey(displayModel));
        if (hit != null) return Optional.of(hit);

        for (var entry : byItemModel.entrySet()) {
            if (entry.getKey().endsWith("/" + normalizeKey(modelId))
                || entry.getKey().endsWith(":" + normalizeKey(modelId))) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    public Optional<ItemStack> createShopItem(String bellItemsId) {
        if (bellItemsId == null || bellItemsPlugin == null) return Optional.empty();
        try {
            Class<?> apiClass = Class.forName("pl.bell.bellitems.api.BellItemsAPI");
            Object api = apiClass.getMethod("get").invoke(null);
            @SuppressWarnings("unchecked")
            Optional<ItemStack> stack = (Optional<ItemStack>) apiClass
                .getMethod("createItem", String.class, int.class)
                .invoke(api, bellItemsId, 1);
            return stack.map(ItemStack::clone);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    public Optional<CatalogItemMeta> readMeta(String bellItemsId) {
        if (bellItemsId == null) return Optional.empty();
        try {
            Class<?> apiClass = Class.forName("pl.bell.bellitems.api.BellItemsAPI");
            Object api = apiClass.getMethod("get").invoke(null);
            var defOpt = (Optional<?>) apiClass.getMethod("getDefinition", String.class).invoke(api, bellItemsId);
            if (defOpt.isEmpty()) return Optional.empty();
            Object def = defOpt.get();
            String name = (String) def.getClass().getMethod("getDisplayName").invoke(def);
            Material mat = (Material) def.getClass().getMethod("getMaterial").invoke(def);
            Object skin = def.getClass().getMethod("getSkin").invoke(def);
            String itemModel = (String) skin.getClass().getMethod("getItemModel").invoke(skin);
            return Optional.of(new CatalogItemMeta(name, mat, itemModel));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    public record CatalogItemMeta(String displayName, Material material, String itemModel) {}

    /** Exposed for BellItemsProvider iteration. */
    public java.util.Set<String> byItemIdForProvider() {
        refreshIfEmpty();
        return byItemId.values().stream().collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private void refreshIfEmpty() {
        if (byItemId.isEmpty() && bellItemsPlugin != null && bellItemsPlugin.isEnabled()) {
            refresh();
        }
    }

    private static String normalizeKey(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeModelKey(String model) {
        return model.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }
}
