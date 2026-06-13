package pl.bellmarket.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
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
        Inventory inv = Bukkit.createInventory(holder, 27,
            colorize("&8⚙ &cBellMarket Admin &8⚙"));
        holder.setInventory(inv);

        // Fill background
        fill(inv, 27);

        // Stats
        int totalPlayers = plugin.getCurrency().getTopList(Integer.MAX_VALUE).size();
        inv.setItem(4, makeItem(Material.BOOK,
            "&6Shop Statistics",
            List.of(
                "&7Categories: &f" + plugin.getCategories().getCategories().size(),
                "&7Players with balance: &f" + totalPlayers,
                "&7Currency: &f" + plugin.getLang().getCurrencyName()
            )));

        // Give coins button
        inv.setItem(10, makeItem(Material.EMERALD,
            "&aGive Coins",
            List.of("&7Give coins to an online player.", "", "&eLeft-click &7to use")));

        // Take coins button
        inv.setItem(12, makeItem(Material.REDSTONE,
            "&cTake Coins",
            List.of("&7Take coins from an online player.", "", "&eLeft-click &7to use")));

        // Set coins button
        inv.setItem(14, makeItem(Material.GOLD_INGOT,
            "&6Set Coins",
            List.of("&7Set exact balance for a player.", "", "&eLeft-click &7to use")));

        // Top list button
        inv.setItem(16, makeItem(Material.GOLDEN_APPLE,
            "&eTop Players",
            List.of("&7View top 10 players by balance.", "", "&eLeft-click &7to view")));

        // Reload button
        inv.setItem(22, makeItem(Material.COMMAND_BLOCK,
            "&bReload Config",
            List.of("&7Reload all categories and config.", "", "&eLeft-click &7to reload")));

        admin.openInventory(inv);
        admin.sendMessage(plugin.getLang().component("admin.panel-opened"));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof AdminHolder)) return;

        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 27) return;

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
        String actionName = switch (action) {
            case "give" -> "give coins to";
            case "take" -> "take coins from";
            case "set"  -> "set balance for";
            default     -> action;
        };
        admin.sendMessage(colorize("&8[&cAdmin&8] &eEnter the player name to " + actionName + " (or &ccancel&e):"));
    }

    public boolean handleChatInput(Player admin, String message) {
        if (!awaitingInput.containsKey(admin.getUniqueId())) return false;

        String state = awaitingInput.get(admin.getUniqueId());

        if (message.equalsIgnoreCase("cancel")) {
            awaitingInput.remove(admin.getUniqueId());
            admin.sendMessage(colorize("&8[&cAdmin&8] &7Cancelled."));
            return true;
        }

        String[] parts = state.split(":", 2);
        String action = parts[0];
        String step   = parts[1];

        if (step.equals("step1")) {
            // Player name entered
            Player target = Bukkit.getPlayer(message);
            OfflinePlayer offline = target != null ? target : Bukkit.getOfflinePlayerIfCached(message);
            if (offline == null || offline.getName() == null) {
                admin.sendMessage(plugin.getLang().component("player-not-found", "player", message));
                awaitingInput.remove(admin.getUniqueId());
                return true;
            }
            awaitingInput.put(admin.getUniqueId(), action + ":" + offline.getUniqueId());
            admin.sendMessage(colorize("&8[&cAdmin&8] &eEnter the amount for &f" + offline.getName() + "&e:"));
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
