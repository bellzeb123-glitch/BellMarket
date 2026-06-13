package pl.bellmarket.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import pl.bellmarket.BellMarket;
import pl.bellmarket.gui.AdminGUI;
import pl.bellmarket.gui.PriceEditorGUI;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BellMarketCommand implements CommandExecutor, TabCompleter {

    private static final List<String> LANGS = List.of("en", "pl");

    private final BellMarket plugin;
    private final AdminGUI adminGUI;
    private final PriceEditorGUI priceEditor;

    public BellMarketCommand(BellMarket plugin) {
        this.plugin = plugin;
        this.adminGUI = new AdminGUI(plugin);
        this.priceEditor = new PriceEditorGUI(plugin);
    }

    public AdminGUI getAdminGUI()           { return adminGUI; }
    public PriceEditorGUI getPriceEditor()  { return priceEditor; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // /bm — open the shop
            if (!(sender instanceof Player player)) {
                sender.sendMessage(c(plugin.getLang().getRaw("player-only")));
                return true;
            }
            plugin.getShopGUI().openMainMenu(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "admin"    -> { return doAdmin(sender); }
            case "reload"   -> { return doReload(sender); }
            case "generate" -> { return doReload(sender); }
            case "prices"   -> { return doPrices(sender); }
            case "lang"     -> { return doLang(sender, args); }
            default -> {
                if (sender instanceof Player player) {
                    plugin.getShopGUI().openMainMenu(player);
                } else {
                    sender.sendMessage(c("&7Usage: &f/bm [admin|reload|prices|lang]"));
                }
                return true;
            }
        }
    }

    private boolean doAdmin(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(c(plugin.getLang().getRaw("player-only")));
            return true;
        }
        if (!player.hasPermission("bellmarket.admin")) {
            player.sendMessage(c(plugin.getLang().getRaw("no-permission")));
            return true;
        }
        adminGUI.openFor(player);
        return true;
    }

    private boolean doPrices(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(c(plugin.getLang().getRaw("player-only")));
            return true;
        }
        if (!player.hasPermission("bellmarket.admin")) {
            player.sendMessage(c(plugin.getLang().getRaw("no-permission")));
            return true;
        }
        priceEditor.openTierList(player);
        return true;
    }

    private boolean doReload(CommandSender sender) {
        if (!sender.hasPermission("bellmarket.admin")) {
            sender.sendMessage(c(plugin.getLang().getRaw("no-permission")));
            return true;
        }
        try {
            plugin.reload();
            sender.sendMessage(plugin.getLang().component("admin.reloaded"));
        } catch (Exception e) {
            sender.sendMessage(c("&cReload failed: " + e.getMessage()));
            plugin.getLogger().warning("Reload error: " + e.getMessage());
        }
        return true;
    }

    /**
     * /bm lang <en|pl> — switches language GLOBALLY.
     *
     * Fix: previously only LangManager.reload() ran, so the shop GUI and other
     * managers kept the old language. Now we call the full plugin.reload(),
     * which reloads config + lang + categories + providers — so the change is
     * reflected everywhere (/bm, /bm admin, all GUIs).
     */
    private boolean doLang(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bellmarket.admin")) {
            sender.sendMessage(c(plugin.getLang().getRaw("no-permission")));
            return true;
        }
        if (args.length < 2) {
            String current = plugin.getConfig().getString("language", "en");
            sender.sendMessage(c("&8[&6BellMarket&8] &7Current language: &f" + current.toUpperCase()));
            sender.sendMessage(c("&7Usage: &f/bm lang <en|pl>"));
            return true;
        }
        String lang = args[1].toLowerCase(Locale.ROOT);
        if (!LANGS.contains(lang)) {
            sender.sendMessage(c("&7Usage: &f/bm lang <en|pl>"));
            return true;
        }

        plugin.getConfig().set("language", lang);
        plugin.saveConfig();
        plugin.reload();   // ← KEY FIX: full reload, language now global

        sender.sendMessage(c("&8[&6BellMarket&8] &aLanguage switched to: &f" + lang.toUpperCase()));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("admin", "reload", "prices", "lang")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("lang")) {
            for (String s : LANGS) {
                if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(s);
            }
        }
        return out;
    }

    private Component c(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }
}
