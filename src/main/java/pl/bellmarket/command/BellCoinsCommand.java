package pl.bellmarket.command;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import pl.bellmarket.BellMarket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BellCoinsCommand implements CommandExecutor, TabCompleter {

    private final BellMarket plugin;

    public BellCoinsCommand(BellMarket plugin) {
        this.plugin = plugin;
        plugin.getCommand("bellcoins").setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // Show own balance
            if (!(sender instanceof Player player)) {
                sender.sendMessage(c("&cConsole must specify a player: /bellcoins balance <player>"));
                return true;
            }
            if (!player.hasPermission("bellmarket.coins.balance")) {
                player.sendMessage(plugin.getLang().component("no-permission"));
                return true;
            }
            long balance = plugin.getCurrency().getBalance(player);
            player.sendMessage(plugin.getLang().component("currency.balance",
                "amount", plugin.getLang().formatAmount(balance)));
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "balance", "bal" -> {
                if (args.length == 1) {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(c("&cUsage: /bellcoins balance <player>"));
                        return true;
                    }
                    if (!player.hasPermission("bellmarket.coins.balance")) {
                        player.sendMessage(plugin.getLang().component("no-permission"));
                        return true;
                    }
                    long balance = plugin.getCurrency().getBalance(player);
                    player.sendMessage(plugin.getLang().component("currency.balance",
                        "amount", plugin.getLang().formatAmount(balance)));
                } else {
                    if (!sender.hasPermission("bellmarket.coins.balance.others")) {
                        sender.sendMessage(plugin.getLang().component("no-permission"));
                        return true;
                    }
                    OfflinePlayer target = getOfflinePlayer(args[1]);
                    if (target == null) {
                        sender.sendMessage(plugin.getLang().component("player-not-found", "player", args[1]));
                        return true;
                    }
                    long balance = plugin.getCurrency().getBalance(target.getUniqueId());
                    sender.sendMessage(plugin.getLang().component("currency.balance-other",
                        "player", target.getName() != null ? target.getName() : args[1],
                        "amount", plugin.getLang().formatAmount(balance)));
                }
            }

            case "give" -> {
                if (!sender.hasPermission("bellmarket.coins.give")) {
                    sender.sendMessage(plugin.getLang().component("no-permission"));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(c("&cUsage: /bellcoins give <player> <amount>"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(plugin.getLang().component("player-not-found", "player", args[1]));
                    return true;
                }
                long amount = parseAmount(args[2]);
                if (amount <= 0) {
                    sender.sendMessage(plugin.getLang().component("invalid-amount"));
                    return true;
                }
                plugin.getCurrency().addCoins(target, amount);
                sender.sendMessage(plugin.getLang().component("currency.given",
                    "player", target.getName(), "amount", plugin.getLang().formatAmount(amount)));
                target.sendMessage(plugin.getLang().component("currency.received",
                    "amount", plugin.getLang().formatAmount(amount)));
            }

            case "take", "remove" -> {
                if (!sender.hasPermission("bellmarket.coins.take")) {
                    sender.sendMessage(plugin.getLang().component("no-permission"));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(c("&cUsage: /bellcoins take <player> <amount>"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(plugin.getLang().component("player-not-found", "player", args[1]));
                    return true;
                }
                long amount = parseAmount(args[2]);
                if (amount <= 0) {
                    sender.sendMessage(plugin.getLang().component("invalid-amount"));
                    return true;
                }
                plugin.getCurrency().takeCoins(target, amount);
                sender.sendMessage(plugin.getLang().component("currency.taken",
                    "player", target.getName(), "amount", plugin.getLang().formatAmount(amount)));
                target.sendMessage(plugin.getLang().component("currency.removed",
                    "amount", plugin.getLang().formatAmount(amount)));
            }

            case "set" -> {
                if (!sender.hasPermission("bellmarket.coins.set")) {
                    sender.sendMessage(plugin.getLang().component("no-permission"));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(c("&cUsage: /bellcoins set <player> <amount>"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(plugin.getLang().component("player-not-found", "player", args[1]));
                    return true;
                }
                long amount = parseAmount(args[2]);
                if (amount < 0) {
                    sender.sendMessage(plugin.getLang().component("invalid-amount"));
                    return true;
                }
                plugin.getCurrency().setBalance(target, amount);
                sender.sendMessage(plugin.getLang().component("currency.set",
                    "player", target.getName(), "amount", plugin.getLang().formatAmount(amount)));
            }

            case "top", "leaderboard" -> {
                if (!sender.hasPermission("bellmarket.coins.top")) {
                    sender.sendMessage(plugin.getLang().component("no-permission"));
                    return true;
                }
                sender.sendMessage(plugin.getLang().component("currency.top-header"));
                List<Map.Entry<UUID, Long>> top = plugin.getCurrency().getTopList(10);
                for (int i = 0; i < top.size(); i++) {
                    Map.Entry<UUID, Long> entry = top.get(i);
                    String name = plugin.getCurrency().getPlayerName(entry.getKey());
                    sender.sendMessage(plugin.getLang().component("currency.top-entry",
                        "rank", String.valueOf(i + 1),
                        "player", name,
                        "amount", plugin.getLang().formatAmount(entry.getValue())));
                }
            }

            default -> sender.sendMessage(c("&cUnknown subcommand. Use: balance, give, take, set, top"));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            completions.addAll(List.of("balance", "give", "take", "set", "top"));
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("top")) {
            Bukkit.getOnlinePlayers().forEach(p -> completions.add(p.getName()));
        }
        String filter = args[args.length - 1].toLowerCase();
        completions.removeIf(s -> !s.toLowerCase().startsWith(filter));
        return completions;
    }

    private OfflinePlayer getOfflinePlayer(String name) {
        Player online = Bukkit.getPlayer(name);
        if (online != null) return online;
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
        return op;
    }

    private long parseAmount(String str) {
        try { return Long.parseLong(str); }
        catch (NumberFormatException e) { return -1; }
    }

    private Component c(String text) {
        return plugin.getLang().colorize(text);
    }
}
