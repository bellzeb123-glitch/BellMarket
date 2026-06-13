package pl.bellmarket.model;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class Category {

    private final String id;
    private final String name;
    private final String displayName;
    private final int order;
    private final boolean enabled;
    private final Material iconMaterial;
    private final String iconName;
    private final List<String> iconLore;
    private final List<Product> products;
    private String requiredPermission;

    public Category(String id, String name, String displayName, int order, boolean enabled,
                    Material iconMaterial, String iconName, List<String> iconLore,
                    List<Product> products) {
        this.id = id; this.name = name; this.displayName = displayName; this.order = order;
        this.enabled = enabled; this.iconMaterial = iconMaterial; this.iconName = iconName;
        this.iconLore = iconLore != null ? iconLore : List.of();
        this.products = products != null ? new ArrayList<>(products) : new ArrayList<>();
    }

    public ItemStack buildIcon(boolean selected) {
        Material mat = iconMaterial != null ? iconMaterial : Material.PAPER;
        ItemStack item = new ItemStack(mat, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String displayStr = iconName != null && !iconName.isEmpty() ? iconName : name;
        meta.displayName(colorize("&f&l" + displayStr).decoration(TextDecoration.ITALIC, false));

        List<Component> loreComponents = new ArrayList<>();
        for (String line : iconLore) {
            loreComponents.add(colorize("&7" + line).decoration(TextDecoration.ITALIC, false));
        }
        if (selected) loreComponents.add(colorize("&7▶ Currently selected")
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(loreComponents);
        item.setItemMeta(meta);
        return item;
    }

    private static Component colorize(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    public List<Product> getEnabledProducts() {
        return products.stream().filter(Product::isEnabled).toList();
    }

    public String getId()             { return id; }
    public String getName()           { return name; }
    public String getDisplayName()    { return displayName; }
    public int getOrder()             { return order; }
    public boolean isEnabled()        { return enabled; }
    public Material getIconMaterial() { return iconMaterial; }
    public String getIconName()       { return iconName; }
    public List<Product> getProducts(){ return products; }
    public String getRequiredPermission()           { return requiredPermission; }
    public void setRequiredPermission(String perm)  { this.requiredPermission = perm; }
}
