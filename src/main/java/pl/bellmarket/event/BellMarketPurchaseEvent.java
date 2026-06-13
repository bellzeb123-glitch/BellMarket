package pl.bellmarket.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import pl.bellmarket.model.Product;

public class BellMarketPurchaseEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private boolean cancelled = false;
    private final Player player;
    private final Product product;

    public BellMarketPurchaseEvent(Player player, Product product) {
        this.player = player; this.product = product;
    }

    public Player getPlayer()   { return player; }
    public Product getProduct() { return product; }
    @Override public boolean isCancelled()       { return cancelled; }
    @Override public void setCancelled(boolean c){ cancelled = c; }
    @Override public HandlerList getHandlers()   { return HANDLERS; }
    public static HandlerList getHandlerList()   { return HANDLERS; }
}
