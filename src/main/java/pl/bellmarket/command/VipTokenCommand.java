/*
 * BellMarket - VipTokenCommand
 *
 * SESJA-2 new command: /vt (aliases: /viptokens, /vtoken)
 *
 * Subcommands:
 *   /vt                      → /vt balance for self
 *   /vt balance [player]     → check VIP token balance (own or other's)
 *   /vt give <player> <n>    → admin: grant tokens
 *   /vt take <player> <n>    → admin: remove tokens
 *   /vt set <player> <n>     → admin: set absolute balance
 *   /vt top [page]           → leaderboard, 10 entries per page
 *
 * Permissions:
 *   bellmarket.viptoken.balance         (default true)  — check own
 *   bellmarket.viptoken.balance.others  (default op)    — check other players'
 *   bellmarket.viptoken.admin           (default op)    — give/take/set
 *   bellmarket.viptoken.top             (default true)  — view leaderboard
 */
package pl.bellmarket.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import pl.bellmarket.BellMarket;
import pl.bellmarket.api.BellMarketAPI;
import pl.bellmarket.currency.VipTokenManager;

import java.util.*;
import java.util.stream.Collectors;

public class VipTokenCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("balance", "give", "take", "set", "top", "help");
    private static final int TOP_PAGE_SIZE = 10;

    private final BellMarket plugin;

    public VipTokenCommand(BellMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return cmdBalance(sender, new String[]{"balance"});
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "balance", "bal" -> cmdBalance(sender, args);
            case "give"           -> cmdGive(sender, args);
            case "take", "remove" -> cmdTake(sender, args);
            case "set"            -> cmdSet(sender, args);
            case "top"            -> cmdTop(sender, args);
            case "help", "?"      -> cmdHelp(sender);
            default               -> cmdHelp(sender);
        };
    }

    // ─── /vt balance [player] ─────────────────────────────────────────────
    private boolean cmdBalance(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            // checking someone else's
            if (!sender.hasPermission("bellmarket.viptoken.balance.others")) {
                send(sender, "&cYou don't have permission to view other players' VIP tokens.");
                return true;
            }
            OfflinePlayer target = resolveOffline(args[1]);
            if (target == null || (target.getName() == null && !target.hasPlayedBefore())) {
                send(sender, "&cPlayer not found: &f" + args[1]);
                return true;
            }
            long bal = plugin.getVipTokens().getBalance(target.getUniqueId());
            send(sender, "&8[&5VIP&8] &7" + target.getName() + "'s balance: &d" + bal + " &7VIP Tokens");
            return true;
        }
        // own balance
        if (!(sender instanceof Player player)) {
            send(sender, "&cConsole must specify a player: /vt balance <player>");
            return true;
        }
        if (!player.hasPermission("bellmarket.viptoken.balance")) {
            send(sender, "&cYou don't have permission to view VIP tokens.");
            return true;
        }
        long bal = plugin.getVipTokens().getBalance(player.getUniqueId());
        send(sender, "&8[&5VIP&8] &7Your balance: &d" + bal + " &7VIP Tokens");
        return true;
    }

    // ─── /vt give <player> <amount> ───────────────────────────────────────
    private boolean cmdGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bellmarket.viptoken.admin")) {
            send(sender, "&cYou don't have permission.");
            return true;
        }
        if (args.length < 3) {
            send(sender, "&7Usage: &f/vt give <player> <amount>");
            return true;
        }
        OfflinePlayer target = resolveOffline(args[1]);
        if (target == null || (target.getName() == null && !target.hasPlayedBefore())) {
            send(sender, "&cPlayer not found: &f" + args[1]);
            return true;
        }
        long amount = parseLongOrZero(args[2]);
        if (amount <= 0) {
            send(sender, "&cAmount must be positive.");
            return true;
        }
        long newBal = plugin.getVipTokens().addCoins(target.getUniqueId(), amount,
            "admin: " + sender.getName());
        send(sender, "&aGave &d" + amount + " VIP Tokens &7to &f" + target.getName()
            + "&7. New balance: &d" + newBal);
        // Notify target if online
        Player online = target.getPlayer();
        if (online != null && online.isOnline()) {
            send(online, "&8[&5VIP&8] &7You received &d+" + amount + " VIP Tokens&7! "
                + "Balance: &d" + newBal);
        }
        return true;
    }

    // ─── /vt take <player> <amount> ───────────────────────────────────────
    private boolean cmdTake(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bellmarket.viptoken.admin")) {
            send(sender, "&cYou don't have permission.");
            return true;
        }
        if (args.length < 3) {
            send(sender, "&7Usage: &f/vt take <player> <amount>");
            return true;
        }
        OfflinePlayer target = resolveOffline(args[1]);
        if (target == null || (target.getName() == null && !target.hasPlayedBefore())) {
            send(sender, "&cPlayer not found: &f" + args[1]);
            return true;
        }
        long amount = parseLongOrZero(args[2]);
        if (amount <= 0) {
            send(sender, "&cAmount must be positive.");
            return true;
        }
        VipTokenManager vt = plugin.getVipTokens();
        long current = vt.getBalance(target.getUniqueId());
        long toTake = Math.min(amount, current);
        if (toTake == 0) {
            send(sender, "&7" + target.getName() + " has no VIP tokens to take.");
            return true;
        }
        boolean ok = vt.takeCoins(target.getUniqueId(), toTake, "admin: " + sender.getName());
        if (!ok) {
            send(sender, "&cFailed to take tokens (race condition?).");
            return true;
        }
        send(sender, "&aTook &d" + toTake + " VIP Tokens &7from &f" + target.getName()
            + "&7. New balance: &d" + vt.getBalance(target.getUniqueId()));
        return true;
    }

    // ─── /vt set <player> <amount> ────────────────────────────────────────
    private boolean cmdSet(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bellmarket.viptoken.admin")) {
            send(sender, "&cYou don't have permission.");
            return true;
        }
        if (args.length < 3) {
            send(sender, "&7Usage: &f/vt set <player> <amount>");
            return true;
        }
        OfflinePlayer target = resolveOffline(args[1]);
        if (target == null || (target.getName() == null && !target.hasPlayedBefore())) {
            send(sender, "&cPlayer not found: &f" + args[1]);
            return true;
        }
        long amount = parseLongOrZero(args[2]);
        if (amount < 0) {
            send(sender, "&cAmount must be zero or positive.");
            return true;
        }
        plugin.getVipTokens().setBalance(target.getUniqueId(), amount,
            "admin set: " + sender.getName());
        send(sender, "&aSet &f" + target.getName() + "&7's balance to &d" + amount + " VIP Tokens&7.");
        return true;
    }

    // ─── /vt top [page] ───────────────────────────────────────────────────
    private boolean cmdTop(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bellmarket.viptoken.top")) {
            send(sender, "&cYou don't have permission.");
            return true;
        }
        int page = 1;
        if (args.length >= 2) {
            try { page = Math.max(1, Integer.parseInt(args[1])); }
            catch (NumberFormatException e) { page = 1; }
        }
        VipTokenManager vt = plugin.getVipTokens();
        List<Map.Entry<UUID, Long>> all = vt.getTopList(Integer.MAX_VALUE);
        if (all.isEmpty()) {
            send(sender, "&7No VIP token holders yet.");
            return true;
        }
        int totalPages = (all.size() + TOP_PAGE_SIZE - 1) / TOP_PAGE_SIZE;
        if (page > totalPages) page = totalPages;
        int start = (page - 1) * TOP_PAGE_SIZE;
        int end = Math.min(start + TOP_PAGE_SIZE, all.size());

        send(sender, "&8&m──────────&r &5&l VIP Token Top &7(Page " + page + "/" + totalPages + ")&r &8&m──────────");
        for (int i = start; i < end; i++) {
            Map.Entry<UUID, Long> e = all.get(i);
            String name = Optional.ofNullable(Bukkit.getOfflinePlayer(e.getKey()).getName())
                .orElse(e.getKey().toString().substring(0, 8));
            send(sender, "&7#" + (i + 1) + " &f" + name + " &8» &d" + e.getValue());
        }
        if (totalPages > 1) {
            send(sender, "&7Use &f/vt top " + Math.min(totalPages, page + 1) + " &7for next page.");
        }
        return true;
    }

    // ─── /vt help ─────────────────────────────────────────────────────────
    private boolean cmdHelp(CommandSender sender) {
        send(sender, "&8&m─────────────&r &5&l VIP Tokens Help &r&8&m─────────────");
        send(sender, "&f/vt balance &7- Check your VIP token balance");
        if (sender.hasPermission("bellmarket.viptoken.balance.others"))
            send(sender, "&f/vt balance <player> &7- Check another player's balance");
        send(sender, "&f/vt top [page] &7- Show VIP token leaderboard");
        if (sender.hasPermission("bellmarket.viptoken.admin")) {
            send(sender, "&f/vt give <player> <n> &7- Grant VIP tokens");
            send(sender, "&f/vt take <player> <n> &7- Remove VIP tokens");
            send(sender, "&f/vt set <player> <n> &7- Set absolute balance");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUBS.stream()
                .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("balance") || sub.equals("bal") || sub.equals("give") ||
                sub.equals("take") || sub.equals("remove") || sub.equals("set")) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .collect(Collectors.toList());
            }
        }
        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("give") || sub.equals("take") || sub.equals("set") || sub.equals("remove")) {
                return List.of("1", "5", "10", "100");
            }
        }
        return Collections.emptyList();
    }

    // ─── helpers ──────────────────────────────────────────────────────────
    private static void send(CommandSender s, String msg) {
        s.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(msg));
    }

    private static OfflinePlayer resolveOffline(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        @SuppressWarnings("deprecation")
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        return off;
    }

    private static long parseLongOrZero(String s) {
        try { return Long.parseLong(s); }
        catch (NumberFormatException e) { return 0; }
    }
}
