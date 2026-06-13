package pl.bellmarket.model;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Category — IMMUTABLE (9-arg constructor, no setters).
 */
public class Category {

    private final String       id;
    private final String       name;
    private final String       displayName;
    private final int          order;
    private final boolean      enabled;
    private final Material     iconMaterial;
    private final String       iconName;
    private final List<String> iconLore;
    private final List<Product> products;

    public Category(String id, String name, String displayName,
                    int order, boolean enabled,
                    Material iconMaterial, String iconName,
                    List<String> iconLore, List<Product> products) {
        this.id           = id;
        this.name         = name;
        this.displayName  = displayName;
        this.order        = order;
        this.enabled      = enabled;
        this.iconMaterial = iconMaterial != null ? iconMaterial : Material.CHEST;
        this.iconName     = iconName;
        this.iconLore     = iconLore  != null ? List.copyOf(iconLore)  : List.of();
        this.products     = products  != null ? new ArrayList<>(products) : new ArrayList<>();
    }

    /**
     * Builds the inventory icon for this category.
     * @param selected if true, appends "&7▶ Currently selected" to lore
     */
    public ItemStack buildIcon(boolean selected) {
        ItemStack item = new ItemStack(iconMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        // Display name: white bold + displayName
        String displayStr = "&f&l" + displayName;
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(displayStr));

        // Lore
        List<Component> loreComponents = new ArrayList<>();
        for (String line : iconLore) {
            loreComponents.add(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize("&7" + line));
        }
        if (selected) {
            loreComponents.add(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize("&7▶ Currently selected"));
        }
        meta.lore(loreComponents);

        item.setItemMeta(meta);
        return item;
    }

    // ─── getters ─────────────────────────────────────────────────────────
    public String          getId()           { return id; }
    public String          getName()         { return name; }
    public String          getDisplayName()  { return displayName; }
    public int             getOrder()        { return order; }
    public boolean         isEnabled()       { return enabled; }
    public Material        getIconMaterial() { return iconMaterial; }
    public String          getIconName()     { return iconName; }
    public List<String>    getIconLore()     { return iconLore; }
    public List<Product>   getProducts()     { return products; }

    /** Returns only enabled products. */
    public List<Product> getEnabledProducts() {
        return products.stream().filter(Product::isEnabled).toList();
    }
}
