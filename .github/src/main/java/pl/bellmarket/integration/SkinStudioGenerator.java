/*
 * BellMarket - Premium Shop Plugin
 * Copyright (c) 2026 BellMarket. All rights reserved.
 * Unauthorized copying, modification or distribution is prohibited.
 */
package pl.bellmarket.integration;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import pl.bellmarket.BellMarket;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class SkinStudioGenerator {

    private final BellMarket plugin;

    // Tier display settings: prefix → [color, order]
    private static final Map<String, Object[]> TIER_META = new LinkedHashMap<>();
    static {
        TIER_META.put("bronze",     new Object[]{"&6Bronze",     1, "ORANGE_STAINED_GLASS_PANE"});
        TIER_META.put("living",     new Object[]{"&aLiving",     2, "LIME_STAINED_GLASS_PANE"});
        TIER_META.put("corrupted",  new Object[]{"&5Corrupted",  3, "PURPLE_STAINED_GLASS_PANE"});
        TIER_META.put("palladium",  new Object[]{"&bPalladium",  4, "CYAN_STAINED_GLASS_PANE"});
        TIER_META.put("ultimatium", new Object[]{"&eUltimatium", 5, "YELLOW_STAINED_GLASS_PANE"});
        TIER_META.put("craftenmine",new Object[]{"&6FMM",        6, "BLAZE_ROD"});
        TIER_META.put("frost",      new Object[]{"&bFrost",      7, "LIGHT_BLUE_STAINED_GLASS_PANE"});
        TIER_META.put("primis",     new Object[]{"&dPrimis",     8, "MAGENTA_STAINED_GLASS_PANE"});
        TIER_META.put("magmaguys",  new Object[]{"&cUnique",     9, "RED_STAINED_GLASS_PANE"});
    }

    // Weapon type → material for icon
    private static final Map<String, String> WEAPON_ICONS = new LinkedHashMap<>();
    static {
        WEAPON_ICONS.put("sword",      "IRON_SWORD");
        WEAPON_ICONS.put("axe",        "IRON_AXE");
        WEAPON_ICONS.put("bow",        "BOW");
        WEAPON_ICONS.put("crossbow",   "CROSSBOW");
        WEAPON_ICONS.put("scythe",     "IRON_HOE");
        WEAPON_ICONS.put("trident",    "TRIDENT");
        WEAPON_ICONS.put("spear",      "TRIDENT");
        WEAPON_ICONS.put("mace",       "MACE");
        WEAPON_ICONS.put("helmet",     "IRON_HELMET");
        WEAPON_ICONS.put("chestplate", "IRON_CHESTPLATE");
        WEAPON_ICONS.put("leggings",   "IRON_LEGGINGS");
        WEAPON_ICONS.put("boots",      "IRON_BOOTS");
        WEAPON_ICONS.put("toothpick",  "STICK");
        WEAPON_ICONS.put("gladius",    "IRON_SWORD");
    }

    public SkinStudioGenerator(BellMarket plugin) {
        this.plugin = plugin;
    }

    /**
     * Generate category files from SkinStudio config.
     * Returns number of categories generated, -1 on error.
     */
    public int generate(long defaultPrice) {
        // Check SkinStudio
        Plugin skinStudio = plugin.getServer().getPluginManager().getPlugin("SkinStudio");
        if (skinStudio == null) {
            plugin.getLogger().warning("SkinStudio not found! Cannot generate categories.");
            return -1;
        }

        // Read SkinStudio config
        File ssConfig = new File(skinStudio.getDataFolder(), "config.yml");
        if (!ssConfig.exists()) {
            plugin.getLogger().warning("SkinStudio config.yml not found at: " + ssConfig.getPath());
            return -1;
        }

        FileConfiguration ss = YamlConfiguration.loadConfiguration(ssConfig);
        ConfigurationSection skins = ss.getConfigurationSection("skins");
        if (skins == null) {
            plugin.getLogger().warning("No 'skins' section in SkinStudio config.yml");
            return -1;
        }

        // Group skins by tier
        Map<String, List<String>> byTier = new LinkedHashMap<>();
        for (String tier : TIER_META.keySet()) byTier.put(tier, new ArrayList<>());
        byTier.put("other", new ArrayList<>());

        for (String skinId : skins.getKeys(false)) {
            String tier = detectTier(skinId);
            byTier.computeIfAbsent(tier, k -> new ArrayList<>()).add(skinId);
        }

        File categoriesDir = new File(plugin.getDataFolder(), "categories");
        if (!categoriesDir.exists()) categoriesDir.mkdirs();

        int generated = 0;

        for (Map.Entry<String, List<String>> entry : byTier.entrySet()) {
            String tier = entry.getKey();
            List<String> tierSkins = entry.getValue();
            if (tierSkins.isEmpty()) continue;

            Object[] meta = TIER_META.getOrDefault(tier,
                new Object[]{"&7" + capitalize(tier), 99, "CHEST"});

            String color     = (String) meta[0];
            int order        = (int) meta[1];
            String iconMat   = (String) meta[2];

            // File name: e.g. 01_bronze_skins.yml
            String fileName  = String.format("%02d_%s_skins.yml", order, tier);
            File outFile     = new File(categoriesDir, fileName);

            // Don't overwrite existing files
            if (outFile.exists()) {
                plugin.getLogger().info("Skipping (already exists): " + fileName);
                continue;
            }

            try {
                StringBuilder sb = new StringBuilder();
                sb.append("# ================================================================\n");
                sb.append("# BellMarket - Generated Category: ").append(color).append(" Skins\n");
                sb.append("# Generated automatically from SkinStudio config.\n");
                sb.append("# To regenerate: /bellmarket generate\n");
                sb.append("# ================================================================\n\n");
                sb.append("category:\n");
                sb.append("  name: \"").append(color).append(" Skins\"\n");
                sb.append("  display-name: \"").append(color).append("\"\n");
                sb.append("  icon:\n");
                sb.append("    material: ").append(iconMat).append("\n");
                sb.append("    name: \"").append(color).append(" Skins\"\n");
                sb.append("    lore:\n");
                sb.append("      - \"&7Browse ").append(color).append("&7 tier skins\"\n");
                sb.append("      - \"\"\n");
                sb.append("      - \"&eClick to open\"\n");
                sb.append("  order: ").append(order).append("\n");
                sb.append("  enabled: true\n\n");
                sb.append("products:\n\n");

                for (String skinId : tierSkins) {
                    ConfigurationSection skinSec = skins.getConfigurationSection(skinId);
                    if (skinSec == null) continue;

                    String displayName = skinSec.getString("display-name", skinId);
                    String itemModel   = skinSec.getString("item-model", "");
                    String weaponType  = detectWeaponType(skinId);
                    String iconMaterial = WEAPON_ICONS.getOrDefault(weaponType, "PAPER");

                    sb.append("  ").append(skinId).append(":\n");
                    sb.append("    type: SKIN_TOKEN\n");
                    sb.append("    skin-id: ").append(skinId).append("\n");
                    sb.append("    name: \"").append(displayName).append("\"\n");
                    sb.append("    lore:\n");
                    sb.append("      - \"&7Applies ").append(color).append("&7 skin.\"\n");
                    sb.append("      - \"&7Use with &e/skinstudio&7 after purchase.\"\n");
                    sb.append("      - \"\"\n");
                    sb.append("      - \"&6Price: &e{price} {currency}\"\n");
                    sb.append("      - \"\"\n");
                    sb.append("      - \"&aLeft-click &7to purchase\"\n");
                    sb.append("    icon:\n");
                    sb.append("      material: ").append(iconMaterial).append("\n");
                    if (!itemModel.isEmpty()) {
                        sb.append("      item-model: \"").append(itemModel).append("\"\n");
                    }
                    sb.append("    price: ").append(defaultPrice).append("\n");
                    sb.append("    include-change-token: true\n");
                    sb.append("    enabled: true\n\n");
                }

                java.nio.file.Files.writeString(outFile.toPath(), sb.toString());
                plugin.getLogger().info("Generated: " + fileName + " (" + tierSkins.size() + " skins)");
                generated++;

            } catch (IOException e) {
                plugin.getLogger().warning("Could not write " + fileName + ": " + e.getMessage());
            }
        }

        return generated;
    }

    private String detectTier(String skinId) {
        for (String tier : TIER_META.keySet()) {
            if (skinId.startsWith(tier + "_") || skinId.startsWith(tier)) {
                return tier;
            }
        }
        return "other";
    }

    private String detectWeaponType(String skinId) {
        String lower = skinId.toLowerCase();
        for (String type : WEAPON_ICONS.keySet()) {
            if (lower.contains("_" + type) || lower.endsWith(type)) {
                return type;
            }
        }
        return "paper";
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
