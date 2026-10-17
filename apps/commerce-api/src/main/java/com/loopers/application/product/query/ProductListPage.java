package com.loopers.application.product.query;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

public record ProductListPage(List<ProductView> items, long total) {
    public ProductListPage {
        items = List.copyOf(items);
        if (total < items.size()) {
            throw new IllegalArgumentException("목록 전체 개수가 결과보다 작습니다.");
        }
    }

    public static ProductListPage from(Page<ProductView> page) {
        return new ProductListPage(page.getContent(), page.getTotalElements());
    }

    public Page<ProductView> toPage(Pageable pageable) {
        return new PageImpl<>(items, pageable, total);
    }
}
