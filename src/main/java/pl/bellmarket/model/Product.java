package pl.bellmarket.model;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import pl.bellmarket.BellMarket;

import java.util.ArrayList;
import java.util.List;

public class Product {

    public enum Type {
        SKIN_TOKEN,  // Gives a SkinStudio skin token (+ optional change token)
        ITEM,        // Gives a specific ItemStack
        COMMAND,     // Executes one or more commands
        MOUNT        // Alias for COMMAND, semantically a mount
    }

    private final String id;
    private final Type type;
    private final String name;
    private final List<String> lore;
    private final long price;
    private final boolean enabled;

    // Icon
    private final Material iconMaterial;
    private final String iconItemModel; // nullable
    private final String iconName;      // nullable, defaults to name

    // Type-specific fields
    private final String skinId;           // for SKIN_TOKEN
    private final boolean includeChangeToken; // for SKIN_TOKEN
    private final List<String> commands;   // for COMMAND/MOUNT
    private final ItemStack giveItem;      // for ITEM

    private Product(Builder b) {
        this.id = b.id;
        this.type = b.type;
        this.name = b.name;
        this.lore = b.lore;
        this.price = b.price;
        this.enabled = b.enabled;
        this.iconMaterial = b.iconMaterial;
        this.iconItemModel = b.iconItemModel;
        this.iconName = b.iconName;
        this.skinId = b.skinId;
        this.includeChangeToken = b.includeChangeToken;
        this.commands = b.commands;
        this.giveItem = b.giveItem;
    }

    public ItemStack buildIcon() {
        BellMarket plugin = BellMarket.getInstance();
        ItemStack item = new ItemStack(iconMaterial != null ? iconMaterial : Material.PAPER);
        ItemMeta meta = item.getItemMeta();

        // Display name
        String displayName = iconName != null ? iconName : name;
        meta.displayName(colorize(displayName));

        // Set item model if specified
        if (iconItemModel != null && !iconItemModel.isEmpty()) {
            try {
                String[] parts = iconItemModel.split(":", 2);
                NamespacedKey key = parts.length == 2
                    ? new NamespacedKey(parts[0], parts[1])
                    : NamespacedKey.minecraft(iconItemModel);
                meta.setItemModel(key);
            } catch (Exception ignored) {}
        }

        // Build lore with price substitution
        List<Component> loreComponents = new ArrayList<>();
        for (String line : lore) {
            String processed = line
                .replace("{price}", String.format("%,d", price))
                .replace("{currency}", plugin.getLang().getCurrencyName())
                .replace("{symbol}", plugin.getLang().getCurrencySymbol());
            loreComponents.add(colorize(processed));
        }
        meta.lore(loreComponents);

        if (!enabled) {
            // Gray out disabled items
            meta.displayName(colorize("&7" + displayName + " &8(Unavailable)"));
        }

        item.setItemMeta(meta);
        return item;
    }

    private Component colorize(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    // Getters
    public String getId()              { return id; }
    public Type getType()              { return type; }
    public String getName()            { return name; }
    public List<String> getLore()      { return lore; }
    public long getPrice()             { return price; }
    public boolean isEnabled()         { return enabled; }
    public String getSkinId()          { return skinId; }
    public boolean includeChangeToken(){ return includeChangeToken; }
    public List<String> getCommands()  { return commands; }
    public ItemStack getGiveItem()     { return giveItem != null ? giveItem.clone() : null; }

    // ── Builder ───────────────────────────────────────────────

    public static class Builder {
        String id;
        Type type = Type.COMMAND;
        String name = "Unknown Product";
        List<String> lore = new ArrayList<>();
        long price = 0;
        boolean enabled = true;
        Material iconMaterial = Material.PAPER;
        String iconItemModel;
        String iconName;
        String skinId;
        boolean includeChangeToken = false;
        List<String> commands = new ArrayList<>();
        ItemStack giveItem;

        public Builder id(String id)                     { this.id = id; return this; }
        public Builder type(Type type)                   { this.type = type; return this; }
        public Builder name(String name)                 { this.name = name; return this; }
        public Builder lore(List<String> lore)           { this.lore = lore; return this; }
        public Builder price(long price)                 { this.price = price; return this; }
        public Builder enabled(boolean enabled)          { this.enabled = enabled; return this; }
        public Builder iconMaterial(Material mat)        { this.iconMaterial = mat; return this; }
        public Builder iconItemModel(String model)       { this.iconItemModel = model; return this; }
        public Builder iconName(String name)             { this.iconName = name; return this; }
        public Builder skinId(String skinId)             { this.skinId = skinId; return this; }
        public Builder includeChangeToken(boolean b)     { this.includeChangeToken = b; return this; }
        public Builder commands(List<String> commands)   { this.commands = commands; return this; }
        public Builder giveItem(ItemStack item)          { this.giveItem = item; return this; }
        public Product build()                           { return new Product(this); }
    }
}
