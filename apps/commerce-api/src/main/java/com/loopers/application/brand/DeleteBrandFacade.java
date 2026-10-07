package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional
public class DeleteBrandFacade {
    private final BrandRepository repository;
    private final ProductRepository products;

    public void delete(long id) {
        Brand brand =
                repository
                        .findByIdForUpdate(id)
                        .orElseThrow(() -> new CoreException(ErrorType.BRAND_NOT_FOUND));
        for (long productId : products.findActiveIdsByBrandId(id)) {
            products.delete(productId);
        }
        brand.delete();
        repository.save(brand);
    }
}
