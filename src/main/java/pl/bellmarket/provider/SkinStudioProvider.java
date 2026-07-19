/*
 * BellMarket - SkinStudioProvider (SESJA-2)
 *
 * Changes from Sesja 1.2:
 *   + Auto-populated skinstudio.yml template on first run:
 *       - tiers: ALL detected tiers pre-listed with auto-detected metadata
 *         (admin can edit any default-price / color / icon directly)
 *       - skin-prices: {} empty BUT followed by commented examples for
 *         EVERY detected skin grouped by tier (admin uncomments to override)
 *   + Template generation respects detected SkinStudio config — works on
 *     any server, not just bellzeb's
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

public class SkinStudioProvider implements ProductProvider {

    private final BellMarket plugin;

    /** Detected/configured metadata for one tier. */
    private record TierMeta(String displayName, String color, Material icon, long defaultPrice) {}

    /** Color code → matching stained glass pane. */
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
        if (!skinConfigFile.exists()) return Collections.emptyList();
        FileConfiguration skinCfg = YamlConfiguration.loadConfiguration(skinConfigFile);
        ConfigurationSection skins = skinCfg.getConfigurationSection("skins");
        if (skins == null) return Collections.emptyList();

        // Step 1: Group skins by tier prefix
        Map<String, List<String>> skinsByTier = new LinkedHashMap<>();
        for (String skinKey : skins.getKeys(false)) {
            skinsByTier.computeIfAbsent(tierOf(skinKey), k -> new ArrayList<>()).add(skinKey);
        }
        List<String> orderedTiers = new ArrayList<>(skinsByTier.keySet());
        Collections.sort(orderedTiers);

        // Step 2: Auto-detect each tier's metadata (color, icon, display-name)
        Map<String, TierMeta> autoDetected = new LinkedHashMap<>();
        for (String tier : orderedTiers) {
            String firstSkin = skinsByTier.get(tier).get(0);
            String firstDisplay = skins.getString(firstSkin + ".display-name", "");
            String color = detectTierColor(firstDisplay);
            Material icon = COLOR_TO_GLASS.getOrDefault(color, Material.LIGHT_GRAY_STAINED_GLASS_PANE);
            String displayName = detectTierDisplayName(firstDisplay, tier);
            autoDetected.put(tier, new TierMeta(displayName, color, icon, defaultPrice));
        }

        // Step 3: Load or create the per-server config file
        FileConfiguration provCfg = loadOrCreateProviderConfig(autoDetected, skinsByTier, defaultPrice);
        long globalDefault = provCfg.getLong("default-price", defaultPrice);
        ConfigurationSection tiersCfg     = provCfg.getConfigurationSection("tiers");
        ConfigurationSection skinPricesCfg = provCfg.getConfigurationSection("skin-prices");
        boolean includeChange = plugin.getConfig().getBoolean(
            "providers.skinstudio.include-change-token", false);

        // FIXES4: global excluded-skins list
        List<String> globalExcluded = provCfg.getStringList("excluded-skins");

        // Step 4: Build categories — admin overrides from provCfg take precedence over auto-detected
        List<Category> out = new ArrayList<>();
        int orderCursor = 10;
        for (String tier : orderedTiers) {
            TierMeta auto = autoDetected.get(tier);
            ConfigurationSection tierCfg = tiersCfg != null ? tiersCfg.getConfigurationSection(tier) : null;

            String tierColor   = tierCfg != null ? tierCfg.getString("color", auto.color()) : auto.color();
            String tierDisplay = tierCfg != null ? tierCfg.getString("display-name", auto.displayName()) : auto.displayName();
            Material tierIcon  = parseMaterial(
                tierCfg != null ? tierCfg.getString("icon") : null,
                COLOR_TO_GLASS.getOrDefault(tierColor, auto.icon()));
            // FIXES4: skip tier if explicitly disabled
            if (tierCfg != null && !tierCfg.getBoolean("enabled", true)) {
                plugin.getLogger().info("[SkinStudioProvider] Tier '" + tier + "' disabled in skinstudio.yml, skipping");
                continue;
            }
            // Craftenmine: prawdziwe bronie FMM są w ręcznym VIP (01_vip.yml), nie jako skiny.
            if ("craftenmine".equalsIgnoreCase(tier)) {
                plugin.getLogger().info("[SkinStudioProvider] Tier 'craftenmine' ukryty — sprzedaż w VIP.");
                continue;
            }

            long tierDefaultPrice = tierCfg != null
                ? tierCfg.getLong("default-price", globalDefault)
                : globalDefault;

            List<Product> products = new ArrayList<>();
            for (String skinKey : skinsByTier.get(tier)) {
                ConfigurationSection sd = skins.getConfigurationSection(skinKey);
                if (sd == null) continue;
                long price = (skinPricesCfg != null && skinPricesCfg.contains(skinKey))
                    ? skinPricesCfg.getLong(skinKey)
                    : tierDefaultPrice;
                // FIXES4: check per-tier and global excluded-skins
                List<String> tierExcluded = tierCfg != null ? tierCfg.getStringList("excluded-skins") : List.of();
                if (globalExcluded.contains(skinKey) || tierExcluded.contains(skinKey)) continue;

                Product p = buildSkinProduct(skinKey, sd, price, includeChange, tierColor, tierDisplay);
                if (p != null) products.add(p);
            }
            if (products.isEmpty()) continue;

            List<String> catLore;
            try {
                catLore = plugin.getLang().getList("provider.skinstudio.category-lore",
                    "color", tierColor, "tier", tierDisplay,
                    "count", String.valueOf(products.size()));
                if (catLore == null || catLore.isEmpty()) catLore = defaultCategoryLore(tierColor, tierDisplay, products.size());
            } catch (Throwable t) {
                catLore = defaultCategoryLore(tierColor, tierDisplay, products.size());
            }

            String catName = tierColor + "✦ " + tierDisplay;
            out.add(new Category(
                "skinstudio_" + tier, catName, tierDisplay,
                orderCursor, true, tierIcon, catName, catLore, products
            ));
            orderCursor += 10;
        }
        return out;
    }

    private Product buildSkinProduct(String skinKey, ConfigurationSection sd,
                                     long price, boolean includeChange,
                                     String tierColor, String tierDisplay) {
        String productName = sd.getString("display-name", capitalize(skinKey.replace('_', ' ')));
        String itemModel = sd.getString("item-model", null);
        List<String> itemTypes = sd.getStringList("item-types");
        Material iconMat = Material.PAPER;
        if (!itemTypes.isEmpty()) {
            try { iconMat = Material.valueOf(itemTypes.get(0).toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) {}
        }
        List<String> lore;
        try {
            lore = plugin.getLang().getList("provider.skinstudio.product-lore",
                "skin-id", skinKey, "tier", tierDisplay, "color", tierColor,
                "price", String.valueOf(price), "currency", safeCurrencyName());
            if (lore == null || lore.isEmpty()) lore = defaultProductLore(skinKey, tierColor, tierDisplay, price);
        } catch (Throwable t) {
            lore = defaultProductLore(skinKey, tierColor, tierDisplay, price);
        }
        return new Product.Builder()
            .id("skinstudio_" + skinKey)
            .type(Product.Type.SKIN_TOKEN)
            .name(productName).lore(lore).price(price).enabled(true)
            .iconMaterial(iconMat).iconItemModel(itemModel)
            .skinId(skinKey).includeChangeToken(includeChange)
            .currency(Currency.BELLCOINS).providerSource("skinstudio")
            .build();
    }

    /**
     * Loads the per-server skinstudio.yml — generates a FULL template on
     * first run, pre-populated with detected tiers and commented examples
     * for every detected skin.
     */
    private FileConfiguration loadOrCreateProviderConfig(
            Map<String, TierMeta> tiers, Map<String, List<String>> skinsByTier, long defaultPrice) {
        File dir = new File(plugin.getDataFolder(), "providers");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "skinstudio.yml");
        if (!f.exists()) {
            try {
                Files.writeString(f.toPath(), buildTemplate(tiers, skinsByTier, defaultPrice));
                plugin.getLogger().info("[SkinStudioProvider] Generated providers/skinstudio.yml with "
                    + tiers.size() + " tiers and " + countSkins(skinsByTier) + " skin entries");
            } catch (IOException e) {
                plugin.getLogger().warning("[SkinStudioProvider] Failed to write skinstudio.yml: " + e.getMessage());
            }
        }
        return YamlConfiguration.loadConfiguration(f);
    }

    /** Generates the full YAML template string. */
    private String buildTemplate(Map<String, TierMeta> tiers, Map<String, List<String>> skinsByTier, long defaultPrice) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("# ==============================================================\n");
        sb.append("#  BellMarket - SkinStudio Provider Configuration\n");
        sb.append("# ==============================================================\n");
        sb.append("# Auto-generated on first run. Re-generated if you delete this file.\n");
        sb.append("# Edit freely — changes apply on /bm reload (no restart needed).\n");
        sb.append("#\n");
        sb.append("# Pricing priority (highest → lowest):\n");
        sb.append("#   1. skin-prices.<skin-key>          (per-skin override)\n");
        sb.append("#   2. tiers.<tier-name>.default-price (per-tier default)\n");
        sb.append("#   3. default-price                   (global default below)\n");
        sb.append("# ==============================================================\n\n");

        sb.append("# Global default price for any skin without a tier or per-skin entry\n");
        sb.append("default-price: ").append(defaultPrice).append("\n\n");

        sb.append("# ─── TIERS ────────────────────────────────────────────────────\n");
        sb.append("# A tier = prefix before first underscore in skin keys.\n");
        sb.append("# All ").append(tiers.size()).append(" detected tiers are listed below with\n");
        sb.append("# auto-detected metadata. Edit any value to override:\n");
        sb.append("#   default-price → price for ALL skins of this tier (unless\n");
        sb.append("#                   overridden per-skin in skin-prices below)\n");
        sb.append("#   color         → tier color code (matches stained glass icon)\n");
        sb.append("#   icon          → category icon material in shop GUI\n");
        sb.append("#   display-name  → category name shown to players\n");
        sb.append("# ──────────────────────────────────────────────────────────────\n");
        sb.append("tiers:\n");
        for (Map.Entry<String, TierMeta> e : tiers.entrySet()) {
            TierMeta m = e.getValue();
            sb.append("  ").append(e.getKey()).append(":\n");
            sb.append("    enabled: true\n");
            sb.append("    display-name: \"").append(m.displayName()).append("\"\n");
            sb.append("    color: \"").append(m.color()).append("\"\n");
            sb.append("    icon: ").append(m.icon().name()).append("\n");
            sb.append("    default-price: ").append(m.defaultPrice()).append("\n");
            sb.append("    excluded-skins: []\n");
        }
        sb.append("\n");

        sb.append("# ─── PER-SKIN PRICE OVERRIDES ─────────────────────────────────\n");
        sb.append("# This section is empty by default — all skins use their tier's\n");
        sb.append("# default-price above. To set a custom price for a specific skin,\n");
        sb.append("# UNCOMMENT the relevant line below (remove the leading '# ').\n");
        sb.append("#\n");
        sb.append("# Every detected skin is listed below for easy editing. The values\n");
        sb.append("# shown after ':' are the current tier defaults — change them to\n");
        sb.append("# whatever you want.\n");
        sb.append("# ──────────────────────────────────────────────────────────────\n");
        sb.append("skin-prices: {}\n\n");

        // Commented examples grouped by tier — one block per tier
        for (Map.Entry<String, List<String>> e : skinsByTier.entrySet()) {
            String tier = e.getKey();
            TierMeta meta = tiers.get(tier);
            List<String> tierSkins = new ArrayList<>(e.getValue());
            Collections.sort(tierSkins);
            sb.append("# ─── ").append(meta.displayName())
              .append(" (").append(tierSkins.size()).append(" skins) ───\n");
            sb.append("# Uncomment any line to set a custom price for that skin:\n");
            for (String skin : tierSkins) {
                sb.append("#   ").append(skin).append(": ").append(meta.defaultPrice()).append("\n");
            }
            sb.append("#\n");
        }

        sb.append("# ──────────────────────────────────────────────────────────────\n");
        sb.append("# Editing tip: to enable a per-skin price, move the line ABOVE\n");
        sb.append("# the comments, under 'skin-prices:'. Example:\n");
        sb.append("#\n");
        sb.append("# skin-prices:\n");
        sb.append("#   bronze_sword: 150\n");
        sb.append("#   ultimatium_dragon_sword: 5000\n");
        sb.append("# ──────────────────────────────────────────────────────────────\n");

        return sb.toString();
    }

    // ─── Detection helpers ────────────────────────────────────────────────

    private static String tierOf(String skinKey) {
        int us = skinKey.indexOf('_');
        return us > 0 ? skinKey.substring(0, us).toLowerCase(Locale.ROOT) : "other";
    }

    /**
     * Extracts the tier display-name from a SkinStudio display-name like
     * "&8[&6Brąz&8] &fToken Skina: Miecz" → "Brąz".
     * Falls back to Capitalize(tier-key).
     */
    private static String detectTierDisplayName(String displayName, String tierKey) {
        if (displayName != null) {
            int openBracket = displayName.indexOf('[');
            int closeBracket = displayName.indexOf(']');
            if (openBracket >= 0 && closeBracket > openBracket) {
                String inside = displayName.substring(openBracket + 1, closeBracket);
                // strip color codes
                String clean = inside.replaceAll("&[0-9a-fA-Fk-oK-OrR]", "").trim();
                if (!clean.isEmpty()) return clean;
            }
        }
        return capitalize(tierKey);
    }

    /**
     * Extracts the tier color from "&8[&6Tier&8] ..." → "&6".
     * Falls back to first non-frame color, then "&7".
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
        try { return Material.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return fallback; }
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

    private static int countSkins(Map<String, List<String>> m) {
        int t = 0; for (List<String> v : m.values()) t += v.size(); return t;
    }

    private String safeCurrencyName() {
        try { return plugin.getLang().getCurrencyName(); }
        catch (Throwable t) { return "BellCoins"; }
    }

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
