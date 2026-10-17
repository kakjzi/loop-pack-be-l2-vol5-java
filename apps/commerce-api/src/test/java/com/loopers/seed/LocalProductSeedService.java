package com.loopers.seed;

import com.loopers.application.brand.fixture.BrandFixture;
import com.loopers.application.like.fixture.LikeFixture;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.infrastructure.user.fixture.UserFixture;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class LocalProductSeedService {
    private static final long SEED = 20261012L;
    private static final int BRAND_COUNT = 2;
    private static final int PRODUCTS_PER_BRAND = 5_000;
    private static final int LIKED_SAMPLE_SIZE = 100;

    private final BrandFixture brands;
    private final ProductFixture products;
    private final UserFixture users;
    private final LikeFixture likes;

    public LocalProductSeedService(BrandFixture brands, ProductFixture products, UserFixture users, LikeFixture likes) {
        this.brands = brands;
        this.products = products;
        this.users = users;
        this.likes = likes;
    }

    @Transactional
    public SeedResult seed() {
        List<Long> userIds = users.createUsers(4);
        List<SeedBrand> result = new ArrayList<>();
        int insertedLikes = 0;
        for (int index = 0; index < BRAND_COUNT; index++) {
            var brand = brands.createBrand("week4-seed-brand-" + (index + 1));
            var saved = products.createProducts(brand.getId(), PRODUCTS_PER_BRAND, SEED + index);
            products.createdAtForBrand(brand.getId(),
                    ZonedDateTime.parse("2026-10-01T00:00:00+09:00").plusDays(index));
            // 작은 표본에 0~4개 좋아요를 넣고, 나머지는 0개로 둔다. 1·2·3개 동점과 인기 상품을 포함한다.
            for (int sample = 0; sample < LIKED_SAMPLE_SIZE; sample++) {
                int count = sample == 0 ? userIds.size() : sample % userIds.size();
                for (int user = 0; user < count; user++) {
                    likes.createLike(userIds.get(user), saved.get(sample).getId());
                    insertedLikes++;
                }
            }
            result.add(new SeedBrand(brand.getId(), saved.size(),
                    "http://localhost:8080/api/v1/products?brandId=" + brand.getId()
                            + "&sort=price_asc&page=0&size=20"));
        }
        return new SeedResult(SEED, BRAND_COUNT * PRODUCTS_PER_BRAND, insertedLikes, result);
    }

    public record SeedResult(long seed, int insertedProducts, int insertedLikes, List<SeedBrand> brands) {}

    public record SeedBrand(long brandId, int products, String postmanUrl) {}
}
