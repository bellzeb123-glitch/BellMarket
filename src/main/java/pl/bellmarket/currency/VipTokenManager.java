/*
 * BellMarket - VipTokenManager
 *
 * Parallel currency manager for VIP Tokens. Separate from CurrencyManager
 * to:
 *   - keep BellCoins storage untouched (no migration risk)
 *   - allow VIP token storage to be cleared independently
 *   - allow different persistence strategy if needed in future
 *
 * Storage: plugins/BellMarket/viptokens.yml (flat YAML, UUID → long).
 *
 * Public API is mirrored on CurrencyManager so anyone reading code finds
 * familiar method names: getBalance, hasEnough, addCoins, takeCoins, setBalance.
 *
 * Fires VipTokenChangeEvent on every mutation so BellDiscord/BellVIP can react.
 */
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
    private File dataFile;
    private FileConfiguration dataConfig;

    public VipTokenManager(BellMarket plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        balances.clear();
        dataFile = new File(plugin.getDataFolder(), "viptokens.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.getParentFile().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create viptokens.yml: " + e.getMessage());
                return;
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        for (String key : dataConfig.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                balances.put(id, dataConfig.getLong(key, 0L));
            } catch (IllegalArgumentException ignored) {
                // skip malformed key
            }
        }
    }

    public long getBalance(UUID id) {
        return balances.getOrDefault(id, 0L);
    }

    public long getBalance(Player player) {
        return getBalance(player.getUniqueId());
    }

    public boolean hasEnough(UUID id, long amount) {
        return getBalance(id) >= amount;
    }

    public boolean hasEnough(Player player, long amount) {
        return hasEnough(player.getUniqueId(), amount);
    }

    public long addCoins(UUID id, long amount, String reason) {
        long oldBalance = getBalance(id);
        long newBalance = oldBalance + amount;
        balances.put(id, newBalance);
        savePlayer(id);
        fire(id, oldBalance, newBalance, reason);
        return newBalance;
    }

    public long addCoins(Player player, long amount, String reason) {
        return addCoins(player.getUniqueId(), amount, reason);
    }

    public boolean takeCoins(UUID id, long amount, String reason) {
        long oldBalance = getBalance(id);
        if (oldBalance < amount) return false;
        long newBalance = oldBalance - amount;
        balances.put(id, newBalance);
        savePlayer(id);
        fire(id, oldBalance, newBalance, reason);
        return true;
    }

    public boolean takeCoins(Player player, long amount, String reason) {
        return takeCoins(player.getUniqueId(), amount, reason);
    }

    public void setBalance(UUID id, long amount, String reason) {
        long oldBalance = getBalance(id);
        balances.put(id, Math.max(0L, amount));
        savePlayer(id);
        fire(id, oldBalance, getBalance(id), reason);
    }

    public void setBalance(Player player, long amount, String reason) {
        setBalance(player.getUniqueId(), amount, reason);
    }

    public List<Map.Entry<UUID, Long>> getTopList(int limit) {
        List<Map.Entry<UUID, Long>> sorted = new ArrayList<>(balances.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return sorted.subList(0, Math.min(limit, sorted.size()));
    }

    public void loadAll() { reload(); }

    public void saveAll() {
        if (dataConfig == null || dataFile == null) return;
        for (Map.Entry<UUID, Long> e : balances.entrySet()) {
            dataConfig.set(e.getKey().toString(), e.getValue());
        }
        try { dataConfig.save(dataFile); } catch (IOException ex) {
            plugin.getLogger().severe("Could not save viptokens.yml: " + ex.getMessage());
        }
    }

    public void savePlayer(UUID id) {
        if (dataConfig == null) return;
        dataConfig.set(id.toString(), balances.get(id));
        try { dataConfig.save(dataFile); } catch (IOException ex) {
            plugin.getLogger().severe("Could not save viptokens.yml: " + ex.getMessage());
        }
    }

    private void fire(UUID id, long oldBal, long newBal, String reason) {
        if (oldBal == newBal) return;
        OfflinePlayer p = Bukkit.getOfflinePlayer(id);
        VipTokenChangeEvent ev = new VipTokenChangeEvent(p, oldBal, newBal, reason);
        Bukkit.getPluginManager().callEvent(ev);
    }
}
