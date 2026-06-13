package pl.bellmarket.currency;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import pl.bellmarket.BellMarket;
import pl.bellmarket.event.VipTokenChangeEvent;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class VipTokenManager {

    private final BellMarket plugin;
    private final Map<UUID, Long> balances = new HashMap<>();
    private FileConfiguration dataConfig;
    private File dataFile;

    public VipTokenManager(BellMarket plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        dataFile = new File(plugin.getDataFolder(), "viptokens.yml");
        if (!dataFile.getParentFile().exists()) dataFile.getParentFile().mkdirs();
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); }
            catch (IOException e) { plugin.getLogger().warning("Could not create viptokens.yml"); }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        balances.clear();
        var sec = dataConfig.getConfigurationSection("balances");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                try { balances.put(UUID.fromString(key), sec.getLong(key)); }
                catch (IllegalArgumentException ignored) {}
            }
        }
    }

    public long getBalance(Player player) { return balances.getOrDefault(player.getUniqueId(), 0L); }
    public long getBalance(UUID uuid)     { return balances.getOrDefault(uuid, 0L); }

    public boolean hasEnough(Player player, long amount) { return getBalance(player) >= amount; }

    public void addTokens(Player player, long amount, String reason) {
        long before = getBalance(player);
        long after = before + amount;
        balances.put(player.getUniqueId(), after);
        savePlayer(player.getUniqueId());
        Bukkit.getPluginManager().callEvent(new VipTokenChangeEvent(player, before, after, reason));
    }

    public void takeTokens(Player player, long amount, String reason) {
        long before = getBalance(player);
        long after = Math.max(0, before - amount);
        balances.put(player.getUniqueId(), after);
        savePlayer(player.getUniqueId());
        Bukkit.getPluginManager().callEvent(new VipTokenChangeEvent(player, before, after, reason));
    }

    public void setBalance(Player player, long amount) {
        long before = getBalance(player);
        long after = Math.max(0, amount);
        balances.put(player.getUniqueId(), after);
        savePlayer(player.getUniqueId());
        Bukkit.getPluginManager().callEvent(new VipTokenChangeEvent(player, before, after, "set"));
    }

    private void savePlayer(UUID uuid) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            dataConfig.set("balances." + uuid, balances.get(uuid));
            try { dataConfig.save(dataFile); }
            catch (IOException e) { plugin.getLogger().warning("Could not save viptoken balance: " + e.getMessage()); }
        });
    }

    public void saveAll() {
        balances.forEach((uuid, bal) -> dataConfig.set("balances." + uuid, bal));
        try { dataConfig.save(dataFile); }
        catch (IOException ignored) {}
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
