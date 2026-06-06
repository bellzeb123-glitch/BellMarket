/*
 * BellMarket - Currency enum
 *
 * Multi-currency support for BellMarket.
 * BELLCOINS  — main currency, managed by CurrencyManager (existing storage in coins.yml)
 * VIPTOKEN   — VIP-only currency, managed by VipTokenManager (new storage in viptokens.yml)
 *
 * Adding a new currency? Add an enum value here, add a Manager class implementing
 * the same interface as CurrencyManager, and register it in BellMarket.onEnable().
 */
package pl.bellmarket.currency;

import java.util.Locale;

public enum Currency {
    BELLCOINS("BellCoins", "✦"),
    VIPTOKEN("VipTokens", "👑");

    private final String displayName;
    private final String symbol;

    Currency(String displayName, String symbol) {
        this.displayName = displayName;
        this.symbol = symbol;
    }

    public String getDisplayName() { return displayName; }
    public String getSymbol()      { return symbol; }

    /** Parse a YAML string ("bellcoins", "viptoken", "vip_token", null) → Currency. */
    public static Currency parse(String raw) {
        if (raw == null || raw.isEmpty()) return BELLCOINS; // default
        String norm = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
        if (norm.startsWith("vip") || norm.contains("token")) return VIPTOKEN;
        return BELLCOINS;
    }
}
