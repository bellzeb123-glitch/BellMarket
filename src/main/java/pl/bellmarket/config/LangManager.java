/*
 * BellMarket - LangManager (LANG FIX)
 *
 * Fixed language switching using the merge technique:
 *   1. Load base from JAR (always has all keys, latest version)
 *   2. Overlay admin customizations from disk (only existing keys)
 *   3. Save merged result back to disk (new keys auto-added)
 *
 * This fixes /bm lang not working — previously saveResource(false)
 * never overwrote existing files, so new keys never reached disk and
 * missing keys fell back to the other language.
 */
package pl.bellmarket.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import pl.bellmarket.BellMarket;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class LangManager {

    private final BellMarket plugin;
    private FileConfiguration lang;
    private String currencyName, currencySymbol, prefix;

    public LangManager(BellMarket plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        String language = plugin.getConfig().getString("language", "en").toLowerCase();
        lang = loadAndMerge(language);

        currencyName   = lang.getString("currency.name",
            plugin.getConfig().getString("currency.name", "BellCoins"));
        currencySymbol = lang.getString("currency.symbol",
            plugin.getConfig().getString("currency.symbol", "✦"));
        prefix         = lang.getString("prefix", "&8[&6BellMarket&8] &r");

        plugin.getLogger().info("[LangManager] Loaded language: " + language);
    }

    /**
     * Merge technique (LuckPerms/EssentialsX pattern):
     *   base (from jar) ← overlay (from disk, only existing keys) → save back
     */
    private FileConfiguration loadAndMerge(String language) {
        String fileName = "lang/" + language + ".yml";
        File diskFile   = new File(plugin.getDataFolder(), fileName);

        // 1. Base from JAR — always complete, latest keys
        FileConfiguration base = loadFromJar(fileName);
        if (base == null) {
            plugin.getLogger().warning("[LangManager] No " + fileName + " in jar, trying en.yml");
            base = loadFromJar("lang/en.yml");
        }
        if (base == null) {
            plugin.getLogger().severe("[LangManager] No language files in jar! Using empty config.");
            return new YamlConfiguration();
        }

        // 2. Overlay disk customizations — only keys that already exist in base
        if (diskFile.exists()) {
            FileConfiguration disk = YamlConfiguration.loadConfiguration(diskFile);
            for (String key : disk.getKeys(true)) {
                if (disk.isString(key) && base.contains(key)) {
                    base.set(key, disk.getString(key));
                } else if (disk.isList(key) && base.contains(key)) {
                    base.set(key, disk.getStringList(key));
                }
            }
        }

        // 3. Save merged back — new keys from jar now persisted to disk
        try {
            if (!diskFile.getParentFile().exists()) diskFile.getParentFile().mkdirs();
            base.save(diskFile);
        } catch (Exception e) {
            plugin.getLogger().warning("[LangManager] Could not save merged lang file: " + e.getMessage());
        }

        return base;
    }

    private FileConfiguration loadFromJar(String fileName) {
        try (var stream = plugin.getResource(fileName)) {
            if (stream == null) return null;
            return YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("[LangManager] Error loading " + fileName + " from jar: " + e.getMessage());
            return null;
        }
    }

    public String getRaw(String key, String... args) {
        String msg = lang.getString(key);
        if (msg == null) return "&cMissing lang key: " + key;
        return applyPlaceholders(msg, args);
    }

    public List<String> getList(String key, String... args) {
        List<String> list = lang.getStringList(key);
        return list.stream().map(line -> applyPlaceholders(line, args)).toList();
    }

    public Component component(String key, String... args) {
        return colorize(getRaw(key, args));
    }

    public Component colorize(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    public String getCurrencyName()   { return currencyName; }
    public String getCurrencySymbol() { return currencySymbol; }

    public String formatAmount(long amount) {
        String fmt = lang.getString("currency.format",
            plugin.getConfig().getString("currency.format", "{symbol}{amount}"));
        return fmt.replace("{symbol}", currencySymbol)
                  .replace("{amount}", String.format("%,d", amount))
                  .replace("{name}", currencyName);
    }

    private String applyPlaceholders(String msg, String... args) {
        msg = msg.replace("{currency}", currencyName)
                 .replace("{symbol}", currencySymbol);
        if (args.length >= 2 && args.length % 2 == 0) {
            for (int i = 0; i < args.length - 1; i += 2) {
                msg = msg.replace("{" + args[i] + "}", args[i + 1]);
            }
        }
        return msg;
    }
}
