package pl.bellmarket.currency;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import pl.bellmarket.BellMarket;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public class CurrencyManager {

    private final BellMarket plugin;
    private final Map<UUID, Long> balances = new HashMap<>();
    private File dataFile;
    private FileConfiguration dataConfig;

    public CurrencyManager(BellMarket plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        saveAll();
        balances.clear();
        dataFile = new File(plugin.getDataFolder(), "data/balances.yml");
        dataFile.getParentFile().mkdirs();
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); }
            catch (IOException e) { plugin.getLogger().severe("Could not create data/balances.yml: " + e.getMessage()); }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        loadAll();
    }

    private void loadAll() {
        if (!dataConfig.isConfigurationSection("balances")) return;
        for (String key : Objects.requireNonNull(dataConfig.getConfigurationSection("balances")).getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                balances.put(uuid, dataConfig.getLong("balances." + key));
            } catch (IllegalArgumentException ignored) {}
        }
        plugin.getLogger().info("Loaded " + balances.size() + " coin balances.");
    }

    private long getStartingBalance() {
        return plugin.getConfig().getLong("currency.starting-balance", 0);
    }

    public long getBalance(UUID uuid) {
        return balances.getOrDefault(uuid, getStartingBalance());
    }

    public boolean hasEnough(UUID uuid, long amount) {
        return getBalance(uuid) >= amount;
    }

    public void setBalance(UUID uuid, long amount) {
        balances.put(uuid, Math.max(0, amount));
        savePlayer(uuid);
    }

    public void addCoins(UUID uuid, long amount) {
        setBalance(uuid, getBalance(uuid) + amount);
    }

    public void takeCoins(UUID uuid, long amount) {
        setBalance(uuid, getBalance(uuid) - amount);
    }

    /** Saves one player's balance asynchronously. */
    private void savePlayer(UUID uuid) {
        long bal = balances.getOrDefault(uuid, 0L);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            dataConfig.set("balances." + uuid, bal);
            try { dataConfig.save(dataFile); }
            catch (IOException e) { plugin.getLogger().log(Level.WARNING, "Could not save balances", e); }
        });
    }

    /** Saves all balances synchronously (called on disable/reload). */
    public void saveAll() {
        if (dataConfig == null) return;
        for (Map.Entry<UUID, Long> entry : balances.entrySet()) {
            dataConfig.set("balances." + entry.getKey(), entry.getValue());
        }
        try { dataConfig.save(dataFile); }
        catch (IOException e) { plugin.getLogger().log(Level.WARNING, "Could not save balances", e); }
    }

    /** Returns top N players by balance. */
    public List<Map.Entry<UUID, Long>> getTopList(int limit) {
        return balances.entrySet().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
                .limit(limit)
                .toList();
    }

    /** Returns display name for a UUID (offline-safe). */
    public String getPlayerName(UUID uuid) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        String name = op.getName();
        return name != null ? name : uuid.toString().substring(0, 8);
    }
}
