package pl.bellmarket.currency;

import java.util.Locale;

public enum Currency {
    BELLCOINS("BellCoins", "✦"),
    VIPTOKEN("VipTokens", "✦");

    private final String displayName;
    private final String symbol;

    Currency(String displayName, String symbol) {
        this.displayName = displayName;
        this.symbol = symbol;
    }

    public String getDisplayName() { return displayName; }
    public String getSymbol()      { return symbol; }

    public static Currency parse(String raw) {
        if (raw == null || raw.isEmpty()) return BELLCOINS;
        String norm = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
        if (norm.startsWith("vip") || norm.contains("token")) return VIPTOKEN;
        return BELLCOINS;
    }
}
