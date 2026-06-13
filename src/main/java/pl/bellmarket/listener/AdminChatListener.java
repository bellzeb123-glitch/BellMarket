package pl.bellmarket.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import pl.bellmarket.BellMarket;
import pl.bellmarket.gui.AdminGUI;
import pl.bellmarket.gui.PriceEditorGUI;

public class AdminChatListener implements Listener {

    private final BellMarket plugin;
    private final AdminGUI adminGUI;
    private final PriceEditorGUI priceEditor;

    public AdminChatListener(BellMarket plugin, AdminGUI adminGUI, PriceEditorGUI priceEditor) {
        this.plugin = plugin;
        this.adminGUI = adminGUI;
        this.priceEditor = priceEditor;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();

        // Check PriceEditorGUI first (price input)
        if (priceEditor.isAwaitingInput(player)) {
            event.setCancelled(true);
            String text = PlainTextComponentSerializer.plainText().serialize(event.message());
            // Run on main thread
            player.getServer().getScheduler().runTask(plugin, () ->
                    priceEditor.handleChatInput(player, text));
            return;
        }

        // Check AdminGUI (rename input, future)
        if (adminGUI.isAwaitingInput(player)) {
            event.setCancelled(true);
            String text = PlainTextComponentSerializer.plainText().serialize(event.message());
            player.getServer().getScheduler().runTask(plugin, () ->
                    adminGUI.handleChatInput(player, text));
        }
    }
}
