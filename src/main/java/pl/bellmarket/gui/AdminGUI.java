package pl.bellmarket.gui;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.bellmarket.BellMarket;
import pl.bellmarket.config.LangManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AdminGUI — panel admina BellMarket.
 *
 * Slots (54):
 *   10  Reload
 *   12  Price Editor  ← NEW — opens PriceEditorGUI with back button
 *   14  Language toggle
 *   16  Stats (placeholder)
 *   49  Close
 *
 * All texts from LangManager.getRaw() → /bm lang zmienia je globalnie.
 */
public class AdminGUI implements Listener {

    private final BellMarket plugin;

    // Chat input awaiting (for future rename features)
    private final Map<UUID, String> awaitingInput = new ConcurrentHashMap<>();

    private static final int SLOT_RELOAD = 10;
    private static final int SLOT_PRICES = 12;
    private static final int SLOT_LANG   = 14;
    private static final int SLOT_STATS  = 16;
    private static final int SLOT_CLOSE  = 49;

    public AdminGUI(BellMarket plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ─── chat input (for AdminChatListener) ──────────────────────────────

    public boolean isAwaitingInput(Player player) {
        return awaitingInput.containsKey(player.getUniqueId());
    }

    public void handleChatInput(Player player, String input) {
        String context = awaitingInput.remove(player.getUniqueId());
        if (context == null) return;
        // Future: handle rename, etc.
        openFor(player);
    }

    // ─── open ────────────────────────────────────────────────────────────

    public void openFor(Player player) {
        LangManager lang = plugin.getLang();
        Inventory inv = Bukkit.createInventory(new AdminHolder(), 54,
                LangManager.colorize(lang.getRaw("admin.gui-title")));

        fillBorder(inv);

        // Reload
        inv.setItem(SLOT_RELOAD, makeItem(Material.LIME_DYE,
                lang.getRaw("admin.gui-reload-name"),
                lang.getList("admin.gui-reload-lore")));

        // Price Editor ← NEW
        inv.setItem(SLOT_PRICES, makeItem(Material.GOLD_INGOT,
                lang.getRaw("admin.gui-prices-name"),
                lang.getList("admin.gui-prices-lore")));

        // Language toggle
        String currentLang = plugin.getConfig().getString("language", "en").toUpperCase();
        inv.setItem(SLOT_LANG, makeItem(Material.BOOK,
                lang.getRaw("admin.gui-lang-name"),
                List.of(LangManager.colorize("&7" + lang.getRaw("admin.gui-lang-current") + " &f" + currentLang),
                        LangManager.colorize(""),
                        LangManager.colorize("&eLeft-click &7→ EN"),
                        LangManager.colorize("&eRight-click &7→ PL"))));

        // Stats
        inv.setItem(SLOT_STATS, makeItem(Material.PAPER,
                lang.getRaw("admin.gui-stats-name"),
                lang.getList("admin.gui-stats-lore")));

        // Close
        inv.setItem(SLOT_CLOSE, makeItem(Material.BARRIER,
                lang.getRaw("gui.close-button"), List.of()));

        player.openInventory(inv);
    }

    // ─── click ───────────────────────────────────────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof AdminHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!player.hasPermission("bellmarket.admin")) return;

        int slot = event.getRawSlot();

        switch (slot) {
            case SLOT_RELOAD -> {
                player.closeInventory();
                plugin.reload();
                player.sendMessage(plugin.getLang().component("admin.reloaded"));
            }
            case SLOT_PRICES -> {
                player.closeInventory();
                plugin.getPriceEditor().open(player, true); // fromAdmin=true → back button returns here
            }
            case SLOT_LANG -> {
                String newLang = event.isLeftClick() ? "en" : "pl";
                plugin.getConfig().set("language", newLang);
                plugin.saveConfig();
                plugin.reload(); // reloads LangManager → all GUI texts update
                // Reopen admin panel in new language
                openFor(player);
                player.sendMessage(plugin.getLang().component("admin.language-switched",
                        "lang", newLang.toUpperCase()));
            }
            case SLOT_STATS -> {
                player.sendMessage(LangManager.colorize("&8[&6BellMarket&8] &7Stats coming soon."));
            }
            case SLOT_CLOSE -> player.closeInventory();
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private ItemStack makeItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(name));
        List<net.kyori.adventure.text.Component> comps = new ArrayList<>();
        for (String line : lore) {
            comps.add(LegacyComponentSerializer.legacyAmpersand().deserialize(line));
        }
        meta.lore(comps);
        item.setItemMeta(meta);
        return item;
    }

    private void fillBorder(Inventory inv) {
        ItemStack filler = makeItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 9; i++)  inv.setItem(i, filler);
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);
        for (int row = 1; row < 5; row++) {
            inv.setItem(row * 9, filler);
            inv.setItem(row * 9 + 8, filler);
        }
    }

    public static class AdminHolder implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
}
