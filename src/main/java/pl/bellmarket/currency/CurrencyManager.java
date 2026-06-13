package pl.bellmarket.currency;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import pl.bellmarket.BellMarket;

import java.io.File;
import java.io.IOException;
import java.util.*;

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
        dataFile = new File(plugin.getDataFolder(), "data/balances.yml");
        if (!dataFile.getParentFile().exists()) dataFile.getParentFile().mkdirs();
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); } catch (IOException e) {
                plugin.getLogger().severe("Could not create balances.yml: " + e.getMessage());
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        loadAll();
    }

    // ── Read operations ──────────────────────────────────────

    public long getBalance(UUID uuid) {
        return balances.getOrDefault(uuid, getStartingBalance());
    }

    public long getBalance(Player player) {
        return getBalance(player.getUniqueId());
    }

    public boolean hasEnough(UUID uuid, long amount) {
        return getBalance(uuid) >= amount;
    }

    public boolean hasEnough(Player player, long amount) {
        return hasEnough(player.getUniqueId(), amount);
    }

    // ── Write operations ─────────────────────────────────────

    public void setBalance(UUID uuid, long amount) {
        balances.put(uuid, Math.max(0, amount));
        savePlayer(uuid);
    }

    public void setBalance(Player player, long amount) {
        setBalance(player.getUniqueId(), amount);
    }

    /**
     * Add coins to a player. Returns new balance.
     */
    public long addCoins(UUID uuid, long amount) {
        long newBalance = getBalance(uuid) + amount;
        setBalance(uuid, newBalance);
        return newBalance;
    }

    public long addCoins(Player player, long amount) {
        return addCoins(player.getUniqueId(), amount);
    }

    /**
     * Take coins from a player. Returns false if insufficient funds.
     */
    public boolean takeCoins(UUID uuid, long amount) {
        long current = getBalance(uuid);
        if (current < amount) return false;
        setBalance(uuid, current - amount);
        return true;
    }

    public boolean takeCoins(Player player, long amount) {
        return takeCoins(player.getUniqueId(), amount);
    }

    // ── Top list ─────────────────────────────────────────────

    public List<Map.Entry<UUID, Long>> getTopList(int limit) {
        return balances.entrySet().stream()
            .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
            .limit(limit)
            .toList();
    }

    // ── Storage ──────────────────────────────────────────────

    private void loadAll() {
        balances.clear();
        if (dataConfig.contains("balances")) {
            for (String uuidStr : dataConfig.getConfigurationSection("balances").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    long balance = dataConfig.getLong("balances." + uuidStr, getStartingBalance());
                    balances.put(uuid, balance);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        plugin.getLogger().info("Loaded " + balances.size() + " player balances.");
    }

    public void saveAll() {
        for (Map.Entry<UUID, Long> entry : balances.entrySet()) {
            dataConfig.set("balances." + entry.getKey().toString(), entry.getValue());
        }
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save balances.yml: " + e.getMessage());
        }
    }

    private void savePlayer(UUID uuid) {
        dataConfig.set("balances." + uuid.toString(), balances.get(uuid));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try { dataConfig.save(dataFile); }
            catch (IOException e) {
                plugin.getLogger().severe("Could not save balance for " + uuid + ": " + e.getMessage());
            }
        });
    }

    private long getStartingBalance() {
        return plugin.getConfig().getLong("currency.starting-balance", 0);
    }

    // ── Player name lookup ────────────────────────────────────

    public String getPlayerName(UUID uuid) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        return op.getName() != null ? op.getName() : uuid.toString().substring(0, 8);
    }
}
