package pl.bellmarket.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import pl.bellmarket.currency.Currency;
import pl.bellmarket.model.Product;

public class BellMarketPurchaseEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private boolean cancelled;
    private final Player player;
    private final Product product;
    private final Currency currency;
    private final long price;

    public BellMarketPurchaseEvent(Player player, Product product, Currency currency, long price) {
        this.player = player; this.product = product; this.currency = currency; this.price = price;
    }
    public Player   getPlayer()   { return player; }
    public Product  getProduct()  { return product; }
    public Currency getCurrency() { return currency; }
    public long     getPrice()    { return price; }
    @Override public boolean isCancelled()           { return cancelled; }
    @Override public void    setCancelled(boolean c) { this.cancelled = c; }
    @Override public HandlerList getHandlers()       { return HANDLERS; }
    public static HandlerList getHandlerList()       { return HANDLERS; }
}
