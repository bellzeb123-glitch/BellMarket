/*
 * BellMarket - BellItemsProvider
 * Auto-generates shop categories from plugins/BellItems/items/*.yml
 */
package pl.bellmarket.provider;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.model.Category;
import pl.bellmarket.model.Product;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BellItemsProvider implements ProductProvider {

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

        bridge.refresh();
        List<Product> products = new ArrayList<>();

        for (String id : bridge.byItemIdForProvider()) {
            var stackOpt = bridge.createShopItem(id);
            var metaOpt = bridge.readMeta(id);
            if (stackOpt.isEmpty() || metaOpt.isEmpty()) continue;

            ItemStack stack = stackOpt.get();
            BellItemsCatalogBridge.CatalogItemMeta meta = metaOpt.get();
            long price = defaultPrice;

            products.add(new Product.Builder()
                .id("bellitems_" + id)
                .type(Product.Type.ITEM)
                .name(meta.displayName())
                .lore(List.of(
                    "&7Source: &fBellItems",
                    "&7ID: &8" + id,
                    "",
                    "&6Price: &e" + price + " BellCoins",
                    "",
                    "&aLeft-click &7to purchase"
                ))
                .price(price)
                .enabled(true)
                .iconMaterial(meta.material() != null ? meta.material() : Material.NETHERITE_SWORD)
                .iconItemModel(meta.itemModel())
                .giveItem(stack)
                .currency(Currency.BELLCOINS)
                .providerSource("bellitems")
                .build());
        }

        if (products.isEmpty()) return Collections.emptyList();

        Category cat = new Category(
            "bellitems_gear",
            "&6⚔ BellItems Gear",
            "Custom weapons & armor from BellItems catalog",
            250,
            true,
            Material.NETHERITE_SWORD,
            "&6⚔ BellItems Gear",
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
}
