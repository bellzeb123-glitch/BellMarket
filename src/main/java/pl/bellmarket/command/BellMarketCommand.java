package pl.bellmarket.command;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import pl.bellmarket.BellMarket;
import pl.bellmarket.config.LangManager;

import java.util.List;
import java.util.Locale;

public class BellMarketCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS  = List.of("admin", "reload", "generate", "lang", "prices");
    private static final List<String> LANGS = List.of("en", "pl");

    private final BellMarket plugin;

    public BellMarketCommand(BellMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        LangManager lang = plugin.getLang();

        // No args → open shop
        if (args.length == 0) {
            return openShop(sender, lang);
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "admin" -> { return openAdmin(sender, lang); }
            case "reload" -> { return doReload(sender, lang); }
            case "generate" -> { return doGenerate(sender, lang); }
            case "lang" -> { return doLang(sender, lang, args); }
            case "prices" -> { return openPrices(sender, lang); }
            default -> { return openShop(sender, lang); }
        }
    }

    private boolean openShop(CommandSender sender, LangManager lang) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Player only.");
            return true;
        }
        if (!player.hasPermission("bellmarket.shop")) {
            player.sendMessage(lang.component("no-permission"));
            return true;
        }
        plugin.getShopGUI().openMainMenu(player);
        return true;
    }

    private boolean openAdmin(CommandSender sender, LangManager lang) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Player only.");
            return true;
        }
        if (!player.hasPermission("bellmarket.admin")) {
            player.sendMessage(lang.component("no-permission"));
            return true;
        }
        plugin.getAdminGUI().openFor(player);
        return true;
    }

    private boolean doReload(CommandSender sender, LangManager lang) {
        if (!sender.hasPermission("bellmarket.admin")) {
            sender.sendMessage(lang.component("no-permission"));
            return true;
        }
        plugin.reload();
        sender.sendMessage(plugin.getLang().component("admin.reloaded"));
        return true;
    }

    private boolean doGenerate(CommandSender sender, LangManager lang) {
        if (!sender.hasPermission("bellmarket.admin")) {
            sender.sendMessage(lang.component("no-permission"));
            return true;
        }
        sender.sendMessage(LangManager.colorize("&8[&6BellMarket&8] &eGenerating from SkinStudio..."));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.reload();
            Bukkit.getScheduler().runTask(plugin, () ->
                    sender.sendMessage(plugin.getLang().component("admin.reloaded")));
        });
        return true;
    }

    private boolean doLang(CommandSender sender, LangManager lang, String[] args) {
        if (!sender.hasPermission("bellmarket.admin")) {
            sender.sendMessage(lang.component("no-permission"));
            return true;
        }
        if (args.length < 2) {
            String current = plugin.getConfig().getString("language", "en").toUpperCase();
            sender.sendMessage(lang.component("admin.language-current", "lang", current));
            sender.sendMessage(LangManager.colorize("&7Usage: &f/bm lang <en|pl>"));
            return true;
        }
        String newLang = args[1].toLowerCase(Locale.ROOT);
        if (!LANGS.contains(newLang)) {
            sender.sendMessage(LangManager.colorize("&cAvailable: en, pl"));
            return true;
        }
        plugin.getConfig().set("language", newLang);
        plugin.saveConfig();
        plugin.reload();
        sender.sendMessage(plugin.getLang().component("admin.language-switched",
                "lang", newLang.toUpperCase()));
        return true;
    }

    private boolean openPrices(CommandSender sender, LangManager lang) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Player only.");
            return true;
        }
        if (!player.hasPermission("bellmarket.admin")) {
            player.sendMessage(lang.component("no-permission"));
            return true;
        }
        plugin.getPriceEditor().openTierList(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return SUBS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("lang")) {
            return LANGS.stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
