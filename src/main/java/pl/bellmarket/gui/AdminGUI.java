package pl.bellmarket.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.bellmarket.BellMarket;
import pl.bellmarket.command.BellMarketCommand;

import java.util.*;

public class AdminGUI implements Listener {

    private static class AdminHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
        void setInventory(Inventory inv)           { this.inv = inv; }
    }

    // Awaiting chat input: UUID → action ("give:<player>", "take:<player>", "set:<player>")
    private final Map<UUID, String> awaitingInput = new HashMap<>();

    private final BellMarket plugin;

    public AdminGUI(BellMarket plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void openFor(Player admin) {
        AdminHolder holder = new AdminHolder();
        var lang = plugin.getLang();

        // Powiększony GUI: 36 slotów (4 rzędy) zamiast 27 (3 rzędy)
        Inventory inv = Bukkit.createInventory(holder, 36,
            colorize(lang.getRaw("admin.gui-title")));
        holder.setInventory(inv);
        fill(inv, 36);

        // Stats
        int totalPlayers = plugin.getCurrency().getTopList(Integer.MAX_VALUE).size();
        inv.setItem(4, makeItem(Material.BOOK,
            lang.getRaw("admin.gui-stats-name"),
            List.of(
                lang.getRaw("admin.gui-stats-categories", "count",
                    String.valueOf(plugin.getCategories().getCategories().size())),
                lang.getRaw("admin.gui-stats-players", "count",
                    String.valueOf(totalPlayers)),
                lang.getRaw("admin.gui-stats-currency", "currency",
                    lang.getCurrencyName())
            )));

        // Give / Take / Set / Top / Reload
        inv.setItem(10, makeItem(Material.EMERALD,
            lang.getRaw("admin.gui-give-name"),
            List.of(lang.getRaw("admin.gui-give-lore"), "", lang.getRaw("admin.gui-click-to-use"))));
        inv.setItem(12, makeItem(Material.REDSTONE,
            lang.getRaw("admin.gui-take-name"),
            List.of(lang.getRaw("admin.gui-take-lore"), "", lang.getRaw("admin.gui-click-to-use"))));
        inv.setItem(14, makeItem(Material.GOLD_INGOT,
            lang.getRaw("admin.gui-set-name"),
            List.of(lang.getRaw("admin.gui-set-lore"), "", lang.getRaw("admin.gui-click-to-use"))));
        inv.setItem(16, makeItem(Material.GOLDEN_APPLE,
            lang.getRaw("admin.gui-top-name"),
            List.of(lang.getRaw("admin.gui-top-lore"), "", lang.getRaw("admin.gui-click-to-view"))));
        inv.setItem(22, makeItem(Material.COMMAND_BLOCK,
            lang.getRaw("admin.gui-reload-name"),
            List.of(lang.getRaw("admin.gui-reload-lore"), "", lang.getRaw("admin.gui-click-to-reload"))));

        // ── Edytor Cen (slot 28) — ikona DIAMENT ──
        inv.setItem(28, makeItem(Material.DIAMOND,
            lang.getRaw("admin.gui-prices-name"),
            List.of(lang.getRaw("admin.gui-prices-lore"), "", lang.getRaw("admin.gui-click-to-use"))));

        // ── Zmiana języka (slot 30) ──
        String currentLang = plugin.getConfig().getString("language", "en").toUpperCase();
        inv.setItem(30, makeItem(Material.WRITABLE_BOOK,
            lang.getRaw("admin.gui-lang-name"),
            List.of(
                lang.getRaw("admin.gui-lang-current", "lang", currentLang),
                "",
                lang.getRaw("admin.gui-lang-hint")
            )));

        // ── VIP Token: Give (slot 32) ──
        inv.setItem(32, makeItem(Material.AMETHYST_SHARD,
            lang.getRaw("admin.gui-vt-give-name"),
            List.of(lang.getRaw("admin.gui-vt-give-lore"), "", lang.getRaw("admin.gui-click-to-use"))));

        // ── VIP Token: Take (slot 33) ──
        inv.setItem(33, makeItem(Material.AMETHYST_BLOCK,
            lang.getRaw("admin.gui-vt-take-name"),
            List.of(lang.getRaw("admin.gui-vt-take-lore"), "", lang.getRaw("admin.gui-click-to-use"))));

        // ── VIP Token: Set/Check (slot 34) ──
        inv.setItem(34, makeItem(Material.BUDDING_AMETHYST,
            lang.getRaw("admin.gui-vt-set-name"),
            List.of(
                lang.getRaw("admin.gui-vt-set-lore"),
                lang.getRaw("admin.gui-vt-check-lore"),
                "",
                lang.getRaw("admin.gui-vt-set-hint")
            )));

        admin.openInventory(inv);
        admin.sendMessage(plugin.getLang().component("admin.panel-opened"));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof AdminHolder)) return;

        event.setCancelled(true);
        if (!player.hasPermission("bellmarket.admin")) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 36) return;

        switch (slot) {
            case 10 -> promptInput(player, "give");
            case 12 -> promptInput(player, "take");
            case 14 -> promptInput(player, "set");
            case 16 -> showTopList(player);
            case 22 -> {
                plugin.reload();
                player.sendMessage(plugin.getLang().component("admin.reloaded"));
                player.closeInventory();
            }
            case 28 -> {
                // Edytor Cen — otwórz PriceEditorGUI z BellMarketCommand
                player.closeInventory();
                PluginCommand cmd = plugin.getCommand("bellmarket");
                if (cmd != null && cmd.getExecutor() instanceof BellMarketCommand bmc) {
                    bmc.getPriceEditor().openTierList(player);
                }
            }
            case 30 -> {
                // Zmiana języka — LPM=EN, PPM=PL
                String newLang = event.isLeftClick() ? "en" : "pl";
                plugin.getConfig().set("language", newLang);
                plugin.saveConfig();
                plugin.reload();
                openFor(player); // reopen z nowym językiem
            }
            case 32 -> promptInput(player, "vtgive");
            case 33 -> promptInput(player, "vttake");
            case 34 -> promptInput(player, "vtset");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof AdminHolder)
            event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // Clean up awaiting input if they close without typing
        if (event.getInventory().getHolder() instanceof AdminHolder) {
            awaitingInput.remove(event.getPlayer().getUniqueId());
        }
    }

    private void promptInput(Player admin, String action) {
        admin.closeInventory();
        awaitingInput.put(admin.getUniqueId(), action + ":step1");
        // Komunikat zależny od języka — klucz admin.prompt-player-<action>
        String key = switch (action) {
            case "give"   -> "admin.prompt-player-give";
            case "take"   -> "admin.prompt-player-take";
            case "set"    -> "admin.prompt-player-set";
            case "vtgive" -> "admin.prompt-player-vtgive";
            case "vttake" -> "admin.prompt-player-vttake";
            case "vtset"  -> "admin.prompt-player-vtset";
            default       -> "admin.prompt-player-give";
        };
        admin.sendMessage(plugin.getLang().component(key));
    }

    public boolean handleChatInput(Player admin, String message) {
        if (!awaitingInput.containsKey(admin.getUniqueId())) return false;
        if (!admin.hasPermission("bellmarket.admin")) {
            awaitingInput.remove(admin.getUniqueId());
            return false;
        }

        String state = awaitingInput.get(admin.getUniqueId());

        if (message.equalsIgnoreCase("cancel") || message.equalsIgnoreCase("anuluj")) {
            awaitingInput.remove(admin.getUniqueId());
            admin.sendMessage(plugin.getLang().component("admin.prompt-cancelled"));
            return true;
        }

        String[] parts = state.split(":", 2);
        String action = parts[0];
        String step   = parts[1];

        boolean isVip = action.startsWith("vt");

        if (step.equals("step1")) {
            // Player name entered
            Player target = Bukkit.getPlayer(message);
            OfflinePlayer offline = target != null ? target : Bukkit.getOfflinePlayerIfCached(message);
            if (offline == null || offline.getName() == null) {
                admin.sendMessage(plugin.getLang().component("player-not-found", "player", message));
                awaitingInput.remove(admin.getUniqueId());
                return true;
            }

            // VIP set/check — najpierw pokaż aktualny balans gracza
            if (isVip) {
                long bal = plugin.getVipTokens().getBalance(offline.getUniqueId());
                admin.sendMessage(plugin.getLang().component("admin.vt-current-balance",
                    "player", offline.getName(), "amount", String.valueOf(bal)));
            }

            awaitingInput.put(admin.getUniqueId(), action + ":" + offline.getUniqueId());
            admin.sendMessage(plugin.getLang().component("admin.prompt-amount",
                "player", offline.getName()));
        } else {
            // Amount entered
            UUID targetUuid;
            try { targetUuid = UUID.fromString(step); }
            catch (Exception e) {
                awaitingInput.remove(admin.getUniqueId());
                return true;
            }

            long amount;
            try { amount = Long.parseLong(message); }
            catch (NumberFormatException e) {
                admin.sendMessage(plugin.getLang().component("invalid-amount"));
                awaitingInput.remove(admin.getUniqueId());
                return true;
            }

            String targetName = plugin.getCurrency().getPlayerName(targetUuid);

            switch (action) {
                // ── BellCoins ──
                case "give" -> {
                    plugin.getCurrency().addCoins(targetUuid, amount);
                    admin.sendMessage(plugin.getLang().component("currency.given",
                        "player", targetName, "amount", plugin.getLang().formatAmount(amount)));
                    Player online = Bukkit.getPlayer(targetUuid);
                    if (online != null) online.sendMessage(plugin.getLang().component("currency.received",
                        "amount", plugin.getLang().formatAmount(amount)));
                }
                case "take" -> {
                    plugin.getCurrency().takeCoins(targetUuid, amount);
                    admin.sendMessage(plugin.getLang().component("currency.taken",
                        "player", targetName, "amount", plugin.getLang().formatAmount(amount)));
                    Player online = Bukkit.getPlayer(targetUuid);
                    if (online != null) online.sendMessage(plugin.getLang().component("currency.removed",
                        "amount", plugin.getLang().formatAmount(amount)));
                }
                case "set" -> {
                    plugin.getCurrency().setBalance(targetUuid, amount);
                    admin.sendMessage(plugin.getLang().component("currency.set",
                        "player", targetName, "amount", plugin.getLang().formatAmount(amount)));
                }
                // ── VIP Tokens ──
                case "vtgive" -> {
                    long newBal = plugin.getVipTokens().addCoins(targetUuid, amount, "admin: " + admin.getName());
                    admin.sendMessage(plugin.getLang().component("viptoken.given",
                        "amount", String.valueOf(amount), "player", targetName, "balance", String.valueOf(newBal)));
                    Player online = Bukkit.getPlayer(targetUuid);
                    if (online != null) online.sendMessage(plugin.getLang().component("viptoken.received",
                        "amount", String.valueOf(amount), "balance", String.valueOf(newBal)));
                }
                case "vttake" -> {
                    long current = plugin.getVipTokens().getBalance(targetUuid);
                    long toTake = Math.min(amount, current);
                    plugin.getVipTokens().takeCoins(targetUuid, toTake, "admin: " + admin.getName());
                    admin.sendMessage(plugin.getLang().component("viptoken.taken",
                        "amount", String.valueOf(toTake), "player", targetName,
                        "balance", String.valueOf(plugin.getVipTokens().getBalance(targetUuid))));
                }
                case "vtset" -> {
                    plugin.getVipTokens().setBalance(targetUuid, amount, "admin set: " + admin.getName());
                    admin.sendMessage(plugin.getLang().component("viptoken.set",
                        "player", targetName, "amount", String.valueOf(amount)));
                }
            }
            awaitingInput.remove(admin.getUniqueId());
            // Reopen admin panel
            Bukkit.getScheduler().runTask(plugin, () -> openFor(admin));
        }

        return true;
    }

    public boolean isAwaitingInput(Player player) {
        return awaitingInput.containsKey(player.getUniqueId());
    }

    private void showTopList(Player admin) {
        admin.closeInventory();
        admin.sendMessage(plugin.getLang().component("currency.top-header"));
        List<Map.Entry<UUID, Long>> top = plugin.getCurrency().getTopList(10);
        for (int i = 0; i < top.size(); i++) {
            Map.Entry<UUID, Long> entry = top.get(i);
            String name = plugin.getCurrency().getPlayerName(entry.getKey());
            admin.sendMessage(plugin.getLang().component("currency.top-entry",
                "rank", String.valueOf(i + 1),
                "player", name,
                "amount", plugin.getLang().formatAmount(entry.getValue())));
        }
    }

    private ItemStack makeItem(Material mat, String name, List<String> loreLines) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(name));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) lore.add(colorize(line));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void fill(Inventory inv, int size) {
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = glass.getItemMeta();
        meta.displayName(Component.empty());
        glass.setItemMeta(meta);
        for (int i = 0; i < size; i++) inv.setItem(i, glass);
    }

    private Component colorize(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
