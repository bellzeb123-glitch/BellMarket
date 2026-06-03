package pl.bellmarket.api;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.bellmarket.BellMarket;
import pl.bellmarket.model.Product;

import java.util.List;
import java.util.Map;

public class PurchaseProcessor {

    private final BellMarket plugin;

    public PurchaseProcessor(BellMarket plugin) {
        this.plugin = plugin;
    }

    public enum Result {
        SUCCESS,
        NOT_ENOUGH_COINS,
        PRODUCT_DISABLED,
        DELIVERY_FAILED
    }

    /**
     * Process a purchase for a player.
     */
    public Result process(Player player, Product product) {
        if (!product.isEnabled()) return Result.PRODUCT_DISABLED;

        long price = product.getPrice();

        // Check balance
        if (!plugin.getCurrency().hasEnough(player, price)) {
            return Result.NOT_ENOUGH_COINS;
        }

        // Deduct coins
        plugin.getCurrency().takeCoins(player, price);

        // Deliver product
        boolean delivered = deliver(player, product);
        if (!delivered) {
            // Refund on delivery failure
            plugin.getCurrency().addCoins(player, price);
            return Result.DELIVERY_FAILED;
        }

        // Log purchase
        if (plugin.getConfig().getBoolean("admin.log-purchases", true)) {
            plugin.getLogger().info(plugin.getLang().getRaw("admin.purchase-log",
                "player", player.getName(),
                "product", product.getName(),
                "price", String.valueOf(price)));
        }

        // Notify admin on large purchase
        long threshold = plugin.getConfig().getLong("admin.large-purchase-notify", 0);
        if (threshold > 0 && price >= threshold) {
            String msg = plugin.getLang().getRaw("prefix") +
                "&e" + player.getName() + "&7 made a large purchase: &f" + product.getName() +
                "&7 for &6" + plugin.getLang().formatAmount(price) + "&7.";
            Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("bellmarket.admin"))
                .forEach(p -> p.sendMessage(plugin.getLang().colorize(msg)));
        }

        // Play success sound
        playSound(player, "purchase-success", Sound.ENTITY_PLAYER_LEVELUP);

        return Result.SUCCESS;
    }

    private boolean deliver(Player player, Product product) {
        return switch (product.getType()) {
            case SKIN_TOKEN   -> deliverSkinToken(player, product);
            case ITEM         -> deliverItem(player, product);
            case COMMAND, MOUNT -> deliverCommands(player, product);
        };
    }

    private boolean deliverSkinToken(Player player, Product product) {
        // Check SkinStudio is available
        if (Bukkit.getPluginManager().getPlugin("SkinStudio") == null) {
            plugin.getLogger().warning("SkinStudio not found! Cannot deliver skin token for: " + product.getId());
            return false;
        }

        // Give skin token via SkinStudio command
        String skinId = product.getSkinId();
        if (skinId == null || skinId.isEmpty()) {
            plugin.getLogger().warning("No skin-id defined for product: " + product.getId());
            return false;
        }

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
            "skintoken give " + player.getName() + " " + skinId + " 1");

        // Optionally give change token
        if (product.includeChangeToken()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "skintoken giveremove " + player.getName() + " 1");
        }

        player.sendMessage(plugin.getLang().component("shop.item-given",
            "item", product.getName()));
        return true;
    }

    private boolean deliverItem(Player player, Product product) {
        ItemStack item = product.getGiveItem();
        if (item == null) {
            plugin.getLogger().warning("No item defined for product: " + product.getId());
            return false;
        }

        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            // Drop on ground if inventory full
            leftover.values().forEach(i ->
                player.getWorld().dropItemNaturally(player.getLocation(), i));
            player.sendMessage(plugin.getLang().component("shop.item-given",
                "item", product.getName()));
            player.sendMessage(plugin.getLang().colorize(
                plugin.getLang().getRaw("prefix") + "&7Your inventory was full, item dropped at your feet."));
        } else {
            player.sendMessage(plugin.getLang().component("shop.item-given",
                "item", product.getName()));
        }
        return true;
    }

    private boolean deliverCommands(Player player, Product product) {
        List<String> commands = product.getCommands();
        if (commands == null || commands.isEmpty()) {
            plugin.getLogger().warning("No commands defined for product: " + product.getId());
            return false;
        }

        for (String cmd : commands) {
            String processed = cmd.replace("{player}", player.getName())
                                  .replace("{uuid}", player.getUniqueId().toString());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processed);
        }

        player.sendMessage(plugin.getLang().component("shop.command-executed"));
        return true;
    }

    private void playSound(Player player, String configKey, Sound defaultSound) {
        try {
            String soundName = plugin.getConfig().getString("sounds." + configKey);
            Sound sound = soundName != null ? Sound.valueOf(soundName) : defaultSound;
            player.playSound(player, sound, 1f, 1f);
        } catch (Exception ignored) {
            player.playSound(player, defaultSound, 1f, 1f);
        }
    }
}
