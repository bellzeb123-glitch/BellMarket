/*
 * BellMarket - SkinStudioProvider
 *
 * SESJA-1.1: groups skins by tier prefix into separate categories, mirroring
 * the legacy 01_bronze / 02_living / ... layout (but generated in memory,
 * never written to disk — so the include-change-token bug cannot return).
 *
 * Tier detection: prefix BEFORE first underscore of skin key.
 *   bronze_sword     → tier "bronze"
 *   living_axe       → tier "living"
 *   frost_palace_*   → tier "frost"   (only first segment matters)
 *
 * Icons: reads SkinStudio config:
 *   - display-name → product name (preserves original PL formatting)
 *   - item-model   → setItemModel (3D preview in shop GUI)
 *   - item-types[0] → icon material (first compatible material)
 *
 * Unknown tiers fall back to generic gray category at order 999.
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
import java.util.*;

public class SkinStudioProvider implements ProductProvider {

    private final BellMarket plugin;

    /** Per-tier metadata: display name, color, icon material, order. */
    private record TierMeta(String displayName, String color, Material icon, int order) {}

    private static final Map<String, TierMeta> TIER_META = new LinkedHashMap<>();
    static {
        TIER_META.put("bronze",      new TierMeta("Brąz",        "&6", Material.COPPER_INGOT,           10));
        TIER_META.put("living",      new TierMeta("Żyjący",      "&a", Material.SLIME_BLOCK,            20));
        TIER_META.put("corrupted",   new TierMeta("Skażony",     "&5", Material.WITHER_SKELETON_SKULL,  30));
        TIER_META.put("palladium",   new TierMeta("Palladium",   "&b", Material.NETHERITE_INGOT,        40));
        TIER_META.put("ultimatium",  new TierMeta("Ultimatium",  "&e", Material.DIAMOND_BLOCK,          50));
        TIER_META.put("craftenmine", new TierMeta("Craftenmine", "&6", Material.NETHERITE_BLOCK,        60));
        TIER_META.put("frost",       new TierMeta("Frost",       "&b", Material.ICE,                    70));
        TIER_META.put("primis",      new TierMeta("Primis",      "&d", Material.AMETHYST_BLOCK,         80));
        TIER_META.put("magmaguys",   new TierMeta("MagmaGuys",   "&c", Material.MAGMA_BLOCK,            90));
    }

    public SkinStudioProvider(BellMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getProviderId() { return "skinstudio"; }

    @Override
    public boolean isAvailable() {
        Plugin sk = plugin.getServer().getPluginManager().getPlugin("SkinStudio");
        return sk != null && sk.isEnabled();
    }

    @Override
    public List<Category> generateCategories(long defaultPrice) {
        Plugin sk = plugin.getServer().getPluginManager().getPlugin("SkinStudio");
        if (sk == null) {
            plugin.getLogger().warning("[SkinStudioProvider] SkinStudio not found");
            return Collections.emptyList();
        }

        File configFile = new File(sk.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.getLogger().warning("[SkinStudioProvider] SkinStudio config.yml missing");
            return Collections.emptyList();
        }

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
        ConfigurationSection skins = cfg.getConfigurationSection("skins");
        if (skins == null) {
            plugin.getLogger().warning("[SkinStudioProvider] No 'skins' section");
            return Collections.emptyList();
        }

        // Plugin config: provider-level overrides
        boolean includeChange = plugin.getConfig().getBoolean(
            "providers.skinstudio.include-change-token", false);
        int baseOrder = plugin.getConfig().getInt(
            "providers.skinstudio.order", 10);

        // Group skin keys by tier
        Map<String, List<String>> byTier = new LinkedHashMap<>();
        for (String skinKey : skins.getKeys(false)) {
            String tier = tierOf(skinKey);
            byTier.computeIfAbsent(tier, k -> new ArrayList<>()).add(skinKey);
        }

        // Build one Category per tier
        List<Category> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> tierEntry : byTier.entrySet()) {
            String tierKey = tierEntry.getKey();
            List<String> tierSkins = tierEntry.getValue();
            TierMeta meta = TIER_META.getOrDefault(tierKey, defaultTierMeta(tierKey));

            List<Product> products = new ArrayList<>();
            for (String skinKey : tierSkins) {
                ConfigurationSection sd = skins.getConfigurationSection(skinKey);
                if (sd == null) continue;
                Product p = buildSkinProduct(skinKey, sd, defaultPrice, includeChange, meta);
                if (p != null) products.add(p);
            }

            if (products.isEmpty()) continue;

            String catName = meta.color() + "✦ " + meta.displayName();
            List<String> catLore = List.of(
                "&7Tier: " + meta.color() + meta.displayName(),
                "&7Skins available: &f" + products.size(),
                "",
                "&eClick to open"
            );

            Category cat = new Category(
                "skinstudio_" + tierKey,                      // id
                catName,                                       // name (raw)
                meta.color() + meta.displayName(),             // displayName (clean)
                baseOrder + meta.order(),                      // order
                true,                                          // enabled
                meta.icon(),                                   // icon material
                catName,                                       // icon name
                catLore,                                       // icon lore
                products                                       // products
            );
            out.add(cat);
        }

        return out;
    }

    private Product buildSkinProduct(String skinKey, ConfigurationSection sd,
                                     long defaultPrice, boolean includeChange,
                                     TierMeta meta) {
        // From SkinStudio config:
        String displayName = sd.getString("display-name",
            meta.color() + capitalize(skinKey.replace('_', ' ')));
        String itemModel = sd.getString("item-model", null);
        List<String> itemTypes = sd.getStringList("item-types");

        // First listed item-type as the icon material; fallback PAPER
        Material iconMat = Material.PAPER;
        if (!itemTypes.isEmpty()) {
            try {
                iconMat = Material.valueOf(itemTypes.get(0).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {}
        }

        List<String> lore = List.of(
            "&7Skin ID: &8" + skinKey,
            "&7Tier: " + meta.color() + meta.displayName(),
            "",
            "&6Cena: &e" + defaultPrice + " BellCoins",
            "",
            "&aKliknij LPM &7aby kupić"
        );

        return new Product.Builder()
            .id("skinstudio_" + skinKey)
            .type(Product.Type.SKIN_TOKEN)
            .name(displayName)
            .lore(lore)
            .price(defaultPrice)
            .enabled(true)
            .iconMaterial(iconMat)
            .iconItemModel(itemModel)          // ← 3D preview from resource pack
            .skinId(skinKey)
            .includeChangeToken(includeChange) // ← explicit false unless config says otherwise
            .currency(Currency.BELLCOINS)
            .providerSource("skinstudio")
            .build();
    }

    /** Extract tier from key like "bronze_sword" → "bronze". */
    private static String tierOf(String skinKey) {
        int us = skinKey.indexOf('_');
        return us > 0 ? skinKey.substring(0, us).toLowerCase(Locale.ROOT) : "other";
    }

    private static TierMeta defaultTierMeta(String tier) {
        return new TierMeta(capitalize(tier), "&7", Material.PAPER, 999);
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
}
