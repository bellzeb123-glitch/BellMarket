/*
 * BellMarket - PriceEditorGUI (SESJA-2 FIX)
 *
 * Fixes:
 *   - scanTiers() now auto-detects tier color + icon FROM SkinStudio config.yml
 *     (same logic as SkinStudioProvider) instead of relying on empty tiers:{} config.
 *     Admin can still override in skinstudio.yml, but auto-detection works out-of-box.
 *   - Tier icons show as properly colored stained glass panes.
 */
package pl.bellmarket.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
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
import java.util.*;

public class PriceEditorGUI implements Listener {

    private static final int SIZE_TIERS  = 27;
    private static final int SIZE_SKINS  = 54;
    private static final int SKINS_PER_PAGE = 45;
    private static final int SLOT_BACK   = 49;
    private static final int SLOT_PREV   = 45;
    private static final int SLOT_NEXT   = 53;
    private static final int SLOT_INFO   = 47;

    /** Color code → matching stained glass pane — identical map to SkinStudioProvider. */
    private static final Map<String, Material> COLOR_TO_GLASS = new HashMap<>();
    static {
        COLOR_TO_GLASS.put("&0", Material.BLACK_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&1", Material.BLUE_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&2", Material.GREEN_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&3", Material.CYAN_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&4", Material.RED_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&5", Material.PURPLE_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&6", Material.ORANGE_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&7", Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&8", Material.GRAY_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&9", Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&a", Material.LIME_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&b", Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&c", Material.RED_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&d", Material.MAGENTA_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&e", Material.YELLOW_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&f", Material.WHITE_STAINED_GLASS_PANE);
    }

    private final BellMarket plugin;
    private final Map<UUID, PendingInput> awaiting = new HashMap<>();

    public PriceEditorGUI(BellMarket plugin) {
        this.plugin = plugin;
    }

    private record PendingInput(String skinKey, String tier, int page) {}
    private record Holder(String view, String tier, int page) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    // ─── Data classes ──────────────────────────────────────────────────────
    private record TierMeta(String displayName, String color, Material icon, long defaultPrice) {}
    private record SkinEntry(String key, String tier, long currentPrice, boolean isOverridden,
                             Material material, String displayName) {}

    // ─── Entry points ──────────────────────────────────────────────────────

    public void openTierList(Player player) {
        Map<String, TierMeta> tiers = scanTiers();
        if (tiers.isEmpty()) {
            player.sendMessage(colorize("&cNo SkinStudio tiers detected."));
            return;
        }
        Inventory inv = Bukkit.createInventory(new Holder("tiers", null, 0), SIZE_TIERS,
            colorize("&8❀ &5&lEdit Skin Prices &7- Tiers"));

        int slot = 10;
        for (Map.Entry<String, TierMeta> e : tiers.entrySet()) {
            if (slot >= SIZE_TIERS - 1) break;
            inv.setItem(slot, makeTierIcon(e.getKey(), e.getValue()));
            slot++;
            if (slot % 9 == 8) slot += 2;
        }
        fillBackground(inv);
        player.openInventory(inv);
    }

    public void openSkinList(Player player, String tier, int page) {
        List<SkinEntry> skins = scanSkinsOfTier(tier);
        if (skins.isEmpty()) {
            player.sendMessage(colorize("&cNo skins in tier: &f" + tier));
            return;
        }
        skins.sort(Comparator.comparing(SkinEntry::key));
        int totalPages = (skins.size() + SKINS_PER_PAGE - 1) / SKINS_PER_PAGE;
        page = Math.max(0, Math.min(page, totalPages - 1));

        TierMeta meta = scanTiers().getOrDefault(tier, new TierMeta(capitalize(tier), "&7", Material.LIGHT_GRAY_STAINED_GLASS_PANE, 500));

        Inventory inv = Bukkit.createInventory(new Holder("skins", tier, page), SIZE_SKINS,
            colorize(meta.color() + "❀ " + meta.displayName() + " &7— Page " + (page + 1) + "/" + totalPages));

        int start = page * SKINS_PER_PAGE;
        int end = Math.min(start + SKINS_PER_PAGE, skins.size());
        for (int i = start; i < end; i++) {
            inv.setItem(i - start, makeSkinIcon(skins.get(i), meta));
        }
        for (int i = 45; i < 54; i++) inv.setItem(i, makePane(Material.GRAY_STAINED_GLASS_PANE, " "));
        if (page > 0)             inv.setItem(SLOT_PREV, simpleItem(Material.ARROW, "&aPrevious page"));
        if (page < totalPages-1)  inv.setItem(SLOT_NEXT, simpleItem(Material.ARROW, "&aNext page"));
        inv.setItem(SLOT_BACK, simpleItem(Material.BARRIER, "&cBack to tiers"));
        inv.setItem(SLOT_INFO, simpleItem(Material.PAPER,
            meta.color() + meta.displayName(),
            "&7Total skins: &f" + skins.size(),
            "&7Tier default: &e" + meta.defaultPrice() + " BellCoins",
            "",
            "&eLeft-click: &fset custom price",
            "&eShift-click: &fclear override"));

        player.openInventory(inv);
    }

    // ─── Events ────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;

        switch (h.view()) {
            case "tiers" -> {
                String tier = extractTag(clicked, "tier:");
                if (tier != null) openSkinList(player, tier, 0);
            }
            case "skins" -> handleSkinClick(player, h, e.getSlot(), e.getClick(), clicked);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) { }

    private void handleSkinClick(Player player, Holder h, int slot, ClickType click, ItemStack clicked) {
        if (slot == SLOT_BACK) { openTierList(player); return; }
        if (slot == SLOT_PREV) { openSkinList(player, h.tier(), h.page() - 1); return; }
        if (slot == SLOT_NEXT) { openSkinList(player, h.tier(), h.page() + 1); return; }
        if (slot >= 45) return;

        String skinKey = extractTag(clicked, "skin:");
        if (skinKey == null) return;

        if (click.isShiftClick()) {
            removeOverride(skinKey);
            player.sendMessage(colorize("&aCleared override for &f" + skinKey));
            plugin.reload();
            openSkinList(player, h.tier(), h.page());
            return;
        }

        awaiting.put(player.getUniqueId(), new PendingInput(skinKey, h.tier(), h.page()));
        player.closeInventory();
        player.sendMessage(colorize("&8&m──────────────────────────"));
        player.sendMessage(colorize("&7Enter new price for &f" + skinKey + "&7 in chat."));
        player.sendMessage(colorize("&7Type &fcancel &7to abort, &freset &7to clear override."));
        player.sendMessage(colorize("&8&m──────────────────────────"));
    }

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
            player.sendMessage(colorize("&7Cancelled."));
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
        try { newPrice = Long.parseLong(raw); }
        catch (NumberFormatException ex) {
            player.sendMessage(colorize("&cInvalid number: &f" + raw));
            openSkinList(player, pending.tier(), pending.page());
            return;
        }
        if (newPrice < 0) {
            player.sendMessage(colorize("&cPrice must be zero or positive."));
            openSkinList(player, pending.tier(), pending.page());
            return;
        }
        setOverride(pending.skinKey(), newPrice);
        player.sendMessage(colorize("&aSet &f" + pending.skinKey() + " &ato &e" + newPrice + " BellCoins"));
        plugin.reload();
        openSkinList(player, pending.tier(), pending.page());
    }

    public boolean isAwaitingInput(Player player) {
        return awaiting.containsKey(player.getUniqueId());
    }

    // ─── Scanning — reads BOTH skinstudio.yml (admin overrides)
    //               AND SkinStudio config.yml (auto-detection) ─────────────

    /**
     * Scans all detected tiers. Priority:
     *   1. Overrides from plugins/BellMarket/providers/skinstudio.yml
     *   2. AUTO-DETECT from SkinStudio config.yml display-names (FIX)
     *   3. Generic fallback (gray glass, Capitalize(tier))
     */
    private Map<String, TierMeta> scanTiers() {
        Map<String, TierMeta> out = new LinkedHashMap<>();

        // Load SkinStudio skins for auto-detection
        ConfigurationSection skins = loadSkinStudioSkins();
        if (skins == null) return out;

        // Group by tier + collect first display-name per tier for auto-detection
        Map<String, String> firstDisplayByTier = new LinkedHashMap<>();
        Map<String, List<String>> skinsByTier   = new LinkedHashMap<>();
        for (String key : new TreeSet<>(skins.getKeys(false))) {
            String tier = tierOf(key);
            skinsByTier.computeIfAbsent(tier, k -> new ArrayList<>()).add(key);
            if (!firstDisplayByTier.containsKey(tier)) {
                firstDisplayByTier.put(tier, skins.getString(key + ".display-name", ""));
            }
        }

        // Load admin overrides from skinstudio.yml
        FileConfiguration prov = loadProviderConfig();
        long globalDefault = prov.getLong("default-price", 500);
        ConfigurationSection tiersCfg = prov.getConfigurationSection("tiers");

        for (String tier : skinsByTier.keySet()) {
            ConfigurationSection tcfg = tiersCfg != null ? tiersCfg.getConfigurationSection(tier) : null;

            // Auto-detect from SkinStudio display-name
            String firstDisplay = firstDisplayByTier.getOrDefault(tier, "");
            String autoColor   = detectTierColor(firstDisplay);
            String autoDisplay = detectTierDisplayName(firstDisplay, tier);
            Material autoIcon  = COLOR_TO_GLASS.getOrDefault(autoColor, Material.LIGHT_GRAY_STAINED_GLASS_PANE);

            // Admin can override anything via skinstudio.yml tiers section
            String finalColor   = (tcfg != null) ? tcfg.getString("color", autoColor)        : autoColor;
            String finalDisplay = (tcfg != null) ? tcfg.getString("display-name", autoDisplay): autoDisplay;
            Material finalIcon  = parseMaterial(
                (tcfg != null) ? tcfg.getString("icon") : null,
                COLOR_TO_GLASS.getOrDefault(finalColor, autoIcon));
            long finalDefault   = (tcfg != null) ? tcfg.getLong("default-price", globalDefault) : globalDefault;

            out.put(tier, new TierMeta(finalDisplay, finalColor, finalIcon, finalDefault));
        }
        return out;
    }

    private List<SkinEntry> scanSkinsOfTier(String tier) {
        List<SkinEntry> out = new ArrayList<>();
        ConfigurationSection skins = loadSkinStudioSkins();
        if (skins == null) return out;

        FileConfiguration prov  = loadProviderConfig();
        long globalDefault      = prov.getLong("default-price", 500);
        ConfigurationSection tiersCfg   = prov.getConfigurationSection("tiers");
        ConfigurationSection pricesCfg  = prov.getConfigurationSection("skin-prices");
        ConfigurationSection tcfg       = tiersCfg != null ? tiersCfg.getConfigurationSection(tier) : null;
        long tierDefault        = (tcfg != null) ? tcfg.getLong("default-price", globalDefault) : globalDefault;

        for (String key : skins.getKeys(false)) {
            if (!tier.equals(tierOf(key))) continue;
            boolean overridden = pricesCfg != null && pricesCfg.contains(key);
            long price         = overridden ? pricesCfg.getLong(key) : tierDefault;
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

    // ─── Writing skinstudio.yml ───────────────────────────────────────────

    private void setOverride(String skinKey, long price) {
        File f = new File(plugin.getDataFolder(), "providers/skinstudio.yml");
        if (!f.exists()) return;
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
            plugin.getLogger().warning("[PriceEditor] Could not save: " + e.getMessage());
        }
    }

    // ─── Icon builders ────────────────────────────────────────────────────

    private ItemStack makeTierIcon(String tier, TierMeta m) {
        return simpleItem(m.icon(),
            m.color() + "✦ " + m.displayName(),
            "&7Skins: &f" + countSkinsInTier(tier),
            "&7Default price: &e" + m.defaultPrice() + " BellCoins",
            "",
            "&eClick &7to edit skins",
            "&8tier:" + tier);
    }

    private ItemStack makeSkinIcon(SkinEntry s, TierMeta tierMeta) {
        List<String> lore = new ArrayList<>();
        lore.add(tierMeta.color() + tierMeta.displayName() + " &7tier");
        lore.add("&7Current price: &e" + s.currentPrice() + " BellCoins");
        lore.add(s.isOverridden() ? "&8(custom override)" : "&8(tier default)");
        lore.add("");
        lore.add("&eLeft-click: &fset price");
        if (s.isOverridden()) lore.add("&eShift-click: &fclear override");
        lore.add("&8skin:" + s.key());
        return simpleItem(s.material(), s.displayName(), lore.toArray(new String[0]));
    }

    private int countSkinsInTier(String tier) {
        ConfigurationSection skins = loadSkinStudioSkins();
        if (skins == null) return 0;
        int count = 0;
        for (String k : skins.getKeys(false)) if (tier.equals(tierOf(k))) count++;
        return count;
    }

    private ItemStack simpleItem(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(colorize(name).decoration(TextDecoration.ITALIC, false));
            if (lore.length > 0) {
                List<Component> comps = new ArrayList<>();
                for (String l : lore)
                    comps.add(colorize(l).decoration(TextDecoration.ITALIC, false));
                meta.lore(comps);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack makePane(Material mat, String name) { return simpleItem(mat, name); }

    private void fillBackground(Inventory inv) {
        ItemStack pane = makePane(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, pane);
        }
    }

    // ─── Detection helpers (mirrored from SkinStudioProvider) ────────────

    private static String tierOf(String skinKey) {
        int us = skinKey.indexOf('_');
        return us > 0 ? skinKey.substring(0, us).toLowerCase(Locale.ROOT) : "other";
    }

    private static String detectTierColor(String displayName) {
        if (displayName == null || displayName.isEmpty()) return "&7";
        int bracket = displayName.indexOf("&8[");
        if (bracket >= 0) {
            int amp = displayName.indexOf('&', bracket + 3);
            if (amp > 0 && amp + 1 < displayName.length()) {
                String code = displayName.substring(amp, amp + 2).toLowerCase(Locale.ROOT);
                if (COLOR_TO_GLASS.containsKey(code)) return code;
            }
        }
        for (int i = 0; i < displayName.length() - 1; i++) {
            if (displayName.charAt(i) == '&') {
                char c = Character.toLowerCase(displayName.charAt(i + 1));
                if (c != '8' && c != '7' && c != 'f' && c != 'r') {
                    String code = ("&" + c).toLowerCase(Locale.ROOT);
                    if (COLOR_TO_GLASS.containsKey(code)) return code;
                }
            }
        }
        return "&7";
    }

    private static String detectTierDisplayName(String displayName, String fallback) {
        if (displayName != null) {
            int open = displayName.indexOf('[');
            int close = displayName.indexOf(']');
            if (open >= 0 && close > open) {
                String clean = displayName.substring(open + 1, close)
                    .replaceAll("&[0-9a-fA-Fk-oK-OrR]", "").trim();
                if (!clean.isEmpty()) return clean;
            }
        }
        return capitalize(fallback);
    }

    private static Material parseMaterial(String name, Material fallback) {
        if (name == null || name.isEmpty()) return fallback;
        try { return Material.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return fallback; }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String extractTag(ItemStack item, String prefix) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta.lore() == null) return null;
        for (int i = meta.lore().size() - 1; i >= 0; i--) {
            String txt = PlainTextComponentSerializer.plainText().serialize(meta.lore().get(i));
            int idx = txt.indexOf(prefix);
            if (idx >= 0) return txt.substring(idx + prefix.length()).trim();
        }
        return null;
    }

    private static Component colorize(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s == null ? "" : s);
    }
}
