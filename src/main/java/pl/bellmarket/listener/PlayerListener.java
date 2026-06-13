package pl.bellmarket.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import pl.bellmarket.BellMarket;

public class PlayerListener implements Listener {
    private final BellMarket plugin;
    public PlayerListener(BellMarket plugin) { this.plugin = plugin; }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Initialize player balance if needed (lazy init)
        plugin.getCurrency().getBalance(event.getPlayer());
    }
}
