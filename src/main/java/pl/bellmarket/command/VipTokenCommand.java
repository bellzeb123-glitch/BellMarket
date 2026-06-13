package pl.bellmarket.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import pl.bellmarket.BellMarket;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class VipTokenCommand implements CommandExecutor, TabCompleter {
    private final BellMarket plugin;
    public VipTokenCommand(BellMarket plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Player only."); return true; }
            long balance = plugin.getVipTokens().getBalance(p);
            p.sendMessage(c(plugin.getLang().getRaw("viptoken.balance-self", "amount", String.valueOf(balance))));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "balance" -> balance(sender, args);
            case "give"    -> modify(sender, args, "give");
            case "take"    -> modify(sender, args, "take");
            case "set"     -> modify(sender, args, "set");
            case "top"     -> top(sender);
            default        -> { sender.sendMessage(c("&cUsage: /vt <balance|give|take|set|top>")); yield true; }
        };
    }

    private boolean balance(CommandSender sender, String[] args) {
        if (args.length < 2) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Player only."); return true; }
            sender.sendMessage(c(plugin.getLang().getRaw("viptoken.balance-self", "amount",
                String.valueOf(plugin.getVipTokens().getBalance(p)))));
            return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) { sender.sendMessage(c(plugin.getLang().getRaw("viptoken.player-not-found", "name", args[1]))); return true; }
        sender.sendMessage(c(plugin.getLang().getRaw("viptoken.balance-other", "player", target.getName(),
            "amount", String.valueOf(plugin.getVipTokens().getBalance(target)))));
        return true;
    }

    private boolean modify(CommandSender sender, String[] args, String action) {
        if (!sender.hasPermission("bellmarket.viptoken.admin")) {
            sender.sendMessage(c(plugin.getLang().getRaw("viptoken.no-permission"))); return true; }
        if (args.length < 3) { sender.sendMessage(c("&cUsage: /vt " + action + " <player> <amount>")); return true; }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) { sender.sendMessage(c(plugin.getLang().getRaw("viptoken.player-not-found", "name", args[1]))); return true; }
        long amount;
        try { amount = Long.parseLong(args[2]); if (amount < 0) throw new NumberFormatException(); }
        catch (NumberFormatException e) { sender.sendMessage(c(plugin.getLang().getRaw("viptoken.invalid-amount"))); return true; }
        switch (action) {
            case "give" -> plugin.getVipTokens().addTokens(target, amount, "admin-give");
            case "take" -> plugin.getVipTokens().takeTokens(target, amount, "admin-take");
            case "set"  -> plugin.getVipTokens().setBalance(target, amount);
        }
        long newBal = plugin.getVipTokens().getBalance(target);
        sender.sendMessage(c(plugin.getLang().getRaw("viptoken." + action,
            "player", target.getName(), "amount", String.valueOf(amount), "balance", String.valueOf(newBal))));
        return true;
    }

    private boolean top(CommandSender sender) {
        sender.sendMessage(c("&6&l=== Top VIP Token Holders ==="));
        plugin.getVipTokens().getTopList(10).forEach(entry -> {
            String name = plugin.getVipTokens().getPlayerName(entry.getKey());
            sender.sendMessage(c("&7" + name + "&7: &d" + entry.getValue()));
        });
        return true;
    }

    private Component c(String s) { return LegacyComponentSerializer.legacyAmpersand().deserialize(s); }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return List.of("balance","give","take","set","top");
        return List.of();
    }
}
