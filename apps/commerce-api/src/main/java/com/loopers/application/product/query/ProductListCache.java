package com.loopers.application.product.query;

import java.util.Optional;

public interface ProductListCache {
    Optional<ProductListPage> get(ProductListKey key);
    void put(ProductListKey key, ProductListPage page);
    boolean invalidateAll();
}
