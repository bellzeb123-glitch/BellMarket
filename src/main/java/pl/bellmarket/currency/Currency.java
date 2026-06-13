package pl.bellmarket.currency;

public enum Currency {
    BELLCOINS("BellCoins", "✦"),
    VIPTOKEN("VIP Tokens", "◈");

    private final String displayName;
    private final String symbol;

    Currency(String displayName, String symbol) {
        this.displayName = displayName;
        this.symbol = symbol;
    }

    public String getDisplayName() { return displayName; }
    public String getSymbol()      { return symbol; }
}
