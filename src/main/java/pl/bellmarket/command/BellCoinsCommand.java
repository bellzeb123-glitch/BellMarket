package pl.bellmarket.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import pl.bellmarket.BellMarket;

import java.util.List;
import java.util.Locale;

public class BellCoinsCommand implements CommandExecutor {
    private final BellMarket plugin;
    public BellCoinsCommand(BellMarket plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Player only."); return true; }
            long bal = plugin.getCurrency().getBalance(p);
            p.sendMessage(c(plugin.getLang().getRaw("currency.balance",
                "symbol", plugin.getLang().getCurrencySymbol(),
                "amount", plugin.getLang().formatAmount(bal),
                "currency", plugin.getLang().getCurrencyName())));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "balance" -> balance(sender, args);
            case "give"    -> modify(sender, args, "give");
            case "take"    -> modify(sender, args, "take");
            case "set"     -> modifySet(sender, args);
            case "top"     -> top(sender);
            default        -> { sender.sendMessage(c("&cUsage: /bc <balance|give|take|set|top>")); yield true; }
        };
    }

    private boolean balance(CommandSender sender, String[] args) {
        if (args.length < 2) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Player only."); return true; }
            long b = plugin.getCurrency().getBalance(p);
            sender.sendMessage(c(plugin.getLang().getRaw("currency.balance",
                "symbol", plugin.getLang().getCurrencySymbol(),
                "amount", plugin.getLang().formatAmount(b),
                "currency", plugin.getLang().getCurrencyName())));
            return true;
        }
        if (!sender.hasPermission("bellmarket.coins.balance.others")) {
            sender.sendMessage(c(plugin.getLang().getRaw("no-permission"))); return true; }
        Player t = Bukkit.getPlayer(args[1]);
        if (t == null) { sender.sendMessage(c(plugin.getLang().getRaw("player-not-found", "player", args[1]))); return true; }
        long b = plugin.getCurrency().getBalance(t);
        sender.sendMessage(c(plugin.getLang().getRaw("currency.balance-other",
            "player", t.getName(), "symbol", plugin.getLang().getCurrencySymbol(),
            "amount", plugin.getLang().formatAmount(b), "currency", plugin.getLang().getCurrencyName())));
        return true;
    }

    private boolean modify(CommandSender sender, String[] args, String action) {
        String perm = action.equals("give") ? "bellmarket.coins.give" : "bellmarket.coins.take";
        if (!sender.hasPermission(perm)) { sender.sendMessage(c(plugin.getLang().getRaw("no-permission"))); return true; }
        if (args.length < 3) { sender.sendMessage(c("&cUsage: /bc " + action + " <player> <amount>")); return true; }
        Player t = Bukkit.getPlayer(args[1]);
        if (t == null) { sender.sendMessage(c(plugin.getLang().getRaw("player-not-found", "player", args[1]))); return true; }
        long amount; try { amount = Long.parseLong(args[2]); } catch (NumberFormatException e) {
            sender.sendMessage(c(plugin.getLang().getRaw("invalid-amount"))); return true; }
        if (action.equals("give")) plugin.getCurrency().addCoins(t, amount, "admin");
        else plugin.getCurrency().takeCoins(t, amount, "admin");
        sender.sendMessage(c(plugin.getLang().getRaw("currency." + action + "n",
            "player", t.getName(), "symbol", plugin.getLang().getCurrencySymbol(),
            "amount", String.valueOf(amount), "currency", plugin.getLang().getCurrencyName())));
        return true;
    }

    private boolean modifySet(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bellmarket.coins.set")) {
            sender.sendMessage(c(plugin.getLang().getRaw("no-permission"))); return true; }
        if (args.length < 3) { sender.sendMessage(c("&cUsage: /bc set <player> <amount>")); return true; }
        Player t = Bukkit.getPlayer(args[1]);
        if (t == null) { sender.sendMessage(c(plugin.getLang().getRaw("player-not-found", "player", args[1]))); return true; }
        long amount; try { amount = Long.parseLong(args[2]); } catch (NumberFormatException e) {
            sender.sendMessage(c(plugin.getLang().getRaw("invalid-amount"))); return true; }
        plugin.getCurrency().setBalance(t, amount);
        sender.sendMessage(c(plugin.getLang().getRaw("currency.set",
            "player", t.getName(), "symbol", plugin.getLang().getCurrencySymbol(),
            "amount", String.valueOf(amount), "currency", plugin.getLang().getCurrencyName())));
        return true;
    }

    private boolean top(CommandSender sender) {
        if (!sender.hasPermission("bellmarket.coins.top")) {
            sender.sendMessage(c(plugin.getLang().getRaw("no-permission"))); return true; }
        sender.sendMessage(c(plugin.getLang().getRaw("currency.top-header", "currency", plugin.getLang().getCurrencyName())));
        int rank = 1;
        for (var entry : plugin.getCurrency().getTopList(10)) {
            String name = plugin.getCurrency().getPlayerName(entry.getKey());
            sender.sendMessage(c(plugin.getLang().getRaw("currency.top-entry",
                "rank", String.valueOf(rank++), "player", name,
                "symbol", plugin.getLang().getCurrencySymbol(),
                "amount", plugin.getLang().formatAmount(entry.getValue()))));
        }
        return true;
    }

    private Component c(String s) { return LegacyComponentSerializer.legacyAmpersand().deserialize(s); }
}
