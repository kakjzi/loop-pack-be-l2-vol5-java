package com.loopers.application.product.query;

import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductQuery {
    Optional<ProductView> find(long id);
    List<Long> findVisibleIds(List<Long> ids);
    Page<ProductView> list(Long brandId, Pageable pageable, ProductSort sort);
}
