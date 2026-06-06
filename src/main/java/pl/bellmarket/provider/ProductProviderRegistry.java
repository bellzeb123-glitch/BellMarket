/*
 * BellMarket - ProductProviderRegistry
 *
 * Central registry of all ProductProvider implementations. Tracks built-in
 * providers (SkinStudio, future MythicMobs/EliteMobs/FMM) and external ones
 * registered by other plugins.
 *
 * Order of operations on reload:
 *   1. CategoryManager loads YAML categories from disk (file-based content)
 *   2. ProductProviderRegistry runs each enabled provider
 *      → each one generates a Category, added to CategoryManager's list
 *   3. ShopGUI rebuilds
 *
 * External plugin registration example:
 *
 *     public void onEnable() {
 *         BellMarketAPI.getProviderRegistry()
 *             .register(new BellMountsProvider(this));
 *     }
 */
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

    /**
     * Register a provider. Idempotent: re-registering the same providerId
     * replaces the previous one (so plugins can hot-reload).
     */
    public synchronized void register(ProductProvider provider) {
        Objects.requireNonNull(provider, "provider == null");
        providers.removeIf(p -> p.getProviderId().equalsIgnoreCase(provider.getProviderId()));
        providers.add(provider);
        plugin.getLogger().info("[Providers] Registered: " + provider.getProviderId());
    }

    public synchronized boolean unregister(String providerId) {
        return providers.removeIf(p -> p.getProviderId().equalsIgnoreCase(providerId));
    }

    public synchronized List<ProductProvider> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(providers));
    }

    public synchronized Optional<ProductProvider> get(String providerId) {
        return providers.stream()
            .filter(p -> p.getProviderId().equalsIgnoreCase(providerId))
            .findFirst();
    }

    /**
     * Whether this provider is enabled in config.yml under providers.<id>.enabled
     * (defaults to true if config section is missing — backwards compatible).
     */
    public boolean isEnabledInConfig(String providerId) {
        ConfigurationSection sec = plugin.getConfig()
            .getConfigurationSection("providers." + providerId.toLowerCase(Locale.ROOT));
        if (sec == null) return true;
        return sec.getBoolean("enabled", true);
    }

    /**
     * Read default-price from config.yml for a provider, falling back to 100.
     */
    public long getDefaultPrice(String providerId) {
        return plugin.getConfig().getLong(
            "providers." + providerId.toLowerCase(Locale.ROOT) + ".default-price",
            100L);
    }

    /**
     * Run every enabled+available provider and collect their categories.
     * Caller (CategoryManager.reload) adds them to its catalog.
     */
    public synchronized List<Category> generateAll() {
        List<Category> out = new ArrayList<>();
        for (ProductProvider p : providers) {
            String id = p.getProviderId();
            if (!isEnabledInConfig(id)) {
                plugin.getLogger().info("[Providers] " + id + " — disabled in config, skipping");
                continue;
            }
            if (!p.isAvailable()) {
                plugin.getLogger().info("[Providers] " + id + " — not available (plugin missing), skipping");
                continue;
            }
            try {
                Category cat = p.generateCategory(getDefaultPrice(id));
                if (cat != null) {
                    out.add(cat);
                    plugin.getLogger().info("[Providers] " + id + " → category '"
                        + cat.getId() + "' (" + cat.getProducts().size() + " products)");
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("[Providers] " + id + " — generation failed: " + t.getMessage());
                t.printStackTrace();
            }
        }
        return out;
    }
}
