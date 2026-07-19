/*
 * BellMarket - Product model
 *
 * SESJA-1 CHANGES vs upstream:
 *   + new field `currency` (default BELLCOINS) — which currency this product costs in
 *   + new field `requiredPermission` (default null) — gate purchase + visibility
 *   + new field `providerSource` (default "manual") — debug/audit: where did this product come from
 *   + new Builder methods for all three
 *   + Builder defaults preserved for all existing fields (no breaking change)
 *
 * The includeChangeToken default REMAINS `false` — that was already the boolean
 * default. The actual change_token bug was in PurchaseProcessor.deliverSkinToken
 * and SkinStudioGenerator (both fixed in their respective files).
 */
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
        /** Gives a Bukkit ItemStack defined by giveItem field. */
        ITEM,
        /** Runs a list of console commands defined by commands field. */
        COMMAND,
        /** Gives a SkinStudio skin token (and optionally a change token). */
        SKIN_TOKEN,
        /** Same as COMMAND but semantically tagged as mount; UI may render differently. */
        MOUNT,
        /** SESJA-1: VIP-only product. Requires bellmarket.vip OR custom requiredPermission. */
        VIP_EXCLUSIVE
    }

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

    // ─── SESJA-1 additions ────────────────────────────────────────────────
    private final Currency currency;
    private final String requiredPermission;
    private final String providerSource;
    // ──────────────────────────────────────────────────────────────────────

    private Product(Builder b) {
        this.id                = b.id;
        this.type              = b.type;
        this.name              = b.name;
        this.lore              = b.lore != null ? b.lore : new ArrayList<>();
        this.price             = b.price;
        this.enabled           = b.enabled;
        this.iconMaterial      = b.iconMaterial;
        this.iconItemModel     = b.iconItemModel;
        this.iconName          = b.iconName;
        this.skinId            = b.skinId;
        this.includeChangeToken = b.includeChangeToken;
        this.commands          = b.commands != null ? b.commands : new ArrayList<>();
        this.giveItem          = b.giveItem;

        this.currency           = b.currency != null ? b.currency : Currency.BELLCOINS;
        this.requiredPermission = b.requiredPermission;
        this.providerSource     = b.providerSource != null ? b.providerSource : "manual";
    }

    public ItemStack buildIcon() {
        ItemStack icon;
        boolean fromGiveItem = type == Type.ITEM && giveItem != null && !giveItem.getType().isAir();
        if (fromGiveItem) {
            icon = giveItem.clone();
            icon.setAmount(1);
        } else {
            icon = new ItemStack(iconMaterial != null ? iconMaterial : Material.PAPER);
        }

        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            if (fromGiveItem) {
                // Zachowaj nazwę/lore/model z prawdziwego itemu (FMM / BellItems).
                // Dopisz tylko cenę na dole.
                Currency cur = currency != null ? currency : Currency.BELLCOINS;
                List<Component> existing = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                existing.add(Component.empty());
                existing.add(colorize("&dCena: &e" + price + " " + cur.getDisplayName())
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                existing.add(colorize("&aLPM &7— kup")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                meta.lore(existing);
            } else {
                if (iconName != null && !iconName.isEmpty()) {
                    meta.displayName(colorize(iconName)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                } else if (name != null) {
                    meta.displayName(colorize(name)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                }
                if (lore != null && !lore.isEmpty()) {
                    Currency cur = currency != null ? currency : Currency.BELLCOINS;
                    List<Component> resolved = new ArrayList<>();
                    for (String l : lore) {
                        String line = l.replace("{price}", String.valueOf(price))
                                       .replace("{currency}", cur.getDisplayName())
                                       .replace("{symbol}", cur.getSymbol());
                        resolved.add(colorize(line)
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                    }
                    meta.lore(resolved);
                }
                if (iconItemModel != null && !iconItemModel.isEmpty()) {
                    try {
                        NamespacedKey key = NamespacedKey.fromString(iconItemModel);
                        if (key != null) meta.setItemModel(key);
                    } catch (Throwable ignored) {
                        // older Paper without setItemModel — skip silently
                    }
                }
            }
            icon.setItemMeta(meta);
        }
        return icon;
    }

    public static Component colorize(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s == null ? "" : s);
    }

    // ─── Getters ──────────────────────────────────────────────────────────
    public String  getId()                 { return id; }
    public Type    getType()               { return type; }
    public String  getName()               { return name; }
    public List<String> getLore()          { return lore; }
    public long    getPrice()              { return price; }
    public boolean isEnabled()             { return enabled; }
    public String  getSkinId()             { return skinId; }
    public boolean includeChangeToken()    { return includeChangeToken; }
    public List<String> getCommands()      { return commands; }
    public ItemStack getGiveItem()         { return giveItem; }
    public Material  getIconMaterial()     { return iconMaterial; }
    public String    getIconItemModel()    { return iconItemModel; }
    public String    getIconName()         { return iconName; }

    public Currency getCurrency()           { return currency; }
    public String   getRequiredPermission() { return requiredPermission; }
    public String   getProviderSource()     { return providerSource; }

    // ─── Builder ──────────────────────────────────────────────────────────
    public static class Builder {
        String id;
        Type type = Type.ITEM;
        String name;
        List<String> lore;
        long price;
        boolean enabled = true;
        Material iconMaterial;
        String iconItemModel;
        String iconName;
        String skinId;
        boolean includeChangeToken = false;          // ← explicit default
        List<String> commands;
        ItemStack giveItem;

        Currency currency = Currency.BELLCOINS;       // ← SESJA-1
        String requiredPermission;                    // ← SESJA-1
        String providerSource = "manual";             // ← SESJA-1

        public Builder id(String v)               { this.id = v; return this; }
        public Builder type(Type v)               { this.type = v; return this; }
        public Builder name(String v)             { this.name = v; return this; }
        public Builder lore(List<String> v)       { this.lore = v; return this; }
        public Builder price(long v)              { this.price = v; return this; }
        public Builder enabled(boolean v)         { this.enabled = v; return this; }
        public Builder iconMaterial(Material v)   { this.iconMaterial = v; return this; }
        public Builder iconItemModel(String v)    { this.iconItemModel = v; return this; }
        public Builder iconName(String v)         { this.iconName = v; return this; }
        public Builder skinId(String v)           { this.skinId = v; return this; }
        public Builder includeChangeToken(boolean v) { this.includeChangeToken = v; return this; }
        public Builder commands(List<String> v)   { this.commands = v; return this; }
        public Builder giveItem(ItemStack v)      { this.giveItem = v; return this; }

        public Builder currency(Currency v)           { this.currency = v; return this; }
        public Builder requiredPermission(String v)   { this.requiredPermission = v; return this; }
        public Builder providerSource(String v)       { this.providerSource = v; return this; }

        public Product build() { return new Product(this); }
    }
}
