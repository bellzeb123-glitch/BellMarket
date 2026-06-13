package pl.bellmarket.provider;

import pl.bellmarket.model.Category;
import java.util.Collections;
import java.util.List;

public interface ProductProvider {
    String getProviderId();
    boolean isAvailable();

    default List<Category> generateCategories(long defaultPrice) {
        return Collections.emptyList();
    }

    default String describe() {
        return getProviderId() + " (" + (isAvailable() ? "available" : "unavailable") + ")";
    }
}
