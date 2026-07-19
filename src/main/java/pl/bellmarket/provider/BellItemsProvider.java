/*
 * BellMarket - BellItemsProvider
 * Jedna auto-sekcja sklepu (bellitems_gear) z katalogu BellItems API.
 */
package pl.bellmarket.provider;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.model.Category;
import pl.bellmarket.model.Product;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BellItemsProvider implements ProductProvider {

    private static final String TEMPLATE = """
# ============================================================
#  BellMarket - BellItems Provider
# ============================================================
# Jedna sekcja: bellitems_gear. Ceny: item-prices.<id> (bez prefiksu bellitems_).
# list-all-items: false = tylko included-items / item-prices (nowe itemy NIE wchodzą same).
# ============================================================

enabled: true
base-order: 250
default-price: ${DEFAULT_PRICE}
list-all-items: true
included-items: []
excluded-items: []

categories:
  gear:
    enabled: true
    display-name: "BellItems Gear"
    icon: NETHERITE_SWORD

item-prices: {}
item-enabled: {}
""";

    private final BellMarket plugin;
    private final BellItemsCatalogBridge bridge;

    public BellItemsProvider(BellMarket plugin, BellItemsCatalogBridge bridge) {
        this.plugin = plugin;
        this.bridge = bridge;
    }

    public BellItemsProvider(BellMarket plugin) {
        this(plugin, plugin.getBellItemsCatalogBridge());
    }

    @Override
    public String getProviderId() {
        return "bellitems";
    }

    @Override
    public boolean isAvailable() {
        return bridge != null && bridge.isAvailable();
    }

    @Override
    public List<Category> generateCategories(long defaultPrice) {
        if (!isAvailable()) return Collections.emptyList();

        FileConfiguration cfg = loadOrCreateProviderConfig(defaultPrice);
        if (!cfg.getBoolean("enabled", true)) return Collections.emptyList();

        var catCfg = cfg.getConfigurationSection("categories.gear");
        if (catCfg != null && !catCfg.getBoolean("enabled", true)) return Collections.emptyList();

        bridge.refresh();
        long globalDefault = cfg.getLong("default-price", defaultPrice);
        boolean listAll = cfg.getBoolean("list-all-items", true);
        Set<String> excluded = new HashSet<>(cfg.getStringList("excluded-items"));
        Set<String> included = new HashSet<>(cfg.getStringList("included-items"));
        var itemPrices = cfg.getConfigurationSection("item-prices");
        var itemEnabled = cfg.getConfigurationSection("item-enabled");
        if (itemPrices != null) {
            included.addAll(itemPrices.getKeys(false));
        }

        List<Product> products = new ArrayList<>();
        for (String id : bridge.byItemIdForProvider()) {
            if (excluded.contains(id)) continue;
            if (!listAll && !included.contains(id)) continue;

            var stackOpt = bridge.createShopItem(id);
            var metaOpt = bridge.readMeta(id);
            if (stackOpt.isEmpty() || metaOpt.isEmpty()) continue;

            ItemStack stack = stackOpt.get();
            BellItemsCatalogBridge.CatalogItemMeta meta = metaOpt.get();
            long price = (itemPrices != null && itemPrices.contains(id))
                    ? itemPrices.getLong(id)
                    : globalDefault;
            boolean buyable = itemEnabled == null || itemEnabled.getBoolean(id, true);

            products.add(new Product.Builder()
                .id("bellitems_" + id)
                .type(Product.Type.ITEM)
                .name(meta.displayName())
                .lore(List.of(
                    "&7Source: &fBellItems",
                    "&7ID: &8" + id,
                    "",
                    buyable
                        ? ("&6Price: &e" + price + " BellCoins")
                        : "&cNiedostepny do kupna",
                    "",
                    buyable ? "&aLeft-click &7to purchase" : "&7Skontaktuj sie z adminem"
                ))
                .price(price)
                .enabled(buyable)
                .iconMaterial(meta.material() != null ? meta.material() : Material.NETHERITE_SWORD)
                .iconItemModel(meta.itemModel())
                .giveItem(stack)
                .currency(Currency.BELLCOINS)
                .providerSource("bellitems")
                .build());
        }

        if (products.isEmpty()) return Collections.emptyList();

        String display = catCfg != null ? catCfg.getString("display-name", "BellItems Gear") : "BellItems Gear";
        Material icon = parseMaterial(catCfg != null ? catCfg.getString("icon") : null, Material.NETHERITE_SWORD);
        int order = cfg.getInt("base-order", 250);
        String catName = "&6⚔ " + display;

        Category cat = new Category(
            "bellitems_gear",
            catName,
            "Custom weapons & armor from BellItems catalog",
            order,
            true,
            icon,
            catName,
            List.of(
                "&7Auto-generated from BellItems",
                "&7Items: &f" + products.size(),
                "",
                "&eClick to browse"
            ),
            products
        );
        return ProductProvider.single(cat);
    }

    private FileConfiguration loadOrCreateProviderConfig(long defaultPrice) {
        File dir = new File(plugin.getDataFolder(), "providers");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "bellitems.yml");
        if (!f.exists()) {
            try {
                Files.writeString(f.toPath(), TEMPLATE.replace("${DEFAULT_PRICE}", String.valueOf(defaultPrice)));
            } catch (IOException e) {
                plugin.getLogger().warning("[BellItemsProvider] " + e.getMessage());
            }
        }
        return YamlConfiguration.loadConfiguration(f);
    }

    private static Material parseMaterial(String n, Material fb) {
        if (n == null || n.isEmpty()) return fb;
        try {
            return Material.valueOf(n.toUpperCase());
        } catch (IllegalArgumentException e) {
            return fb;
        }
    }
}
