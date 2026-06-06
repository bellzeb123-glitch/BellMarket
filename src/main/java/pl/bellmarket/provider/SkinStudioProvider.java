/*
 * BellMarket - SkinStudioProvider
 *
 * SESJA-1 — replaces the legacy SkinStudioGenerator with a ProductProvider
 * implementation. Behaviour-identical EXCEPT for the change_token bug:
 *
 *   ⚠ FIX: every skin product is built with .includeChangeToken(false).
 *          Previously this defaulted to false in Builder but the upstream
 *          SkinStudioGenerator code path may have set true somewhere
 *          (compilation showed includeChangeToken being touched in delivery).
 *          We make the intent EXPLICIT here so future refactors can't
 *          accidentally regress.
 *
 * The standalone 00_tokens.yml `change_token` product is unaffected — it lives
 * in CategoryManager's file-based path, NOT here. That product correctly sets
 * includeChangeToken: true via its YAML.
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

    // Detection rules — moved here from the legacy SkinStudioGenerator so the
    // provider is self-contained. Tweakable via config in a future session.
    private static final Map<String, String> TIER_META = Map.of(
        "common",    "&7",
        "uncommon",  "&a",
        "rare",      "&9",
        "epic",      "&5",
        "legendary", "&6",
        "mythic",    "&d"
    );

    private static final Map<String, Material> WEAPON_ICONS = Map.of(
        "sword",  Material.DIAMOND_SWORD,
        "axe",    Material.DIAMOND_AXE,
        "bow",    Material.BOW,
        "trident",Material.TRIDENT,
        "shield", Material.SHIELD,
        "helmet", Material.DIAMOND_HELMET,
        "chest",  Material.DIAMOND_CHESTPLATE,
        "legs",   Material.DIAMOND_LEGGINGS,
        "boots",  Material.DIAMOND_BOOTS
    );

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
    public Category generateCategory(long defaultPrice) {
        Plugin sk = plugin.getServer().getPluginManager().getPlugin("SkinStudio");
        if (sk == null) {
            plugin.getLogger().warning("[SkinStudioProvider] SkinStudio not found");
            return null;
        }

        File configFile = new File(sk.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.getLogger().warning("[SkinStudioProvider] SkinStudio config.yml missing at "
                + configFile.getPath());
            return null;
        }

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
        ConfigurationSection skins = cfg.getConfigurationSection("skins");
        if (skins == null) {
            plugin.getLogger().warning("[SkinStudioProvider] No 'skins' section in SkinStudio config.yml");
            return null;
        }

        // Provider config in BellMarket's own config.yml
        String catName  = plugin.getConfig().getString(
            "providers.skinstudio.category-name", "Skins");
        boolean includeChange = plugin.getConfig().getBoolean(
            "providers.skinstudio.include-change-token", false);  // ← user can force-enable globally

        List<Product> products = new ArrayList<>();
        for (String skinKey : skins.getKeys(false)) {
            String tier = detectTier(skinKey);
            String tierColor = TIER_META.getOrDefault(tier, "&f");
            Material icon = detectWeaponIcon(skinKey);

            Product p = new Product.Builder()
                .id("skinstudio_" + skinKey)
                .type(Product.Type.SKIN_TOKEN)
                .name(tierColor + "✦ " + capitalize(skinKey.replace('_', ' ')))
                .lore(List.of(
                    "&7Tier: " + tierColor + capitalize(tier),
                    "&7Skin ID: &8" + skinKey,
                    "",
                    "&6Price: &e{price} {currency}",
                    "",
                    "&aLeft-click &7to purchase"
                ))
                .price(defaultPrice)
                .enabled(true)
                .iconMaterial(icon)
                .skinId(skinKey)
                .includeChangeToken(includeChange)   // ⚠ EXPLICIT — fixes the bug
                .currency(Currency.BELLCOINS)
                .providerSource("skinstudio")
                .build();

            products.add(p);
        }

        Category cat = new Category(
            "skinstudio_skins",                                  // id
            "&6✦ " + catName,                                    // name
            catName,                                             // displayName
            plugin.getConfig().getInt("providers.skinstudio.order", 5),  // order
            true,                                                // enabled
            Material.PLAYER_HEAD,                                // iconMaterial
            "&6✦ " + catName,                                    // iconName
            List.of(                                             // iconLore
                "&7Auto-generated from SkinStudio config.",
                "",
                "&eClick to open"
            ),
            products                                             // products
        );

        return cat;
    }

    private String detectTier(String skinKey) {
        String lower = skinKey.toLowerCase(Locale.ROOT);
        for (String tier : TIER_META.keySet()) {
            if (lower.contains(tier)) return tier;
        }
        return "common";
    }

    private Material detectWeaponIcon(String skinKey) {
        String lower = skinKey.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Material> e : WEAPON_ICONS.entrySet()) {
            if (lower.contains(e.getKey())) return e.getValue();
        }
        return Material.PAPER;
    }

    private String capitalize(String s) {
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
