/*
 * BellMarket - BellMarketCommand (SESJA-2)
 *
 * Sesja 2 additions:
 *   + /bm lang [en|pl]      — language switcher (changes config + reloads lang)
 *   + /bm prices            — open the in-game price editor GUI
 *   + tab completions for both
 *
 * Existing subcommands preserved unchanged:
 *   /bm                     — open shop main menu
 *   /bm admin               — open admin GUI
 *   /bm reload              — reload plugin
 *   /bm generate [price]    — regenerate from SkinStudio
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
import pl.bellmarket.gui.PriceEditorGUI;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    public AdminGUI getAdminGUI()             { return adminGUI; }
    public PriceEditorGUI getPriceEditor()    { return priceEditor; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return openShop(sender);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "admin"    -> openAdmin(sender);
            case "reload"   -> doReload(sender);
            case "generate" -> doGenerate(sender, args);
            case "lang"     -> doLang(sender, args);
            case "prices"   -> openPrices(sender);
            default         -> openShop(sender);
        };
    }

    // ─── existing subcommands ─────────────────────────────────────────────

    private boolean openShop(CommandSender sender) {
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

    private boolean openAdmin(CommandSender sender) {
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

    private boolean doReload(CommandSender sender) {
        if (!sender.hasPermission("bellmarket.admin")) {
            sender.sendMessage(plugin.getLang().component("no-permission"));
            return true;
        }
        plugin.reload();
        sender.sendMessage(plugin.getLang().component("admin.reloaded"));
        return true;
    }

    private boolean doGenerate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bellmarket.admin")) {
            sender.sendMessage(plugin.getLang().component("no-permission"));
            return true;
        }
        long price = 100;
        if (args.length >= 2) {
            try { price = Long.parseLong(args[1]); } catch (Exception ignored) {}
        }
        final long finalPrice = price;
        sender.sendMessage(colorize("&8[&6BellMarket&8] &eGenerating categories from SkinStudio..."));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getConfig().set("providers.skinstudio.default-price", finalPrice);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.reload();
                    int count = plugin.getCategories().getCategories().stream()
                        .filter(c -> c.getId() != null && c.getId().startsWith("skinstudio_"))
                        .mapToInt(c -> c.getProducts().size()).sum();
                    if (count > 0) {
                        sender.sendMessage(colorize("&8[&6BellMarket&8] &aGenerated &f" + count + "&a SkinStudio products!"));
                    } else {
                        sender.sendMessage(colorize("&8[&6BellMarket&8] &7No SkinStudio products generated."));
                    }
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(colorize(
                    "&8[&6BellMarket&8] &cSkinStudio not found or error occurred.")));
            }
        });
        return true;
    }

    // ─── SESJA-2: /bm lang ────────────────────────────────────────────────

    private boolean doLang(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bellmarket.admin")) {
            sender.sendMessage(plugin.getLang().component("no-permission"));
            return true;
        }
        if (args.length < 2) {
            String current = plugin.getConfig().getString("language", "en");
            sender.sendMessage(colorize("&8[&6BellMarket&8] &7Current language: &f" + current));
            sender.sendMessage(colorize("&7Usage: &f/bm lang <en|pl>"));
            return true;
        }
        String lang = args[1].toLowerCase(Locale.ROOT);
        if (!LANGS.contains(lang)) {
            sender.sendMessage(colorize("&cUnsupported language. Available: en, pl"));
            return true;
        }
        plugin.getConfig().set("language", lang);
        plugin.saveConfig();
        plugin.getLang().reload();
        sender.sendMessage(colorize("&8[&6BellMarket&8] &aLanguage switched to: &f" + lang));
        return true;
    }

    // ─── SESJA-2: /bm prices ──────────────────────────────────────────────

    private boolean openPrices(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is player-only.");
            return true;
        }
        if (!player.hasPermission("bellmarket.admin")) {
            player.sendMessage(plugin.getLang().component("no-permission"));
            return true;
        }
        priceEditor.openTierList(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String pre = args[0].toLowerCase(Locale.ROOT);
            for (String s : SUBS) if (s.startsWith(pre)) out.add(s);
            return out;
        }
        if (args.length == 2 && "lang".equalsIgnoreCase(args[0])) {
            String pre = args[1].toLowerCase(Locale.ROOT);
            for (String l : LANGS) if (l.startsWith(pre)) out.add(l);
            return out;
        }
        return out;
    }

    private static Component colorize(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }
}
