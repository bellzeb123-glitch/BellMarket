package pl.bellmarket.provider;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import pl.bellmarket.BellMarket;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Reflection bridge to FreeMinecraftModels scripted custom items (Lua).
 * <p>
 * {@code ScriptedItemAPI.applyScriptedItemData} only stamps PDC + item-model —
 * it does <b>not</b> set name/lore. {@code ModelItemFactory} is documented but
 * missing from current FMM jars, so we build the stack from
 * {@code PropScriptConfigFields} (same fields {@code /fmm giveitem} uses).
 */
public final class FmmScriptedItemBridge {

    public record ScriptedItemMeta(String id, String displayName, Material material, String itemModel) {}

    private final BellMarket plugin;

    public FmmScriptedItemBridge(BellMarket plugin) {
        this.plugin = plugin;
    }

    public boolean isAvailable() {
        Plugin fmm = plugin.getServer().getPluginManager().getPlugin("FreeMinecraftModels");
        return fmm != null && fmm.isEnabled();
    }

    private ClassLoader fmmClassLoader() {
        Plugin fmm = plugin.getServer().getPluginManager().getPlugin("FreeMinecraftModels");
        return fmm != null ? fmm.getClass().getClassLoader() : getClass().getClassLoader();
    }

    /**
     * All registered FMM scripted custom-item definitions (models with {@code material:} + Lua).
     * Key = FMM item id (used by {@code /fmm giveitem}).
     */
    public Map<String, ScriptedItemMeta> listScriptedItems() {
        if (!isAvailable()) return Collections.emptyMap();
        ClassLoader cl = fmmClassLoader();
        for (String className : List.of(
                "com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager",
                "com.magmaguy.freeminecraftmodels.api.ItemScriptManager")) {
            try {
                Class<?> mgr = Class.forName(className, true, cl);
                Method getDefs = mgr.getMethod("getItemDefinitions");
                Object raw = getDefs.invoke(null);
                if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) continue;

                Map<String, ScriptedItemMeta> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String id = String.valueOf(e.getKey());
                    Object cfg = e.getValue();
                    if (cfg == null) continue;
                    out.put(id, metaFromConfig(id, cfg));
                }
                return out;
            } catch (Throwable ignored) {}
        }
        plugin.getLogger().log(Level.FINE, "[FMM] ItemScriptManager unavailable");
        return Collections.emptyMap();
    }

    public boolean isValidItemId(String itemId) {
        if (itemId == null || itemId.isBlank() || !isAvailable()) return false;
        try {
            Class<?> api = Class.forName(
                "com.magmaguy.freeminecraftmodels.api.ScriptedItemAPI", true, fmmClassLoader());
            Object ok = api.getMethod("isValidItemId", String.class).invoke(null, itemId);
            return Boolean.TRUE.equals(ok);
        } catch (Throwable ignored) {
            return listScriptedItems().containsKey(itemId);
        }
    }

    /**
     * Builds a holdable FMM scripted item — ta sama ścieżka co {@code /fmm giveitem}
     * ({@code utils.ModelItemFactory.createCustomItem}).
     */
    public Optional<ItemStack> createScriptedItem(String itemId) {
        if (itemId == null || itemId.isBlank() || !isAvailable()) return Optional.empty();

        String resolvedId = resolveItemId(itemId);
        if (resolvedId == null) {
            // even if isValidItemId failed, try factory with raw id
            resolvedId = itemId;
        }

        Object cfg = getItemConfig(resolvedId);
        if (cfg == null) {
            plugin.getLogger().warning("[FMM] Brak configu dla itemu: " + resolvedId);
            return Optional.empty();
        }

        // 1) Dokładnie jak FMM GiveItemCommand (utils.ModelItemFactory)
        try {
            ClassLoader cl = fmmClassLoader();
            Class<?> factory = Class.forName(
                "com.magmaguy.freeminecraftmodels.utils.ModelItemFactory", true, cl);
            Class<?> cfgClass = Class.forName(
                "com.magmaguy.freeminecraftmodels.config.props.PropScriptConfigFields", true, cl);
            Method create = factory.getMethod("createCustomItem", String.class, cfgClass);
            Object stack = create.invoke(null, resolvedId, cfg);
            if (stack instanceof ItemStack is && !is.getType().isAir()) {
                return Optional.of(is);
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING,
                "[FMM] ModelItemFactory.createCustomItem failed for " + resolvedId + ": " + t.getMessage());
        }

        // 2) Fallback: ręcznie z configu + ScriptedItemAPI stamp
        return createFromConfigFields(resolvedId, cfg);
    }

    private Optional<ItemStack> createFromConfigFields(String resolvedId, Object cfg) {
        Material mat = readParsedMaterial(cfg);
        if (mat == null || mat.isAir()) mat = Material.STICK;

        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return Optional.empty();

        String display = readItemName(cfg);
        if (display != null && !display.isBlank()) {
            meta.displayName(legacy(display).decoration(TextDecoration.ITALIC, false));
        }

        List<String> loreLines = readLore(cfg);
        if (!loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>(loreLines.size());
            for (String line : loreLines) {
                lore.add(legacy(line == null ? "" : line).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
        }

        stack.setItemMeta(meta);
        applyEnchantments(stack, cfg);

        try {
            Class<?> api = Class.forName(
                "com.magmaguy.freeminecraftmodels.api.ScriptedItemAPI", true, fmmClassLoader());
            api.getMethod("applyScriptedItemData", ItemStack.class, String.class)
                .invoke(null, stack, resolvedId);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING,
                "[FMM] applyScriptedItemData failed for " + resolvedId + ": " + t.getMessage());
        }

        ensureDisplayModel(stack, resolvedId);
        return Optional.of(stack);
    }

    private String resolveItemId(String itemId) {
        if (isValidItemId(itemId)) return itemId;
        String stripped = itemId.replaceAll("_idle$", "");
        if (!stripped.equals(itemId) && isValidItemId(stripped)) return stripped;
        if (isValidItemId(itemId + "_idle")) return itemId; // base id for scripts
        return listScriptedItems().containsKey(itemId) ? itemId : null;
    }

    private Object getItemConfig(String itemId) {
        try {
            Class<?> api = Class.forName(
                "com.magmaguy.freeminecraftmodels.api.ScriptedItemAPI", true, fmmClassLoader());
            return api.getMethod("getItemConfig", String.class).invoke(null, itemId);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "[FMM] getItemConfig failed: " + t.getMessage());
            return null;
        }
    }

    private static Material readParsedMaterial(Object cfg) {
        try {
            Method m = cfg.getClass().getMethod("getParsedMaterial");
            Object v = m.invoke(cfg);
            if (v instanceof Material mat) return mat;
        } catch (Throwable ignored) {}
        try {
            Method m = cfg.getClass().getMethod("getMaterial");
            Object v = m.invoke(cfg);
            if (v instanceof Material mat) return mat;
            if (v != null && !String.valueOf(v).isBlank()) {
                return Material.valueOf(String.valueOf(v).trim().toUpperCase(Locale.ROOT));
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** FMM uses {@code itemName} (YAML key {@code name:}), not CustomConfigFields#getName. */
    private static String readItemName(Object cfg) {
        for (String method : List.of("getItemName", "getName")) {
            try {
                Method m = cfg.getClass().getMethod(method);
                Object v = m.invoke(cfg);
                if (v != null && !String.valueOf(v).isBlank()) {
                    String s = String.valueOf(v);
                    // getName() on MagmaCore often returns filename — skip bare ids
                    if ("getName".equals(method) && !s.contains("&") && !s.contains("§") && s.equals(s.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    return s;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> readLore(Object cfg) {
        try {
            Method m = cfg.getClass().getMethod("getLore");
            Object v = m.invoke(cfg);
            if (v instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object line : list) {
                    if (line != null) out.add(String.valueOf(line));
                }
                return out;
            }
        } catch (Throwable ignored) {}
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static void applyEnchantments(ItemStack stack, Object cfg) {
        try {
            Method m = cfg.getClass().getMethod("getParsedEnchantments");
            Object v = m.invoke(cfg);
            if (v instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() instanceof Enchantment ench && e.getValue() instanceof Number lvl) {
                        stack.addUnsafeEnchantment(ench, lvl.intValue());
                    }
                }
                return;
            }
        } catch (Throwable ignored) {}
        try {
            Method m = cfg.getClass().getMethod("getEnchantments");
            Object v = m.invoke(cfg);
            if (v instanceof List<?> list) {
                for (Object entry : list) {
                    String s = String.valueOf(entry);
                    String[] parts = s.split(",");
                    if (parts.length != 2) continue;
                    Enchantment ench = Enchantment.getByName(parts[0].trim().toUpperCase(Locale.ROOT));
                    if (ench == null) continue;
                    try {
                        stack.addUnsafeEnchantment(ench, Integer.parseInt(parts[1].trim()));
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    private void ensureDisplayModel(ItemStack stack, String itemId) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        try {
            if (meta.hasItemModel()) return;
        } catch (Throwable ignored) {
            // older API — fall through and try set
        }
        // Item definition is .../items/display/<id>.json (bow/crossbow WITHOUT _idle).
        // _idle is only an internal model state referenced by that definition.
        String modelId = itemId.toLowerCase(Locale.ROOT).replaceAll("_idle$", "");
        try {
            var key = org.bukkit.NamespacedKey.fromString("freeminecraftmodels:display/" + modelId);
            if (key != null) {
                meta.setItemModel(key);
                stack.setItemMeta(meta);
            }
        } catch (Throwable ignored) {}
    }

    private boolean DisplayModelHas(String modelId) {
        try {
            Class<?> reg = Class.forName(
                "com.magmaguy.freeminecraftmodels.config.DisplayModelRegistry", true, fmmClassLoader());
            Object ok = reg.getMethod("hasDisplayModel", String.class).invoke(null, modelId);
            return Boolean.TRUE.equals(ok);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private ScriptedItemMeta metaFromConfig(String id, Object cfg) {
        String name = readItemName(cfg);
        if (name == null || name.isBlank()) name = id;
        Material mat = readParsedMaterial(cfg);
        if (mat == null) mat = Material.STICK;
        String model = "freeminecraftmodels:display/"
            + id.toLowerCase(Locale.ROOT).replaceAll("_idle$", "");
        return new ScriptedItemMeta(id, name, mat, model);
    }

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s == null ? "" : s);
    }
}
