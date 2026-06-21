/*
 * BellMarket - BellItemsProvider
 * Auto-generates shop categories from plugins/BellItems/items/*.yml
 */
package pl.bellmarket.provider;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.model.Category;
import pl.bellmarket.model.Product;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class BellItemsProvider implements ProductProvider {

    private final Plugin bellItemsPlugin;

    public BellItemsProvider(BellMarket plugin) {
        this.bellItemsPlugin = plugin.getServer().getPluginManager().getPlugin("BellItems");
    }

    public BellItemsProvider(Plugin bellItemsPlugin) {
        this.bellItemsPlugin = bellItemsPlugin;
    }

    @Override
    public String getProviderId() {
        return "bellitems";
    }

    @Override
    public boolean isAvailable() {
        if (bellItemsPlugin == null) return false;
        try {
            return (boolean) bellItemsPlugin.getClass().getMethod("isEnabled").invoke(bellItemsPlugin);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Category> generateCategories(long defaultPrice) {
        if (!isAvailable()) return Collections.emptyList();

        try {
            Class<?> apiClass = Class.forName("pl.bell.bellitems.api.BellItemsAPI");
            Object api = apiClass.getMethod("get").invoke(null);
            Collection<String> ids = (Collection<String>) apiClass.getMethod("getItemIds").invoke(api);
            if (ids.isEmpty()) return Collections.emptyList();

            Method createItem = apiClass.getMethod("createItem", String.class, int.class);
            Method getDefinition = apiClass.getMethod("getDefinition", String.class);

            long price = defaultPrice;
            List<Product> products = new ArrayList<>();
            for (String id : ids) {
                var defOpt = (java.util.Optional<?>) getDefinition.invoke(api, id);
                if (defOpt.isEmpty()) continue;

                Object def = defOpt.get();
                String displayName = (String) def.getClass().getMethod("getDisplayName").invoke(def);
                Material material = (Material) def.getClass().getMethod("getMaterial").invoke(def);

                var stackOpt = (java.util.Optional<ItemStack>) createItem.invoke(api, id, 1);
                if (stackOpt.isEmpty()) continue;
                ItemStack stack = stackOpt.get();

                products.add(new Product.Builder()
                    .id("bellitems_" + id)
                    .type(Product.Type.ITEM)
                    .name(displayName)
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
                    .iconMaterial(material)
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
        } catch (Throwable t) {
            if (bellItemsPlugin instanceof org.bukkit.plugin.Plugin p) {
                p.getLogger().warning("[BellItemsProvider] " + t.getMessage());
            }
            return Collections.emptyList();
        }
    }
}
