/*
 * BellMarket - ShopGUI
 *
 * CHANGES (this fix):
 *   1. Slot 7 in top bar = "featured category" shortcut (configurable via
 *      shop.featured-category-id in config.yml). VIP for you, any category
 *      for other servers. Non-VIP sees it but gets "VIP required" on click.
 *      OP passes automatically (has all perms in Bukkit).
 *   2. Slot 8 (BellCoins buy button) = UNTOUCHED.
 *   3. Category grid (slots 9-44) = all categories as before.
 *   4. openMainMenu shows ALL categories (VIP visible to everyone).
 */
package pl.bellmarket.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import pl.bellmarket.api.PurchaseProcessor;
import pl.bellmarket.model.Category;
import pl.bellmarket.model.Product;

import java.util.*;

public class ShopGUI implements Listener {

    // ── Holders ────────────────────────────────────────────────
    public static class ShopHolder implements InventoryHolder {
        private final String categoryId;
        private final int page;
        private Inventory inv;
        public ShopHolder(String categoryId, int page) { this.categoryId = categoryId; this.page = page; }
        @Override public Inventory getInventory() { return inv; }
        public void setInventory(Inventory inv)   { this.inv = inv; }
        public String getCategoryId()             { return categoryId; }
        public int getPage()                      { return page; }
        public boolean isMainMenu()               { return categoryId == null; }
    }

    public static class ConfirmHolder implements InventoryHolder {
        private final String categoryId;
        private final String productId;
        private final int page;
        private Inventory inv;
        public ConfirmHolder(String categoryId, String productId, int page) {
            this.categoryId = categoryId; this.productId = productId; this.page = page;
        }
        @Override public Inventory getInventory() { return inv; }
        public void setInventory(Inventory inv)   { this.inv = inv; }
        public String getCategoryId()             { return categoryId; }
        public String getProductId()              { return productId; }
        public int getPage()                      { return page; }
    }

    // ── Layout ─────────────────────────────────────────────────
    private static final int GUI_SIZE          = 54;
    private static final int PRODUCTS_START    = 18;
    private static final int PRODUCTS_END      = 44;
    private static final int PRODUCTS_PER_PAGE = PRODUCTS_END - PRODUCTS_START + 1;
    private static final int SLOT_BACK         = 0;
    private static final int SLOT_BALANCE      = 4;
    private static final int SLOT_BUY_CURRENCY = 8;   // BellCoins — untouched
    private static final int SLOT_PREV_PAGE    = 45;
    private static final int SLOT_PAGE_INFO    = 49;
    private static final int SLOT_NEXT_PAGE    = 53;

    private static final int MAIN_SIZE         = 54;
    private static final int MAIN_CAT_START    = 9;
    private static final int MAIN_CAT_END      = 44;
    private static final int MAIN_BALANCE      = 4;
    private static final int MAIN_BUY_CURRENCY = 8;   // BellCoins — untouched
    private static final int MAIN_FEATURED     = 7;   // ← NEW: featured category shortcut

    private final BellMarket plugin;
    private final PurchaseProcessor purchaseProcessor;

    public ShopGUI(BellMarket plugin) {
        this.plugin = plugin;
        this.purchaseProcessor = new PurchaseProcessor(plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ── Open methods ───────────────────────────────────────────

    public void openMainMenu(Player player) {
        // All categories visible — VIP shown to everyone, blocked on click for non-VIP
        List<Category> categories = plugin.getCategories().getCategories();

        ShopHolder holder = new ShopHolder(null, 0);
        String title = plugin.getConfig().getString("shop.title", "&8✦ &6BellMarket &8✦");
        Inventory inv = Bukkit.createInventory(holder, MAIN_SIZE, colorize(title));
        holder.setInventory(inv);
        fillBackground(inv, MAIN_SIZE);

        // ── Top bar ────────────────────────────────────────────
        inv.setItem(MAIN_BALANCE, buildBalanceButton(player));
        inv.setItem(MAIN_BUY_CURRENCY, buildBuyCurrencyButton());   // untouched

        // Slot 7: featured category shortcut (configured via shop.featured-category-id)
        buildFeaturedSlot(player, inv);

        // ── Category grid (slots 9–44) ─────────────────────────
        int slot = MAIN_CAT_START;
        for (Category cat : categories) {
            if (slot > MAIN_CAT_END) break;
            inv.setItem(slot, cat.buildIcon(false));
            slot++;
        }

        playSound(player, "shop-open", Sound.UI_BUTTON_CLICK);
        player.openInventory(inv);
    }

    /**
     * Builds slot 7 as a shortcut to the "featured category" configured via
     *   shop.featured-category-id: "09_vip"   (in config.yml)
     * If the key is missing/empty, slot 7 stays as gray filler.
     */
    private void buildFeaturedSlot(Player player, Inventory inv) {
        String featuredId = plugin.getConfig().getString("shop.featured-category-id", "");
        if (featuredId.isEmpty()) return;

        Category cat = plugin.getCategories().getCategory(featuredId);
        if (cat == null) return;

        boolean canEnter = plugin.getCategories().canSee(player, cat);

        ItemStack icon = new ItemStack(cat.getIconMaterial());
        ItemMeta meta  = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(colorize(cat.getName()));
            List<Component> lore = new ArrayList<>();
            if (canEnter) {
                lore.add(colorize("&eClick &7to open"));
            } else {
                lore.add(colorize("&5VIP Access Required"));
                lore.add(colorize("&7Subscribe to gain entry."));
            }
            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        inv.setItem(MAIN_FEATURED, icon);
    }

    public void openCategory(Player player, String categoryId, int page) {
        Category category = plugin.getCategories().getCategory(categoryId);
        if (category == null) {
            player.sendMessage(plugin.getLang().component("shop.purchase-failed"));
            return;
        }
        if (!plugin.getCategories().canSee(player, category)) {
            player.sendMessage(plugin.getLang().component("shop.vip-required"));
            return;
        }

        List<Product> products = category.getEnabledProducts();
        int totalPages = Math.max(1, (int) Math.ceil((double) products.size() / PRODUCTS_PER_PAGE));
        page = Math.max(0, Math.min(page, totalPages - 1));

        ShopHolder holder = new ShopHolder(categoryId, page);
        String rawTitle = plugin.getConfig().getString("shop.category-title", "&8✦ &6{category} &8✦");
        Inventory inv = Bukkit.createInventory(holder, GUI_SIZE,
            colorize(rawTitle.replace("{category}", category.getDisplayName())));
        holder.setInventory(inv);
        fillBackground(inv, GUI_SIZE);

        inv.setItem(SLOT_BACK, buildBackButton());
        inv.setItem(SLOT_BALANCE, buildBalanceButton(player));
        inv.setItem(SLOT_BUY_CURRENCY, buildBuyCurrencyButton());

        ItemStack catLabel = new ItemStack(category.getIconMaterial());
        ItemMeta catMeta = catLabel.getItemMeta();
        catMeta.displayName(colorize(category.getName()));
        catMeta.lore(List.of(colorize("&7Page &f" + (page + 1))));
        catLabel.setItemMeta(catMeta);
        inv.setItem(13, catLabel);

        int start = page * PRODUCTS_PER_PAGE;
        int end = Math.min(start + PRODUCTS_PER_PAGE, products.size());
        int slot = PRODUCTS_START;
        for (int i = start; i < end; i++) inv.setItem(slot++, products.get(i).buildIcon());

        if (page > 0)              inv.setItem(SLOT_PREV_PAGE, buildPrevButton());
        if (page < totalPages - 1) inv.setItem(SLOT_NEXT_PAGE, buildNextButton());
        inv.setItem(SLOT_PAGE_INFO, buildPageInfo(page + 1, totalPages));

        playSound(player, "navigate", Sound.UI_BUTTON_CLICK);
        player.openInventory(inv);
    }

    public void openConfirm(Player player, String categoryId, String productId, int page) {
        Category category = plugin.getCategories().getCategory(categoryId);
        if (category == null) return;
        Product product = category.getProducts().stream()
            .filter(p -> p.getId().equals(productId)).findFirst().orElse(null);
        if (product == null) return;

        ConfirmHolder holder = new ConfirmHolder(categoryId, productId, page);
        String title = plugin.getLang().getRaw("shop.confirm-title");
        Inventory inv = Bukkit.createInventory(holder, 27, colorize(title));
        holder.setInventory(inv);
        fillBackground(inv, 27);
        inv.setItem(13, product.buildIcon());
        inv.setItem(11, buildConfirmButton(product));
        inv.setItem(15, buildCancelButton());
        player.openInventory(inv);
    }

    // ── Events ─────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory inv = event.getInventory();

        if (inv.getHolder() instanceof ConfirmHolder holder) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot == 11) handleConfirmedPurchase(player, holder);
            else if (slot == 15) {
                playSound(player, "navigate", Sound.UI_BUTTON_CLICK);
                openCategory(player, holder.getCategoryId(), holder.getPage());
            }
            return;
        }

        if (!(inv.getHolder() instanceof ShopHolder holder)) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= inv.getSize()) return;
        ItemStack clicked = inv.getItem(slot);
        if (clicked == null || clicked.getType() == Material.AIR) return;

        if (holder.isMainMenu()) handleMainMenuClick(player, slot);
        else handleCategoryClick(player, holder, slot);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ShopHolder
         || event.getInventory().getHolder() instanceof ConfirmHolder)
            event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) { }

    // ── Click handlers ─────────────────────────────────────────

    private void handleMainMenuClick(Player player, int slot) {
        // Slot 7: featured category shortcut
        if (slot == MAIN_FEATURED) {
            String featuredId = plugin.getConfig().getString("shop.featured-category-id", "");
            if (featuredId.isEmpty()) return;
            Category cat = plugin.getCategories().getCategory(featuredId);
            if (cat == null) return;
            if (!plugin.getCategories().canSee(player, cat)) {
                player.sendMessage(plugin.getLang().component("shop.vip-required"));
                playSound(player, "purchase-fail", Sound.ENTITY_VILLAGER_NO);
                return;
            }
            playSound(player, "navigate", Sound.UI_BUTTON_CLICK);
            openCategory(player, cat.getId(), 0);
            return;
        }

        // Slot 8: buy BellCoins (untouched)
        if (slot == MAIN_BUY_CURRENCY) { sendPremiumUrl(player); return; }

        // Slots 9–44: category grid
        if (slot >= MAIN_CAT_START && slot <= MAIN_CAT_END) {
            List<Category> categories = plugin.getCategories().getCategories();
            int index = slot - MAIN_CAT_START;
            if (index < 0 || index >= categories.size()) return;
            Category cat = categories.get(index);
            if (!plugin.getCategories().canSee(player, cat)) {
                player.sendMessage(plugin.getLang().component("shop.vip-required"));
                playSound(player, "purchase-fail", Sound.ENTITY_VILLAGER_NO);
                return;
            }
            playSound(player, "navigate", Sound.UI_BUTTON_CLICK);
            openCategory(player, cat.getId(), 0);
        }
    }

    private void handleCategoryClick(Player player, ShopHolder holder, int slot) {
        String catId = holder.getCategoryId();
        int page     = holder.getPage();

        if (slot == SLOT_BACK)                          { playSound(player, "navigate", Sound.UI_BUTTON_CLICK); openMainMenu(player); return; }
        if (slot == SLOT_BUY_CURRENCY)                  { sendPremiumUrl(player); return; }
        if (slot == SLOT_PREV_PAGE && page > 0)         { openCategory(player, catId, page - 1); return; }
        if (slot == SLOT_NEXT_PAGE)                     { openCategory(player, catId, page + 1); return; }

        if (slot >= PRODUCTS_START && slot <= PRODUCTS_END) {
            Category category = plugin.getCategories().getCategory(catId);
            if (category == null) return;
            List<Product> products = category.getEnabledProducts();
            int index = (page * PRODUCTS_PER_PAGE) + (slot - PRODUCTS_START);
            if (index >= products.size()) return;
            openConfirm(player, catId, products.get(index).getId(), page);
        }
    }

    private void handleConfirmedPurchase(Player player, ConfirmHolder holder) {
        Category category = plugin.getCategories().getCategory(holder.getCategoryId());
        if (category == null) return;
        Product product = category.getProducts().stream()
            .filter(p -> p.getId().equals(holder.getProductId())).findFirst().orElse(null);
        if (product == null) return;

        PurchaseProcessor.Result result = purchaseProcessor.process(player, product);
        switch (result) {
            case SUCCESS -> {
                long bal = plugin.getCurrency().getBalance(player);
                player.sendMessage(plugin.getLang().component("shop.purchase-success",
                    "product", product.getName(),
                    "price", String.valueOf(product.getPrice()),
                    "balance", plugin.getLang().formatAmount(bal)));
                playSound(player, "purchase-success", Sound.ENTITY_PLAYER_LEVELUP);
                Bukkit.getScheduler().runTask(plugin, () ->
                    openCategory(player, holder.getCategoryId(), holder.getPage()));
            }
            case NOT_ENOUGH_COINS -> {
                long bal = plugin.getCurrency().getBalance(player);
                player.sendMessage(plugin.getLang().component("shop.not-enough-currency",
                    "price", String.valueOf(product.getPrice()),
                    "balance", plugin.getLang().formatAmount(bal)));
                playSound(player, "purchase-fail", Sound.ENTITY_VILLAGER_NO);
                Bukkit.getScheduler().runTask(plugin, () ->
                    openCategory(player, holder.getCategoryId(), holder.getPage()));
            }
            case PRODUCT_DISABLED -> { player.sendMessage(plugin.getLang().component("shop.out-of-stock")); playSound(player, "purchase-fail", Sound.ENTITY_VILLAGER_NO); }
            case DELIVERY_FAILED  -> { player.sendMessage(plugin.getLang().component("shop.purchase-failed")); playSound(player, "purchase-fail", Sound.ENTITY_VILLAGER_NO); }
            case NO_PERMISSION    -> { player.sendMessage(plugin.getLang().component("no-permission")); playSound(player, "purchase-fail", Sound.ENTITY_VILLAGER_NO); }
            case CANCELLED        -> { player.sendMessage(plugin.getLang().component("shop.purchase-failed")); playSound(player, "purchase-fail", Sound.ENTITY_VILLAGER_NO); }
        }
    }

    // ── Item builders ──────────────────────────────────────────

    private ItemStack buildBalanceButton(Player player) {
        long balance = plugin.getCurrency().getBalance(player);
        ItemStack item = new ItemStack(Material.SUNFLOWER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(plugin.getLang().getRaw("gui.your-balance")));
        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getLang().getList("gui.your-balance-lore",
                "amount", plugin.getLang().formatAmount(balance)))
            lore.add(colorize(line));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildBuyCurrencyButton() {
        ItemStack item = new ItemStack(Material.EMERALD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(plugin.getLang().getRaw("gui.currency-button",
            "currency", plugin.getLang().getCurrencyName())));
        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getLang().getList("gui.currency-button-lore",
                "currency", plugin.getLang().getCurrencyName()))
            lore.add(colorize(line));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildBackButton() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(plugin.getLang().getRaw("gui.back-button")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildPrevButton() {
        ItemStack item = new ItemStack(Material.SPECTRAL_ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(plugin.getLang().getRaw("gui.prev-page")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildNextButton() {
        ItemStack item = new ItemStack(Material.SPECTRAL_ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(plugin.getLang().getRaw("gui.next-page")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildPageInfo(int current, int total) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(plugin.getLang().getRaw("gui.page-info",
            "current", String.valueOf(current), "total", String.valueOf(total))));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildConfirmButton(Product product) {
        ItemStack item = new ItemStack(Material.LIME_WOOL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(plugin.getLang().getRaw("gui.confirm-yes")));
        meta.lore(List.of(colorize(plugin.getLang().getRaw("shop.confirm-info",
            "product", product.getName(),
            "price", plugin.getLang().formatAmount(product.getPrice())))));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildCancelButton() {
        ItemStack item = new ItemStack(Material.RED_WOOL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(plugin.getLang().getRaw("gui.cancel-button")));
        item.setItemMeta(meta);
        return item;
    }

    private void fillBackground(Inventory inv, int size) {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        meta.displayName(Component.empty());
        filler.setItemMeta(meta);
        for (int i = 0; i < size; i++) inv.setItem(i, filler);
    }

    private void sendPremiumUrl(Player player) {
        String url = plugin.getConfig().getString("shop.premium-url", "");
        String buttonText = plugin.getConfig().getString("shop.premium-button-text",
            "&eClick here to buy {currency}!")
            .replace("{currency}", plugin.getLang().getCurrencyName());
        player.sendMessage(plugin.getLang().colorize(plugin.getLang().getRaw("prefix") + buttonText));
        if (!url.isEmpty()) {
            player.sendMessage(plugin.getLang().colorize(
                plugin.getLang().getRaw("shop.buy-currency-url", "url", url)));
            player.sendMessage(Component.text(url)
                .clickEvent(ClickEvent.openUrl(url))
                .color(net.kyori.adventure.text.format.NamedTextColor.AQUA));
        }
        player.closeInventory();
    }

    private void playSound(Player player, String configKey, Sound defaultSound) {
        try {
            String soundName = plugin.getConfig().getString("sounds." + configKey);
            Sound sound = soundName != null ? Sound.valueOf(soundName) : defaultSound;
            player.playSound(player, sound, 1f, 1f);
        } catch (Exception ignored) {
            player.playSound(player, defaultSound, 1f, 1f);
        }
    }

    private Component colorize(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
