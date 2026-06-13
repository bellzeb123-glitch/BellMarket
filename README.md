# BellMarket

A clean, configurable shop plugin for Minecraft 1.21+ with dual-currency support,
VIP access system, in-game price editor and native SkinStudio integration.

## Features
- 54-slot shop GUI with category navigation and purchase confirmation
- Dual currency: **BellCoins** (main) + **VIP Tokens** (premium)
- VIP category featured in the top bar, bought with VIP Tokens
- In-game price editor (`/bm prices`) and admin panel (`/bm admin`)
- Full EN / PL language support — switch with `/bm lang <en|pl>`
- Manual categories via YAML + automatic SkinStudio skin detection

## Build
```
mvn package
```
Output: `target/BellMarket-1.26.1.2.jar`

## Requirements
- Paper / Purpur 1.21+
- Java 21+
- SkinStudio (optional, for automatic skin categories)

## Pro Edition
Auto-detection for MythicMobs / EliteMobs / FreeMinecraftModels, custom GUI textures
and more are available in the **BellMarketPro** addon.

---
Author: Bellzeb
