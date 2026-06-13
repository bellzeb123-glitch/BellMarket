package pl.bellmarket.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.bellmarket.BellMarket;
import pl.bellmarket.model.Category;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AdminGUI implements Listener {

    public static class AdminHolder implements InventoryHolder {
        private final String view;
        private Inventory inv;
        public AdminHolder(String view)           { this.view = view; }
        @Override public Inventory getInventory() { return inv; }
        public void setInventory(Inventory inv)   { this.inv = inv; }
        public String getView()                   { return view; }
    }

    private static final int FIRST_CAT_SLOT = 27;

    private final BellMarket plugin;
    private final Map<UUID, String> awaitingInput = new HashMap<>();

    public AdminGUI(BellMarket plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ── Chat-input API (used by AdminChatListener) ─────────────────────────

    public void promptInput(Player player, String context) {
        awaitingInput.put(player.getUniqueId(), context);
    }

    public boolean isAwaitingInput(Player player) {
        return awaitingInput.containsKey(player.getUniqueId());
    }

    public boolean handleChatInput(Player player, String message) {
        String context = awaitingInput.remove(player.getUniqueId());
        if (context == null) return false;

        if (message.equalsIgnoreCase("cancel")) {
            player.sendMessage(c("&8[&6BellMarket&8] &7Input cancelled."));
            Bukkit.getScheduler().runTask(plugin, () -> openFor(player));
            return true;
        }
        player.sendMessage(c("&8[&6BellMarket&8] &7Received: &f" + message));
        Bukkit.getScheduler().runTask(plugin, () -> openFor(player));
        return true;
    }

    // ── Main admin panel ───────────────────────────────────────────────────

    public void openFor(Player player) {
        AdminHolder holder = new AdminHolder("main");
        Inventory inv = Bukkit.createInventory(holder, 54,
            plugin.buildTitle("&8\u2699 &6BellMarket Admin"));
        holder.setInventory(inv);

        fillBackground(inv);

        inv.setItem(10, makeItem(Material.SUNFLOWER, "&e&l\u27f3 Reload",
            "&7Reload all categories, providers",
            "&7and config without server restart.",
            "", "&eClick to reload"));

        inv.setItem(12, makeItem(Material.CHEST, "&a&lCategories",
            "&7Loaded: &f" + plugin.getCategories().getCategories().size() + " categories",
            "&7Total products: &f" +
                plugin.getCategories().getCategories().stream()
                    .mapToInt(cat -> cat.getProducts().size()).sum(),
            "", "&7Categories listed below"));

        inv.setItem(14, makeItem(Material.WRITABLE_BOOK, "&b&lLanguage",
            "&7Current: &f" + plugin.getConfig().getString("language", "en").toUpperCase(),
            "",
            "&eLeft-click: &fSwitch to EN",
            "&eRight-click: &fSwitch to PL"));

        inv.setItem(16, makeItem(Material.GOLD_INGOT, "&6&lEconomy",
            "&7Currency: &f" + plugin.getLang().getCurrencyName(),
            "&7Symbol: &f" + plugin.getLang().getCurrencySymbol()));

        List<Category> cats = plugin.getCategories().getCategories();
        for (int i = 0; i < Math.min(cats.size(), 18); i++) {
            Category cat = cats.get(i);
            List<String> lore = new ArrayList<>();
            lore.add("&7ID: &8" + cat.getId());
            lore.add("&7Products: &f" + cat.getProducts().size()
                + " &8(" + cat.getEnabledProducts().size() + " enabled)");
            lore.add("&7Order: &f" + cat.getOrder());
            lore.add("");
            lore.add("&eClick &7to open this category in shop");
            inv.setItem(FIRST_CAT_SLOT + i, makeItem(
                cat.getIconMaterial() != null ? cat.getIconMaterial() : Material.PAPER,
                cat.getName(), lore.toArray(new String[0])));
        }

        inv.setItem(49, makeItem(Material.BARRIER, "&c&lClose"));
        player.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof AdminHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (!player.hasPermission("bellmarket.admin")) return;

        int slot = e.getRawSlot();

        switch (slot) {
            case 10 -> {
                player.closeInventory();
                plugin.reload();
                player.sendMessage(c("&8[&6BellMarket&8] &aReloaded! "
                    + plugin.getCategories().getCategories().size() + " categories loaded."));
                player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                return;
            }
            case 14 -> {
                String lang = e.getClick().isLeftClick() ? "en" : "pl";
                plugin.getConfig().set("language", lang);
                plugin.saveConfig();
                plugin.getLang().reload();
                player.playSound(player, Sound.UI_BUTTON_CLICK, 1f, 1f);
                player.sendMessage(c("&8[&6BellMarket&8] &aLanguage: &f" + lang.toUpperCase()));
                openFor(player);
                return;
            }
            case 49 -> {
                player.closeInventory();
                return;
            }
        }

        if (slot >= FIRST_CAT_SLOT && slot < FIRST_CAT_SLOT + 18) {
            int index = slot - FIRST_CAT_SLOT;
            List<Category> cats = plugin.getCategories().getCategories();
            if (index >= cats.size()) return;
            Category cat = cats.get(index);
            player.closeInventory();
            player.sendMessage(c("&8[&6BellMarket&8] &7Opening: &f" + cat.getName()));
            player.playSound(player, Sound.UI_BUTTON_CLICK, 1f, 1f);
            Bukkit.getScheduler().runTask(plugin,
                () -> plugin.getShopGUI().openCategory(player, cat.getId(), 0));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof AdminHolder) e.setCancelled(true);
    }

    private ItemStack makeItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(c(name).decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) {
            meta.lore(List.of(lore).stream()
                .map(l -> c(l.startsWith("&") ? l : "&7" + l)
                    .decoration(TextDecoration.ITALIC, false))
                .toList());
        }
        item.setItemMeta(meta);
        return item;
    }

    private void fillBackground(Inventory inv) {
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = glass.getItemMeta();
        if (m != null) { m.displayName(Component.empty()); glass.setItemMeta(m); }
        for (int i = 0; i < 54; i++) inv.setItem(i, glass);
    }

    private Component c(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }
}
