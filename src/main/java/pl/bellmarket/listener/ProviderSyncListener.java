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
        if (!"SkinStudio".equalsIgnoreCase(event.getPlugin().getName())) return;
        Bukkit.getScheduler().runTask(plugin, plugin::refreshProviderCategories);
    }
}
