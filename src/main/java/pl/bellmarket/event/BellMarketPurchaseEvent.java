/*
 * BellMarket - BellMarketPurchaseEvent
 *
 * Fired AFTER a player successfully purchases a product.
 * Cancellable: if a listener cancels it, the purchase is rolled back
 * (currency refunded, no delivery).
 *
 * Listeners get the player, product, currency used, amount paid.
 * Used by BellDiscord (webhook), statistics plugins, audit logs.
 */
package pl.bellmarket.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.model.Product;

public class BellMarketPurchaseEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Product product;
    private final Currency currency;
    private final long amountPaid;
    private boolean cancelled = false;

    public BellMarketPurchaseEvent(Player player, Product product, Currency currency, long amountPaid) {
        this.player = player;
        this.product = product;
        this.currency = currency;
        this.amountPaid = amountPaid;
    }

    public Player getPlayer()     { return player; }
    public Product getProduct()   { return product; }
    public Currency getCurrency() { return currency; }
    public long getAmountPaid()   { return amountPaid; }

    @Override public boolean isCancelled()      { return cancelled; }
    @Override public void setCancelled(boolean b) { this.cancelled = b; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
