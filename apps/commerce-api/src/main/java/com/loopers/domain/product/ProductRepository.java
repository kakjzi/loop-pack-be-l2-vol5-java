package com.loopers.domain.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface ProductRepository {
    Optional<Product> findById(long id);

    Product save(Product product);

    Page<Product> findAll(Pageable pageable);
}
