/*
 * BellMarket - ProductProvider
 *
 * SESJA-1.1: changed return type to List<Category> so providers like
 * SkinStudioProvider can produce MULTIPLE categories (one per tier).
 *
 * Old contract: Category generateCategory(long defaultPrice).
 * New contract: List<Category> generateCategories(long defaultPrice).
 */
package pl.bellmarket.provider;

import pl.bellmarket.model.Category;

import java.util.Collections;
import java.util.List;

public interface ProductProvider {

    /** Stable identifier used in config (e.g. "skinstudio", "mythicmobs"). */
    String getProviderId();

    /** Whether the underlying plugin is loaded and ready. */
    boolean isAvailable();

    /**
     * Generates one or more categories with their products. Called once at
     * startup and once per /bellmarket reload.
     *
     * Returning null or empty list = nothing shown for this provider.
     */
    List<Category> generateCategories(long defaultPrice);

    /** Optional human-readable description. */
    default String describe() {
        return getProviderId() + " (" + (isAvailable() ? "available" : "unavailable") + ")";
    }

    /** Convenience helper for providers that produce exactly one category. */
    static List<Category> single(Category cat) {
        return cat == null ? Collections.emptyList() : List.of(cat);
    }
}
