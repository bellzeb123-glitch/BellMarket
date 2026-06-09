/*
 * BellMarket - PriceEditorGUI (ANVIL INPUT)
 *
 * CHANGE: price input now uses an Anvil GUI instead of chat.
 *   - Click skin → anvil opens immediately with current price pre-filled
 *   - Player edits the number in the rename field (no T key needed)
 *   - Click the output paper → price saved, returns to tier view
 *   - Close anvil without confirming → price unchanged, returns to tier view
 *   - PrepareAnvilEvent sets repair cost to 0 (no XP required)
 */
package pl.bellmarket.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
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

    private static final int SIZE_TIERS      = 27;
    private static final int SIZE_SKINS      = 54;
    private static final int SKINS_PER_PAGE  = 45;
    private static final int SLOT_BACK       = 49;
    private static final int SLOT_PREV       = 45;
    private static final int SLOT_NEXT       = 53;
    private static final int SLOT_INFO       = 47;
    private static final int ANVIL_OUTPUT    = 2;   // output slot in anvil

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

    // ── Holders ────────────────────────────────────────────────
    private record Holder(String view, String tier, int page) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    /** Marks our anvil GUI so we can identify it in events. */
    private record AnvilHolder(String skinKey, String tier, int page) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    // ── Data ───────────────────────────────────────────────────
    private record TierMeta(String displayName, String color, Material icon, long defaultPrice) {}
    private record SkinEntry(String key, String tier, long currentPrice, boolean isOverridden,
                             Material material, String displayName, String itemModel) {}

    public PriceEditorGUI(BellMarket plugin) { this.plugin = plugin; }

    // ── Entry points ───────────────────────────────────────────

    public void openTierList(Player player) {
        Map<String, TierMeta> tiers = scanTiers();
        if (tiers.isEmpty()) {
            player.sendMessage(colorize("&cNo SkinStudio tiers detected."));
            return;
        }
        Inventory inv = Bukkit.createInventory(new Holder("tiers", null, 0), SIZE_TIERS,
            colorize("&8❀ &5&lEdit Skin Prices &7— Tiers"));
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
        if (skins.isEmpty()) { player.sendMessage(colorize("&cNo skins in tier: &f" + tier)); return; }
        skins.sort(Comparator.comparing(SkinEntry::key));
        int totalPages = (skins.size() + SKINS_PER_PAGE - 1) / SKINS_PER_PAGE;
        page = Math.max(0, Math.min(page, totalPages - 1));
        TierMeta meta = scanTiers().getOrDefault(tier,
            new TierMeta(capitalize(tier), "&7", Material.LIGHT_GRAY_STAINED_GLASS_PANE, 500));

        Inventory inv = Bukkit.createInventory(new Holder("skins", tier, page), SIZE_SKINS,
            colorize(meta.color() + "❀ " + meta.displayName() + " &7— " + (page+1) + "/" + totalPages));

        int start = page * SKINS_PER_PAGE;
        int end   = Math.min(start + SKINS_PER_PAGE, skins.size());
        for (int i = start; i < end; i++) inv.setItem(i - start, makeSkinIcon(skins.get(i), meta));

        for (int i = 45; i < 54; i++) inv.setItem(i, makePane(" "));
        if (page > 0)            inv.setItem(SLOT_PREV, simpleItem(Material.ARROW, "&aPrevious page"));
        if (page < totalPages-1) inv.setItem(SLOT_NEXT, simpleItem(Material.ARROW, "&aNext page"));
        inv.setItem(SLOT_BACK, simpleItem(Material.BARRIER, "&cBack to tiers"));
        inv.setItem(SLOT_INFO, simpleItem(Material.PAPER,
            meta.color() + meta.displayName(),
            "&7Total: &f" + skins.size() + " skins",
            "&7Tier default: &e" + meta.defaultPrice() + " BellCoins",
            "",
            "&eLeft-click: &fset price (opens input)",
            "&eShift-click: &freset to tier default"));
        player.openInventory(inv);
    }

    /**
     * Opens an Anvil GUI pre-filled with the current price.
     * The player edits the number and clicks the output to confirm.
     * PrepareAnvilEvent sets cost to 0 so no XP is needed.
     */
    public void openAnvilInput(Player player, SkinEntry skin, TierMeta tierMeta, int page) {
        Inventory anvil = Bukkit.createInventory(
            new AnvilHolder(skin.key(), skin.tier(), page),
            InventoryType.ANVIL,
            colorize("&7Set price — " + skin.displayName()));

        // Left slot: paper with current price as the name (player edits this)
        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        if (meta != null) {
            // Display name = current price as plain number (so the field shows it)
            meta.displayName(Component.text(String.valueOf(skin.currentPrice()))
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                colorize("&7Edit the number above, then"),
                colorize("&eclick the output to confirm."),
                colorize("&7Type &freset &7to clear override.")
            ));
            if (skin.itemModel() != null) {
                try {
                    NamespacedKey key = NamespacedKey.fromString(skin.itemModel());
                    if (key != null) meta.setItemModel(key);
                } catch (Throwable ignored) {}
            }
            paper.setItemMeta(meta);
        }
        anvil.setItem(0, paper);
        player.openInventory(anvil);
    }

    // ── Events ─────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        Inventory inv = e.getInventory();

        // Anvil output click → save price
        if (inv.getHolder() instanceof AnvilHolder holder) {
            e.setCancelled(true);
            if (e.getSlot() != ANVIL_OUTPUT) return;
            ItemStack output = inv.getItem(ANVIL_OUTPUT);
            if (output == null || output.getType().isAir()) return;

            String renameText = ((org.bukkit.inventory.AnvilInventory) inv).getRenameText();
            player.closeInventory();

            if (renameText == null || renameText.equalsIgnoreCase("reset")
                    || renameText.equalsIgnoreCase("remove")) {
                removeOverride(holder.skinKey());
                player.sendMessage(colorize("&aCleared override for &f" + holder.skinKey()));
                plugin.reload();
                Bukkit.getScheduler().runTaskLater(plugin, () ->
                    openSkinList(player, holder.tier(), holder.page()), 1L);
                return;
            }

            long newPrice;
            try { newPrice = Long.parseLong(renameText.trim()); }
            catch (NumberFormatException ex) {
                player.sendMessage(colorize("&cInvalid number: &f" + renameText));
                Bukkit.getScheduler().runTaskLater(plugin, () ->
                    openSkinList(player, holder.tier(), holder.page()), 1L);
                return;
            }
            if (newPrice < 0) {
                player.sendMessage(colorize("&cPrice must be zero or positive."));
                Bukkit.getScheduler().runTaskLater(plugin, () ->
                    openSkinList(player, holder.tier(), holder.page()), 1L);
                return;
            }
            setOverride(holder.skinKey(), newPrice);
            player.sendMessage(colorize("&aSet &f" + holder.skinKey()
                + " &ato &e" + newPrice + " BellCoins"));
            plugin.reload();
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                openSkinList(player, holder.tier(), holder.page()), 1L);
            return;
        }

        // Tier/Skin list clicks
        if (!(inv.getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
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

    /** Remove XP cost from our anvil. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent e) {
        if (!(e.getInventory().getHolder() instanceof AnvilHolder)) return;
        e.getInventory().setRepairCost(0);
        // Ensure the output item always exists (even when no actual "repair")
        ItemStack left = e.getInventory().getItem(0);
        if (left != null && e.getResult() == null) {
            e.setResult(left.clone());
        }
    }

    /** When player closes anvil without confirming → return to skin list. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof AnvilHolder holder)) return;
        if (!(e.getPlayer() instanceof Player player)) return;
        // Small delay so click event processes first
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.getOpenInventory().getTopInventory().getHolder() == null) {
                openSkinList(player, holder.tier(), holder.page());
            }
        }, 2L);
    }

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

        // Left click → open anvil input immediately
        TierMeta tierMeta = scanTiers().getOrDefault(h.tier(),
            new TierMeta(capitalize(h.tier()), "&7", Material.LIGHT_GRAY_STAINED_GLASS_PANE, 500));
        List<SkinEntry> skins = scanSkinsOfTier(h.tier());
        SkinEntry skin = skins.stream()
            .filter(s -> s.key().equals(skinKey)).findFirst().orElse(null);
        if (skin == null) return;
        openAnvilInput(player, skin, tierMeta, h.page());
    }

    // ── Scanning ───────────────────────────────────────────────

    private Map<String, TierMeta> scanTiers() {
        Map<String, TierMeta> out = new LinkedHashMap<>();
        ConfigurationSection skins = loadSkinStudioSkins();
        if (skins == null) return out;
        Map<String, String> firstDisplay = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String key : new TreeSet<>(skins.getKeys(false))) {
            String tier = tierOf(key);
            counts.merge(tier, 1, Integer::sum);
            firstDisplay.putIfAbsent(tier, skins.getString(key + ".display-name", ""));
        }
        FileConfiguration prov = loadProviderConfig();
        long globalDef = prov.getLong("default-price", 500);
        ConfigurationSection tiersCfg = prov.getConfigurationSection("tiers");
        for (String tier : counts.keySet()) {
            ConfigurationSection tcfg = tiersCfg != null ? tiersCfg.getConfigurationSection(tier) : null;
            String fd = firstDisplay.getOrDefault(tier, "");
            String autoColor   = detectTierColor(fd);
            String autoDisplay = detectTierDisplayName(fd, tier);
            Material autoIcon  = COLOR_TO_GLASS.getOrDefault(autoColor, Material.LIGHT_GRAY_STAINED_GLASS_PANE);
            String color   = (tcfg != null) ? tcfg.getString("color", autoColor) : autoColor;
            String display = (tcfg != null) ? tcfg.getString("display-name", autoDisplay) : autoDisplay;
            Material icon  = parseMaterial((tcfg != null) ? tcfg.getString("icon") : null,
                COLOR_TO_GLASS.getOrDefault(color, autoIcon));
            long def = (tcfg != null) ? tcfg.getLong("default-price", globalDef) : globalDef;
            out.put(tier, new TierMeta(display, color, icon, def));
        }
        return out;
    }

    private List<SkinEntry> scanSkinsOfTier(String tier) {
        List<SkinEntry> out = new ArrayList<>();
        ConfigurationSection skins = loadSkinStudioSkins();
        if (skins == null) return out;
        FileConfiguration prov = loadProviderConfig();
        long globalDef = prov.getLong("default-price", 500);
        ConfigurationSection tiersCfg  = prov.getConfigurationSection("tiers");
        ConfigurationSection pricesCfg = prov.getConfigurationSection("skin-prices");
        ConfigurationSection tcfg = tiersCfg != null ? tiersCfg.getConfigurationSection(tier) : null;
        long tierDef = (tcfg != null) ? tcfg.getLong("default-price", globalDef) : globalDef;
        for (String key : skins.getKeys(false)) {
            if (!tier.equals(tierOf(key))) continue;
            boolean overridden = pricesCfg != null && pricesCfg.contains(key);
            long price = overridden ? pricesCfg.getLong(key) : tierDef;
            ConfigurationSection sd = skins.getConfigurationSection(key);
            Material mat = Material.PAPER;
            String display = key;
            String itemModel = null;
            if (sd != null) {
                List<String> types = sd.getStringList("item-types");
                if (!types.isEmpty()) {
                    try { mat = Material.valueOf(types.get(0).toUpperCase(Locale.ROOT)); }
                    catch (IllegalArgumentException ignored) {}
                }
                display   = sd.getString("display-name", key);
                itemModel = sd.getString("item-model", null);
            }
            out.add(new SkinEntry(key, tier, price, overridden, mat, display, itemModel));
        }
        return out;
    }

    private ConfigurationSection loadSkinStudioSkins() {
        Plugin sk = plugin.getServer().getPluginManager().getPlugin("SkinStudio");
        if (sk == null) return null;
        File f = new File(sk.getDataFolder(), "config.yml");
        return f.exists() ? YamlConfiguration.loadConfiguration(f).getConfigurationSection("skins") : null;
    }

    private FileConfiguration loadProviderConfig() {
        File f = new File(plugin.getDataFolder(), "providers/skinstudio.yml");
        return f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
    }

    // ── Writing ────────────────────────────────────────────────

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
        ConfigurationSection p = cfg.getConfigurationSection("skin-prices");
        if (p != null) { p.set(skinKey, null); saveQuietly(cfg, f); }
    }

    private void saveQuietly(FileConfiguration cfg, File f) {
        try { cfg.save(f); }
        catch (IOException e) { plugin.getLogger().warning("[PriceEditor] Save failed: " + e.getMessage()); }
    }

    // ── Icon builders ──────────────────────────────────────────

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
        if (s.isOverridden()) lore.add("&eShift-click: &freset to tier default");
        lore.add("&8skin:" + s.key());

        ItemStack item = new ItemStack(s.material());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(colorize(s.displayName()).decoration(TextDecoration.ITALIC, false));
            List<Component> comp = new ArrayList<>();
            for (String l : lore) comp.add(colorize(l).decoration(TextDecoration.ITALIC, false));
            meta.lore(comp);
            if (s.itemModel() != null) {
                try {
                    NamespacedKey key = NamespacedKey.fromString(s.itemModel());
                    if (key != null) meta.setItemModel(key);
                } catch (Throwable ignored) {}
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private int countSkinsInTier(String tier) {
        ConfigurationSection skins = loadSkinStudioSkins();
        if (skins == null) return 0;
        int c = 0;
        for (String k : skins.getKeys(false)) if (tier.equals(tierOf(k))) c++;
        return c;
    }

    private ItemStack simpleItem(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(colorize(name).decoration(TextDecoration.ITALIC, false));
            if (lore.length > 0) {
                List<Component> comps = new ArrayList<>();
                for (String l : lore) comps.add(colorize(l).decoration(TextDecoration.ITALIC, false));
                meta.lore(comps);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack makePane(String name) {
        return simpleItem(Material.GRAY_STAINED_GLASS_PANE, name);
    }

    private void fillBackground(Inventory inv) {
        ItemStack pane = makePane(" ");
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, pane);
    }

    // ── Helpers ────────────────────────────────────────────────

    private static String tierOf(String k) {
        int us = k.indexOf('_'); return us > 0 ? k.substring(0, us).toLowerCase(Locale.ROOT) : "other";
    }

    private static String detectTierColor(String d) {
        if (d == null || d.isEmpty()) return "&7";
        int b = d.indexOf("&8[");
        if (b >= 0) {
            int a = d.indexOf('&', b + 3);
            if (a > 0 && a + 1 < d.length()) {
                String c = d.substring(a, a + 2).toLowerCase(Locale.ROOT);
                if (COLOR_TO_GLASS.containsKey(c)) return c;
            }
        }
        for (int i = 0; i < d.length() - 1; i++) {
            if (d.charAt(i) == '&') {
                char c = Character.toLowerCase(d.charAt(i + 1));
                if (c != '8' && c != '7' && c != 'f' && c != 'r') {
                    String cd = ("&" + c).toLowerCase(Locale.ROOT);
                    if (COLOR_TO_GLASS.containsKey(cd)) return cd;
                }
            }
        }
        return "&7";
    }

    private static String detectTierDisplayName(String d, String fallback) {
        if (d != null) {
            int o = d.indexOf('['), c = d.indexOf(']');
            if (o >= 0 && c > o) {
                String clean = d.substring(o + 1, c).replaceAll("&[0-9a-fA-Fk-oK-OrR]", "").trim();
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
