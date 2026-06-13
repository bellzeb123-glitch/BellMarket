package pl.bellmarket.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import pl.bellmarket.BellMarket;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public void reload() {
        String language = plugin.getConfig().getString("language", "en");
        File langFile = new File(plugin.getDataFolder(), "lang/" + language + ".yml");
        if (!langFile.exists()) {
            plugin.getLogger().warning("Language file not found: lang/" + language + ".yml, using en.yml");
            langFile = new File(plugin.getDataFolder(), "lang/en.yml");
        }
        lang = YamlConfiguration.loadConfiguration(langFile);
        currencyName   = plugin.getConfig().getString("currency.name", "BellCoins");
        currencySymbol = plugin.getConfig().getString("currency.symbol", "✦");
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
