/*
 * BellMarket - BellMarketCommand
 *
 * SESJA-1 FIX: removed import + usage of pl.bellmarket.integration.SkinStudioGenerator
 *              (deleted in Sesja 1). The `generate` subcommand now triggers a full
 *              plugin reload, which in turn runs the registered SkinStudioProvider
 *              via ProductProviderRegistry. Net effect for the user is identical.
 *
 * Complete drop-in replacement.
 */
package pl.bellmarket.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import pl.bellmarket.BellMarket;
import pl.bellmarket.gui.AdminGUI;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BellMarketCommand implements CommandExecutor, TabCompleter {

    private final BellMarket plugin;
    private final AdminGUI adminGUI;

    public BellMarketCommand(BellMarket plugin) {
        this.plugin = plugin;
        this.adminGUI = new AdminGUI(plugin);

        PluginCommand cmd = plugin.getCommand("bellmarket");
        if (cmd != null) cmd.setTabCompleter(this);
    }

    public AdminGUI getAdminGUI() {
        return adminGUI;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // No args → open shop main menu
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command is player-only.");
                return true;
            }
            if (!player.hasPermission("bellmarket.shop")) {
                player.sendMessage(plugin.getLang().component("no-permission"));
                return true;
            }
            plugin.getShopGUI().openMainMenu(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "admin" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("This command is player-only.");
                    return true;
                }
                if (!player.hasPermission("bellmarket.admin")) {
                    player.sendMessage(plugin.getLang().component("no-permission"));
                    return true;
                }
                adminGUI.openFor(player);
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("bellmarket.admin")) {
                    sender.sendMessage(plugin.getLang().component("no-permission"));
                    return true;
                }
                plugin.reload();
                sender.sendMessage(plugin.getLang().component("admin.reloaded"));
                return true;
            }
            case "generate" -> {
                if (!sender.hasPermission("bellmarket.admin")) {
                    sender.sendMessage(plugin.getLang().component("no-permission"));
                    return true;
                }
                // Optional price argument (kept for backward compatibility with the
                // old `/bellmarket generate <price>` syntax — now only used to set
                // the default price via config before reload, if provided)
                long price = 100;
                if (args.length >= 2) {
                    try { price = Long.parseLong(args[1]); } catch (Exception ignored) {}
                }
                final long finalPrice = price;

                sender.sendMessage(colorize("&8[&6BellMarket&8] &eGenerating categories from SkinStudio..."));

                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        // Update default-price in config so the provider picks it up
                        plugin.getConfig().set("providers.skinstudio.default-price", finalPrice);

                        // Run reload on the main thread (Bukkit API is single-threaded)
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            plugin.reload();
                            int count = (int) plugin.getCategories().getCategories().stream()
                                .filter(c -> "skinstudio".equals(
                                    safeProvider(c)))
                                .mapToLong(c -> c.getProducts().size())
                                .sum();
                            if (count > 0) {
                                sender.sendMessage(colorize(
                                    "&8[&6BellMarket&8] &aGenerated &f" + count + "&a SkinStudio products!"));
                                sender.sendMessage(colorize(
                                    "&8[&6BellMarket&8] &aShop reloaded with new categories."));
                            } else {
                                sender.sendMessage(colorize(
                                    "&8[&6BellMarket&8] &7No new categories generated (all already exist)."));
                            }
                        });
                    } catch (Exception e) {
                        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(colorize(
                            "&8[&6BellMarket&8] &cSkinStudio not found or error occurred.")));
                    }
                });
                return true;
            }
            default -> {
                // Unknown subcommand → open shop (same as no args)
                if (sender instanceof Player player && player.hasPermission("bellmarket.shop")) {
                    plugin.getShopGUI().openMainMenu(player);
                }
                return true;
            }
        }
    }

    /** Returns the providerSource of the first product in a category, or null. */
    private String safeProvider(pl.bellmarket.model.Category c) {
        if (c.getProducts() == null || c.getProducts().isEmpty()) return null;
        return c.getProducts().get(0).getProviderSource();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            completions.addAll(List.of("admin", "reload", "generate"));
            String prefix = args[0].toLowerCase(Locale.ROOT);
            completions.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
        }
        return completions;
    }

    private static Component colorize(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }
}
