package pl.bellmarket.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class BellMarketReloadEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    public enum Phase { PRE_PROVIDERS, POST_PROVIDERS }
    private final Phase phase;

    public BellMarketReloadEvent(Phase phase) { this.phase = phase; }
    public Phase getPhase()                   { return phase; }
    @Override public HandlerList getHandlers(){ return HANDLERS; }
    public static HandlerList getHandlerList(){ return HANDLERS; }
}
