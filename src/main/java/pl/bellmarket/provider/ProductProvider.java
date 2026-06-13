package pl.bellmarket.provider;

import pl.bellmarket.model.Category;
import java.util.List;

public interface ProductProvider {
    String getProviderId();
    boolean isAvailable();
    List<Category> generateCategories(long defaultPrice);
}
