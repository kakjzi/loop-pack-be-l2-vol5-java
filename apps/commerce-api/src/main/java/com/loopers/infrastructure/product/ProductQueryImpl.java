package com.loopers.infrastructure.product;

import com.loopers.application.like.LikedProductQuery;
import com.loopers.application.product.query.ProductQuery;
import com.loopers.application.product.query.ProductSort;
import com.loopers.application.product.query.ProductView;
import com.loopers.infrastructure.product.ProductJpaRepository.ProductRow;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductQueryImpl implements ProductQuery, LikedProductQuery {
    private final ProductJpaRepository repository;

    @Override
    public Optional<ProductView> find(long id) {
        return repository.findVisibleById(id).map(ProductQueryImpl::toView);
    }

    @Override
    public Page<ProductView> list(Long brandId, Pageable pageable, ProductSort sort) {
        List<ProductView> items = findPageRows(brandId, pageable, sort).stream()
                .map(ProductQueryImpl::toView)
                .toList();
        // 페이지 결과로 전체 개수를 확정할 수 있으면 COUNT 조회를 생략한다.
        return PageableExecutionUtils.getPage(items, pageable, () -> countVisibleProducts(brandId));
    }

    private List<ProductRow> findPageRows(Long brandId, Pageable pageable, ProductSort sort) {
        int size = pageable.getPageSize();
        long offset = pageable.getOffset();
        if (brandId == null) {
            return switch (sort) {
                case LATEST -> repository.findLatest(size, offset);
                case PRICE_ASC -> repository.findLowestPrice(size, offset);
                case LIKES_DESC -> repository.findMostLiked(size, offset);
            };
        }
        return switch (sort) {
            case LATEST -> repository.findLatestByBrandId(brandId, size, offset);
            case PRICE_ASC -> repository.findLowestPriceByBrandId(brandId, size, offset);
            case LIKES_DESC -> repository.findMostLikedByBrandId(brandId, size, offset);
        };
    }

    private long countVisibleProducts(Long brandId) {
        return brandId == null ? repository.countVisible() : repository.countVisibleByBrandId(brandId);
    }

    @Override
    public List<ProductView> findByUser(long userId) {
        return repository.findLikedByUserId(userId).stream().map(ProductQueryImpl::toView).toList();
    }

    private static ProductView toView(ProductRow row) {
        return new ProductView(
                row.getProductId(), row.getName(), row.getPrice(),
                new ProductView.BrandView(row.getBrandId(), row.getBrandName()), row.getLikeCount());
    }
}
