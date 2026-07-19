package pl.bellmarket.model;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

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

    public Category(String id, String name, String displayName, int order, boolean enabled,
                    Material iconMaterial, String iconName, List<String> iconLore, List<Product> products) {
        this.id = id;
        this.name = name;
        this.displayName = displayName;
        this.order = order;
        this.enabled = enabled;
        this.iconMaterial = iconMaterial;
        this.iconName = iconName;
        this.iconLore = iconLore;
        this.products = products;
    }

    public ItemStack buildIcon(boolean selected) {
        ItemStack item = new ItemStack(iconMaterial);
        ItemMeta meta = item.getItemMeta();

        String displayStr = selected ? "&f&l" + iconName : "&7" + iconName;
        meta.displayName(colorize(displayStr));

        List<Component> loreComponents = new ArrayList<>();
        for (String line : iconLore) loreComponents.add(colorize(line));
        if (selected) loreComponents.add(colorize("&7▶ Currently selected"));
        meta.lore(loreComponents);

        item.setItemMeta(meta);
        return item;
    }

    private Component colorize(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    public String getId()               { return id; }
    public String getName()             { return name; }
    public String getDisplayName()      { return displayName; }
    public int getOrder()               { return order; }
    public boolean isEnabled()          { return enabled; }
    public Material getIconMaterial()   { return iconMaterial; }
    public String getIconName()         { return iconName; }
    public List<String> getIconLore()   { return iconLore; }
    public List<Product> getProducts()  { return products; }

    public List<Product> getEnabledProducts() {
        return products.stream().filter(Product::isEnabled).toList();
    }
}
