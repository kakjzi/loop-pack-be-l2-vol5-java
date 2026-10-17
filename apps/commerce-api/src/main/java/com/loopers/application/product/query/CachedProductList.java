package com.loopers.application.product.query;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class CachedProductList {
    private final ProductQuery query;
    private final ProductListCache cache;
    @Value("${product-list-cache.enabled:true}") private boolean enabled;

    public Page<ProductView> list(Long brandId, Pageable pageable, ProductSort sort) {
        // 쓰기 TX의 미커밋 결과를 게시하거나, 자신의 변경을 캐시로 가리지 않는다.
        if (!enabled || (TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly())) {
            return query.list(brandId, pageable, sort);
        }
        ProductListKey key = new ProductListKey(brandId, sort, pageable.getPageNumber(), pageable.getPageSize());
        var cached = cache.get(key);
        if (cached.isPresent()) {
            ProductListPage page = cached.get();
            var ids = page.items().stream().map(ProductView::productId).toList();
            // 삭제 무효화 실패와 늦은 재삽입에서도 상품/브랜드 비노출을 지킨다.
            if (ids.isEmpty() || query.findVisibleIds(ids).size() == ids.size()) {
                return page.toPage(pageable);
            }
        }
        Page<ProductView> page = query.list(brandId, pageable, sort);
        cache.put(key, ProductListPage.from(page));
        return page;
    }
}
