package pl.bellmarket.model;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.bellmarket.currency.Currency;

import java.util.ArrayList;
import java.util.List;

public class Product {

    public enum Type {
        COMMAND,
        SKIN_TOKEN,
        VIP_EXCLUSIVE,
        ITEM
    }

    private final String        id;
    private final Type          type;
    private final String        name;
    private final List<String>  lore;
    private long                price;
    private boolean             enabled;
    private final Material      iconMaterial;
    private final String        iconItemModel;
    private final String        iconName;
    private final String        skinId;
    private final boolean       includeChangeToken;
    private final List<String>  commands;
    private final ItemStack     giveItem;
    private final Currency      currency;
    private final String        requiredPermission;
    private final String        providerSource;

    private Product(Builder b) {
        this.id                 = b.id;
        this.type               = b.type;
        this.name               = b.name;
        this.lore               = b.lore != null ? List.copyOf(b.lore) : List.of();
        this.price              = b.price;
        this.enabled            = b.enabled;
        this.iconMaterial       = b.iconMaterial != null ? b.iconMaterial : Material.PAPER;
        this.iconItemModel      = b.iconItemModel;
        this.iconName           = b.iconName;
        this.skinId             = b.skinId;
        this.includeChangeToken = b.includeChangeToken;
        this.commands           = b.commands != null ? List.copyOf(b.commands) : List.of();
        this.giveItem           = b.giveItem;
        this.currency           = b.currency != null ? b.currency : Currency.BELLCOINS;
        this.requiredPermission = b.requiredPermission;
        this.providerSource     = b.providerSource != null ? b.providerSource : "manual";
    }

    /** Builds the inventory icon for this product. */
    public ItemStack buildIcon() {
        ItemStack item = new ItemStack(iconMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        // Display name
        String resolved = name;
        if (resolved != null && !resolved.isEmpty()) {
            meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(resolved));
        }

        // Lore
        if (!lore.isEmpty()) {
            List<Component> loreComponents = new ArrayList<>();
            for (String line : lore) {
                loreComponents.add(LegacyComponentSerializer.legacyAmpersand().deserialize(line));
            }
            meta.lore(loreComponents);
        }

        // Custom item model (Paper 1.21+)
        if (iconItemModel != null && !iconItemModel.isEmpty()) {
            try {
                NamespacedKey key = NamespacedKey.fromString(iconItemModel);
                if (key != null) meta.setItemModel(key);
            } catch (Throwable ignored) {}
        }

        item.setItemMeta(meta);
        return item;
    }

    // ─── getters ─────────────────────────────────────────────────────────
    public String        getId()                  { return id; }
    public Type          getType()                { return type; }
    public String        getName()                { return name; }
    public List<String>  getLore()                { return lore; }
    public long          getPrice()               { return price; }
    public boolean       isEnabled()              { return enabled; }
    public Material      getIconMaterial()        { return iconMaterial; }
    public String        getIconItemModel()       { return iconItemModel; }
    public String        getIconName()            { return iconName; }
    public String        getSkinId()              { return skinId; }
    public boolean       isIncludeChangeToken()   { return includeChangeToken; }
    public List<String>  getCommands()            { return commands; }
    public ItemStack     getGiveItem()            { return giveItem; }
    public Currency      getCurrency()            { return currency; }
    public String        getRequiredPermission()  { return requiredPermission; }
    public String        getProviderSource()      { return providerSource; }

    public void setPrice(long price)     { this.price = price; }
    public void setEnabled(boolean val)  { this.enabled = val; }

    // ─── Builder ─────────────────────────────────────────────────────────
    public static class Builder {
        private String        id;
        private Type          type          = Type.COMMAND;
        private String        name          = "Product";
        private List<String>  lore          = new ArrayList<>();
        private long          price         = 0;
        private boolean       enabled       = true;
        private Material      iconMaterial  = Material.PAPER;
        private String        iconItemModel;
        private String        iconName;
        private String        skinId;
        private boolean       includeChangeToken = false;
        private List<String>  commands      = new ArrayList<>();
        private ItemStack     giveItem;
        private Currency      currency      = Currency.BELLCOINS;
        private String        requiredPermission;
        private String        providerSource = "manual";

        public Builder id(String id)                        { this.id = id; return this; }
        public Builder type(Type type)                      { this.type = type; return this; }
        public Builder name(String name)                    { this.name = name; return this; }
        public Builder lore(List<String> lore)              { this.lore = lore; return this; }
        public Builder price(long price)                    { this.price = price; return this; }
        public Builder enabled(boolean enabled)             { this.enabled = enabled; return this; }
        public Builder iconMaterial(Material mat)           { this.iconMaterial = mat; return this; }
        public Builder iconItemModel(String model)          { this.iconItemModel = model; return this; }
        public Builder iconName(String name)                { this.iconName = name; return this; }
        public Builder skinId(String skinId)                { this.skinId = skinId; return this; }
        public Builder includeChangeToken(boolean v)        { this.includeChangeToken = v; return this; }
        public Builder commands(List<String> commands)      { this.commands = commands; return this; }
        public Builder giveItem(ItemStack item)             { this.giveItem = item; return this; }
        public Builder currency(Currency currency)          { this.currency = currency; return this; }
        public Builder requiredPermission(String perm)      { this.requiredPermission = perm; return this; }
        public Builder providerSource(String source)        { this.providerSource = source; return this; }

        public Product build() {
            if (id == null) throw new IllegalStateException("Product id cannot be null");
            return new Product(this);
        }
    }
}
