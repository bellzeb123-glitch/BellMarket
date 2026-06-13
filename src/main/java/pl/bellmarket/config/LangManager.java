package pl.bellmarket.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import pl.bellmarket.BellMarket;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * LangManager — system językowy BellMarket z MERGE.
 *
 * FIX v1.26.1.2: zastąpiono saveResource (nie nadpisuje!) algorytmem MERGE
 * z language-system.md. Zmiana języka (/bm lang) działa teraz globalnie —
 * wszystkie GUI, komendy, opisy przeładowują się przez BellMarket.reload().
 *
 * API:
 *   get(key, args...)  → String z §-kolorami, podmienionymi placeholderami
 *   getRaw(key)        → String z &-kolorami (do GUI/lore)
 *   getList(key)       → List<String> z §-kolorami
 *   component(key)     → Adventure Component
 *   formatAmount(n)    → np. "1,234" z symbolem waluty
 */
public class LangManager {

    private final BellMarket plugin;
    private FileConfiguration lang;
    private String currencyName;
    private String currencySymbol;

    public LangManager(BellMarket plugin) {
        this.plugin = plugin;
        reload();
    }

    // ─── reload ──────────────────────────────────────────────────────────

    /**
     * Przeładowuje język z aktualnego config.yml (klucz "language").
     * Wywoływany przez BellMarket.reload() — zmiana globalna.
     */
    public void reload() {
        String langCode = plugin.getConfig().getString("language", "en")
                .toLowerCase(Locale.ROOT);
        lang = loadAndMerge(langCode);

        // Cache currency strings for fast access
        currencyName   = plugin.getConfig().getString("currency.name", "BellCoins");
        currencySymbol = plugin.getConfig().getString("currency.symbol", "✦");
    }

    // ─── public API ──────────────────────────────────────────────────────

    /**
     * Returns colorized message with optional placeholder substitution.
     * Placeholders passed as key-value pairs: get("key", "placeholder", "value", ...)
     */
    public String get(String key, String... args) {
        String raw = lang.getString(key, "&cMissing lang key: " + key);
        raw = applyPlaceholders(raw, args);
        return colorize(raw);
    }

    /**
     * Returns raw message with & color codes (for use in GUI item names/lore).
     */
    public String getRaw(String key, String... args) {
        String raw = lang.getString(key, "&cMissing: " + key);
        return applyPlaceholders(raw, args);
    }

    /**
     * Returns colorized list (for lore, broadcast lines, etc.).
     */
    public List<String> getList(String key, String... args) {
        List<String> rawList = lang.getStringList(key);
        return rawList.stream()
                .map(line -> colorize(applyPlaceholders(line, args)))
                .toList();
    }

    /**
     * Returns an Adventure Component for the given key (for Paper sendMessage).
     */
    public Component component(String key, String... args) {
        return LegacyComponentSerializer.legacyAmpersand()
                .deserialize(getRaw(key, args));
    }

    /** Currency name from config */
    public String getCurrencyName()   { return currencyName; }

    /** Currency symbol from config */
    public String getCurrencySymbol() { return currencySymbol; }

    /**
     * Formats a long amount with thousand separators.
     * Respects currency.format config: "{symbol}{amount}" or "{amount} {name}"
     */
    public String formatAmount(long amount) {
        String fmt    = plugin.getConfig().getString("currency.format", "{symbol}{amount}");
        String symbol = currencySymbol;
        String name   = currencyName;
        String num    = String.format("%,d", amount);
        return fmt.replace("{symbol}", symbol)
                  .replace("{amount}", num)
                  .replace("{name}", name);
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    public static String colorize(String text) {
        if (text == null) return "";
        return LegacyComponentSerializer.legacySection().serialize(
                LegacyComponentSerializer.legacyAmpersand().deserialize(text));
    }

    /**
     * Applies key-value placeholder pairs: {"currency", "BellCoins", "amount", "100"}
     * → replaces {currency} with BellCoins, {amount} with 100
     */
    public String applyPlaceholders(String text, String... args) {
        if (text == null || args == null || args.length == 0) return text;
        for (int i = 0; i + 1 < args.length; i += 2) {
            text = text.replace("{" + args[i] + "}", args[i + 1]);
        }
        return text;
    }

    // ─── MERGE logic ─────────────────────────────────────────────────────

    /**
     * FIX: MERGE zamiast saveResource.
     * Ładuje bazę z jara (wszystkie klucze) + nakłada customizacje z dysku.
     * Nowe klucze z jara auto-trafiają na dysk przy każdym reload.
     */
    private FileConfiguration loadAndMerge(String langCode) {
        String fileName = "lang/" + langCode + ".yml";
        File diskFile   = new File(plugin.getDataFolder(), fileName);

        // 1. Baza z jara — zawsze aktualna, wszystkie klucze
        FileConfiguration base = loadFromJar(fileName);
        if (base == null) {
            plugin.getLogger().warning("Lang file not found in jar: " + fileName
                    + " — falling back to en");
            base = loadFromJar("lang/en.yml");
        }
        if (base == null) {
            plugin.getLogger().severe("CRITICAL: No lang files in jar!");
            return new YamlConfiguration();
        }

        // 2. Nadpisz wartościami z dysku (customizacje admina) — tylko istniejące klucze
        if (diskFile.exists()) {
            FileConfiguration disk = YamlConfiguration.loadConfiguration(diskFile);
            for (String key : disk.getKeys(true)) {
                if (!disk.isConfigurationSection(key) && base.contains(key)) {
                    base.set(key, disk.get(key));
                }
            }
        }

        // 3. Zapisz z powrotem — nowe klucze z jara trafiają na dysk
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
            plugin.getLogger().log(Level.WARNING, "Error loading from jar: " + fileName, e);
            return null;
        }
    }
}
