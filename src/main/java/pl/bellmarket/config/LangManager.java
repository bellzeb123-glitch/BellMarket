package pl.bellmarket.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import pl.bellmarket.BellMarket;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class LangManager {

    private final BellMarket plugin;
    private FileConfiguration lang;
    private String currencyName;
    private String currencySymbol;

    public LangManager(BellMarket plugin) {
        this.plugin = plugin;
        reload();
    }

    /**
     * FIX: MERGE pattern (z language-system.md).
     * Baza z JARa (wszystkie klucze) + nadpisanie customizacjami z dysku.
     * Nowe klucze pojawiają się automatycznie. Zmiana języka działa globalnie.
     */
    public void reload() {
        String language = plugin.getConfig().getString("language", "en");
        lang = loadAndMerge(language);
        currencyName   = plugin.getConfig().getString("currency.name", "BellCoins");
        currencySymbol = plugin.getConfig().getString("currency.symbol", "✦");
    }

    // ── MERGE logic ─────────────────────────────────────────────────────

    private FileConfiguration loadAndMerge(String langCode) {
        String fileName = "lang/" + langCode + ".yml";
        File diskFile = new File(plugin.getDataFolder(), fileName);

        // 1. Baza z JARa — zawsze aktualna, wszystkie klucze
        FileConfiguration base = loadFromJar(fileName);
        if (base == null) {
            plugin.getLogger().warning("Lang file not found in jar: " + fileName + ", falling back to en");
            base = loadFromJar("lang/en.yml");
        }
        if (base == null) {
            // Ostateczny fallback — plik z dysku
            plugin.getLogger().severe("No lang files in jar! Loading from disk.");
            return diskFile.exists()
                ? YamlConfiguration.loadConfiguration(diskFile)
                : new YamlConfiguration();
        }

        // 2. Nadpisz customizacjami admina z dysku (tylko istniejące klucze)
        if (diskFile.exists()) {
            FileConfiguration disk = YamlConfiguration.loadConfiguration(diskFile);
            for (String key : disk.getKeys(true)) {
                if (!disk.isConfigurationSection(key) && base.contains(key)) {
                    base.set(key, disk.get(key));
                }
            }
        }

        // 3. Zapisz merged result na dysk — nowe klucze z jara trafiają na dysk
        try {
            diskFile.getParentFile().mkdirs();
            base.save(diskFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save lang file: " + fileName, e);
        }

        return base;
    }

    private FileConfiguration loadFromJar(String fileName) {
        try (InputStream stream = plugin.getResource(fileName)) {
            if (stream == null) return null;
            return YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error loading lang from jar: " + fileName, e);
            return null;
        }
    }

    /**
     * Get a translated message with placeholder replacements.
     */
    public String get(String key, Object... args) {
        String prefix = lang.getString("prefix", "&8[&6BellMarket&8] &r");
        String msg = lang.getString(key, "&cMissing lang key: " + key);
        msg = prefix + msg;
        msg = applyPlaceholders(msg, args);
        return msg;
    }

    /**
     * Get a translated message without prefix.
     */
    public String getRaw(String key, Object... args) {
        String msg = lang.getString(key, "&cMissing lang key: " + key);
        return applyPlaceholders(msg, args);
    }

    /**
     * Get a list of translated strings (for lore etc).
     */
    public List<String> getList(String key, Object... args) {
        List<String> list = lang.getStringList(key);
        return list.stream()
            .map(line -> applyPlaceholders(line, args))
            .collect(Collectors.toList());
    }

    /**
     * Convert a formatted string to a Component.
     */
    public Component component(String key, Object... args) {
        return colorize(get(key, args));
    }

    /**
     * Colorize a raw string.
     */
    public Component colorize(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    /**
     * Apply placeholders: pairs of key, value.
     * e.g. applyPlaceholders(msg, "player", "Steve", "amount", "100")
     */
    private String applyPlaceholders(String msg, Object... args) {
        // Apply currency defaults
        msg = msg.replace("{currency}", currencyName)
                 .replace("{symbol}", currencySymbol);

        // Apply custom placeholders
        for (int i = 0; i + 1 < args.length; i += 2) {
            String placeholder = "{" + args[i] + "}";
            String value = String.valueOf(args[i + 1]);
            msg = msg.replace(placeholder, value);
        }
        return msg;
    }

    public String getCurrencyName()   { return currencyName; }
    public String getCurrencySymbol() { return currencySymbol; }

    public String formatAmount(long amount) {
        String format = plugin.getConfig().getString("currency.format", "{symbol}{amount}");
        return format.replace("{symbol}", currencySymbol)
                     .replace("{amount}", String.format("%,d", amount))
                     .replace("{currency}", currencyName);
    }
}
