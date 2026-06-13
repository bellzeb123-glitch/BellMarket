package pl.bellmarket.currency;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import pl.bellmarket.BellMarket;
import pl.bellmarket.event.VipTokenChangeEvent;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public class VipTokenManager {

    private final BellMarket plugin;
    private final Map<UUID, Long> balances = new HashMap<>();
    private File dataFile;
    private FileConfiguration dataConfig;

    public VipTokenManager(BellMarket plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        saveAll();
        balances.clear();
        dataFile = new File(plugin.getDataFolder(), "viptokens.yml");
        dataFile.getParentFile().mkdirs();
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); }
            catch (IOException e) { plugin.getLogger().severe("Could not create viptokens.yml: " + e.getMessage()); }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        loadAll();
    }

    private void loadAll() {
        for (String key : dataConfig.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                balances.put(uuid, dataConfig.getLong(key));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public long getBalance(UUID uuid) {
        return balances.getOrDefault(uuid, 0L);
    }

    public boolean hasEnough(UUID uuid, long amount) {
        return getBalance(uuid) >= amount;
    }

    public void setBalance(UUID uuid, long amount, String reason) {
        long oldBal = getBalance(uuid);
        long newBal = Math.max(0, amount);
        balances.put(uuid, newBal);
        fire(uuid, oldBal, newBal, reason);
        savePlayer(uuid, newBal);
    }

    public void addCoins(UUID uuid, long amount, String reason) {
        setBalance(uuid, getBalance(uuid) + amount, reason);
    }

    public void takeCoins(UUID uuid, long amount, String reason) {
        setBalance(uuid, getBalance(uuid) - amount, reason);
    }

    private void fire(UUID uuid, long oldBal, long newBal, String reason) {
        VipTokenChangeEvent event = new VipTokenChangeEvent(uuid, oldBal, newBal, reason);
        Bukkit.getPluginManager().callEvent(event);
    }

    private void savePlayer(UUID uuid, long balance) {
        dataConfig.set(uuid.toString(), balance);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try { dataConfig.save(dataFile); }
            catch (IOException e) { plugin.getLogger().log(Level.WARNING, "Could not save viptokens", e); }
        });
    }

    public void saveAll() {
        if (dataConfig == null) return;
        balances.forEach((uuid, bal) -> dataConfig.set(uuid.toString(), bal));
        try { dataConfig.save(dataFile); }
        catch (IOException e) { plugin.getLogger().log(Level.WARNING, "Could not save viptokens", e); }
    }

    public List<Map.Entry<UUID, Long>> getTopList(int limit) {
        List<Map.Entry<UUID, Long>> sorted = new ArrayList<>(balances.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return sorted.subList(0, Math.min(limit, sorted.size()));
    }

    public String getPlayerName(UUID uuid) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        String name = op.getName();
        return name != null ? name : uuid.toString().substring(0, 8);
    }
}
