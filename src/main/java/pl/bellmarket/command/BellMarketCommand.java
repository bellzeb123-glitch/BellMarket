package pl.bellmarket.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import pl.bellmarket.BellMarket;
import pl.bellmarket.gui.AdminGUI;

import java.util.ArrayList;
import java.util.List;

public class BellMarketCommand implements CommandExecutor, TabCompleter {

    private final BellMarket plugin;
    private final AdminGUI adminGUI;

    public BellMarketCommand(BellMarket plugin) {
        this.plugin = plugin;
        this.adminGUI = new AdminGUI(plugin);
        plugin.getCommand("bellmarket").setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is player-only.");
            return true;
        }

        if (args.length > 0) {
            switch (args[0].toLowerCase()) {
                case "admin" -> {
                    if (!player.hasPermission("bellmarket.admin")) {
                        player.sendMessage(plugin.getLang().component("no-permission"));
                        return true;
                    }
                    adminGUI.openFor(player);
                    return true;
                }
                case "reload" -> {
                    if (!player.hasPermission("bellmarket.admin")) {
                        player.sendMessage(plugin.getLang().component("no-permission"));
                        return true;
                    }
                    plugin.reload();
                    player.sendMessage(plugin.getLang().component("admin.reloaded"));
                    return true;
                }
            }
        }

        if (!player.hasPermission("bellmarket.shop")) {
            player.sendMessage(plugin.getLang().component("no-permission"));
            return true;
        }

        plugin.getShopGUI().openMainMenu(player);
        return true;
    }

    public AdminGUI getAdminGUI() { return adminGUI; }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1 && sender.hasPermission("bellmarket.admin")) {
            completions.addAll(List.of("admin", "reload"));
            String filter = args[0].toLowerCase();
            completions.removeIf(s -> !s.toLowerCase().startsWith(filter));
        }
        return completions;
    }
}
