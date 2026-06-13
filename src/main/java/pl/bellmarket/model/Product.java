package pl.bellmarket.model;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.bellmarket.currency.Currency;

import java.util.ArrayList;
import java.util.List;

public class Product {

    public enum Type { SKIN_TOKEN, ITEM, COMMAND, VIP_EXCLUSIVE }

    private final String id;
    private final Type type;
    private final String name;
    private final List<String> lore;
    private final long price;
    private final boolean enabled;
    private final Material iconMaterial;
    private final String iconItemModel;
    private final String iconName;
    private final String skinId;
    private final boolean includeChangeToken;
    private final List<String> commands;
    private final ItemStack giveItem;
    private final Currency currency;
    private final String requiredPermission;
    private final String providerSource;
    private final boolean manual;

    private Product(Builder b) {
        this.id = b.id; this.type = b.type; this.name = b.name; this.lore = b.lore;
        this.price = b.price; this.enabled = b.enabled;
        this.iconMaterial = b.iconMaterial; this.iconItemModel = b.iconItemModel;
        this.iconName = b.iconName; this.skinId = b.skinId;
        this.includeChangeToken = b.includeChangeToken; this.commands = b.commands;
        this.giveItem = b.giveItem; this.currency = b.currency;
        this.requiredPermission = b.requiredPermission; this.providerSource = b.providerSource;
        this.manual = b.manual;
    }

    public ItemStack buildIcon() {
        Material mat = iconMaterial != null ? iconMaterial : Material.PAPER;
        ItemStack item = new ItemStack(mat, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String display = iconName != null && !iconName.isEmpty() ? iconName : name;
        meta.displayName(colorize("&f&l" + display).decoration(TextDecoration.ITALIC, false));

        if (!lore.isEmpty()) {
            List<Component> loreCmp = new ArrayList<>();
            for (String line : lore) {
                loreCmp.add(colorize("&7" + line).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(loreCmp);
        }

        if (iconItemModel != null && !iconItemModel.isEmpty()) {
            try {
                NamespacedKey key = NamespacedKey.fromString(iconItemModel);
                if (key != null) meta.setItemModel(key);
            } catch (Throwable ignored) {}
        }

        item.setItemMeta(meta);
        return item;
    }

    private static Component colorize(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    public String getId()              { return id; }
    public Type getType()              { return type; }
    public String getName()            { return name; }
    public List<String> getLore()      { return lore; }
    public long getPrice()             { return price; }
    public boolean isEnabled()         { return enabled; }
    public Material getIconMaterial()  { return iconMaterial; }
    public String getIconItemModel()   { return iconItemModel; }
    public String getIconName()        { return iconName; }
    public String getSkinId()          { return skinId; }
    public boolean isIncludeChangeToken() { return includeChangeToken; }
    public List<String> getCommands()  { return commands; }
    public ItemStack getGiveItem()     { return giveItem; }
    public Currency getCurrency()      { return currency; }
    public String getRequiredPermission() { return requiredPermission; }
    public String getProviderSource()  { return providerSource; }
    public boolean isManual()          { return manual; }
    public String getDisplayName()       { return name; }

    public static class Builder {
        private String id = "", name = "", iconName = "", skinId = "", iconItemModel = "";
        private String requiredPermission = null, providerSource = "manual";
        private Type type = Type.COMMAND;
        private List<String> lore = new ArrayList<>(), commands = new ArrayList<>();
        private long price = 0;
        private boolean enabled = true, includeChangeToken = false, manual = false;
        private Material iconMaterial = Material.PAPER;
        private ItemStack giveItem = null;
        private Currency currency = Currency.BELLCOINS;

        public Builder id(String v)               { id = v; return this; }
        public Builder type(Type v)               { type = v; return this; }
        public Builder name(String v)             { name = v; return this; }
        public Builder lore(List<String> v)       { lore = v; return this; }
        public Builder price(long v)              { price = v; return this; }
        public Builder enabled(boolean v)         { enabled = v; return this; }
        public Builder iconMaterial(Material v)   { if (v != null) iconMaterial = v; return this; }
        public Builder iconItemModel(String v)    { if (v != null) iconItemModel = v; return this; }
        public Builder iconName(String v)         { if (v != null) iconName = v; return this; }
        public Builder skinId(String v)           { if (v != null) skinId = v; return this; }
        public Builder includeChangeToken(boolean v) { includeChangeToken = v; return this; }
        public Builder commands(List<String> v)   { commands = v; return this; }
        public Builder giveItem(ItemStack v)      { giveItem = v; return this; }
        public Builder currency(Currency v)       { if (v != null) currency = v; return this; }
        public Builder requiredPermission(String v) { requiredPermission = v; return this; }
        public Builder providerSource(String v)   { providerSource = v; return this; }
        public Builder manual(boolean v)          { manual = v; return this; }
        public Product build()                    { return new Product(this); }
    }
}
