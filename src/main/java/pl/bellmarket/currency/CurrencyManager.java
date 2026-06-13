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
    private FileConfiguration dataConfig;
    private File dataFile;

    public CurrencyManager(BellMarket plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        dataFile = new File(plugin.getDataFolder(), "balances.yml");
        if (!dataFile.getParentFile().exists()) dataFile.getParentFile().mkdirs();
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); }
            catch (IOException e) { plugin.getLogger().severe("Could not create balances.yml: " + e.getMessage()); }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        loadAll();
    }

    private void loadAll() {
        balances.clear();
        var sec = dataConfig.getConfigurationSection("balances");
        if (sec == null) { plugin.getLogger().info("Loaded 0 player balances."); return; }
        for (String key : sec.getKeys(false)) {
            try { balances.put(UUID.fromString(key), sec.getLong(key)); }
            catch (IllegalArgumentException ignored) {}
        }
        plugin.getLogger().info("Loaded " + balances.size() + " player balances.");
    }

    private long getStartingBalance() {
        return plugin.getConfig().getLong("currency.starting-balance", 0);
    }

    public long getBalance(Player player) {
        return balances.getOrDefault(player.getUniqueId(), getStartingBalance());
    }

    public boolean hasEnough(Player player, long amount) {
        return getBalance(player) >= amount;
    }

    public void addCoins(Player player, long amount, String reason) {
        long current = getBalance(player);
        setBalance(player, current + amount);
    }

    public void takeCoins(Player player, long amount, String reason) {
        long current = getBalance(player);
        setBalance(player, Math.max(0, current - amount));
    }

    public void setBalance(Player player, long amount) {
        balances.put(player.getUniqueId(), Math.max(0, amount));
        savePlayer(player.getUniqueId());
    }

    private void savePlayer(UUID uuid) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            dataConfig.set("balances." + uuid, balances.get(uuid));
            try { dataConfig.save(dataFile); }
            catch (IOException e) { plugin.getLogger().warning("Could not save balance for " + uuid + ": " + e.getMessage()); }
        });
    }

    public void saveAll() {
        for (Map.Entry<UUID, Long> entry : balances.entrySet()) {
            dataConfig.set("balances." + entry.getKey(), entry.getValue());
        }
        try { dataConfig.save(dataFile); }
        catch (IOException e) { plugin.getLogger().warning("Could not save balances.yml: " + e.getMessage()); }
    }

    public List<Map.Entry<UUID, Long>> getTopList(int limit) {
        return balances.entrySet().stream()
            .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
            .limit(limit).toList();
    }

    public String getPlayerName(UUID uuid) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        String name = op.getName();
        return name != null ? name : uuid.toString().substring(0, 8);
    }
}
