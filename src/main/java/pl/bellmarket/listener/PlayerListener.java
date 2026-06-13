package pl.bellmarket.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import pl.bellmarket.BellMarket;

public class PlayerListener implements Listener {

    private final BellMarket plugin;

    public PlayerListener(BellMarket plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        long balance = plugin.getCurrency().getBalance(player.getUniqueId());

        // First join — give starting balance
        if (!player.hasPlayedBefore()) {
            long starting = plugin.getConfig().getLong("currency.starting-balance", 0);
            if (starting > 0) {
                plugin.getCurrency().setBalance(player.getUniqueId(), starting);
                plugin.getLogger().info("Given starting balance of " + starting
                        + " to new player: " + player.getName());
            }
        }
    }
}
