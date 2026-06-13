package pl.bellmarket.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import pl.bellmarket.BellMarket;
import pl.bellmarket.gui.AdminGUI;

public class PlayerListener implements Listener {

    private final BellMarket plugin;

    public PlayerListener(BellMarket plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Initialize balance for new players
        long balance = plugin.getCurrency().getBalance(player.getUniqueId());
        if (balance == 0 && !event.getPlayer().hasPlayedBefore()) {
            long starting = plugin.getConfig().getLong("currency.starting-balance", 0);
            if (starting > 0) {
                plugin.getCurrency().setBalance(player, starting);
                plugin.getLogger().info("Given starting balance of " + starting +
                    " to new player: " + player.getName());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();

        // Check if admin is awaiting input for AdminGUI
        // We need to access AdminGUI through the command
        // AdminGUI is registered as a listener itself and handles its own chat
        // This listener handles additional chat interception if needed

        // Get message as plain text
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());

        // AdminGUI chat handling is done within AdminGUI itself
        // This is a hook point for future extensions
    }
}
