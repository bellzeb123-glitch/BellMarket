/*
 * BellMarket - SkinStudioProvider
 *
 * GENERIC version (sellable). Works on ANY SkinStudio config without hardcoded
 * tier lists. Server-specific customisation lives in a dedicated config file:
 *
 *     plugins/BellMarket/providers/skinstudio.yml
 *
 * which is auto-created on first run with sensible defaults and helpful comments.
 *
 * Detection (when admin doesn't override in config):
 *   - tier        = prefix before first underscore in skin key
 *   - tier color  = extracted from SkinStudio display-name (the &X code inside [...])
 *   - tier icon   = stained-glass pane matching the detected colour
 *   - tier name   = Capitalize(tier-key)
 *   - product name= taken verbatim from SkinStudio display-name
 *   - product icon material = first entry in skin's item-types
 *   - product 3D model = item-model from SkinStudio
 *
 * Pricing priority:
 *   1. skin-prices.<skinKey> (per-skin override)
 *   2. tiers.<tier>.default-price (per-tier default)
 *   3. default-price (global default)
 *   4. provider config default-price (from main config.yml)
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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class SkinStudioProvider implements ProductProvider {

    private final BellMarket plugin;

    /** Mapping from Minecraft colour code → matching stained glass pane material. */
    private static final Map<String, Material> COLOR_TO_GLASS = new HashMap<>();
    static {
        COLOR_TO_GLASS.put("&0", Material.BLACK_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&1", Material.BLUE_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&2", Material.GREEN_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&3", Material.CYAN_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&4", Material.RED_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&5", Material.PURPLE_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&6", Material.ORANGE_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&7", Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&8", Material.GRAY_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&9", Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&a", Material.LIME_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&b", Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&c", Material.RED_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&d", Material.MAGENTA_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&e", Material.YELLOW_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&f", Material.WHITE_STAINED_GLASS_PANE);
    }

    public SkinStudioProvider(BellMarket plugin) {
        this.plugin = plugin;
    }

    @Override public String getProviderId() { return "skinstudio"; }

    @Override
    public boolean isAvailable() {
        Plugin sk = plugin.getServer().getPluginManager().getPlugin("SkinStudio");
        return sk != null && sk.isEnabled();
    }

    @Override
    public List<Category> generateCategories(long defaultPrice) {
        Plugin sk = plugin.getServer().getPluginManager().getPlugin("SkinStudio");
        if (sk == null) return Collections.emptyList();

        File skinConfigFile = new File(sk.getDataFolder(), "config.yml");
        if (!skinConfigFile.exists()) {
            plugin.getLogger().warning("[SkinStudioProvider] SkinStudio config.yml missing");
            return Collections.emptyList();
        }
        FileConfiguration skinCfg = YamlConfiguration.loadConfiguration(skinConfigFile);
        ConfigurationSection skins = skinCfg.getConfigurationSection("skins");
        if (skins == null) {
            plugin.getLogger().warning("[SkinStudioProvider] No 'skins' section in SkinStudio config");
            return Collections.emptyList();
        }

        // Load (or create) the per-server price/customisation file
        FileConfiguration provCfg = loadOrCreateProviderConfig();
        long globalDefault = provCfg.getLong("default-price", defaultPrice);
        ConfigurationSection tiersCfg     = provCfg.getConfigurationSection("tiers");
        ConfigurationSection skinPricesCfg = provCfg.getConfigurationSection("skin-prices");
        boolean includeChange = plugin.getConfig().getBoolean(
            "providers.skinstudio.include-change-token", false);

        // Group skins by tier prefix
        Map<String, List<String>> byTier = new LinkedHashMap<>();
        for (String skinKey : skins.getKeys(false)) {
            byTier.computeIfAbsent(tierOf(skinKey), k -> new ArrayList<>()).add(skinKey);
        }

        // Order tiers alphabetically for stable display
        List<String> tierOrder = new ArrayList<>(byTier.keySet());
        Collections.sort(tierOrder);

        List<Category> out = new ArrayList<>();
        int orderCursor = 10;
        for (String tier : tierOrder) {
            List<String> tierSkins = byTier.get(tier);

            // Per-tier override (or auto-detect)
            ConfigurationSection tierCfg = tiersCfg != null ? tiersCfg.getConfigurationSection(tier) : null;

            // Auto-detect color from first skin in this tier
            String firstSkinKey = tierSkins.get(0);
            String firstDisplay = skins.getString(firstSkinKey + ".display-name", "");
            String autoColor = detectTierColor(firstDisplay);

            String tierColor   = tierCfg != null ? tierCfg.getString("color", autoColor) : autoColor;
            String tierDisplay = tierCfg != null ? tierCfg.getString("display-name", capitalize(tier)) : capitalize(tier);
            Material tierIcon  = parseMaterial(
                tierCfg != null ? tierCfg.getString("icon") : null,
                COLOR_TO_GLASS.getOrDefault(tierColor, Material.LIGHT_GRAY_STAINED_GLASS_PANE));
            long tierDefaultPrice = tierCfg != null
                ? tierCfg.getLong("default-price", globalDefault)
                : globalDefault;

            // Build products for this tier
            List<Product> products = new ArrayList<>();
            for (String skinKey : tierSkins) {
                ConfigurationSection sd = skins.getConfigurationSection(skinKey);
                if (sd == null) continue;

                // Price priority: skin-prices override → tier default → global default
                long price = skinPricesCfg != null && skinPricesCfg.contains(skinKey)
                    ? skinPricesCfg.getLong(skinKey)
                    : tierDefaultPrice;

                Product p = buildSkinProduct(skinKey, sd, price, includeChange, tierColor, tierDisplay);
                if (p != null) products.add(p);
            }
            if (products.isEmpty()) continue;

            // Resolve category lore through LangManager (en/pl)
            List<String> catLore;
            try {
                catLore = plugin.getLang().getList("provider.skinstudio.category-lore",
                    "color", tierColor,
                    "tier",  tierDisplay,
                    "count", String.valueOf(products.size()));
                if (catLore == null || catLore.isEmpty()) catLore = defaultCategoryLore(tierColor, tierDisplay, products.size());
            } catch (Throwable t) {
                catLore = defaultCategoryLore(tierColor, tierDisplay, products.size());
            }

            String catName = tierColor + "✦ " + tierDisplay;

            Category cat = new Category(
                "skinstudio_" + tier,    // id
                catName,                  // name (raw with color)
                tierDisplay,              // displayName
                orderCursor,              // order
                true,                     // enabled
                tierIcon,                 // icon material (STAINED GLASS PANE)
                catName,                  // icon name
                catLore,                  // icon lore
                products                  // products
            );
            out.add(cat);
            orderCursor += 10;
        }

        return out;
    }

    private Product buildSkinProduct(String skinKey, ConfigurationSection sd,
                                     long price, boolean includeChange,
                                     String tierColor, String tierDisplay) {
        // From SkinStudio config
        String productName = sd.getString("display-name", capitalize(skinKey.replace('_', ' ')));
        String itemModel = sd.getString("item-model", null);
        List<String> itemTypes = sd.getStringList("item-types");

        // Icon material = first item-type from SkinStudio
        Material iconMat = Material.PAPER;
        if (!itemTypes.isEmpty()) {
            try {
                iconMat = Material.valueOf(itemTypes.get(0).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {}
        }

        // Product lore through LangManager
        List<String> lore;
        try {
            lore = plugin.getLang().getList("provider.skinstudio.product-lore",
                "skin-id",  skinKey,
                "tier",     tierDisplay,
                "color",    tierColor,
                "price",    String.valueOf(price),
                "currency", safeCurrencyName());
            if (lore == null || lore.isEmpty()) lore = defaultProductLore(skinKey, tierColor, tierDisplay, price);
        } catch (Throwable t) {
            lore = defaultProductLore(skinKey, tierColor, tierDisplay, price);
        }

        return new Product.Builder()
            .id("skinstudio_" + skinKey)
            .type(Product.Type.SKIN_TOKEN)
            .name(productName)
            .lore(lore)
            .price(price)
            .enabled(true)
            .iconMaterial(iconMat)
            .iconItemModel(itemModel)
            .skinId(skinKey)
            .includeChangeToken(includeChange)
            .currency(Currency.BELLCOINS)
            .providerSource("skinstudio")
            .build();
    }

    /**
     * Loads plugins/BellMarket/providers/skinstudio.yml, creating it from the
     * bundled template on first run.
     */
    private FileConfiguration loadOrCreateProviderConfig() {
        File providersDir = new File(plugin.getDataFolder(), "providers");
        if (!providersDir.exists()) providersDir.mkdirs();
        File f = new File(providersDir, "skinstudio.yml");

        if (!f.exists()) {
            // Copy bundled template if present; otherwise write a minimal default
            try (InputStream in = plugin.getResource("providers/skinstudio.yml")) {
                if (in != null) {
                    Files.copy(in, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    plugin.getLogger().info("[SkinStudioProvider] Created default providers/skinstudio.yml");
                } else {
                    writeMinimalTemplate(f);
                    plugin.getLogger().info("[SkinStudioProvider] Wrote minimal providers/skinstudio.yml");
                }
            } catch (IOException e) {
                plugin.getLogger().warning("[SkinStudioProvider] Could not create skinstudio.yml: " + e.getMessage());
            }
        }

        return YamlConfiguration.loadConfiguration(f);
    }

    private void writeMinimalTemplate(File f) throws IOException {
        String template = """
            # BellMarket - SkinStudio Provider Configuration
            # Auto-generated on first run. Edit freely — changes apply on /bm reload.
            #
            # Pricing priority:
            #   1. skin-prices.<key>       (specific override, highest priority)
            #   2. tiers.<name>.default-price
            #   3. default-price           (global default below)

            default-price: 500

            # Per-tier customisation (OPTIONAL). A tier = prefix before first underscore
            # in skin key. Plugin auto-detects color/icon/name from SkinStudio config
            # if the tier is not listed here.
            #
            # Example:
            # tiers:
            #   bronze:
            #     display-name: "Bronze"
            #     color: "&6"
            #     icon: ORANGE_STAINED_GLASS_PANE
            #     default-price: 300
            tiers: {}

            # Per-skin price overrides (highest priority).
            # Example:
            # skin-prices:
            #   bronze_sword: 250
            #   ultimatium_dragon_sword: 5000
            skin-prices: {}
            """;
        Files.writeString(f.toPath(), template);
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private static String tierOf(String skinKey) {
        int us = skinKey.indexOf('_');
        return us > 0 ? skinKey.substring(0, us).toLowerCase(Locale.ROOT) : "other";
    }

    /**
     * Extracts the tier colour from a display-name like
     * "&8[&6Bronze&8] &fSkin Token: Sword" → "&6".
     * Looks for the first colour code AFTER "&8[".
     * Falls back to the first non-frame colour code, then "&7".
     */
    private static String detectTierColor(String displayName) {
        if (displayName == null || displayName.isEmpty()) return "&7";
        int bracket = displayName.indexOf("&8[");
        if (bracket >= 0) {
            int amp = displayName.indexOf('&', bracket + 3);
            if (amp > 0 && amp + 1 < displayName.length()) {
                String code = displayName.substring(amp, amp + 2).toLowerCase(Locale.ROOT);
                if (COLOR_TO_GLASS.containsKey(code)) return code;
            }
        }
        // Fallback: first non-frame colour code
        for (int i = 0; i < displayName.length() - 1; i++) {
            if (displayName.charAt(i) == '&') {
                char c = Character.toLowerCase(displayName.charAt(i + 1));
                if (c != '8' && c != '7' && c != 'f' && c != 'r') {
                    String code = ("&" + c).toLowerCase(Locale.ROOT);
                    if (COLOR_TO_GLASS.containsKey(code)) return code;
                }
            }
        }
        return "&7";
    }

    private static Material parseMaterial(String name, Material fallback) {
        if (name == null || name.isEmpty()) return fallback;
        try {
            return Material.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        String[] parts = s.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0)))
              .append(part.length() > 1 ? part.substring(1) : "")
              .append(' ');
        }
        return sb.toString().trim();
    }

    private String safeCurrencyName() {
        try { return plugin.getLang().getCurrencyName(); }
        catch (Throwable t) { return "BellCoins"; }
    }

    // Hard-coded English fallbacks — only used if lang file is missing the keys
    private static List<String> defaultProductLore(String skinKey, String color, String tier, long price) {
        return List.of(
            "&7Skin ID: &8" + skinKey,
            "&7Tier: " + color + tier,
            "",
            "&6Price: &e" + price,
            "",
            "&aLeft-click &7to purchase"
        );
    }

    private static List<String> defaultCategoryLore(String color, String tier, int count) {
        return List.of(
            "&7Tier: " + color + tier,
            "&7Skins available: &f" + count,
            "",
            "&eClick to open"
        );
    }
}
