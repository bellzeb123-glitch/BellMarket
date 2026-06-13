package pl.bellmarket.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class VipTokenChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final long before, after;
    private final String reason;

    public VipTokenChangeEvent(Player player, long before, long after, String reason) {
        this.player = player; this.before = before; this.after = after; this.reason = reason;
    }

    public Player getPlayer() { return player; }
    public long getBefore()   { return before; }
    public long getAfter()    { return after; }
    public long getDelta()    { return after - before; }
    public String getReason() { return reason; }
    @Override public HandlerList getHandlers()  { return HANDLERS; }
    public static HandlerList getHandlerList()  { return HANDLERS; }
}
