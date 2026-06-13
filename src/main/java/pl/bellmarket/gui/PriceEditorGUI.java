package pl.bellmarket.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.bellmarket.BellMarket;

import java.io.File;
import java.util.*;

/**
 * In-game price editor for SkinStudio skins.
 *
 * Rewritten for i18n: all user-facing text comes from the language files
 * under the "price-editor" section, so /bm lang switches it too.
 *
 * Adds a "Back to Admin" button so the editor returns to /bm admin
 * instead of leaving the player in a dead-end GUI.
 */
public class PriceEditorGUI implements Listener {

    // ── Inner data records ────────────────────────────────────────────────
    public record Holder(String view, String tier, int page) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
    public record TierMeta(String displayName, String color, Material icon, long defaultPrice) {}
    public record SkinEntry(String key, String tier, long currentPrice, boolean isOverridden,
                            Material material, String displayName, String itemModel) {}
    public record PendingInput(String skinKey, String tier, int page) {}

    private static final int SIZE_TIERS = 54;
    private static final int SIZE_SKINS = 54;
    private static final int SLOT_BACK = 49;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_NEXT = 53;
    private static final int SLOT_INFO = 4;

    private final BellMarket plugin;
    private final Map<UUID, PendingInput> pendingInput = new HashMap<>();

    public PriceEditorGUI(BellMarket plugin) {
        this.plugin = plugin;
    }

    // ── Lang helpers ──────────────────────────────────────────────────────
    private String L(String key, String... args) {
        return plugin.getLang().getRaw("price-editor." + key, args);
    }
    private Component LC(String key, String... args) {
        return colorize(L(key, args));
    }

    // ── Tier list view ────────────────────────────────────────────────────
    public void openTierList(Player player) {
        Map<String, TierMeta> tiers = scanTiers();
        if (tiers.isEmpty()) {
            player.sendMessage(LC("no-tiers"));
            return;
        }

        Inventory inv = Bukkit.createInventory(
            new Holder("tiers", null, 0), SIZE_TIERS,
            plugin.buildTitle(L("title-tiers")));

        int slot = 9;
        for (Map.Entry<String, TierMeta> e : tiers.entrySet()) {
            if (slot >= SIZE_TIERS - 9) break;
            inv.setItem(slot++, makeTierIcon(e.getKey(), e.getValue()));
        }

        // Back to admin panel
        inv.setItem(SLOT_BACK, makeButton(Material.ARROW, L("back-to-admin")));
        player.openInventory(inv);
    }

    // ── Skin list view ────────────────────────────────────────────────────
    public void openSkinList(Player player, String tier, int page) {
        List<SkinEntry> skins = scanSkinsOfTier(tier);
        if (skins.isEmpty()) {
            player.sendMessage(LC("no-skins", "tier", tier));
            return;
        }

        TierMeta meta = scanTiers().get(tier);
        Inventory inv = Bukkit.createInventory(
            new Holder("skins", tier, page), SIZE_SKINS,
            plugin.buildTitle(L("title-skins", "tier", meta != null ? meta.displayName() : tier)));

        int perPage = 36;
        int start = page * perPage;
        int slot = 9;
        for (int i = start; i < Math.min(start + perPage, skins.size()); i++) {
            inv.setItem(slot++, makeSkinIcon(skins.get(i), meta));
        }

        // Navigation
        inv.setItem(SLOT_BACK, makeButton(Material.ARROW, L("back-to-tiers")));
        if (page > 0)
            inv.setItem(SLOT_PREV, makeButton(Material.SPECTRAL_ARROW, L("prev-page")));
        if (start + perPage < skins.size())
            inv.setItem(SLOT_NEXT, makeButton(Material.SPECTRAL_ARROW, L("next-page")));
        inv.setItem(SLOT_INFO, makeButton(Material.PAPER,
            L("skins-count", "count", String.valueOf(skins.size()))));

        player.openInventory(inv);
    }

    // ── Click handling ────────────────────────────────────────────────────
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;

        int slot = e.getSlot();
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        if (holder.view().equals("tiers")) {
            if (slot == SLOT_BACK) {
                // Back to the admin panel
                var bmCmd = plugin.getBellMarketCommand();
                if (bmCmd != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> bmCmd.getAdminGUI().openFor(player));
                }
                return;
            }
            // Click a tier → open its skins
            String tierKey = tierKeyFromIcon(clicked);
            if (tierKey != null) {
                openSkinList(player, tierKey, 0);
            }

        } else if (holder.view().equals("skins")) {
            if (slot == SLOT_BACK) { openTierList(player); return; }
            if (slot == SLOT_PREV) { openSkinList(player, holder.tier(), holder.page() - 1); return; }
            if (slot == SLOT_NEXT) { openSkinList(player, holder.tier(), holder.page() + 1); return; }
            handleSkinClick(player, holder, slot, e.getClick(), clicked);
        }
    }

    private void handleSkinClick(Player player, Holder holder, int slot, ClickType click, ItemStack item) {
        String skinKey = skinKeyFromIcon(item);
        if (skinKey == null) return;

        if (click.isShiftClick()) {
            // Shift-click resets to tier default
            saveSkinPrice(holder.tier(), skinKey, -1);
            player.sendMessage(LC("price-reset", "skin", skinKey));
            openSkinList(player, holder.tier(), holder.page());
            return;
        }

        // Left-click: prompt for new price via chat
        pendingInput.put(player.getUniqueId(),
            new PendingInput(skinKey, holder.tier(), holder.page()));
        player.closeInventory();
        player.sendMessage(LC("setting-price", "skin", skinKey));
        player.sendMessage(LC("type-price"));
    }

    /** Called by AdminChatListener when a player with pending input types a value. */
    public boolean isAwaitingInput(Player player) {
        return pendingInput.containsKey(player.getUniqueId());
    }

    public boolean handleChatInput(Player player, String message) {
        PendingInput input = pendingInput.remove(player.getUniqueId());
        if (input == null) return false;

        if (message.equalsIgnoreCase("cancel")) {
            player.sendMessage(LC("cancelled"));
            Bukkit.getScheduler().runTask(plugin,
                () -> openSkinList(player, input.tier(), input.page()));
            return true;
        }

        long price;
        try {
            price = Long.parseLong(message.trim());
            if (price < 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            player.sendMessage(LC("invalid-price"));
            Bukkit.getScheduler().runTask(plugin,
                () -> openSkinList(player, input.tier(), input.page()));
            return true;
        }

        saveSkinPrice(input.tier(), input.skinKey(), price);
        player.sendMessage(LC("price-set",
            "skin", input.skinKey(),
            "price", String.valueOf(price),
            "currency", plugin.getLang().getCurrencyName()));
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.reload();
            openSkinList(player, input.tier(), input.page());
        });
        return true;
    }

    // ── SkinStudio scanning ───────────────────────────────────────────────
    private Map<String, TierMeta> scanTiers() {
        Map<String, TierMeta> tiers = new LinkedHashMap<>();
        var ss = Bukkit.getServer().getPluginManager().getPlugin("SkinStudio");
        if (ss == null) return tiers;

        File cfgFile = new File(plugin.getDataFolder(), "providers/skinstudio.yml");
        if (!cfgFile.exists()) return tiers;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(cfgFile);

        long globalDefault = cfg.getLong("default-price", 500);
        ConfigurationSection skins = cfg.getConfigurationSection("skins");
        if (skins == null) {
            // Tiers might come from SkinStudio's own config; scan loadSkinStudioSkins
            for (SkinEntry s : loadSkinStudioSkins()) {
                tiers.computeIfAbsent(s.tier(), t -> new TierMeta(
                    detectTierDisplayName(t), detectTierColor(t),
                    Material.LIGHT_BLUE_STAINED_GLASS, globalDefault));
            }
            return tiers;
        }

        for (String key : skins.getKeys(false)) {
            String tier = tierOf(key);
            tiers.computeIfAbsent(tier, t -> new TierMeta(
                detectTierDisplayName(t), detectTierColor(t),
                Material.LIGHT_BLUE_STAINED_GLASS, globalDefault));
        }
        return tiers;
    }

    private List<SkinEntry> scanSkinsOfTier(String tier) {
        List<SkinEntry> out = new ArrayList<>();
        for (SkinEntry s : loadSkinStudioSkins()) {
            if (s.tier().equals(tier)) out.add(s);
        }
        return out;
    }

    private List<SkinEntry> loadSkinStudioSkins() {
        List<SkinEntry> out = new ArrayList<>();
        File cfgFile = new File(plugin.getDataFolder(), "providers/skinstudio.yml");
        if (!cfgFile.exists()) return out;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(cfgFile);
        long globalDefault = cfg.getLong("default-price", 500);
        ConfigurationSection prices = cfg.getConfigurationSection("skin-prices");

        ConfigurationSection skins = cfg.getConfigurationSection("skins");
        if (skins != null) {
            for (String key : skins.getKeys(false)) {
                long price = prices != null && prices.contains(key)
                    ? prices.getLong(key) : globalDefault;
                boolean overridden = prices != null && prices.contains(key);
                out.add(new SkinEntry(key, tierOf(key), price, overridden,
                    Material.PAPER, key, null));
            }
        }
        return out;
    }

    private void saveSkinPrice(String tier, String skinKey, long price) {
        File cfgFile = new File(plugin.getDataFolder(), "providers/skinstudio.yml");
        if (!cfgFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(cfgFile);
        if (price < 0) {
            cfg.set("skin-prices." + skinKey, null);  // reset to default
        } else {
            cfg.set("skin-prices." + skinKey, price);
        }
        try {
            cfg.save(cfgFile);
            plugin.getLogger().info("[PriceEditor] Saved price for " + skinKey + ": "
                + (price < 0 ? "default" : price));
        } catch (Exception e) {
            plugin.getLogger().warning("[PriceEditor] Save failed: " + e.getMessage());
        }
    }

    // ── Icon builders ─────────────────────────────────────────────────────
    private ItemStack makeTierIcon(String tier, TierMeta meta) {
        ItemStack item = new ItemStack(meta.icon());
        ItemMeta im = item.getItemMeta();
        if (im == null) return item;
        im.displayName(colorize(meta.color() + meta.displayName())
            .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getLang().getList("price-editor.tier-lore",
                "count", String.valueOf(countInTier(tier)),
                "price", String.valueOf(meta.defaultPrice()),
                "currency", plugin.getLang().getCurrencyName())) {
            lore.add(colorize(line).decoration(TextDecoration.ITALIC, false));
        }
        // Hidden marker for click detection
        lore.add(colorize("&8tier:" + tier).decoration(TextDecoration.ITALIC, false));
        im.lore(lore);
        item.setItemMeta(im);
        return item;
    }

    private ItemStack makeSkinIcon(SkinEntry skin, TierMeta meta) {
        ItemStack item = new ItemStack(skin.material());
        ItemMeta im = item.getItemMeta();
        if (im == null) return item;
        im.displayName(colorize("&f" + skin.displayName())
            .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(colorize(L("skin-price", "price", String.valueOf(skin.currentPrice()),
            "currency", plugin.getLang().getCurrencyName()))
            .decoration(TextDecoration.ITALIC, false));
        if (!skin.isOverridden()) {
            lore.add(colorize(L("tier-default")).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(colorize(L("click-set-price")).decoration(TextDecoration.ITALIC, false));
        lore.add(colorize(L("shift-reset")).decoration(TextDecoration.ITALIC, false));
        lore.add(colorize("&8skin:" + skin.key()).decoration(TextDecoration.ITALIC, false));
        im.lore(lore);
        item.setItemMeta(im);
        return item;
    }

    private ItemStack makeButton(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta im = item.getItemMeta();
        if (im == null) return item;
        im.displayName(colorize(name).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(im);
        return item;
    }

    // ── Click-marker extraction ───────────────────────────────────────────
    private String tierKeyFromIcon(ItemStack item) {
        return extractMarker(item, "tier:");
    }
    private String skinKeyFromIcon(ItemStack item) {
        return extractMarker(item, "skin:");
    }
    private String extractMarker(ItemStack item, String prefix) {
        if (item == null || !item.hasItemMeta() || item.getItemMeta().lore() == null) return null;
        for (Component line : item.getItemMeta().lore()) {
            String plain = LegacyComponentSerializer.legacyAmpersand().serialize(line);
            int idx = plain.indexOf(prefix);
            if (idx >= 0) {
                return plain.substring(idx + prefix.length()).trim();
            }
        }
        return null;
    }

    // ── Tier helpers ──────────────────────────────────────────────────────
    private String tierOf(String skinKey) {
        int idx = skinKey.indexOf('_');
        return idx > 0 ? skinKey.substring(0, idx) : "default";
    }
    private long countInTier(String tier) {
        return loadSkinStudioSkins().stream().filter(s -> s.tier().equals(tier)).count();
    }
    private String detectTierColor(String tier) {
        return switch (tier.toLowerCase(Locale.ROOT)) {
            case "common" -> "&7";  case "rare" -> "&9";  case "epic" -> "&5";
            case "legendary" -> "&6";  case "mythic" -> "&c";  default -> "&b";
        };
    }
    private String detectTierDisplayName(String tier) {
        return tier.isEmpty() ? tier
            : Character.toUpperCase(tier.charAt(0)) + tier.substring(1);
    }

    private Component colorize(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s == null ? "" : s);
    }
}
