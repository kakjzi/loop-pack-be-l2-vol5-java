package com.loopers.application.brand;

import com.loopers.application.product.query.ProductListCacheInvalidator;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional
public class UpdateBrandFacade {
    private final ProductListCacheInvalidator invalidator;
    private final BrandRepository repository;

    public BrandInfo update(long id, String name) {
        Brand brand =
                repository
                        .findByIdForUpdate(id)
                        .orElseThrow(() -> new CoreException(ErrorType.BRAND_NOT_FOUND));
        brand.rename(name);
        BrandInfo result = BrandInfo.from(repository.save(brand));
        invalidator.afterCommit();
        return result;
    }
}
