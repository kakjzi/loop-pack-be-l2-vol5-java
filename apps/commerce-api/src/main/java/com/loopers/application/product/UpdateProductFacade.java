package com.loopers.application.product;

import com.loopers.application.product.query.ProductListCacheInvalidator;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional
public class UpdateProductFacade {
    private final ProductListCacheInvalidator invalidator;
    private final ProductRepository repository;

    public ProductInfo update(long id, String name, Long price) {
        if (price == null) {
            throw new CoreException(ErrorType.INVALID_REQUEST);
        }
        Product product =
                repository
                        .findById(id)
                        .orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND));
        product.update(name, price);
        ProductInfo result = ProductInfo.from(repository.updateInformation(product));
        invalidator.afterCommit();
        return result;
    }
}
