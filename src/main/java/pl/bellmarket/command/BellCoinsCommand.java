package pl.bellmarket.command;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import pl.bellmarket.BellMarket;
import pl.bellmarket.config.LangManager;
import pl.bellmarket.currency.CurrencyManager;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class BellCoinsCommand implements CommandExecutor, TabCompleter {

    private final BellMarket plugin;

    public BellCoinsCommand(BellMarket plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        LangManager lang = plugin.getLang();
        CurrencyManager cm = plugin.getCurrency();

        if (args.length == 0) {
            return cmdBalance(sender, args, lang, cm);
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "balance", "bal"           -> { return cmdBalance(sender, args, lang, cm); }
            case "give"                     -> { return cmdGive(sender, args, lang, cm); }
            case "take", "remove"           -> { return cmdTake(sender, args, lang, cm); }
            case "set"                      -> { return cmdSet(sender, args, lang, cm); }
            case "top", "leaderboard"       -> { return cmdTop(sender, lang, cm); }
            default -> { return cmdBalance(sender, args, lang, cm); }
        }
    }

    private boolean cmdBalance(CommandSender sender, String[] args, LangManager lang, CurrencyManager cm) {
        // /bc balance <player> or /bc balance (self)
        if (args.length >= 2) {
            if (!sender.hasPermission("bellmarket.coins.balance.others")) {
                sender.sendMessage(lang.component("no-permission"));
                return true;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            if (!target.hasPlayedBefore() && !target.isOnline()) {
                sender.sendMessage(lang.component("player-not-found", "player", args[1]));
                return true;
            }
            long bal = cm.getBalance(target.getUniqueId());
            sender.sendMessage(lang.component("currency.balance-other",
                    "player", target.getName() != null ? target.getName() : args[1],
                    "symbol", lang.getCurrencySymbol(),
                    "amount", lang.formatAmount(bal),
                    "currency", lang.getCurrencyName()));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("&cConsole must specify a player: /bellcoins balance <player>");
            return true;
        }
        if (!player.hasPermission("bellmarket.coins.balance")) {
            player.sendMessage(lang.component("no-permission"));
            return true;
        }
        long bal = cm.getBalance(player.getUniqueId());
        player.sendMessage(lang.component("currency.balance",
                "symbol", lang.getCurrencySymbol(),
                "amount", lang.formatAmount(bal),
                "currency", lang.getCurrencyName()));
        return true;
    }

    private boolean cmdGive(CommandSender sender, String[] args, LangManager lang, CurrencyManager cm) {
        if (!sender.hasPermission("bellmarket.coins.give")) {
            sender.sendMessage(lang.component("no-permission")); return true;
        }
        if (args.length < 3) {
            sender.sendMessage(LangManager.colorize("&cUsage: /bellcoins give <player> <amount>")); return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) { sender.sendMessage(lang.component("player-not-found", "player", args[1])); return true; }
        long amount = parseAmount(args[2]);
        if (amount < 0) { sender.sendMessage(lang.component("invalid-amount")); return true; }

        cm.addCoins(target.getUniqueId(), amount);
        sender.sendMessage(lang.component("currency.given",
                "symbol", lang.getCurrencySymbol(), "amount", lang.formatAmount(amount),
                "currency", lang.getCurrencyName(), "player", target.getName()));
        target.sendMessage(lang.component("currency.received",
                "symbol", lang.getCurrencySymbol(), "amount", lang.formatAmount(amount),
                "currency", lang.getCurrencyName()));
        return true;
    }

    private boolean cmdTake(CommandSender sender, String[] args, LangManager lang, CurrencyManager cm) {
        if (!sender.hasPermission("bellmarket.coins.take")) {
            sender.sendMessage(lang.component("no-permission")); return true;
        }
        if (args.length < 3) {
            sender.sendMessage(LangManager.colorize("&cUsage: /bellcoins take <player> <amount>")); return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) { sender.sendMessage(lang.component("player-not-found", "player", args[1])); return true; }
        long amount = parseAmount(args[2]);
        if (amount < 0) { sender.sendMessage(lang.component("invalid-amount")); return true; }

        cm.takeCoins(target.getUniqueId(), amount);
        sender.sendMessage(lang.component("currency.taken",
                "symbol", lang.getCurrencySymbol(), "amount", lang.formatAmount(amount),
                "currency", lang.getCurrencyName(), "player", target.getName()));
        target.sendMessage(lang.component("currency.removed",
                "symbol", lang.getCurrencySymbol(), "amount", lang.formatAmount(amount),
                "currency", lang.getCurrencyName()));
        return true;
    }

    private boolean cmdSet(CommandSender sender, String[] args, LangManager lang, CurrencyManager cm) {
        if (!sender.hasPermission("bellmarket.coins.set")) {
            sender.sendMessage(lang.component("no-permission")); return true;
        }
        if (args.length < 3) {
            sender.sendMessage(LangManager.colorize("&cUsage: /bellcoins set <player> <amount>")); return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) { sender.sendMessage(lang.component("player-not-found", "player", args[1])); return true; }
        long amount = parseAmount(args[2]);
        if (amount < 0) { sender.sendMessage(lang.component("invalid-amount")); return true; }

        cm.setBalance(target.getUniqueId(), amount);
        sender.sendMessage(lang.component("currency.set",
                "symbol", lang.getCurrencySymbol(), "amount", lang.formatAmount(amount),
                "currency", lang.getCurrencyName(), "player", target.getName()));
        return true;
    }

    private boolean cmdTop(CommandSender sender, LangManager lang, CurrencyManager cm) {
        if (!sender.hasPermission("bellmarket.coins.top")) {
            sender.sendMessage(lang.component("no-permission")); return true;
        }
        sender.sendMessage(lang.component("currency.top-header", "currency", lang.getCurrencyName()));
        List<Map.Entry<UUID, Long>> top = cm.getTopList(10);
        for (int i = 0; i < top.size(); i++) {
            Map.Entry<UUID, Long> entry = top.get(i);
            String name = cm.getPlayerName(entry.getKey());
            sender.sendMessage(lang.component("currency.top-entry",
                    "rank", String.valueOf(i + 1),
                    "player", name,
                    "symbol", lang.getCurrencySymbol(),
                    "amount", lang.formatAmount(entry.getValue())));
        }
        return true;
    }

    private long parseAmount(String s) {
        try { return Long.parseLong(s); }
        catch (Exception e) { return -1; }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) {
            return List.of("balance","give","take","set","top").stream()
                    .filter(sub -> sub.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
