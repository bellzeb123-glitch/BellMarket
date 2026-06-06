/*
 * BellMarket - ProductProvider
 *
 * The contract that any integration (SkinStudio, MythicMobs, EliteMobs,
 * FreeMinecraftModels, ItemsAdder, Nexo, BellMounts...) implements to feed
 * products into BellMarket's shop.
 *
 * BellMarket runs every registered provider whose:
 *   - getProviderId() is enabled in config.yml under providers.<id>.enabled
 *   - isAvailable() returns true (i.e. the underlying plugin is loaded)
 *
 * Providers are typically registered in BellMarket.onEnable() (built-in) or
 * in an external plugin's onEnable() via BellMarketAPI.getProviderRegistry()
 * .register(...).
 */
package pl.bellmarket.provider;

import pl.bellmarket.model.Category;
import pl.bellmarket.model.Product;

import java.util.List;

public interface ProductProvider {

    /**
     * Stable identifier used in config (e.g. "skinstudio", "mythicmobs",
     * "bellmounts"). Letters, digits, underscore.
     */
    String getProviderId();

    /**
     * Whether the underlying plugin/data source is present and ready.
     * SkinStudioProvider returns true if SkinStudio plugin is loaded,
     * for example. If false, this provider is skipped entirely.
     */
    boolean isAvailable();

    /**
     * Generates the category and its products. Called once at startup and
     * once per /bellmarket reload. Implementations should be cheap-ish to
     * call repeatedly — heavy parsing should be cached internally.
     *
     * Returning null OR a Category with empty product list is allowed and
     * results in nothing being shown for this provider.
     *
     * @param defaultPrice price suggestion from config (provider may ignore)
     * @return generated category or null
     */
    Category generateCategory(long defaultPrice);

    /**
     * Optional: a human-readable description of what this provider does.
     * Shown in admin GUI / `/bellmarket providers` listing.
     */
    default String describe() {
        return getProviderId() + " (" + (isAvailable() ? "available" : "unavailable") + ")";
    }
}
