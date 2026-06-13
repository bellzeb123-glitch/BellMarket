package pl.bellmarket.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import pl.bellmarket.BellMarket;
import pl.bellmarket.gui.AdminGUI;

/**
 * Routes chat input to whichever admin GUI is awaiting it.
 *
 * Both AdminGUI and PriceEditorGUI can request a chat value (e.g. a new price).
 * This listener checks each in turn and forwards the typed line.
 */
public class AdminChatListener implements Listener {

    private final BellMarket plugin;
    private final AdminGUI adminGUI;

    public AdminChatListener(BellMarket plugin, AdminGUI adminGUI) {
        this.plugin = plugin;
        this.adminGUI = adminGUI;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());

        // 1. Price editor (skin price input)
        var bmCmd = plugin.getBellMarketCommand();
        if (bmCmd != null && bmCmd.getPriceEditor() != null
                && bmCmd.getPriceEditor().isAwaitingInput(player)) {
            event.setCancelled(true);
            bmCmd.getPriceEditor().handleChatInput(player, message);
            return;
        }

        // 2. Admin GUI (future text inputs)
        if (adminGUI != null && adminGUI.isAwaitingInput(player)) {
            event.setCancelled(true);
            adminGUI.handleChatInput(player, message);
        }
    }
}
