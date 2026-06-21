package pl.bellmarket.api;

import org.bukkit.entity.Player;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.model.Product;

public record PurchaseRecord(Player player, Product product, long pricePaid, Currency currency) {}
