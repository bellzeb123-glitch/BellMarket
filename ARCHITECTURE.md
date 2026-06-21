# BellMarket — Architecture

## Overview

Universal premium shop plugin for Paper 1.21+ with auto-detection for SkinStudio, BellItems, MythicMobs, EliteMobs, and FreeMinecraftModels. Supports dual currencies (BellCoins and VIP Tokens), category-based YAML product definitions, and a pluggable provider system.

**Main class:** `pl.bellmarket.BellMarket`
**Version:** 1.26.1.2 · **Author:** Bellzeb

## Product Provider System

`ProductProviderRegistry` manages all product sources. Providers generate `Category` objects containing products that are merged into the shop catalog.

### Built-in Providers
| Provider | Source | Registration |
|----------|--------|--------------|
| `SkinStudioProvider` | SkinStudio plugin configs | Always registered |
| `BellItemsProvider` | BellItems custom items | Registered if BellItems is present |

### Pro Providers (via BellMarket-Pro)
MythicMobs, EliteMobs, and FreeMinecraftModels providers are registered by the Pro addon through `ProProviderManager`.

### Provider Lifecycle
1. `providerRegistry.register(provider)` — adds a provider.
2. `providerRegistry.generateAll()` — each provider produces `List<Category>`.
3. `categoryManager.addProviderCategories(generated)` — merged with YAML categories.
4. SkinStudio late-init: a deferred `runTask` retries generation on the next tick in case SkinStudio hasn't finished writing its config yet.

## Currency System

### BellCoins (`CurrencyManager`)
Primary currency. Flat-file storage with balance, give, take, set, and top operations.

### VIP Tokens (`VipTokenManager`)
Secondary premium currency. Same storage pattern, separate balances.

Both managers save on disable and support `reload()`.

## Purchase Flow

1. Player opens shop via `/bellmarket` (or `/bm`, `/shop`).
2. `ShopGUI` renders categories from `CategoryManager`.
3. Player selects a product; price is resolved via `getEffectivePrice(product)`.
   - If Pro is active, `ProFeatures.resolvePrice()` may apply flash-sale discounts or dynamic pricing.
   - Otherwise, the product's base price is used.
4. Currency is deducted; item/command reward is executed.

## Admin GUI

`AdminGUI` (accessed via `/bellmarket admin`) provides:
- Category and product browsing.
- Price editor (`PriceEditor` — registered as a listener for chat-based input).
- Provider sync controls.

`AdminChatListener` captures chat input for admin editing flows.

## Pro Extension Points

BellMarket exposes two hooks for the Pro addon:
- **`setTitleTransformer(Function<String, Component>)`** — overrides GUI title rendering (e.g., custom backgrounds via resource packs).
- **`setProFeatures(ProFeatures)`** — injects a `ProFeatures` implementation that intercepts price resolution, statistics, and flash sales.

`BellMarketAPI.init(plugin, registry)` publishes a static API for external access to the provider registry.

## Commands

| Command | Aliases | Usage |
|---------|---------|-------|
| `/bellmarket` | `/bm`, `/shop` | `/bellmarket [admin\|reload\|generate\|lang\|prices]` |
| `/bellcoins` | `/bc`, `/coins` | `/bellcoins <balance\|give\|take\|set\|top> [player] [amount]` |
| `/vt` | `/viptokens`, `/vtoken` | `/vt <balance\|give\|take\|set\|top> [player] [amount]` |

## Permissions

| Permission | Default | Description |
|------------|---------|-------------|
| `bellmarket.shop` | `true` | Open the shop |
| `bellmarket.admin` | `op` | Admin commands, panel, price editor |
| `bellmarket.vip` | `false` | See and buy VIP-exclusive products |
| `bellmarket.coins.balance` | `true` | Check own BellCoins |
| `bellmarket.coins.balance.others` | `op` | Check others' BellCoins |
| `bellmarket.coins.give` | `op` | Give BellCoins |
| `bellmarket.coins.take` | `op` | Take BellCoins |
| `bellmarket.coins.set` | `op` | Set BellCoins |
| `bellmarket.coins.top` | `true` | BellCoins leaderboard |
| `bellmarket.viptoken.balance` | `true` | Check own VIP Tokens |
| `bellmarket.viptoken.balance.others` | `op` | Check others' VIP Tokens |
| `bellmarket.viptoken.admin` | `op` | Give/take/set VIP Tokens |
| `bellmarket.viptoken.top` | `true` | VIP Tokens leaderboard |

## Soft Dependencies

SkinStudio, BellLP — plugin loads without them but enables respective features when present.

## Listeners

| Listener | Purpose |
|----------|---------|
| `PlayerListener` | Join/quit, balance init |
| `AdminChatListener` | Captures chat for admin editing flows |
| `PriceEditor` | Chat-based price input |
| `ProviderSyncListener` | Reacts to external plugin events to re-sync providers |
