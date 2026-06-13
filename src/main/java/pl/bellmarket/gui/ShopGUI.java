package pl.bellmarket.gui;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.bellmarket.BellMarket;
import pl.bellmarket.api.PurchaseProcessor;
import pl.bellmarket.config.LangManager;
import pl.bellmarket.model.Category;
import pl.bellmarket.model.Product;

import java.util.ArrayList;
import java.util.List;

public class ShopGUI implements Listener {

    private final BellMarket plugin;
    private final PurchaseProcessor purchaseProcessor;

    public ShopGUI(BellMarket plugin) {
        this.plugin = plugin;
        this.purchaseProcessor = new PurchaseProcessor(plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public PurchaseProcessor getPurchaseProcessor() { return purchaseProcessor; }

    // ─── holders ─────────────────────────────────────────────────────────

    public static class ShopHolder implements InventoryHolder {
        private final boolean isMainMenu;
        private final String categoryId;
        private final int page;

        public ShopHolder(boolean isMainMenu, String categoryId, int page) {
            this.isMainMenu = isMainMenu;
            this.categoryId = categoryId;
            this.page = page;
        }

        public boolean isMainMenu() { return isMainMenu; }
        public String getCategoryId() { return categoryId; }
        public int getPage() { return page; }
        @Override public Inventory getInventory() { return null; }
    }

    public static class ConfirmHolder implements InventoryHolder {
        private final Product product;
        private final String categoryId;
        private final int page;

        public ConfirmHolder(Product product, String categoryId, int page) {
            this.product = product; this.categoryId = categoryId; this.page = page;
        }

        public Product getProduct() { return product; }
        public String getCategoryId() { return categoryId; }
        public int getPage() { return page; }
        @Override public Inventory getInventory() { return null; }
    }

    // ─── open ────────────────────────────────────────────────────────────

    public void openMainMenu(Player player) {
        LangManager lang = plugin.getLang();
        String title = plugin.getConfig().getString("shop.title", "&8✦ &6BellMarket &8✦");
        String featuredId = plugin.getConfig().getString("shop.featured-category-id", "");

        // Exclude featured from grid
        List<Category> gridCategories = plugin.getCategories().getCategories().stream()
                .filter(c -> featuredId.isEmpty() || !c.getId().equals(featuredId))
                .toList();

        Inventory inv = Bukkit.createInventory(new ShopHolder(true, null, 0),
                54, LangManager.colorize(title));

        fillBackground(inv);

        // Slot 4 — balance
        inv.setItem(4, buildBalanceButton(player));

        // Slot 7 — featured slot
        if (!featuredId.isEmpty()) {
            inv.setItem(7, buildFeaturedSlot(player, featuredId));
        }

        // Slot 8 — buy currency button
        inv.setItem(8, buildBuyCurrencyButton());

        // Grid: rows 1–4 (slots 10–43), skip borders
        int slot = 10;
        for (Category cat : gridCategories) {
            if (slot >= 44) break;
            inv.setItem(slot, cat.buildIcon(false));
            slot++;
            if ((slot % 9) == 8) slot += 2;
        }

        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
        player.openInventory(inv);
    }

    public void openCategory(Player player, String categoryId, int page) {
        LangManager lang = plugin.getLang();
        Category cat = plugin.getCategories().getCategory(categoryId).orElse(null);
        if (cat == null) {
            player.sendMessage(lang.component("shop.purchase-failed"));
            return;
        }

        if (!plugin.getCategories().canSee(player, cat)) {
            player.sendMessage(lang.component("shop.vip-required"));
            return;
        }

        List<Product> products = cat.getEnabledProducts();
        int totalPages = Math.max(1, (int) Math.ceil(products.size() / 28.0));
        page = Math.max(0, Math.min(page, totalPages - 1));

        String titleTemplate = plugin.getConfig().getString("shop.category-title", "&8✦ &6{category} &8✦");
        String title = LangManager.colorize(titleTemplate.replace("{category}", cat.getDisplayName()));

        Inventory inv = Bukkit.createInventory(new ShopHolder(false, categoryId, page),
                54, title);

        fillBackground(inv);

        // Back button (slot 45)
        inv.setItem(45, buildBackButton());

        // Products grid
        int start = page * 28;
        int end = Math.min(start + 28, products.size());
        int slot = 10;
        for (int i = start; i < end; i++) {
            inv.setItem(slot, products.get(i).buildIcon());
            slot++;
            if ((slot % 9) == 8) slot += 2;
        }

        // Pagination
        if (page > 0) inv.setItem(48, buildPrevButton());
        inv.setItem(49, buildPageInfo(page + 1, totalPages));
        if (page < totalPages - 1) inv.setItem(50, buildNextButton());

        player.openInventory(inv);
    }

    private void openConfirmDialog(Player player, Product product, String categoryId, int page) {
        LangManager lang = plugin.getLang();
        String title = lang.getRaw("shop.confirm-title");

        Inventory inv = Bukkit.createInventory(new ConfirmHolder(product, categoryId, page),
                27, LangManager.colorize(title));

        fillBackground(inv);
        inv.setItem(11, buildConfirmButton(product));
        inv.setItem(13, product.buildIcon()); // preview
        inv.setItem(15, buildCancelButton());

        player.openInventory(inv);
    }

    // ─── click handling ──────────────────────────────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory inv = event.getInventory();
        InventoryHolder holder = inv.getHolder();

        if (holder instanceof ConfirmHolder confirm) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot == 11) handleConfirmedPurchase(player, confirm);
            else if (slot == 15) openCategory(player, confirm.getCategoryId(), confirm.getPage());
            return;
        }

        if (!(holder instanceof ShopHolder shop)) return;
        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= inv.getSize()) return;

        ItemStack clicked = inv.getItem(slot);
        if (clicked == null || clicked.getType() == Material.AIR
                || clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) return;

        if (shop.isMainMenu()) {
            handleMainMenuClick(player, slot, clicked);
        } else {
            handleCategoryClick(player, shop, slot, clicked);
        }
    }

    private void handleMainMenuClick(Player player, int slot, ItemStack clicked) {
        // Find matching category
        for (Category cat : plugin.getCategories().getCategories()) {
            ItemStack icon = cat.buildIcon(false);
            if (icon.getType() == clicked.getType() && icon.getItemMeta() != null
                    && clicked.getItemMeta() != null
                    && icon.getItemMeta().displayName() != null
                    && icon.getItemMeta().displayName().equals(clicked.getItemMeta().displayName())) {

                if (!plugin.getCategories().canSee(player, cat)) {
                    player.sendMessage(plugin.getLang().component("shop.vip-required"));
                    return;
                }
                openCategory(player, cat.getId(), 0);
                return;
            }
        }
    }

    private void handleCategoryClick(Player player, ShopHolder shop, int slot, ItemStack clicked) {
        // Back button
        if (slot == 45) {
            openMainMenu(player);
            return;
        }
        // Prev/Next
        if (slot == 48) { navigate(player, shop, -1); return; }
        if (slot == 50) { navigate(player, shop, 1); return; }

        // Product click — find product by matching icon
        Category cat = plugin.getCategories().getCategory(shop.getCategoryId()).orElse(null);
        if (cat == null) return;

        for (Product p : cat.getEnabledProducts()) {
            ItemStack pIcon = p.buildIcon();
            if (pIcon.getItemMeta() != null && clicked.getItemMeta() != null
                    && pIcon.getItemMeta().displayName() != null
                    && pIcon.getItemMeta().displayName().equals(clicked.getItemMeta().displayName())) {
                openConfirmDialog(player, p, shop.getCategoryId(), shop.getPage());
                return;
            }
        }
    }

    private void handleConfirmedPurchase(Player player, ConfirmHolder confirm) {
        LangManager lang = plugin.getLang();
        Product product = confirm.getProduct();

        PurchaseProcessor.Result result = purchaseProcessor.process(player, product);

        switch (result) {
            case SUCCESS -> {
                player.sendMessage(lang.component("shop.purchase-success",
                        "product", LangManager.colorize(product.getName()),
                        "symbol", lang.getCurrencySymbol(),
                        "price", String.valueOf(product.getPrice()),
                        "currency", lang.getCurrencyName()));
                openCategory(player, confirm.getCategoryId(), confirm.getPage());
            }
            case NOT_ENOUGH_COINS -> {
                long balance = switch (product.getCurrency()) {
                    case BELLCOINS -> plugin.getCurrency().getBalance(player.getUniqueId());
                    case VIPTOKEN  -> plugin.getVipTokens().getBalance(player.getUniqueId());
                };
                player.sendMessage(lang.component("shop.not-enough-currency",
                        "symbol", lang.getCurrencySymbol(),
                        "price", String.valueOf(product.getPrice()),
                        "currency", lang.getCurrencyName(),
                        "balance", String.valueOf(balance)));
                openCategory(player, confirm.getCategoryId(), confirm.getPage());
            }
            case NO_PERMISSION -> {
                player.sendMessage(lang.component("shop.vip-required"));
                openCategory(player, confirm.getCategoryId(), confirm.getPage());
            }
            default -> {
                player.sendMessage(lang.component("shop.purchase-failed"));
                openCategory(player, confirm.getCategoryId(), confirm.getPage());
            }
        }
    }

    private void navigate(Player player, ShopHolder shop, int delta) {
        openCategory(player, shop.getCategoryId(), shop.getPage() + delta);
    }

    // ─── item builders ───────────────────────────────────────────────────

    private ItemStack buildBalanceButton(Player player) {
        LangManager lang = plugin.getLang();
        long balance = plugin.getCurrency().getBalance(player.getUniqueId());
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(lang.getRaw("gui.your-balance")));
        meta.lore(List.of(LegacyComponentSerializer.legacyAmpersand()
                .deserialize("&7" + lang.getCurrencySymbol() + balance + " " + lang.getCurrencyName())));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildBuyCurrencyButton() {
        LangManager lang = plugin.getLang();
        ItemStack item = new ItemStack(Material.EMERALD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(lang.getRaw("gui.currency-button",
                        "currency", lang.getCurrencyName())));
        List<String> lore = lang.getList("gui.currency-button-lore",
                "currency", lang.getCurrencyName());
        List<net.kyori.adventure.text.Component> loreComp = new ArrayList<>();
        for (String l : lore) loreComp.add(LegacyComponentSerializer.legacyAmpersand().deserialize(l));
        meta.lore(loreComp);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildFeaturedSlot(Player player, String featuredId) {
        Category featured = plugin.getCategories().getCategory(featuredId).orElse(null);
        if (featured == null) return new ItemStack(Material.AIR);
        return featured.buildIcon(false);
    }

    private ItemStack buildBackButton() {
        LangManager lang = plugin.getLang();
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(lang.getRaw("gui.back-button")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildPrevButton() {
        LangManager lang = plugin.getLang();
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(lang.getRaw("gui.prev-page")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildNextButton() {
        LangManager lang = plugin.getLang();
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(lang.getRaw("gui.next-page")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildPageInfo(int current, int total) {
        LangManager lang = plugin.getLang();
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(lang.getRaw("gui.page-info",
                        "current", String.valueOf(current),
                        "total", String.valueOf(total))));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildConfirmButton(Product product) {
        LangManager lang = plugin.getLang();
        ItemStack item = new ItemStack(Material.LIME_WOOL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(lang.getRaw("gui.confirm-yes")));
        meta.lore(List.of(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(lang.getRaw("shop.confirm-info",
                        "product", product.getName(),
                        "symbol", lang.getCurrencySymbol(),
                        "price", String.valueOf(product.getPrice())))));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildCancelButton() {
        LangManager lang = plugin.getLang();
        ItemStack item = new ItemStack(Material.RED_WOOL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(lang.getRaw("gui.cancel")));
        item.setItemMeta(meta);
        return item;
    }

    private void fillBackground(Inventory inv) {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(" "));
        filler.setItemMeta(meta);

        for (int i = 0; i < 9; i++)  inv.setItem(i, filler);
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);
        for (int row = 1; row < 5; row++) {
            inv.setItem(row * 9, filler);
            inv.setItem(row * 9 + 8, filler);
        }
    }
}
