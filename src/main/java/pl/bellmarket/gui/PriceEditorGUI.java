package pl.bellmarket.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.model.Category;
import pl.bellmarket.model.Product;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class PriceEditorGUI implements Listener {

    private static final int SIZE_TIERS = 54, SIZE_SKINS = 54, SKINS_PER_PAGE = 45;
    private static final int SLOT_BACK = 49, SLOT_PREV = 45, SLOT_NEXT = 53, SLOT_INFO = 47;

    private static final Map<String, Material> COLOR_TO_GLASS = new HashMap<>();
    static {
        COLOR_TO_GLASS.put("&0",Material.BLACK_STAINED_GLASS_PANE);  COLOR_TO_GLASS.put("&1",Material.BLUE_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&2",Material.GREEN_STAINED_GLASS_PANE);  COLOR_TO_GLASS.put("&3",Material.CYAN_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&4",Material.RED_STAINED_GLASS_PANE);    COLOR_TO_GLASS.put("&5",Material.PURPLE_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&6",Material.ORANGE_STAINED_GLASS_PANE); COLOR_TO_GLASS.put("&7",Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&8",Material.GRAY_STAINED_GLASS_PANE);   COLOR_TO_GLASS.put("&9",Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&a",Material.LIME_STAINED_GLASS_PANE);   COLOR_TO_GLASS.put("&b",Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&c",Material.RED_STAINED_GLASS_PANE);    COLOR_TO_GLASS.put("&d",Material.MAGENTA_STAINED_GLASS_PANE);
        COLOR_TO_GLASS.put("&e",Material.YELLOW_STAINED_GLASS_PANE); COLOR_TO_GLASS.put("&f",Material.WHITE_STAINED_GLASS_PANE);
    }

    private final BellMarket plugin;
    private final Map<UUID, PendingInput> awaiting = new HashMap<>();

    private record PendingInput(String key, String group, int page, boolean isCategory, String categoryId) {}
    private record Holder(String view, String tier, int page, String categoryId) implements InventoryHolder {
        Holder(String view, String tier, int page) { this(view, tier, page, null); }
        @Override public Inventory getInventory() { return null; }
    }
    private record TierMeta(String displayName, String color, Material icon, long defaultPrice) {}
    private record SkinEntry(String key, String tier, long currentPrice, boolean isOverridden,
                             Material material, String displayName, String itemModel) {}
    private record ProductEntry(String productId, String categoryId, String name, long price,
                                Material material, String itemModel, Currency currency) {}

    public PriceEditorGUI(BellMarket plugin) { this.plugin = plugin; }

    private String lang(String key, Object... args) { return plugin.getLang().getRaw(key, args); }

    private static final int TIERS_PER_PAGE = 14, CATS_PER_PAGE = 7;
    private static final int[] TIER_SLOTS = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25};
    private static final int[] CAT_SLOTS  = {37,38,39,40,41,42,43};

    // ─── Entry points ─────────────────────────────────────────────────────
    public void openTierList(Player player) { openTierList(player, 0); }

    public void openTierList(Player player, int page) {
        Map<String, TierMeta> tiers = scanTiers();
        List<Category> cats = plugin.getCategories().getCategories().stream()
            .filter(c -> !c.getId().startsWith("skinstudio_"))
            .toList();

        if (tiers.isEmpty() && cats.isEmpty()) {
            player.sendMessage(plugin.getLang().component("prices.no-tiers"));
            return;
        }

        int tierPages = tiers.isEmpty() ? 0 : (tiers.size() + TIERS_PER_PAGE - 1) / TIERS_PER_PAGE;
        int catPages  = cats.isEmpty()  ? 0 : (cats.size()  + CATS_PER_PAGE  - 1) / CATS_PER_PAGE;
        int totalPages = Math.max(1, Math.max(tierPages, catPages));
        page = Math.max(0, Math.min(page, totalPages - 1));

        Inventory inv = Bukkit.createInventory(new Holder("tiers", null, page), SIZE_TIERS,
            colorize(lang("prices.title-tiers")));

        // SkinStudio tiers — rows 1-2 (slots 10-16, 19-25)
        inv.setItem(4, simple(Material.DIAMOND, lang("prices.section-skinstudio")));
        if (!tiers.isEmpty()) {
            List<Map.Entry<String, TierMeta>> tierList = new ArrayList<>(tiers.entrySet());
            int tStart = page * TIERS_PER_PAGE;
            int tEnd = Math.min(tStart + TIERS_PER_PAGE, tierList.size());
            for (int i = tStart; i < tEnd; i++) {
                Map.Entry<String, TierMeta> e = tierList.get(i);
                inv.setItem(TIER_SLOTS[i - tStart], makeTierIcon(e.getKey(), e.getValue()));
            }
        }

        // Category tabs — row 4 (slots 37-43)
        inv.setItem(31, simple(Material.CHEST, lang("prices.section-categories")));
        if (!cats.isEmpty()) {
            int cStart = page * CATS_PER_PAGE;
            int cEnd = Math.min(cStart + CATS_PER_PAGE, cats.size());
            for (int i = cStart; i < cEnd; i++) {
                Category cat = cats.get(i);
                Currency catCurrency = detectCategoryCurrency(cat);
                inv.setItem(CAT_SLOTS[i - cStart], makeCategoryIcon(cat, catCurrency));
            }
        }

        fillBackground(inv);
        if (page > 0)              inv.setItem(45, simple(Material.ARROW, lang("prices.prev-page")));
        if (page < totalPages - 1) inv.setItem(53, simple(Material.ARROW, lang("prices.next-page")));
        inv.setItem(49, simple(Material.ARROW, lang("admin.prices-back-to-admin")));
        player.openInventory(inv);
    }

    public void openSkinList(Player player, String tier, int page) {
        List<SkinEntry> skins = scanSkinsOfTier(tier);
        if (skins.isEmpty()) { player.sendMessage(plugin.getLang().component("prices.no-skins", "tier", tier)); return; }
        skins.sort(Comparator.comparing(SkinEntry::key));
        int total = (skins.size()+SKINS_PER_PAGE-1)/SKINS_PER_PAGE;
        page = Math.max(0, Math.min(page, total-1));
        TierMeta meta = scanTiers().getOrDefault(tier, new TierMeta(capitalize(tier),"&7",Material.LIGHT_GRAY_STAINED_GLASS_PANE,500));

        Inventory inv = Bukkit.createInventory(new Holder("skins",tier,page), SIZE_SKINS,
            colorize(lang("prices.title-skins", "color", meta.color(), "tier", meta.displayName(),
                "current", String.valueOf(page+1), "total", String.valueOf(total))));
        int start = page*SKINS_PER_PAGE, end = Math.min(start+SKINS_PER_PAGE, skins.size());
        for (int i = start; i < end; i++) inv.setItem(i-start, makeSkinIcon(skins.get(i), meta));
        for (int i=45;i<54;i++) inv.setItem(i, makePane(" "));
        if (page>0)        inv.setItem(SLOT_PREV, simple(Material.ARROW, lang("prices.prev-page")));
        if (page<total-1)  inv.setItem(SLOT_NEXT, simple(Material.ARROW, lang("prices.next-page")));
        inv.setItem(SLOT_BACK, simple(Material.BARRIER, lang("prices.back-to-tiers")));
        inv.setItem(SLOT_INFO, simple(Material.PAPER, meta.color()+meta.displayName(),
            lang("prices.info-skins", "count", String.valueOf(skins.size())),
            lang("prices.info-default", "price", String.valueOf(meta.defaultPrice())),
            "",
            lang("prices.hint-set"),
            lang("prices.hint-reset")));
        player.openInventory(inv);
    }

    public void openProductList(Player player, String categoryId, int page) {
        Category cat = plugin.getCategories().getCategory(categoryId);
        if (cat == null) { player.sendMessage(plugin.getLang().component("prices.no-category", "category", categoryId)); return; }
        List<ProductEntry> products = scanCategoryProducts(cat);
        if (products.isEmpty()) { player.sendMessage(plugin.getLang().component("prices.no-products", "category", cat.getDisplayName())); return; }
        products.sort(Comparator.comparing(ProductEntry::productId));

        int total = (products.size()+SKINS_PER_PAGE-1)/SKINS_PER_PAGE;
        page = Math.max(0, Math.min(page, total-1));
        Currency catCurrency = detectCategoryCurrency(cat);
        String currencyLabel = catCurrency.getDisplayName();

        Inventory inv = Bukkit.createInventory(new Holder("products", null, page, categoryId), SIZE_SKINS,
            colorize(lang("prices.title-products", "category", cat.getDisplayName(),
                "current", String.valueOf(page+1), "total", String.valueOf(total))));
        int start = page*SKINS_PER_PAGE, end = Math.min(start+SKINS_PER_PAGE, products.size());
        for (int i = start; i < end; i++) inv.setItem(i-start, makeProductIcon(products.get(i)));
        for (int i=45;i<54;i++) inv.setItem(i, makePane(" "));
        if (page>0)        inv.setItem(SLOT_PREV, simple(Material.ARROW, lang("prices.prev-page")));
        if (page<total-1)  inv.setItem(SLOT_NEXT, simple(Material.ARROW, lang("prices.next-page")));
        inv.setItem(SLOT_BACK, simple(Material.BARRIER, lang("prices.back-to-tiers")));
        inv.setItem(SLOT_INFO, simple(Material.PAPER, cat.getDisplayName(),
            lang("prices.info-products", "count", String.valueOf(products.size())),
            lang("prices.info-currency", "currency", currencyLabel),
            "",
            lang("prices.hint-set")));
        player.openInventory(inv);
    }

    // ─── Events ───────────────────────────────────────────────────────────
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        switch (h.view()) {
            case "tiers" -> {
                if (e.getSlot() == 49) {
                    player.closeInventory();
                    plugin.getAdminGUI().openFor(player);
                    return;
                }
                if (e.getSlot() == 45 && h.page() > 0) { openTierList(player, h.page() - 1); return; }
                if (e.getSlot() == 53) { openTierList(player, h.page() + 1); return; }
                String t = extractTag(clicked,"tier:"); if(t!=null) { openSkinList(player,t,0); return; }
                String c = extractTag(clicked,"cat:"); if(c!=null) { openProductList(player,c,0); return; }
            }
            case "skins" -> handleSkinClick(player, h, e.getSlot(), e.getClick(), clicked);
            case "products" -> handleProductClick(player, h, e.getSlot(), e.getClick(), clicked);
        }
    }

    @EventHandler public void onClose(InventoryCloseEvent e) {}

    private void handleSkinClick(Player player, Holder h, int slot, ClickType click, ItemStack clicked) {
        if (slot==SLOT_BACK) { openTierList(player); return; }
        if (slot==SLOT_PREV) { openSkinList(player,h.tier(),h.page()-1); return; }
        if (slot==SLOT_NEXT) { openSkinList(player,h.tier(),h.page()+1); return; }
        if (slot>=45) return;
        String skinKey = extractTag(clicked, "skin:");
        if (skinKey == null) return;

        if (click.isShiftClick()) {
            removeOverride(skinKey);
            player.sendMessage(plugin.getLang().component("prices.override-cleared", "skin", skinKey));
            plugin.reload(); openSkinList(player, h.tier(), h.page()); return;
        }

        SkinEntry skin = scanSkinsOfTier(h.tier()).stream()
            .filter(s -> s.key().equals(skinKey)).findFirst().orElse(null);
        if (skin == null) return;

        awaiting.put(player.getUniqueId(), new PendingInput(skinKey, h.tier(), h.page(), false, null));
        player.closeInventory();
        sendPricePrompt(player, skin.displayName(), skin.currentPrice(), Currency.BELLCOINS);
    }

    private void handleProductClick(Player player, Holder h, int slot, ClickType click, ItemStack clicked) {
        if (slot==SLOT_BACK) { openTierList(player); return; }
        if (slot==SLOT_PREV) { openProductList(player,h.categoryId(),h.page()-1); return; }
        if (slot==SLOT_NEXT) { openProductList(player,h.categoryId(),h.page()+1); return; }
        if (slot>=45) return;
        String productId = extractTag(clicked, "product:");
        if (productId == null) return;

        Category cat = plugin.getCategories().getCategory(h.categoryId());
        if (cat == null) return;
        ProductEntry product = scanCategoryProducts(cat).stream()
            .filter(p -> p.productId().equals(productId)).findFirst().orElse(null);
        if (product == null) return;

        awaiting.put(player.getUniqueId(), new PendingInput(productId, null, h.page(), true, h.categoryId()));
        player.closeInventory();
        sendPricePrompt(player, product.name(), product.price(), product.currency());
    }

    private void sendPricePrompt(Player player, String displayName, long currentPrice, Currency currency) {
        String currencyName = currency.getDisplayName();
        player.sendMessage(colorize("&8&m────────────────────────"));
        player.sendMessage(plugin.getLang().component("prices.prompt-setting", "skin", displayName));
        player.sendMessage(plugin.getLang().component("prices.prompt-current-currency",
            "price", String.valueOf(currentPrice), "currency", currencyName));
        player.sendMessage(
            LegacyComponentSerializer.legacyAmpersand().deserialize("&7→ ")
                .append(Component.text(lang("prices.prompt-click"))
                    .color(NamedTextColor.YELLOW)
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false)
                    .clickEvent(ClickEvent.suggestCommand(String.valueOf(currentPrice))))
        );
        player.sendMessage(plugin.getLang().component("prices.prompt-hint"));
        player.sendMessage(colorize("&8&m────────────────────────"));
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

    private void processChatInput(Player player, PendingInput p, String raw) {
        if (raw.equalsIgnoreCase("cancel") || raw.equalsIgnoreCase("anuluj")) {
            player.sendMessage(plugin.getLang().component("prices.cancelled"));
            reopenList(player, p);
            return;
        }
        if (!p.isCategory() && (raw.equalsIgnoreCase("reset")||raw.equalsIgnoreCase("remove"))) {
            removeOverride(p.key());
            player.sendMessage(plugin.getLang().component("prices.override-cleared", "skin", p.key()));
            plugin.reload(); reopenList(player, p); return;
        }
        long price;
        try { price = Long.parseLong(raw); }
        catch (NumberFormatException ex) {
            player.sendMessage(plugin.getLang().component("prices.invalid-number", "input", raw));
            reopenList(player, p); return;
        }
        if (price < 0) { player.sendMessage(plugin.getLang().component("prices.must-positive")); reopenList(player, p); return; }

        if (p.isCategory()) {
            setCategoryProductPrice(p.categoryId(), p.key(), price);
            Currency cur = detectCurrencyForProduct(p.categoryId(), p.key());
            player.sendMessage(plugin.getLang().component("prices.price-set-currency",
                "skin", p.key(), "price", String.valueOf(price), "currency", cur.getDisplayName()));
        } else {
            setOverride(p.key(), price);
            player.sendMessage(plugin.getLang().component("prices.price-set", "skin", p.key(), "price", String.valueOf(price)));
        }
        plugin.reload(); reopenList(player, p);
    }

    private void reopenList(Player player, PendingInput p) {
        if (p.isCategory()) {
            openProductList(player, p.categoryId(), p.page());
        } else {
            openSkinList(player, p.group(), p.page());
        }
    }

    public boolean isAwaitingInput(Player player) { return awaiting.containsKey(player.getUniqueId()); }

    // ─── Scanning SkinStudio ──────────────────────────────────────────────
    private Map<String,TierMeta> scanTiers() {
        Map<String,TierMeta> out = new LinkedHashMap<>();
        ConfigurationSection skins = loadSkinStudioSkins(); if(skins==null) return out;
        Map<String,String> fd = new LinkedHashMap<>();
        Map<String,Integer> cnt = new LinkedHashMap<>();
        for (String k : new TreeSet<>(skins.getKeys(false))) {
            String t = tierOf(k); cnt.merge(t,1,Integer::sum); fd.putIfAbsent(t, skins.getString(k+".display-name",""));
        }
        FileConfiguration prov = loadProviderConfig(); long gd = prov.getLong("default-price",500);
        ConfigurationSection tc = prov.getConfigurationSection("tiers");
        for (String tier : cnt.keySet()) {
            ConfigurationSection tcfg = tc!=null?tc.getConfigurationSection(tier):null;
            String d=fd.getOrDefault(tier,""); String ac=detectTierColor(d); String ad=detectTierDisplayName(d,tier);
            Material ai=COLOR_TO_GLASS.getOrDefault(ac,Material.LIGHT_GRAY_STAINED_GLASS_PANE);
            String c=(tcfg!=null)?tcfg.getString("color",ac):ac; String dn=(tcfg!=null)?tcfg.getString("display-name",ad):ad;
            Material icon=parseMaterial((tcfg!=null)?tcfg.getString("icon"):null,COLOR_TO_GLASS.getOrDefault(c,ai));
            long def=(tcfg!=null)?tcfg.getLong("default-price",gd):gd;
            out.put(tier,new TierMeta(dn,c,icon,def));
        }
        return out;
    }

    private List<SkinEntry> scanSkinsOfTier(String tier) {
        List<SkinEntry> out = new ArrayList<>();
        ConfigurationSection skins = loadSkinStudioSkins(); if(skins==null) return out;
        FileConfiguration prov = loadProviderConfig();
        long gd=prov.getLong("default-price",500);
        ConfigurationSection tc=prov.getConfigurationSection("tiers"), pc=prov.getConfigurationSection("skin-prices");
        ConfigurationSection tcfg=tc!=null?tc.getConfigurationSection(tier):null;
        long td=(tcfg!=null)?tcfg.getLong("default-price",gd):gd;
        for (String k : skins.getKeys(false)) {
            if (!tier.equals(tierOf(k))) continue;
            boolean ov=pc!=null&&pc.contains(k); long p=ov?pc.getLong(k):td;
            ConfigurationSection sd=skins.getConfigurationSection(k);
            Material mat=Material.PAPER; String disp=k; String im=null;
            if (sd!=null) {
                List<String> types=sd.getStringList("item-types");
                if (!types.isEmpty()) { try{mat=Material.valueOf(types.get(0).toUpperCase());}catch(Exception ignore){} }
                disp=sd.getString("display-name",k); im=sd.getString("item-model",null);
            }
            out.add(new SkinEntry(k,tier,p,ov,mat,disp,im));
        }
        return out;
    }

    // ─── Scanning categories ──────────────────────────────────────────────
    private List<ProductEntry> scanCategoryProducts(Category cat) {
        List<ProductEntry> out = new ArrayList<>();
        for (Product p : cat.getProducts()) {
            out.add(new ProductEntry(
                p.getId(), cat.getId(), p.getName(), p.getPrice(),
                p.getIconMaterial() != null ? p.getIconMaterial() : Material.PAPER,
                p.getIconItemModel(), p.getCurrency()
            ));
        }
        return out;
    }

    private Currency detectCategoryCurrency(Category cat) {
        for (Product p : cat.getProducts()) {
            if (p.getCurrency() != null && p.getCurrency() != Currency.BELLCOINS) {
                return p.getCurrency();
            }
        }
        return Currency.BELLCOINS;
    }

    private Currency detectCurrencyForProduct(String categoryId, String productId) {
        Category cat = plugin.getCategories().getCategory(categoryId);
        if (cat == null) return Currency.BELLCOINS;
        for (Product p : cat.getProducts()) {
            if (p.getId().equals(productId)) return p.getCurrency();
        }
        return Currency.BELLCOINS;
    }

    // ─── File loaders ─────────────────────────────────────────────────────
    private ConfigurationSection loadSkinStudioSkins() {
        Plugin sk=plugin.getServer().getPluginManager().getPlugin("SkinStudio"); if(sk==null) return null;
        File f=new File(sk.getDataFolder(),"config.yml"); return f.exists()?YamlConfiguration.loadConfiguration(f).getConfigurationSection("skins"):null;
    }
    private FileConfiguration loadProviderConfig() {
        File f=new File(plugin.getDataFolder(),"providers/skinstudio.yml");
        return f.exists()?YamlConfiguration.loadConfiguration(f):new YamlConfiguration();
    }

    // ─── Writing SkinStudio overrides ─────────────────────────────────────
    private void setOverride(String key, long price) {
        File f=new File(plugin.getDataFolder(),"providers/skinstudio.yml"); if(!f.exists()) return;
        FileConfiguration cfg=YamlConfiguration.loadConfiguration(f);
        ConfigurationSection p=cfg.getConfigurationSection("skin-prices"); if(p==null) p=cfg.createSection("skin-prices");
        p.set(key,price); saveQ(cfg,f);
    }
    private void removeOverride(String key) {
        File f=new File(plugin.getDataFolder(),"providers/skinstudio.yml"); if(!f.exists()) return;
        FileConfiguration cfg=YamlConfiguration.loadConfiguration(f);
        ConfigurationSection p=cfg.getConfigurationSection("skin-prices"); if(p!=null){p.set(key,null);saveQ(cfg,f);}
    }

    // ─── Writing category product prices ──────────────────────────────────
    private void setCategoryProductPrice(String categoryId, String productId, long price) {
        File f = new File(plugin.getDataFolder(), "categories/" + categoryId + ".yml");
        if (!f.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        cfg.set("products." + productId + ".price", price);
        saveQ(cfg, f);
    }

    private void saveQ(FileConfiguration cfg, File f) {
        try{cfg.save(f);}catch(IOException e){plugin.getLogger().warning("[PriceEditor] Save: "+e.getMessage());}
    }

    // ─── Icon builders ────────────────────────────────────────────────────
    private ItemStack makeTierIcon(String tier, TierMeta m) {
        return simple(m.icon(), m.color()+"✦ "+m.displayName(),
            lang("prices.tier-skins", "count", String.valueOf(countInTier(tier))),
            lang("prices.tier-default", "price", String.valueOf(m.defaultPrice())),
            "",
            lang("prices.tier-click"),
            "&8tier:"+tier);
    }

    private ItemStack makeCategoryIcon(Category cat, Currency currency) {
        int productCount = cat.getProducts().size();
        return simple(cat.getIconMaterial(), cat.getDisplayName(),
            lang("prices.cat-products", "count", String.valueOf(productCount)),
            lang("prices.cat-currency", "currency", currency.getDisplayName()),
            "",
            lang("prices.cat-click"),
            "&8cat:"+cat.getId());
    }

    private ItemStack makeSkinIcon(SkinEntry s, TierMeta tm) {
        List<String> lore = new ArrayList<>(List.of(
            tm.color()+tm.displayName()+" " + lang("prices.skin-tier-label"),
            lang("prices.skin-price", "price", String.valueOf(s.currentPrice())),
            s.isOverridden() ? lang("prices.skin-override") : lang("prices.skin-default"),
            "",
            lang("prices.hint-set"),
            s.isOverridden() ? lang("prices.hint-reset") : "",
            "&8skin:"+s.key()));
        ItemStack item = new ItemStack(s.material());
        ItemMeta meta = item.getItemMeta();
        if (meta!=null) {
            meta.displayName(colorize(s.displayName()).decoration(TextDecoration.ITALIC,false));
            meta.lore(lore.stream().map(l->colorize(l).decoration(TextDecoration.ITALIC,false)).toList());
            if (s.itemModel()!=null) { try{NamespacedKey k=NamespacedKey.fromString(s.itemModel());if(k!=null)meta.setItemModel(k);}catch(Throwable ignore){} }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack makeProductIcon(ProductEntry p) {
        String currencyName = p.currency().getDisplayName();
        List<String> lore = new ArrayList<>(List.of(
            lang("prices.product-price", "price", String.valueOf(p.price()), "currency", currencyName),
            "",
            lang("prices.hint-set"),
            "&8product:"+p.productId()));
        ItemStack item = new ItemStack(p.material());
        ItemMeta meta = item.getItemMeta();
        if (meta!=null) {
            meta.displayName(colorize(p.name()).decoration(TextDecoration.ITALIC,false));
            meta.lore(lore.stream().map(l->colorize(l).decoration(TextDecoration.ITALIC,false)).toList());
            if (p.itemModel()!=null) { try{NamespacedKey k=NamespacedKey.fromString(p.itemModel());if(k!=null)meta.setItemModel(k);}catch(Throwable ignore){} }
            item.setItemMeta(meta);
        }
        return item;
    }

    private int countInTier(String tier) {
        ConfigurationSection s=loadSkinStudioSkins(); if(s==null) return 0;
        int c=0; for(String k:s.getKeys(false)) if(tier.equals(tierOf(k)))c++; return c;
    }
    private ItemStack simple(Material mat, String name, String... lore) {
        ItemStack it=new ItemStack(mat); ItemMeta meta=it.getItemMeta();
        if(meta!=null){meta.displayName(colorize(name).decoration(TextDecoration.ITALIC,false));
        if(lore.length>0) meta.lore(Arrays.stream(lore).map(l->colorize(l).decoration(TextDecoration.ITALIC,false)).toList());
        it.setItemMeta(meta);} return it;
    }
    private ItemStack makePane(String n){return simple(Material.GRAY_STAINED_GLASS_PANE,n);}
    private void fillBackground(Inventory inv){ItemStack p=makePane(" ");for(int i=0;i<inv.getSize();i++)if(inv.getItem(i)==null)inv.setItem(i,p);}

    // ─── Helpers ──────────────────────────────────────────────────────────
    private static String tierOf(String k){int u=k.indexOf('_');return u>0?k.substring(0,u).toLowerCase():k;}
    private static String detectTierColor(String d) {
        if(d==null||d.isEmpty()) return "&7";
        int b=d.indexOf("&8["); if(b>=0){int a=d.indexOf('&',b+3);if(a>0&&a+1<d.length()){String c=d.substring(a,a+2).toLowerCase();if(COLOR_TO_GLASS.containsKey(c))return c;}}
        for(int i=0;i<d.length()-1;i++){if(d.charAt(i)=='&'){char c=Character.toLowerCase(d.charAt(i+1));if(c!='8'&&c!='7'&&c!='f'&&c!='r'){String cd="&"+c;if(COLOR_TO_GLASS.containsKey(cd))return cd;}}}
        return "&7";
    }
    private static String detectTierDisplayName(String d, String fb) {
        if(d!=null){int o=d.indexOf('['),c=d.indexOf(']');if(o>=0&&c>o){String cl=d.substring(o+1,c).replaceAll("&[0-9a-fA-Fk-oK-OrR]","").trim();if(!cl.isEmpty())return cl;}}
        return capitalize(fb);
    }
    private static Material parseMaterial(String n,Material fb){if(n==null||n.isEmpty())return fb;try{return Material.valueOf(n.toUpperCase());}catch(Exception e){return fb;}}
    private static String capitalize(String s){if(s==null||s.isEmpty())return s;return Character.toUpperCase(s.charAt(0))+s.substring(1);}
    private static String extractTag(ItemStack item,String prefix){
        if(item==null||!item.hasItemMeta())return null; ItemMeta meta=item.getItemMeta(); if(meta.lore()==null)return null;
        for(int i=meta.lore().size()-1;i>=0;i--){String t=PlainTextComponentSerializer.plainText().serialize(meta.lore().get(i));int idx=t.indexOf(prefix);if(idx>=0)return t.substring(idx+prefix.length()).trim();}
        return null;
    }
    private static Component colorize(String s){return LegacyComponentSerializer.legacyAmpersand().deserialize(s==null?"":s);}
}
