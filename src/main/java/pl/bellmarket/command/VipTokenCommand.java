package pl.bellmarket.command;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import pl.bellmarket.BellMarket;
import pl.bellmarket.config.LangManager;
import pl.bellmarket.currency.VipTokenManager;

import java.util.*;

public class VipTokenCommand implements CommandExecutor, TabCompleter {

    private final BellMarket plugin;

    public VipTokenCommand(BellMarket plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        LangManager lang = plugin.getLang();
        if (args.length == 0) { return cmdBalance(sender, args, lang); }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "balance", "bal"   -> { return cmdBalance(sender, args, lang); }
            case "give"             -> { return cmdGive(sender, args, lang); }
            case "take", "remove"   -> { return cmdTake(sender, args, lang); }
            case "set"              -> { return cmdSet(sender, args, lang); }
            case "top"              -> { return cmdTop(sender, args, lang); }
            case "help"             -> { return cmdHelp(sender, lang); }
            default -> { return cmdBalance(sender, args, lang); }
        }
    }

    private boolean cmdBalance(CommandSender sender, String[] args, LangManager lang) {
        VipTokenManager vtm = plugin.getVipTokens();

        if (args.length >= 2) {
            if (!sender.hasPermission("bellmarket.viptoken.balance.others")) {
                send(sender, "&cYou don't have permission to view other players' VIP tokens.");
                return true;
            }
            OfflinePlayer target = resolveOffline(args[1]);
            if (target.getName() == null || !target.hasPlayedBefore()) {
                sender.sendMessage(lang.component("viptoken.player-not-found", "name", args[1]));
                return true;
            }
            long bal = vtm.getBalance(target.getUniqueId());
            sender.sendMessage(lang.component("viptoken.balance-other",
                    "player", target.getName(), "amount", String.valueOf(bal)));
            return true;
        }

        if (!(sender instanceof Player player)) {
            send(sender, "&cConsole must specify a player: /vt balance <player>");
            return true;
        }
        if (!player.hasPermission("bellmarket.viptoken.balance")) {
            send(sender, "&cYou don't have permission to view VIP tokens.");
            return true;
        }
        long bal = vtm.getBalance(player.getUniqueId());
        player.sendMessage(lang.component("viptoken.balance-self", "amount", String.valueOf(bal)));
        return true;
    }

    private boolean cmdGive(CommandSender sender, String[] args, LangManager lang) {
        if (!sender.hasPermission("bellmarket.viptoken.admin")) {
            send(sender, "&cYou don't have permission."); return true;
        }
        if (args.length < 3) { send(sender, "&7Usage: &f/vt give <player> <amount>"); return true; }

        long amount = parseLongOrZero(args[2]);
        if (amount <= 0) { send(sender, "&cAmount must be positive."); return true; }

        OfflinePlayer target = resolveOffline(args[1]);
        plugin.getVipTokens().addCoins(target.getUniqueId(), amount, "admin give");

        long newBal = plugin.getVipTokens().getBalance(target.getUniqueId());
        sender.sendMessage(lang.component("viptoken.given",
                "amount", String.valueOf(amount),
                "player", target.getName() != null ? target.getName() : args[1],
                "balance", String.valueOf(newBal)));

        if (target instanceof Player p && p.isOnline()) {
            p.sendMessage(lang.component("viptoken.received",
                    "amount", String.valueOf(amount), "balance", String.valueOf(newBal)));
        }
        return true;
    }

    private boolean cmdTake(CommandSender sender, String[] args, LangManager lang) {
        if (!sender.hasPermission("bellmarket.viptoken.admin")) {
            send(sender, "&cYou don't have permission."); return true;
        }
        if (args.length < 3) { send(sender, "&7Usage: &f/vt take <player> <amount>"); return true; }

        long amount = parseLongOrZero(args[2]);
        if (amount <= 0) { send(sender, "&cAmount must be positive."); return true; }

        OfflinePlayer target = resolveOffline(args[1]);
        long current = plugin.getVipTokens().getBalance(target.getUniqueId());
        long toTake = Math.min(amount, current);
        plugin.getVipTokens().takeCoins(target.getUniqueId(), toTake, "admin take");

        long newBal = plugin.getVipTokens().getBalance(target.getUniqueId());
        sender.sendMessage(lang.component("viptoken.taken",
                "amount", String.valueOf(toTake),
                "player", target.getName() != null ? target.getName() : args[1],
                "balance", String.valueOf(newBal)));
        return true;
    }

    private boolean cmdSet(CommandSender sender, String[] args, LangManager lang) {
        if (!sender.hasPermission("bellmarket.viptoken.admin")) {
            send(sender, "&cYou don't have permission."); return true;
        }
        if (args.length < 3) { send(sender, "&7Usage: &f/vt set <player> <amount>"); return true; }

        long amount = parseLongOrZero(args[2]);
        if (amount < 0) { send(sender, "&cAmount must be zero or positive."); return true; }

        OfflinePlayer target = resolveOffline(args[1]);
        plugin.getVipTokens().setBalance(target.getUniqueId(), amount, "admin set");

        sender.sendMessage(lang.component("viptoken.set",
                "amount", String.valueOf(amount),
                "player", target.getName() != null ? target.getName() : args[1]));
        return true;
    }

    private boolean cmdTop(CommandSender sender, String[] args, LangManager lang) {
        if (!sender.hasPermission("bellmarket.viptoken.top")) {
            send(sender, "&cYou don't have permission."); return true;
        }
        int limit = 10;
        if (args.length >= 2) {
            try { limit = Math.max(1, Math.min(Integer.parseInt(args[1]), 100)); }
            catch (NumberFormatException ignored) {}
        }

        List<Map.Entry<UUID, Long>> top = plugin.getVipTokens().getTopList(limit);
        if (top.isEmpty()) { send(sender, "&7No VIP token holders yet."); return true; }

        send(sender, "&5&lTop VIP Token Holders:");
        for (int i = 0; i < top.size(); i++) {
            UUID uuid = top.get(i).getKey();
            String name = Optional.ofNullable(Bukkit.getOfflinePlayer(uuid).getName())
                    .orElse(uuid.toString().substring(0, 8));
            send(sender, "  &7" + (i + 1) + ". &f" + name + " &7— &d" + top.get(i).getValue() + " tokens");
        }
        return true;
    }

    private boolean cmdHelp(CommandSender sender, LangManager lang) {
        send(sender, "&5&l=== VIP Tokens ===");
        send(sender, "&7/vt balance &8— &fCheck your balance");
        send(sender, "&7/vt top &8— &fLeaderboard");
        send(sender, "&7/vt give <player> <amount> &8— &fGive tokens");
        send(sender, "&7/vt take <player> <amount> &8— &fTake tokens");
        send(sender, "&7/vt set <player> <amount> &8— &fSet balance");
        return true;
    }

    private void send(CommandSender sender, String msg) {
        sender.sendMessage(LangManager.colorize(msg));
    }

    private OfflinePlayer resolveOffline(String name) {
        Player online = Bukkit.getPlayer(name);
        return online != null ? online : Bukkit.getOfflinePlayer(name);
    }

    private long parseLongOrZero(String s) {
        try { return Long.parseLong(s); }
        catch (Exception e) { return -1; }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) {
            return List.of("balance","give","take","set","top","help").stream()
                    .filter(sub -> sub.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
