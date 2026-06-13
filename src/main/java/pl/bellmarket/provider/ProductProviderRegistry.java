package pl.bellmarket.provider;

import org.bukkit.configuration.ConfigurationSection;
import pl.bellmarket.BellMarket;
import pl.bellmarket.model.Category;

import java.util.*;

public class ProductProviderRegistry {

    private final BellMarket plugin;
    private final List<ProductProvider> providers = new ArrayList<>();

    public ProductProviderRegistry(BellMarket plugin) {
        this.plugin = plugin;
    }

    public void register(ProductProvider provider) {
        Objects.requireNonNull(provider, "provider == null");
        providers.removeIf(p -> p.getProviderId().equals(provider.getProviderId()));
        providers.add(provider);
        plugin.getLogger().info("[Providers] Registered: " + provider.getProviderId());
    }

    public void unregister(String providerId) {
        providers.removeIf(p -> p.getProviderId().equals(providerId));
    }

    public List<ProductProvider> getAll() {
        return Collections.unmodifiableList(providers);
    }

    public Optional<ProductProvider> get(String providerId) {
        return providers.stream().filter(p -> p.getProviderId().equals(providerId)).findFirst();
    }

    public List<Category> generateAll() {
        List<Category> out = new ArrayList<>();
        for (ProductProvider provider : providers) {
            String id = provider.getProviderId();
            if (!isEnabledInConfig(id)) {
                plugin.getLogger().info("[Providers] " + id + " — disabled in config, skipping");
                continue;
            }
            if (!provider.isAvailable()) {
                plugin.getLogger().info("[Providers] " + id + " — not available (plugin missing), skipping");
                continue;
            }
            try {
                long defaultPrice = getDefaultPrice(id);
                List<Category> cats = provider.generateCategories(defaultPrice);
                out.addAll(cats);
                int totalProducts = cats.stream().mapToInt(c -> c.getProducts().size()).sum();
                plugin.getLogger().info("[Providers] " + id + " → " + cats.size()
                    + " categories, " + totalProducts + " products total");
            } catch (Exception e) {
                plugin.getLogger().warning("[Providers] " + id + " — generation failed: " + e.getMessage());
                e.printStackTrace();
            }
        }
        return out;
    }

    private boolean isEnabledInConfig(String id) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("providers." + id);
        return sec == null || sec.getBoolean("enabled", true);
    }

    private long getDefaultPrice(String id) {
        return plugin.getConfig().getLong("providers." + id + ".default-price", 500);
    }
}
