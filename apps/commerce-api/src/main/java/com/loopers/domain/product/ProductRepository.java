package com.loopers.domain.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    Optional<Product> findById(long id);

    Product save(Product product);

    Page<Product> findAll(Pageable pageable);

    List<Long> findActiveIdsByBrandId(long brandId);

    void delete(long id);

    void deductStock(long id, int quantity);

    Product setStock(long id, int stock);

    Product updateInformation(Product product);
}
