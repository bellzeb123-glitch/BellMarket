/*
 * BellMarket - BellMarketCommand (FIXES4)
 *
 * Change: /bm for VIP players opens featured category directly.
 * Non-VIP players see the main menu as before.
 */
package pl.bellmarket.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import pl.bellmarket.BellMarket;
import pl.bellmarket.gui.AdminGUI;
import pl.bellmarket.gui.PriceEditorGUI;
import pl.bellmarket.model.Category;

import java.util.*;

public class BellMarketCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("admin", "reload", "generate", "lang", "prices");
    private static final List<String> LANGS = List.of("en", "pl");

    private final BellMarket plugin;
    private final AdminGUI adminGUI;
    private final PriceEditorGUI priceEditor;

    public BellMarketCommand(BellMarket plugin) {
        this.plugin = plugin;
        this.adminGUI = new AdminGUI(plugin);
        this.priceEditor = new PriceEditorGUI(plugin);
        PluginCommand cmd = plugin.getCommand("bellmarket");
        if (cmd != null) cmd.setTabCompleter(this);
    }

    public AdminGUI getAdminGUI()          { return adminGUI; }
    public PriceEditorGUI getPriceEditor() { return priceEditor; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return openShop(sender);
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "admin"    -> openAdmin(sender);
            case "reload"   -> doReload(sender);
            case "generate" -> doGenerate(sender, args);
            case "lang"     -> doLang(sender, args);
            case "prices"   -> openPrices(sender);
            default         -> openShop(sender);
        };
    }

    private boolean openShop(CommandSender sender) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Player only."); return true; }
        if (!player.hasPermission("bellmarket.shop")) {
            player.sendMessage(plugin.getLang().component("no-permission")); return true;
        }
        // VIP players go directly to featured category
        String featuredId = plugin.getConfig().getString("shop.featured-category-id", "");
        if (!featuredId.isEmpty()) {
            Category featured = plugin.getCategories().getCategory(featuredId);
            if (featured != null && plugin.getCategories().canSee(player, featured)) {
                plugin.getShopGUI().openCategory(player, featuredId, 0);
                return true;
            }
        }
        plugin.getShopGUI().openMainMenu(player);
        return true;
    }

    private boolean openAdmin(CommandSender sender) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Player only."); return true; }
        if (!player.hasPermission("bellmarket.admin")) {
            player.sendMessage(plugin.getLang().component("no-permission")); return true;
        }
        adminGUI.openFor(player); return true;
    }

    private boolean doReload(CommandSender sender) {
        if (!sender.hasPermission("bellmarket.admin")) {
            sender.sendMessage(plugin.getLang().component("no-permission")); return true;
        }
        plugin.reload();
        sender.sendMessage(plugin.getLang().component("admin.reloaded")); return true;
    }

    private boolean doGenerate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bellmarket.admin")) {
            sender.sendMessage(plugin.getLang().component("no-permission")); return true;
        }
        long price = 100;
        if (args.length >= 2) { try { price = Long.parseLong(args[1]); } catch (Exception ignored) {} }
        final long fp = price;
        sender.sendMessage(colorize("&8[&6BellMarket&8] &eGenerating from SkinStudio..."));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.getConfig().set("providers.skinstudio.default-price", fp);
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.reload();
                int count = plugin.getCategories().getCategories().stream()
                    .filter(c -> c.getId().startsWith("skinstudio_"))
                    .mapToInt(c -> c.getProducts().size()).sum();
                sender.sendMessage(colorize(count > 0
                    ? "&8[&6BellMarket&8] &aGenerated &f" + count + "&a SkinStudio products!"
                    : "&8[&6BellMarket&8] &7No SkinStudio products generated."));
            });
        });
        return true;
    }

    private boolean doLang(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bellmarket.admin")) {
            sender.sendMessage(plugin.getLang().component("no-permission")); return true;
        }
        if (args.length < 2) {
            sender.sendMessage(colorize("&8[&6BellMarket&8] &7Current language: &f"
                + plugin.getConfig().getString("language", "en")));
            sender.sendMessage(colorize("&7Usage: &f/bm lang <en|pl>"));
            return true;
        }
        String lang = args[1].toLowerCase(Locale.ROOT);
        if (!LANGS.contains(lang)) { sender.sendMessage(colorize("&cAvailable: en, pl")); return true; }
        plugin.getConfig().set("language", lang);
        plugin.saveConfig();
        plugin.getLang().reload();
        sender.sendMessage(colorize("&8[&6BellMarket&8] &aLanguage switched to: &f" + lang));
        return true;
    }

    private boolean openPrices(CommandSender sender) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Player only."); return true; }
        if (!player.hasPermission("bellmarket.admin")) {
            player.sendMessage(plugin.getLang().component("no-permission")); return true;
        }
        priceEditor.openTierList(player); return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) return SUBS.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        if (args.length == 2 && "lang".equalsIgnoreCase(args[0]))
            return LANGS.stream().filter(l -> l.startsWith(args[1].toLowerCase())).toList();
        return List.of();
    }

    private Component colorize(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }
}
