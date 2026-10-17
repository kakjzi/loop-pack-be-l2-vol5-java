package com.loopers.application.product.query;

public record ProductListKey(Long brandId, ProductSort sort, int page, int size) {
    public String value() {
        return "product-list:v1:brand:" + (brandId == null ? "all" : brandId)
                + ":sort:" + sort.name() + ":page:" + page + ":size:" + size;
    }
}
