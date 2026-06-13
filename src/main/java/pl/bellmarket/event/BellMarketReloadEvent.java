/*
 * BellMarket - BellMarketReloadEvent
 *
 * Fired during /bellmarket reload, AFTER categories are cleared and BEFORE
 * built-in providers run. External plugins (BellMounts, BellVIP, BellMarket-Pro)
 * use this hook to re-register their providers if they were unloaded.
 *
 * Listeners should NOT mutate the registry directly inside the event handler —
 * the recommended pattern is to call BellMarketAPI.getProviderRegistry()
 * .register(provider) here.
 */
package pl.bellmarket.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class BellMarketReloadEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    public enum Phase {
        /** Before built-in providers run. External plugins re-register their providers here. */
        PRE_PROVIDERS,
        /** After all providers finished generating products. Statistics/cache plugins refresh here. */
        POST_PROVIDERS
    }

    private final Phase phase;

    public BellMarketReloadEvent(Phase phase) {
        this.phase = phase;
    }

    public Phase getPhase() { return phase; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
