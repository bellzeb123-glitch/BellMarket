package pl.bellmarket.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import pl.bellmarket.BellMarket;
import pl.bellmarket.gui.AdminGUI;

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
        if (!adminGUI.isAwaitingInput(player)) return;

        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());

        player.getServer().getScheduler().runTask(plugin,
            () -> adminGUI.handleChatInput(player, message));
    }
}
