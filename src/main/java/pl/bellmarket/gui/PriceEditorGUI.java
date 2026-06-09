/*
 * BellMarket - PriceEditorGUI (FIXES4)
 *
 * Price input: replaced Anvil GUI with chat + suggestCommand.
 * - No materials needed
 * - No XP needed
 * - Clicking a skin sends a clickable message: "[→ Click to edit: 500]"
 * - Clicking that link opens chat with "500" pre-filled
 * - Player changes to new price, Enter → saved
 */
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

import java.io.File;
import java.io.IOException;
import java.util.*;

public class PriceEditorGUI implements Listener {

    private static final int SIZE_TIERS = 27, SIZE_SKINS = 54, SKINS_PER_PAGE = 45;
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

    private record PendingInput(String skinKey, String tier, int page) {}
    private record Holder(String view, String tier, int page) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
    private record TierMeta(String displayName, String color, Material icon, long defaultPrice) {}
    private record SkinEntry(String key, String tier, long currentPrice, boolean isOverridden,
                             Material material, String displayName, String itemModel) {}

    public PriceEditorGUI(BellMarket plugin) { this.plugin = plugin; }

    // ─── Entry points ─────────────────────────────────────────────────────
    public void openTierList(Player player) {
        Map<String, TierMeta> tiers = scanTiers();
        if (tiers.isEmpty()) { player.sendMessage(colorize("&cNo SkinStudio tiers detected.")); return; }
        Inventory inv = Bukkit.createInventory(new Holder("tiers",null,0), SIZE_TIERS,
            colorize("&8❀ &5&lEdit Skin Prices &7— Tiers"));
        int slot = 10;
        for (Map.Entry<String, TierMeta> e : tiers.entrySet()) {
            if (slot >= SIZE_TIERS-1) break;
            inv.setItem(slot, makeTierIcon(e.getKey(), e.getValue()));
            slot++; if (slot % 9 == 8) slot += 2;
        }
        fillBackground(inv); player.openInventory(inv);
    }

    public void openSkinList(Player player, String tier, int page) {
        List<SkinEntry> skins = scanSkinsOfTier(tier);
        if (skins.isEmpty()) { player.sendMessage(colorize("&cNo skins in tier: &f"+tier)); return; }
        skins.sort(Comparator.comparing(SkinEntry::key));
        int total = (skins.size()+SKINS_PER_PAGE-1)/SKINS_PER_PAGE;
        page = Math.max(0, Math.min(page, total-1));
        TierMeta meta = scanTiers().getOrDefault(tier, new TierMeta(capitalize(tier),"&7",Material.LIGHT_GRAY_STAINED_GLASS_PANE,500));

        Inventory inv = Bukkit.createInventory(new Holder("skins",tier,page), SIZE_SKINS,
            colorize(meta.color()+"❀ "+meta.displayName()+" &7— "+(page+1)+"/"+total));
        int start = page*SKINS_PER_PAGE, end = Math.min(start+SKINS_PER_PAGE, skins.size());
        for (int i = start; i < end; i++) inv.setItem(i-start, makeSkinIcon(skins.get(i), meta));
        for (int i=45;i<54;i++) inv.setItem(i, makePane(" "));
        if (page>0)        inv.setItem(SLOT_PREV, simple(Material.ARROW,"&aPrevious page"));
        if (page<total-1)  inv.setItem(SLOT_NEXT, simple(Material.ARROW,"&aNext page"));
        inv.setItem(SLOT_BACK, simple(Material.BARRIER,"&cBack to tiers"));
        inv.setItem(SLOT_INFO, simple(Material.PAPER, meta.color()+meta.displayName(),
            "&7Skins: &f"+skins.size(), "&7Default: &e"+meta.defaultPrice()+" BC",
            "", "&eLeft-click: &fset price", "&eShift-click: &freset"));
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
            case "tiers" -> { String t = extractTag(clicked,"tier:"); if(t!=null) openSkinList(player,t,0); }
            case "skins" -> handleSkinClick(player, h, e.getSlot(), e.getClick(), clicked);
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
            player.sendMessage(colorize("&aCleared override for &f"+skinKey));
            plugin.reload(); openSkinList(player, h.tier(), h.page()); return;
        }

        // Left click: find the skin entry and send suggestCommand prompt
        SkinEntry skin = scanSkinsOfTier(h.tier()).stream()
            .filter(s -> s.key().equals(skinKey)).findFirst().orElse(null);
        if (skin == null) return;

        awaiting.put(player.getUniqueId(), new PendingInput(skinKey, h.tier(), h.page()));
        player.closeInventory();
        sendPricePrompt(player, skin);
    }

    /**
     * Sends a clickable chat prompt. Clicking the link pre-fills chat with
     * the current price — player edits and hits Enter.
     * No materials, no XP, one click.
     */
    private void sendPricePrompt(Player player, SkinEntry skin) {
        player.sendMessage(colorize("&8&m────────────────────────"));
        player.sendMessage(colorize("&7Setting price for &f" + skin.displayName()));
        player.sendMessage(colorize("&7Current: &e" + skin.currentPrice() + " BellCoins"));
        player.sendMessage(
            LegacyComponentSerializer.legacyAmpersand().deserialize("&7→ ")
                .append(Component.text("[ Click here to type new price ]")
                    .color(NamedTextColor.YELLOW)
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false)
                    // Opens chat with current price pre-filled for easy editing
                    .clickEvent(ClickEvent.suggestCommand(String.valueOf(skin.currentPrice()))))
        );
        player.sendMessage(colorize("&8Or type &7reset &8to clear override. &7cancel &8to abort."));
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
        if (raw.equalsIgnoreCase("cancel")) {
            player.sendMessage(colorize("&7Cancelled.")); openSkinList(player,p.tier(),p.page()); return;
        }
        if (raw.equalsIgnoreCase("reset")||raw.equalsIgnoreCase("remove")) {
            removeOverride(p.skinKey());
            player.sendMessage(colorize("&aCleared override for &f"+p.skinKey()));
            plugin.reload(); openSkinList(player,p.tier(),p.page()); return;
        }
        long price;
        try { price = Long.parseLong(raw); }
        catch (NumberFormatException ex) {
            player.sendMessage(colorize("&cInvalid number: &f"+raw));
            openSkinList(player,p.tier(),p.page()); return;
        }
        if (price < 0) { player.sendMessage(colorize("&cMust be ≥ 0.")); openSkinList(player,p.tier(),p.page()); return; }
        setOverride(p.skinKey(), price);
        player.sendMessage(colorize("&aSet &f"+p.skinKey()+" &ato &e"+price+" BellCoins"));
        plugin.reload(); openSkinList(player,p.tier(),p.page());
    }

    public boolean isAwaitingInput(Player player) { return awaiting.containsKey(player.getUniqueId()); }

    // ─── Scanning ─────────────────────────────────────────────────────────
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

    private ConfigurationSection loadSkinStudioSkins() {
        Plugin sk=plugin.getServer().getPluginManager().getPlugin("SkinStudio"); if(sk==null) return null;
        File f=new File(sk.getDataFolder(),"config.yml"); return f.exists()?YamlConfiguration.loadConfiguration(f).getConfigurationSection("skins"):null;
    }
    private FileConfiguration loadProviderConfig() {
        File f=new File(plugin.getDataFolder(),"providers/skinstudio.yml");
        return f.exists()?YamlConfiguration.loadConfiguration(f):new YamlConfiguration();
    }

    // ─── Writing ──────────────────────────────────────────────────────────
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
    private void saveQ(FileConfiguration cfg, File f) {
        try{cfg.save(f);}catch(IOException e){plugin.getLogger().warning("[PriceEditor] Save: "+e.getMessage());}
    }

    // ─── Icon builders ────────────────────────────────────────────────────
    private ItemStack makeTierIcon(String tier, TierMeta m) {
        return simple(m.icon(), m.color()+"✦ "+m.displayName(),
            "&7Skins: &f"+countInTier(tier), "&7Default: &e"+m.defaultPrice()+" BC","","&eClick &7to edit skins","&8tier:"+tier);
    }
    private ItemStack makeSkinIcon(SkinEntry s, TierMeta tm) {
        List<String> lore = new ArrayList<>(List.of(
            tm.color()+tm.displayName()+" &7tier",
            "&7Price: &e"+s.currentPrice()+" BellCoins",
            s.isOverridden()?"&8(override)":"&8(tier default)",
            "","&eLeft-click: &fset","" + (s.isOverridden()?"&eShift-click: &freset":""),
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
