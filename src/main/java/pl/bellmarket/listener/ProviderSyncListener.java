package pl.bellmarket.listener;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import pl.bellmarket.BellMarket;

/** Re-syncs provider categories when SkinStudio (or other deps) finish enabling. */
public class ProviderSyncListener implements Listener {

    private final BellMarket plugin;

    public ProviderSyncListener(BellMarket plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        String name = event.getPlugin().getName();
        if ("SkinStudio".equalsIgnoreCase(name) || "BellItems".equalsIgnoreCase(name)) {
            if (plugin.getBellItemsCatalogBridge() != null) {
                plugin.getBellItemsCatalogBridge().refresh();
            }
            if ("BellItems".equalsIgnoreCase(name)
                && plugin.getProviderRegistry().get("bellitems").isEmpty()) {
                plugin.getProviderRegistry().register(
                    new pl.bellmarket.provider.BellItemsProvider(
                        plugin, plugin.getBellItemsCatalogBridge()));
            }
            Bukkit.getScheduler().runTask(plugin, plugin::refreshProviderCategories);
        }
    }
}
