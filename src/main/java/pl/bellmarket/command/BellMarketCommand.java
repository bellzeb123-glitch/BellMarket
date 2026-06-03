-e /*
 * BellMarket - Premium Shop Plugin
 * Copyright (c) 2026 BellMarket. All rights reserved.
 * Unauthorized copying, modification or distribution is prohibited.
 */
package pl.bellmarket.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import pl.bellmarket.BellMarket;
import pl.bellmarket.gui.AdminGUI;
import pl.bellmarket.integration.SkinStudioGenerator;

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
                case "generate" -> {
                    if (!player.hasPermission("bellmarket.admin")) {
                        player.sendMessage(plugin.getLang().component("no-permission"));
                        return true;
                    }
                    long price = 100;
                    if (args.length >= 2) {
                        try { price = Long.parseLong(args[1]); } catch (Exception ignored) {}
                    }
                    player.sendMessage(plugin.getLang().colorize(
                        "&8[&6BellMarket&8] &eGenerating categories from SkinStudio..."));
                    final long finalPrice = price;
                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                        SkinStudioGenerator gen = new SkinStudioGenerator(plugin);
                        int count = gen.generate(finalPrice);
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (count < 0) {
                                player.sendMessage(plugin.getLang().colorize(
                                    "&8[&6BellMarket&8] &cSkinStudio not found or error occurred."));
                            } else if (count == 0) {
                                player.sendMessage(plugin.getLang().colorize(
                                    "&8[&6BellMarket&8] &7No new categories generated (all already exist)."));
                            } else {
                                player.sendMessage(plugin.getLang().colorize(
                                    "&8[&6BellMarket&8] &aGenerated &f" + count + "&a category files!"));
                                plugin.reload();
                                player.sendMessage(plugin.getLang().colorize(
                                    "&8[&6BellMarket&8] &aShop reloaded with new categories."));
                            }
                        });
                    });
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
            completions.addAll(List.of("admin", "reload", "generate"));
            String filter = args[0].toLowerCase();
            completions.removeIf(s -> !s.toLowerCase().startsWith(filter));
        }
        return completions;
    }
}
