package pl.bellmarket.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import java.util.UUID;

public class VipTokenChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID playerUUID;
    private final long oldBalance;
    private final long newBalance;
    private final String reason;

    public VipTokenChangeEvent(UUID playerUUID, long oldBalance, long newBalance, String reason) {
        this.playerUUID = playerUUID; this.oldBalance = oldBalance;
        this.newBalance = newBalance; this.reason = reason;
    }
    public UUID   getPlayerUUID() { return playerUUID; }
    public long   getOldBalance() { return oldBalance; }
    public long   getNewBalance() { return newBalance; }
    public String getReason()     { return reason; }
    @Override public HandlerList getHandlers()  { return HANDLERS; }
    public static HandlerList getHandlerList()  { return HANDLERS; }
}
