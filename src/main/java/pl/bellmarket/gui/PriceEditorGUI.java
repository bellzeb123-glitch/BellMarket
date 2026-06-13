package pl.bellmarket.gui;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.bellmarket.BellMarket;
import pl.bellmarket.config.LangManager;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * PriceEditorGUI — edytor cen skinów SkinStudio.
 *
 * Dostęp:
 *   /bm prices         → open(player, false)
 *   AdminGUI → Prices  → open(player, true) — przycisk ← Wróć wraca do AdminGUI
 *
 * Flow: Tier list → Skin list → klik = set price (chat input), shift-click = reset.
 */
public class PriceEditorGUI implements Listener {

    private final BellMarket plugin;

    /** Players awaiting chat price input. */
    private final Map<UUID, PendingInput> awaiting = new ConcurrentHashMap<>();

    /** Players who entered from AdminGUI. */
    private final Set<UUID> fromAdmin = ConcurrentHashMap.newKeySet();

    public PriceEditorGUI(BellMarket plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** Check if player is awaiting price input (for AdminChatListener). */
    public boolean isAwaitingInput(Player player) {
        return awaiting.containsKey(player.getUniqueId());
    }

    /** Handle chat input for price editing. */
    public void handleChatInput(Player player, String input) {
        PendingInput pending = awaiting.remove(player.getUniqueId());
        if (pending == null) return;

        LangManager lang = plugin.getLang();

        if (input.equalsIgnoreCase("cancel") || input.equalsIgnoreCase("anuluj")) {
            player.sendMessage(lang.component("admin.prices-cancelled"));
            Bukkit.getScheduler().runTask(plugin, () -> openSkinList(player, pending.tier, pending.page));
            return;
        }

        long newPrice;
        try {
            newPrice = Long.parseLong(input);
            if (newPrice < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage(lang.component("invalid-amount"));
            return;
        }

        // Save to providers/skinstudio.yml
        saveSkinPrice(pending.skinId, newPrice);
        plugin.reload();

        player.sendMessage(lang.component("admin.prices-saved",
                "product", pending.skinId,
                "price", String.valueOf(newPrice)));

        Bukkit.getScheduler().runTask(plugin, () -> openSkinList(player, pending.tier, pending.page));
    }

    // ─── open ────────────────────────────────────────────────────────────

    public void open(Player player, boolean fromAdminPanel) {
        if (fromAdminPanel) fromAdmin.add(player.getUniqueId());
        else fromAdmin.remove(player.getUniqueId());
        openTierList(player);
    }

    /**
     * Scans SkinStudio config for tiers and shows them as clickable icons.
     */
    public void openTierList(Player player) {
        LangManager lang = plugin.getLang();
        Map<String, TierMeta> tiers = scanTiers();

        if (tiers.isEmpty()) {
            player.sendMessage(LangManager.colorize("&cNo SkinStudio tiers detected."));
            return;
        }

        Inventory inv = Bukkit.createInventory(new Holder(null, 0),
                54, LangManager.colorize(lang.getRaw("admin.prices-title")));

        fillBackground(inv);

        int slot = 10;
        for (Map.Entry<String, TierMeta> entry : tiers.entrySet()) {
            if (slot >= 44) break;
            inv.setItem(slot, makeTierIcon(entry.getKey(), entry.getValue()));
            slot++;
            if ((slot % 9) == 8) slot += 2;
        }

        // Back / Close
        if (fromAdmin.contains(player.getUniqueId())) {
            inv.setItem(45, makePane(Material.ARROW, lang.getRaw("gui.back-button"),
                    List.of(LangManager.colorize("&7" + lang.getRaw("admin.prices-back-to-admin")))));
        }
        inv.setItem(49, makePane(Material.BARRIER, lang.getRaw("gui.close-button"), List.of()));

        player.openInventory(inv);
    }

    /**
     * Shows all skins of a tier with current prices. Click to edit, shift-click to reset.
     */
    public void openSkinList(Player player, String tier, int page) {
        LangManager lang = plugin.getLang();
        List<SkinEntry> skins = scanSkinsOfTier(tier);
        skins.sort(Comparator.comparing(s -> s.skinId));

        int totalPages = Math.max(1, (int) Math.ceil(skins.size() / 28.0));
        page = Math.max(0, Math.min(page, totalPages - 1));

        TierMeta tierMeta = scanTiers().getOrDefault(tier, new TierMeta("&7", tier, Material.LIGHT_GRAY_STAINED_GLASS_PANE));

        String title = lang.getRaw("admin.prices-category-title",
                "category", tierMeta.color + capitalize(tier));

        Inventory inv = Bukkit.createInventory(new Holder(tier, page),
                54, LangManager.colorize(title));

        fillBackground(inv);

        int start = page * 28;
        int end = Math.min(start + 28, skins.size());
        int slot = 10;
        for (int i = start; i < end; i++) {
            inv.setItem(slot, makeSkinIcon(skins.get(i), tierMeta));
            slot++;
            if ((slot % 9) == 8) slot += 2;
        }

        // Navigation
        if (page > 0) inv.setItem(48, makePane(Material.ARROW, "&aPrevious page", List.of()));
        if (page < totalPages - 1) inv.setItem(50, makePane(Material.ARROW, "&aNext page", List.of()));

        // Back to tiers
        inv.setItem(45, makePane(Material.BARRIER, "&cBack to tiers", List.of()));
        inv.setItem(49, makePane(Material.PAPER,
                lang.getRaw("gui.page-info", "current", String.valueOf(page + 1), "total", String.valueOf(totalPages)),
                List.of()));

        player.openInventory(inv);
    }

    // ─── click ───────────────────────────────────────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!player.hasPermission("bellmarket.admin")) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;

        int slot = event.getSlot();

        // Tier list view (holder.tier == null)
        if (holder.tier == null) {
            // Back to admin
            if (slot == 45 && fromAdmin.contains(player.getUniqueId())) {
                fromAdmin.remove(player.getUniqueId());
                player.closeInventory();
                plugin.getAdminGUI().openFor(player);
                return;
            }
            // Close
            if (slot == 49) {
                fromAdmin.remove(player.getUniqueId());
                player.closeInventory();
                return;
            }

            // Click tier icon → extract tag
            String tag = extractTag(clicked, "tier:");
            if (tag != null) {
                openSkinList(player, tag, 0);
            }
            return;
        }

        // Skin list view
        if (slot == 45) { openTierList(player); return; }
        if (slot == 48) { openSkinList(player, holder.tier, holder.page - 1); return; }
        if (slot == 50) { openSkinList(player, holder.tier, holder.page + 1); return; }

        handleSkinClick(player, holder, slot, event.getClick(), clicked);
    }

    private void handleSkinClick(Player player, Holder holder, int slot, ClickType click, ItemStack clicked) {
        LangManager lang = plugin.getLang();
        String skinId = extractTag(clicked, "skin:");
        if (skinId == null) return;

        if (click.isShiftClick()) {
            // Reset to default
            removeOverride(skinId);
            plugin.reload();
            openSkinList(player, holder.tier, holder.page);
            return;
        }

        // Start price input via chat
        player.closeInventory();
        TierMeta tierMeta = scanTiers().getOrDefault(holder.tier, new TierMeta("&7", holder.tier, Material.PAPER));
        long currentPrice = getCurrentPrice(skinId, tierMeta.defaultPrice);

        awaiting.put(player.getUniqueId(), new PendingInput(skinId, holder.tier, holder.page));
        player.sendMessage(lang.component("admin.prices-enter",
                "product", skinId, "current", String.valueOf(currentPrice)));
        player.sendMessage(lang.component("admin.prices-cancel-hint"));
    }

    // ─── scanning SkinStudio ─────────────────────────────────────────────

    private Map<String, TierMeta> scanTiers() {
        var ss = Bukkit.getPluginManager().getPlugin("SkinStudio");
        if (ss == null || !ss.isEnabled()) return Map.of();

        File configFile = new File(ss.getDataFolder(), "config.yml");
        if (!configFile.exists()) return Map.of();

        FileConfiguration ssCfg = YamlConfiguration.loadConfiguration(configFile);
        ConfigurationSection skins = ssCfg.getConfigurationSection("skins");
        if (skins == null) return Map.of();

        Map<String, TierMeta> tiers = new LinkedHashMap<>();
        FileConfiguration provCfg = loadProviderConfig();

        for (String skinName : skins.getKeys(false)) {
            String tier = skinName.contains("_") ? skinName.substring(0, skinName.indexOf('_')) : "misc";
            if (tiers.containsKey(tier)) continue;

            String color = "&7";
            String displayName = capitalize(tier);
            Material icon = Material.LIGHT_GRAY_STAINED_GLASS_PANE;
            long defPrice = provCfg.getLong("default-price", 500);

            if (provCfg.isConfigurationSection("tiers." + tier)) {
                ConfigurationSection tc = provCfg.getConfigurationSection("tiers." + tier);
                color = tc.getString("color", color);
                displayName = tc.getString("display-name", displayName);
                icon = parseMat(tc.getString("icon"), icon);
                defPrice = tc.getLong("default-price", defPrice);
            }

            tiers.put(tier, new TierMeta(color, displayName, icon, defPrice));
        }

        return tiers;
    }

    private List<SkinEntry> scanSkinsOfTier(String tier) {
        var ss = Bukkit.getPluginManager().getPlugin("SkinStudio");
        if (ss == null) return List.of();

        File configFile = new File(ss.getDataFolder(), "config.yml");
        if (!configFile.exists()) return List.of();

        FileConfiguration ssCfg = YamlConfiguration.loadConfiguration(configFile);
        ConfigurationSection skins = ssCfg.getConfigurationSection("skins");
        if (skins == null) return List.of();

        FileConfiguration provCfg = loadProviderConfig();
        long defaultPrice = provCfg.getLong("default-price", 500);
        if (provCfg.isConfigurationSection("tiers." + tier)) {
            defaultPrice = provCfg.getLong("tiers." + tier + ".default-price", defaultPrice);
        }

        List<SkinEntry> entries = new ArrayList<>();
        for (String skinName : skins.getKeys(false)) {
            String skinTier = skinName.contains("_") ? skinName.substring(0, skinName.indexOf('_')) : "misc";
            if (!skinTier.equals(tier)) continue;

            long price = provCfg.getLong("skin-prices." + skinName, defaultPrice);
            entries.add(new SkinEntry(skinName, price));
        }
        return entries;
    }

    private long getCurrentPrice(String skinId, long fallback) {
        FileConfiguration cfg = loadProviderConfig();
        return cfg.getLong("skin-prices." + skinId, fallback);
    }

    private FileConfiguration loadProviderConfig() {
        File f = new File(plugin.getDataFolder(), "providers/skinstudio.yml");
        if (!f.exists()) return new YamlConfiguration();
        return YamlConfiguration.loadConfiguration(f);
    }

    private void saveSkinPrice(String skinId, long price) {
        File f = new File(plugin.getDataFolder(), "providers/skinstudio.yml");
        FileConfiguration cfg = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        cfg.set("skin-prices." + skinId, price);
        try { cfg.save(f); }
        catch (IOException e) { plugin.getLogger().log(Level.WARNING, "Could not save skinstudio.yml", e); }
    }

    private void removeOverride(String skinId) {
        File f = new File(plugin.getDataFolder(), "providers/skinstudio.yml");
        if (!f.exists()) return;
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        cfg.set("skin-prices." + skinId, null);
        try { cfg.save(f); }
        catch (IOException e) { plugin.getLogger().log(Level.WARNING, "Could not save skinstudio.yml", e); }
    }

    // ─── item builders ───────────────────────────────────────────────────

    private ItemStack makeTierIcon(String tier, TierMeta meta) {
        return makePane(meta.icon, meta.color + meta.displayName,
                List.of(LangManager.colorize("&7Tier: " + meta.color + meta.displayName),
                        LangManager.colorize(""),
                        LangManager.colorize("&eClick &7to edit prices"),
                        LangManager.colorize("&8tier:" + tier)));
    }

    private ItemStack makeSkinIcon(SkinEntry skin, TierMeta tierMeta) {
        LangManager lang = plugin.getLang();
        return makePane(Material.PAPER, tierMeta.color + capitalize(skin.skinId),
                List.of(LangManager.colorize("&7" + lang.getRaw("admin.prices-current-price",
                                "price", String.valueOf(skin.price),
                                "currency", lang.getCurrencyName())),
                        LangManager.colorize(""),
                        LangManager.colorize("&eLeft-click: &fset price"),
                        LangManager.colorize("&eShift-click: &freset"),
                        LangManager.colorize("&8skin:" + skin.skinId)));
    }

    private ItemStack makePane(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(name));
        List<net.kyori.adventure.text.Component> comps = new ArrayList<>();
        for (String line : lore) comps.add(LegacyComponentSerializer.legacyAmpersand().deserialize(line));
        meta.lore(comps);
        item.setItemMeta(meta);
        return item;
    }

    private void fillBackground(Inventory inv) {
        ItemStack filler = makePane(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 9; i++)  inv.setItem(i, filler);
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);
        for (int row = 1; row < 5; row++) {
            inv.setItem(row * 9, filler);
            inv.setItem(row * 9 + 8, filler);
        }
    }

    private String extractTag(ItemStack item, String prefix) {
        if (item == null || item.getItemMeta() == null || item.getItemMeta().lore() == null) return null;
        for (net.kyori.adventure.text.Component comp : item.getItemMeta().lore()) {
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(comp);
            if (plain.startsWith(prefix)) return plain.substring(prefix.length());
        }
        return null;
    }

    private Material parseMat(String name, Material fallback) {
        if (name == null) return fallback;
        try { return Material.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return fallback; }
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ─── inner classes ───────────────────────────────────────────────────

    public static class Holder implements InventoryHolder {
        final String tier; // null = tier list view
        final int page;
        public Holder(String tier, int page) { this.tier = tier; this.page = page; }
        @Override public Inventory getInventory() { return null; }
    }

    record TierMeta(String color, String displayName, Material icon, long defaultPrice) {
        TierMeta(String color, String displayName, Material icon) {
            this(color, displayName, icon, 500);
        }
    }

    record SkinEntry(String skinId, long price) {}

    record PendingInput(String skinId, String tier, int page) {}
}
