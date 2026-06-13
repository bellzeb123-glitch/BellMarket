/*
 * BellMarket - VipTokenChangeEvent
 *
 * Fired whenever VIP token balance changes for a player.
 * Listeners (BellDiscord, BellVIP, statistics plugins) can react —
 * e.g. send DM "you received 1 VIP token from your subscription renewal".
 */
package pl.bellmarket.event;

import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class VipTokenChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final OfflinePlayer player;
    private final long oldBalance;
    private final long newBalance;
    private final String reason;

    public VipTokenChangeEvent(OfflinePlayer player, long oldBalance, long newBalance, String reason) {
        this.player = player;
        this.oldBalance = oldBalance;
        this.newBalance = newBalance;
        this.reason = reason != null ? reason : "unknown";
    }

    public OfflinePlayer getPlayer() { return player; }
    public long getOldBalance()      { return oldBalance; }
    public long getNewBalance()      { return newBalance; }
    public long getDelta()           { return newBalance - oldBalance; }
    public String getReason()        { return reason; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
