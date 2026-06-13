package pl.bellmarket.provider;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import pl.bellmarket.BellMarket;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;

public class ProductProviderRegistry {

    private final BellMarket plugin;
    private final List<ProductProvider> providers = new ArrayList<>();

    public ProductProviderRegistry(BellMarket plugin) {
        this.plugin = plugin;
    }

    public void register(ProductProvider provider) {
        providers.removeIf(p -> p.getProviderId().equals(provider.getProviderId()));
        providers.add(provider);
    }

    public void unregister(String providerId) {
        providers.removeIf(p -> p.getProviderId().equals(providerId));
    }

    public List<ProductProvider> getProviders() {
        return Collections.unmodifiableList(providers);
    }

    /**
     * Loads (or creates) a provider config file in plugins/BellMarket/providers/<id>.yml.
     * If the file doesn't exist, copies the default from the jar.
     * Used by all providers.
     */
    public FileConfiguration loadOrCreateProviderConfig(String providerId) {
        String resourcePath = "providers/" + providerId + ".yml";
        File diskFile = new File(plugin.getDataFolder(), resourcePath);

        if (!diskFile.exists()) {
            diskFile.getParentFile().mkdirs();
            InputStream jarStream = plugin.getResource(resourcePath);
            if (jarStream != null) {
                // Copy default from jar
                try (jarStream) {
                    FileConfiguration defaults = YamlConfiguration.loadConfiguration(
                            new InputStreamReader(jarStream, StandardCharsets.UTF_8));
                    defaults.save(diskFile);
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING,
                            "Could not save default provider config: " + resourcePath, e);
                }
            }
        }

        return YamlConfiguration.loadConfiguration(diskFile);
    }

    /**
     * Utility: parse a Material name, returning fallback on failure.
     */
    public static org.bukkit.Material parseMaterial(String name, org.bukkit.Material fallback) {
        if (name == null || name.isBlank()) return fallback;
        try {
            return org.bukkit.Material.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * Utility: capitalize first letter.
     */
    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}
