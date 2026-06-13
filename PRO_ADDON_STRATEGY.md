# BellMarket Pro — Strategia Addona

## Zmiana strategii (od wersji 1.26.1.2)

BellMarket Pro **nie jest** oddzielnym niezależnym pluginem.
Jest **addonem** do BellMarket Free — wymaga go jako zależności.

## Dlaczego addon a nie oddzielny plugin

- Gracz instaluje BellMarket Free (darmowy) → działa od razu
- Gracz dokupuje BellMarket Pro → wgrywa JEDEN dodatkowy jar obok
- Pro rozszerza Free przez eventy i API — brak duplikacji kodu
- Aktualizacje Free nie psują Pro (API jest stabilne)

## Struktura

```
BellMarket-1.26.1.2.jar       ← Free (darmowy, BuiltByBit)
BellMarket-Pro-1.26.1.2.jar  ← Pro addon (płatny $20-25 USD)
```

## plugin.yml Pro

```yaml
name: BellMarketPro
version: '1.26.1.2'
main: pl.bellmarket.pro.BellMarketPro
api-version: '1.21'
depend: [BellMarket]   # ← wymagany Free
description: BellMarket Pro addon with auction house statistics and more
```

## Główna klasa Pro

```java
package pl.bellmarket.pro;

public class BellMarketPro extends JavaPlugin {
    @Override
    public void onEnable() {
        // FUTURE: license check goes here, before any feature init
        // if (!LicenseValidator.validate(this)) { disable(); return; }

        // Pobierz API z Free
        if (!BellMarketAPI.isReady()) {
            getLogger().severe("BellMarket Free not found! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Rejestruj dodatkowych providerów
        BellMarketAPI.get().getProviderRegistry().register(new ProAuctionProvider(this));

        // Nasłuchuj zdarzeń Free
        getServer().getPluginManager().registerEvents(new ProPurchaseListener(this), this);
    }
}
```

## Co Pro importuje z Free

TYLKO z pakietów publicznego API:
- `pl.bellmarket.api.*`
- `pl.bellmarket.event.*`
- `pl.bellmarket.model.*` (Category, Product)
- `pl.bellmarket.currency.Currency`

NIGDY:
- `pl.bellmarket.gui.*`
- `pl.bellmarket.config.*`
- `pl.bellmarket.command.*`
- `pl.bellmarket.listener.*`
- Główna klasa BellMarket.java

## Funkcje planowane w Pro (Sesja 4)

- Dom aukcyjny (sprzedaż gracz ↔ gracz)
- Statystyki sprzedaży + rankingi
- Produkty czasowe (flash sale, odliczanie)
- Lista życzeń + alerty o spadku ceny
- Webhook Discord przy zakupie
- Replikacja MySQL (ekonomia multi-serwer)
- Bulk import/export kategorii
- Własne tło GUI (tekstura drewnianej skrzynki)
