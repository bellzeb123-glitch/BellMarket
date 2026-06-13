/*
 * BellMarket - EliteMobsProvider (SESJA-3 FIX5)
 *
 * Delivery: tries EM API via aggressive reflection search.
 * Falls back to building basic ItemStack if API unavailable.
 *
 * EM items need PDC tags from EM to get:
 *   - Dynamic lore (damage, tier, abilities)
 *   - EM combat system recognition
 * Without API, items work visually but lack EM stats.
 */
package pl.bellmarket.provider;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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
import java.util.logging.Level;

public class EliteMobsProvider implements ProductProvider {

    private static final Map<String, Material> HINTS = new HashMap<>();
    static {
        HINTS.put("sword",      Material.DIAMOND_SWORD);
        HINTS.put("axe",        Material.DIAMOND_AXE);
        HINTS.put("bow",        Material.BOW);
        HINTS.put("crossbow",   Material.CROSSBOW);
        HINTS.put("helmet",     Material.DIAMOND_HELMET);
        HINTS.put("chestplate", Material.DIAMOND_CHESTPLATE);
        HINTS.put("chest",      Material.DIAMOND_CHESTPLATE);
        HINTS.put("leggings",   Material.DIAMOND_LEGGINGS);
        HINTS.put("boots",      Material.DIAMOND_BOOTS);
        HINTS.put("trident",    Material.TRIDENT);
        HINTS.put("book",       Material.ENCHANTED_BOOK);
        HINTS.put("shield",     Material.SHIELD);
        HINTS.put("staff",      Material.STICK);
        HINTS.put("scrap",      Material.IRON_NUGGET);
        HINTS.put("token",      Material.NETHER_STAR);
    }

    private final BellMarket plugin;
    // Cache: itemId → EM-generated ItemStack (loaded once at startup)
    private final Map<String, ItemStack> emItemCache = new HashMap<>();
    private boolean apiChecked = false;
    private boolean apiAvailable = false;

    public EliteMobsProvider(BellMarket plugin) { this.plugin = plugin; }

    @Override public String getProviderId() { return "elitemobs"; }

    @Override
    public boolean isAvailable() {
        Plugin em = plugin.getServer().getPluginManager().getPlugin("EliteMobs");
        return em != null && em.isEnabled();
    }

    @Override
    public List<Category> generateCategories(long defaultPrice) {
        Plugin em = plugin.getServer().getPluginManager().getPlugin("EliteMobs");
        if (em == null) return Collections.emptyList();

        FileConfiguration cfg = loadOrCreateProviderConfig(defaultPrice);
        if (!cfg.getBoolean("enabled", true)) return Collections.emptyList();

        long globalDefault = cfg.getLong("default-price", defaultPrice);
        List<String> excluded = cfg.getStringList("excluded-items");
        int baseOrder = cfg.getInt("base-order", 300);
        var itemPrices = cfg.getConfigurationSection("item-prices");
        var cats = cfg.getConfigurationSection("categories");

        File customItemsDir = new File(em.getDataFolder(), "customitems");
        if (!customItemsDir.exists()) return Collections.emptyList();
        File[] files = customItemsDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null || files.length == 0) return Collections.emptyList();

        // Pre-warm EM API cache
        if (!apiChecked) checkEmApi(em);

        Map<String, List<EMItem>> byTier = new TreeMap<>();
        for (File file : files) {
            String itemId = file.getName().replace(".yml", "");
            if (excluded.contains(itemId)) continue;
            YamlConfiguration itemCfg = YamlConfiguration.loadConfiguration(file);
            if (!itemCfg.getBoolean("isEnabled", true)) continue;

            String itemType   = itemCfg.getString("itemType", "STONE");
            int tier          = itemCfg.getInt("itemTier", 0);
            String displayName = readDisplayName(itemCfg, itemId);
            String tierKey    = tier > 0 ? "tier_" + tier : "misc";
            Material mat      = parseMaterialFromId(itemType, itemId);
            String itemModel  = null;
            if (itemCfg.contains("customModelData")) {
                itemModel = itemCfg.getString("customModelData");
            }

            byTier.computeIfAbsent(tierKey, k -> new ArrayList<>())
                  .add(new EMItem(itemId, displayName, mat, tier, itemModel, file));
        }

        List<Category> out = new ArrayList<>();
        int orderCursor = baseOrder;
        for (Map.Entry<String, List<EMItem>> entry : byTier.entrySet()) {
            String tierKey = entry.getKey();
            List<EMItem> items = entry.getValue();

            var catCfg = cats != null ? cats.getConfigurationSection(tierKey) : null;
            String display  = catCfg != null ? catCfg.getString("display-name", formatTier(tierKey)) : formatTier(tierKey);
            String color    = catCfg != null ? catCfg.getString("color", tierColor(tierKey)) : tierColor(tierKey);
            Material icon   = parseMaterial(catCfg != null ? catCfg.getString("icon") : null, tierIcon(tierKey));
            long catDefault = catCfg != null ? catCfg.getLong("default-price", globalDefault) : globalDefault;

            List<Product> products = new ArrayList<>();
            for (EMItem item : items) {
                long price = (itemPrices != null && itemPrices.contains(item.id))
                    ? itemPrices.getLong(item.id) : catDefault;

                // Use EM-generated item if API available, else build from YAML
                ItemStack giveItem = apiAvailable
                    ? emItemCache.getOrDefault(item.id, buildFallbackItem(item))
                    : buildFallbackItem(item);

                products.add(new Product.Builder()
                    .id("elitemobs_" + item.id)
                    .type(Product.Type.ITEM)
                    .name(item.displayName)
                    .lore(buildLore(item, color, display, price, apiAvailable))
                    .price(price).enabled(true)
                    .iconMaterial(item.material).iconItemModel(item.itemModel)
                    .giveItem(giveItem)
                    .currency(Currency.BELLCOINS).providerSource("elitemobs")
                    .build());
            }
            if (products.isEmpty()) continue;

            String catName = color + "⚔ " + display;
            out.add(new Category("elitemobs_" + tierKey, catName, display,
                orderCursor, true, icon, catName,
                List.of("&7EliteMobs Gear", "&7Tier: " + color + display,
                    "&7Items: &f" + products.size(), "", "&eClick to open"),
                products));
            orderCursor += 10;
        }

        plugin.getLogger().info("[EliteMobsProvider] Generated " + out.size()
            + " categories. API available: " + apiAvailable);
        return out;
    }

    /**
     * Tries multiple known EM class structures to generate properly-tagged items.
     * Results cached in emItemCache.
     */
    private void checkEmApi(Plugin em) {
        apiChecked = true;
        plugin.getLogger().info("[EliteMobsProvider] Searching for EM item API...");

        // Candidate class/method pairs to try
        String[][] candidates = {
            {"com.magmaguy.elitemobs.items.CustomItemsHandler", "getItemStack"},
            {"com.magmaguy.elitemobs.items.CustomItemsHandler", "getCustomItem"},
            {"com.magmaguy.elitemobs.api.ItemsAPI",             "getItem"},
            {"com.magmaguy.elitemobs.api.ItemsAPI",             "getItemStack"},
            {"com.magmaguy.elitemobs.items.EliteItemManager",   "getItemStack"},
            {"com.magmaguy.elitemobs.items.UniqueItemHandler",  "getItemStack"},
        };

        File customItemsDir = new File(em.getDataFolder(), "customitems");
        File[] files = customItemsDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null || files.length == 0) return;
        // Test with first item
        String testId = files[0].getName().replace(".yml", "");

        for (String[] candidate : candidates) {
            try {
                Class<?> cls = Class.forName(candidate[0]);
                // Try with just String arg
                for (Method m : cls.getMethods()) {
                    if (!m.getName().equals(candidate[1])) continue;
                    Class<?>[] params = m.getParameterTypes();
                    Object result = null;
                    if (params.length == 1 && params[0] == String.class) {
                        result = m.invoke(null, testId);
                    } else if (params.length == 2 && params[0] == String.class
                               && params[1] == int.class) {
                        result = m.invoke(null, testId, 1);
                    }
                    if (result instanceof ItemStack stack) {
                        plugin.getLogger().info("[EliteMobsProvider] API found: "
                            + candidate[0] + "." + candidate[1]);
                        // Load all items into cache
                        loadAllToCache(cls, m, em);
                        apiAvailable = true;
                        return;
                    }
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "[EliteMobsProvider] Tried "
                    + candidate[0] + ": " + t.getMessage());
            }
        }

        // Scan EM plugin's loaded classes for item-related methods
        plugin.getLogger().warning("[EliteMobsProvider] EM API not found via reflection. "
            + "Items will be delivered without EM tags (no dynamic lore/stats). "
            + "This is expected if EM doesn't expose a public item API.");
        apiAvailable = false;
    }

    private void loadAllToCache(Class<?> cls, Method m, Plugin em) {
        File dir = new File(em.getDataFolder(), "customitems");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;
        int loaded = 0;
        for (File f : files) {
            String id = f.getName().replace(".yml", "");
            try {
                Object result = m.getParameterCount() == 1
                    ? m.invoke(null, id)
                    : m.invoke(null, id, 1);
                if (result instanceof ItemStack stack) {
                    emItemCache.put(id, stack.clone());
                    loaded++;
                }
            } catch (Throwable ignored) {}
        }
        plugin.getLogger().info("[EliteMobsProvider] Cached " + loaded + " EM items via API.");
    }

    /** Builds basic ItemStack from YAML when EM API unavailable. */
    private ItemStack buildFallbackItem(EMItem item) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(item.sourceFile);
        ItemStack stack = new ItemStack(item.material, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String rawName = readDisplayName(cfg, item.id);
        meta.displayName(colorize(rawName).decoration(TextDecoration.ITALIC, false));

        List<String> loreRaw = cfg.getStringList("lore");
        if (!loreRaw.isEmpty()) {
            meta.lore(loreRaw.stream()
                .map(l -> colorize(l).decoration(TextDecoration.ITALIC, false))
                .toList());
        }

        if (item.itemModel != null) {
            try {
                NamespacedKey key = NamespacedKey.fromString(item.itemModel);
                if (key != null) meta.setItemModel(key);
            } catch (Throwable ignored) {}
        }

        stack.setItemMeta(meta);
        return stack;
    }

    private List<String> buildLore(EMItem item, String color, String tierDisplay,
                                    long price, boolean hasApi) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Source: &fEliteMobs");
        lore.add(color + tierDisplay + " &7tier");
        if (!hasApi) lore.add("&8(basic item - no EM stats)");
        lore.add("");
        lore.add("&6Price: &e" + price + " BellCoins");
        lore.add("");
        lore.add("&aLeft-click &7to purchase");
        return lore;
    }

    private record EMItem(String id, String displayName, Material material,
                          int tier, String itemModel, File sourceFile) {}

    private static String readDisplayName(YamlConfiguration cfg, String fallbackId) {
        for (String field : new String[]{"name", "itemName", "displayName", "item-name"}) {
            String val = cfg.getString(field);
            if (val != null && !val.isEmpty())
                return val.replaceAll("&[0-9a-fA-Fk-oK-OrR§]", "").trim();
        }
        return Arrays.stream(fallbackId.split("_"))
            .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
            .reduce((a, b) -> a + " " + b).orElse(fallbackId);
    }

    private static final String TEMPLATE = """
# ============================================================
#  BellMarket - EliteMobs Provider Configuration
# ============================================================
# Items are read from plugins/EliteMobs/customitems/
# Plugin tries EM API for proper items (with EM stats).
# Falls back to basic Bukkit items if API unavailable.
# ============================================================
enabled: true
base-order: 300
default-price: ${DEFAULT_PRICE}
excluded-items: []
categories: {}
item-prices: {}
""";

    private FileConfiguration loadOrCreateProviderConfig(long defaultPrice) {
        File dir = new File(plugin.getDataFolder(), "providers");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "elitemobs.yml");
        if (!f.exists()) {
            try { Files.writeString(f.toPath(), TEMPLATE.replace("${DEFAULT_PRICE}", String.valueOf(defaultPrice))); }
            catch (IOException e) { plugin.getLogger().warning("[EliteMobsProvider] " + e.getMessage()); }
        }
        return YamlConfiguration.loadConfiguration(f);
    }

    private static String formatTier(String t) { return t.startsWith("tier_") ? "Tier " + t.substring(5) : capitalize(t); }
    private static String tierColor(String t) {
        if (!t.startsWith("tier_")) return "&7";
        try { return switch(Integer.parseInt(t.substring(5))) {
            case 1->"&7"; case 2->"&a"; case 3->"&9"; case 4->"&5"; case 5->"&6"; default->"&c";
        }; } catch (NumberFormatException e) { return "&7"; }
    }
    private static Material tierIcon(String t) {
        if (!t.startsWith("tier_")) return Material.PAPER;
        try { return switch(Integer.parseInt(t.substring(5))) {
            case 1->Material.STONE_SWORD; case 2->Material.IRON_SWORD;
            case 3->Material.GOLDEN_SWORD; case 4->Material.DIAMOND_SWORD;
            default->Material.NETHERITE_SWORD;
        }; } catch (NumberFormatException e) { return Material.PAPER; }
    }
    private static Material parseMaterialFromId(String itemType, String itemId) {
        String lower = itemId.toLowerCase();
        for (Map.Entry<String, Material> e : HINTS.entrySet()) if (lower.contains(e.getKey())) return e.getValue();
        try { return Material.valueOf(itemType.toUpperCase()); } catch (IllegalArgumentException ex) { return Material.IRON_NUGGET; }
    }
    private static Material parseMaterial(String n, Material fb) {
        if (n == null || n.isEmpty()) return fb;
        try { return Material.valueOf(n.toUpperCase()); } catch (IllegalArgumentException e) { return fb; }
    }
    private static String capitalize(String s) { return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    private static Component colorize(String s) { return LegacyComponentSerializer.legacyAmpersand().deserialize(s == null ? "" : s); }
}
