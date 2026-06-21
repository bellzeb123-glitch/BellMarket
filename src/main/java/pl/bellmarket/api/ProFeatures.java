package pl.bellmarket.api;

import org.bukkit.command.CommandSender;
import pl.bellmarket.model.Product;

/** Optional Pro hooks registered by the BellMarketPro addon. */
public interface ProFeatures {

    long resolvePrice(Product product);

    void recordPurchase(PurchaseRecord record);

    void showStats(CommandSender sender, String[] args);

    boolean hasActiveFlashSale(Product product);
}
