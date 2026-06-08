/*
 * BellMarket - PriceEditorGUI (SESJA-2)
 *
 * Standalone admin GUI for editing skinstudio.yml prices without touching YAML.
 * Lives separately from AdminGUI to avoid modifying existing code.
 *
 * Flow:
 *   /bm prices                           → openTierList(player)
 *      → GUI with one stained-glass-pane per detected tier
 *   Click tier                           → openSkinList(player, tier, 0)
 *      → paginated GUI with all skins of that tier; current price in lore
 *   Click skin                           → promptPriceInput(player, skinKey)
 *      → close GUI, send chat prompt, wait for chat input
 *   Player types number in chat          → onChat() catches, writes skinstudio.yml,
 *                                          calls plugin.reload(), reopens GUI
 *   Shift-click skin                     → reset (removes per-skin override)
 *
 * Implements Listener directly — registered once in BellMarket.onEnable().
 */
package pl.bellmarket.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import pl.bellmarket.BellMarket;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

public class PriceEditorGUI implements Listener {

    private static final int SIZE_TIERS  = 27;
    private static final int SIZE_SKINS  = 54;
    private static final int SKINS_PER_PAGE = 45;
    private static final int SLOT_BACK   = 49;
    private static final int SLOT_PREV   = 45;
    private static final int SLOT_NEXT   = 53;
    private static final int SLOT_INFO   = 47;

    private final BellMarket plugin;
    /** Players currently waiting to type a price in chat. */
    private final Map<UUID, PendingInput> awaiting = new HashMap<>();

    public PriceEditorGUI(BellMarket plugin) {
        this.plugin = plugin;
    }

    /** What a player is currently editing. */
    private record PendingInput(String skinKey, String tier, int page) {}

    /** Inventory holder marker — identifies our GUIs in click events. */
    private record Holder(String view, String tier, int page) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    // ─── Entry points ─────────────────────────────────────────────────────

    public void openTierList(Player player) {
        Map<String, TierSummary> tiers = scanTiers();
        if (tiers.isEmpty()) {
            player.sendMessage(colorize("&cNo SkinStudio tiers detected. Make sure SkinStudio is loaded."));
            return;
        }
        Inventory inv = Bukkit.createInventory(new Holder("tiers", null, 0), SIZE_TIERS,
            colorize("&8❀ &5&lEdit Skin Prices &7- Tiers"));

        int slot = 10;
        for (Map.Entry<String, TierSummary> e : tiers.entrySet()) {
            if (slot >= SIZE_TIERS - 1) break;
            TierSummary t = e.getValue();
            inv.setItem(slot++, makeTierIcon(e.getKey(), t));
            if (slot % 9 == 8) slot += 2;
        }
        fillBackground(inv);
        player.openInventory(inv);
    }

    public void openSkinList(Player player, String tier, int page) {
        List<SkinEntry> skins = scanSkinsOfTier(tier);
        if (skins.isEmpty()) {
            player.sendMessage(colorize("&cNo skins in tier &f" + tier));
            return;
        }
        skins.sort(Comparator.comparing(SkinEntry::key));
        int totalPages = (skins.size() + SKINS_PER_PAGE - 1) / SKINS_PER_PAGE;
        page = Math.max(0, Math.min(page, totalPages - 1));

        Inventory inv = Bukkit.createInventory(new Holder("skins", tier, page), SIZE_SKINS,
            colorize("&8❀ &5" + capitalize(tier) + " &7- Page " + (page + 1) + "/" + totalPages));

        int start = page * SKINS_PER_PAGE;
        int end   = Math.min(start + SKINS_PER_PAGE, skins.size());
        for (int i = start; i < end; i++) {
            SkinEntry s = skins.get(i);
            inv.setItem(i - start, makeSkinIcon(s));
        }
        // navigation row
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, makePane(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
        if (page > 0)            inv.setItem(SLOT_PREV, simpleItem(Material.ARROW, "&aPrevious page"));
        if (page < totalPages-1) inv.setItem(SLOT_NEXT, simpleItem(Material.ARROW, "&aNext page"));
        inv.setItem(SLOT_BACK, simpleItem(Material.BARRIER, "&cBack to tiers"));
        inv.setItem(SLOT_INFO, simpleItem(Material.PAPER,
            "&7Tier: &f" + capitalize(tier),
            "&7Skins: &f" + skins.size(),
            "",
            "&eLeft-click skin: &fset custom price",
            "&eShift-click:   &fclear override"));

        player.openInventory(inv);
    }

    // ─── Inventory event handlers ─────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;

        switch (h.view()) {
            case "tiers" -> handleTierClick(player, clicked);
            case "skins" -> handleSkinClick(player, h, e.getSlot(), e.getClick(), clicked);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        // No-op — pending input is tracked in awaiting map; closing doesn't cancel
    }

    private void handleTierClick(Player player, ItemStack clicked) {
        String tier = extractTag(clicked, "tier:");
        if (tier == null) return;
        openSkinList(player, tier, 0);
    }

    private void handleSkinClick(Player player, Holder h, int slot, ClickType click, ItemStack clicked) {
        if (slot == SLOT_BACK) { openTierList(player); return; }
        if (slot == SLOT_PREV) { openSkinList(player, h.tier(), h.page() - 1); return; }
        if (slot == SLOT_NEXT) { openSkinList(player, h.tier(), h.page() + 1); return; }
        if (slot >= 45) return; // bottom row navigation/info, ignore other clicks

        String skinKey = extractTag(clicked, "skin:");
        if (skinKey == null) return;

        if (click.isShiftClick()) {
            removeOverride(skinKey);
            player.sendMessage(colorize("&aCleared per-skin override for &f" + skinKey
                + "&7 — now uses tier default."));
            plugin.reload();
            openSkinList(player, h.tier(), h.page());
            return;
        }

        // Normal click → ask for new price in chat
        awaiting.put(player.getUniqueId(), new PendingInput(skinKey, h.tier(), h.page()));
        player.closeInventory();
        player.sendMessage(colorize("&8&m──────────────────────────────"));
        player.sendMessage(colorize("&7Type a new price for &f" + skinKey + "&7 in chat."));
        player.sendMessage(colorize("&7Type &fcancel &7to abort, &freset &7to remove override."));
        player.sendMessage(colorize("&8&m──────────────────────────────"));
    }

    // ─── Chat input handler ───────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent e) {
        Player player = e.getPlayer();
        PendingInput pending = awaiting.get(player.getUniqueId());
        if (pending == null) return;
        e.setCancelled(true);
        awaiting.remove(player.getUniqueId());

        String raw = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> processChatInput(player, pending, raw));
    }

    private void processChatInput(Player player, PendingInput pending, String raw) {
        if (raw.equalsIgnoreCase("cancel")) {
            player.sendMessage(colorize("&7Price edit cancelled."));
            openSkinList(player, pending.tier(), pending.page());
            return;
        }
        if (raw.equalsIgnoreCase("reset") || raw.equalsIgnoreCase("remove")) {
            removeOverride(pending.skinKey());
            player.sendMessage(colorize("&aCleared override for &f" + pending.skinKey()));
            plugin.reload();
            openSkinList(player, pending.tier(), pending.page());
            return;
        }
        long newPrice;
        try {
            newPrice = Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            player.sendMessage(colorize("&cInvalid number: &f" + raw + " &7(cancelled)"));
            openSkinList(player, pending.tier(), pending.page());
            return;
        }
        if (newPrice < 0) {
            player.sendMessage(colorize("&cPrice must be zero or positive."));
            openSkinList(player, pending.tier(), pending.page());
            return;
        }
        setOverride(pending.skinKey(), newPrice);
        player.sendMessage(colorize("&aSet &f" + pending.skinKey() + "&a to &e" + newPrice + " BellCoins"));
        plugin.reload();
        openSkinList(player, pending.tier(), pending.page());
    }

    public boolean isAwaitingInput(Player player) {
        return awaiting.containsKey(player.getUniqueId());
    }

    // ─── Scanning helpers ─────────────────────────────────────────────────

    private record TierSummary(int count, long defaultPrice, Material icon, String color, String displayName) {}
    private record SkinEntry(String key, String tier, long currentPrice, boolean isOverridden,
                             Material material, String displayName) {}

    private Map<String, TierSummary> scanTiers() {
        Map<String, TierSummary> out = new LinkedHashMap<>();
        ConfigurationSection skins = loadSkinStudioSkins();
        if (skins == null) return out;
        FileConfiguration prov = loadProviderConfig();
        long globalDefault = prov.getLong("default-price", 500);
        ConfigurationSection tiersCfg = prov.getConfigurationSection("tiers");

        Map<String, List<String>> grouped = new TreeMap<>();
        for (String key : skins.getKeys(false)) {
            String tier = tierOf(key);
            grouped.computeIfAbsent(tier, k -> new ArrayList<>()).add(key);
        }
        for (Map.Entry<String, List<String>> e : grouped.entrySet()) {
            String tier = e.getKey();
            ConfigurationSection tcfg = tiersCfg != null ? tiersCfg.getConfigurationSection(tier) : null;
            long defPrice = tcfg != null ? tcfg.getLong("default-price", globalDefault) : globalDefault;
            String color = tcfg != null ? tcfg.getString("color", "&7") : "&7";
            String display = tcfg != null ? tcfg.getString("display-name", capitalize(tier)) : capitalize(tier);
            Material icon = parseMaterial(tcfg != null ? tcfg.getString("icon") : null,
                Material.LIGHT_GRAY_STAINED_GLASS_PANE);
            out.put(tier, new TierSummary(e.getValue().size(), defPrice, icon, color, display));
        }
        return out;
    }

    private List<SkinEntry> scanSkinsOfTier(String tier) {
        List<SkinEntry> out = new ArrayList<>();
        ConfigurationSection skins = loadSkinStudioSkins();
        if (skins == null) return out;
        FileConfiguration prov = loadProviderConfig();
        long globalDefault = prov.getLong("default-price", 500);
        ConfigurationSection tiersCfg = prov.getConfigurationSection("tiers");
        ConfigurationSection pricesCfg = prov.getConfigurationSection("skin-prices");
        ConfigurationSection tcfg = tiersCfg != null ? tiersCfg.getConfigurationSection(tier) : null;
        long tierDefault = tcfg != null ? tcfg.getLong("default-price", globalDefault) : globalDefault;

        for (String key : skins.getKeys(false)) {
            if (!tier.equals(tierOf(key))) continue;
            boolean overridden = pricesCfg != null && pricesCfg.contains(key);
            long price = overridden ? pricesCfg.getLong(key) : tierDefault;
            ConfigurationSection sd = skins.getConfigurationSection(key);
            Material mat = Material.PAPER;
            String display = key;
            if (sd != null) {
                List<String> types = sd.getStringList("item-types");
                if (!types.isEmpty()) {
                    try { mat = Material.valueOf(types.get(0).toUpperCase(Locale.ROOT)); }
                    catch (IllegalArgumentException ignored) {}
                }
                display = sd.getString("display-name", key);
            }
            out.add(new SkinEntry(key, tier, price, overridden, mat, display));
        }
        return out;
    }

    private ConfigurationSection loadSkinStudioSkins() {
        Plugin sk = plugin.getServer().getPluginManager().getPlugin("SkinStudio");
        if (sk == null) return null;
        File f = new File(sk.getDataFolder(), "config.yml");
        if (!f.exists()) return null;
        return YamlConfiguration.loadConfiguration(f).getConfigurationSection("skins");
    }

    private FileConfiguration loadProviderConfig() {
        File f = new File(plugin.getDataFolder(), "providers/skinstudio.yml");
        if (!f.exists()) return new YamlConfiguration();
        return YamlConfiguration.loadConfiguration(f);
    }

    // ─── Writing to skinstudio.yml ────────────────────────────────────────

    private void setOverride(String skinKey, long price) {
        File f = new File(plugin.getDataFolder(), "providers/skinstudio.yml");
        if (!f.exists()) {
            plugin.getLogger().warning("[PriceEditor] skinstudio.yml does not exist yet");
            return;
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection prices = cfg.getConfigurationSection("skin-prices");
        if (prices == null) prices = cfg.createSection("skin-prices");
        prices.set(skinKey, price);
        saveQuietly(cfg, f);
    }

    private void removeOverride(String skinKey) {
        File f = new File(plugin.getDataFolder(), "providers/skinstudio.yml");
        if (!f.exists()) return;
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection prices = cfg.getConfigurationSection("skin-prices");
        if (prices == null) return;
        prices.set(skinKey, null);
        saveQuietly(cfg, f);
    }

    private void saveQuietly(FileConfiguration cfg, File f) {
        try { cfg.save(f); }
        catch (IOException e) {
            plugin.getLogger().warning("[PriceEditor] Could not save skinstudio.yml: " + e.getMessage());
        }
    }

    // ─── Item builders ────────────────────────────────────────────────────

    private ItemStack makeTierIcon(String tier, TierSummary t) {
        return simpleItem(t.icon(),
            t.color() + "✦ " + t.displayName() + "  &7(&f" + t.count() + "&7 skins)",
            "",
            "&7Default price: &e" + t.defaultPrice() + " BellCoins",
            "",
            "&eClick &7to edit skins of this tier",
            "&8tier:" + tier);
    }

    private ItemStack makeSkinIcon(SkinEntry s) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Tier: &f" + capitalize(s.tier()));
        lore.add("&7Current price: &e" + s.currentPrice() + " BellCoins");
        lore.add(s.isOverridden()
            ? "&8(custom override active)"
            : "&8(using tier default)");
        lore.add("");
        lore.add("&eLeft-click: &fset custom price");
        if (s.isOverridden()) lore.add("&eShift-click: &fclear override");
        lore.add("&8skin:" + s.key());
        return simpleItem(s.material(), s.displayName(), lore.toArray(new String[0]));
    }

    private ItemStack simpleItem(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(colorize(name).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            if (lore.length > 0) {
                List<Component> comps = new ArrayList<>();
                for (String l : lore) {
                    comps.add(colorize(l).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                }
                meta.lore(comps);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack makePane(Material mat, String name) {
        return simpleItem(mat, name);
    }

    private void fillBackground(Inventory inv) {
        ItemStack pane = makePane(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, pane);
        }
    }

    // ─── Misc helpers ─────────────────────────────────────────────────────

    private static String tierOf(String skinKey) {
        int us = skinKey.indexOf('_');
        return us > 0 ? skinKey.substring(0, us).toLowerCase(Locale.ROOT) : "other";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + (s.length() > 1 ? s.substring(1) : "");
    }

    /** Extracts trailing tag like "tier:bronze" from the LAST line of item lore. */
    private static String extractTag(ItemStack item, String prefix) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta.lore() == null) return null;
        List<Component> lore = meta.lore();
        for (int i = lore.size() - 1; i >= 0; i--) {
            String txt = PlainTextComponentSerializer.plainText().serialize(lore.get(i));
            int idx = txt.indexOf(prefix);
            if (idx >= 0) return txt.substring(idx + prefix.length()).trim();
        }
        return null;
    }

    private static Material parseMaterial(String name, Material fallback) {
        if (name == null || name.isEmpty()) return fallback;
        try { return Material.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return fallback; }
    }

    private static Component colorize(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s == null ? "" : s);
    }
}
