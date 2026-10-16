package com.loopers.infrastructure.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.reset;

import com.loopers.application.brand.DeleteBrandFacade;
import com.loopers.application.brand.UpdateBrandFacade;
import com.loopers.application.brand.fixture.BrandFixture;
import com.loopers.application.like.CreateLikeFacade;
import com.loopers.application.like.DeleteLikeFacade;
import com.loopers.application.product.CreateProductFacade;
import com.loopers.application.product.DeleteProductFacade;
import com.loopers.application.product.GetProductFacade;
import com.loopers.application.product.UpdateProductFacade;
import com.loopers.application.product.query.ProductListKey;
import com.loopers.application.product.query.ProductQuery;
import com.loopers.application.product.query.ProductSort;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.infrastructure.user.fixture.UserFixture;
import com.loopers.utils.DatabaseCleanUp;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
abstract class ProductCacheTestSupport {
    @Autowired protected GetProductFacade facade;
    @Autowired protected CreateProductFacade createProduct;
    @Autowired protected UpdateProductFacade updateProduct;
    @Autowired protected DeleteProductFacade deleteProduct;
    @Autowired protected UpdateBrandFacade updateBrand;
    @Autowired protected DeleteBrandFacade deleteBrand;
    @Autowired protected CreateLikeFacade createLike;
    @Autowired protected DeleteLikeFacade deleteLike;
    @Autowired protected ProductFixture products;
    @Autowired protected BrandFixture brands;
    @Autowired protected UserFixture users;
    @Autowired protected EntityManagerFactory entityManagerFactory;
    @Autowired protected DatabaseCleanUp cleanUp;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected MockMvc mvc;
    @Autowired @Qualifier("productListRedisTemplate") protected StringRedisTemplate redis;
    @MockitoSpyBean protected ProductQuery query;
    @MockitoSpyBean protected RedisProductListCache cache;

    @BeforeEach
    void clearCache() {
        assertThat(cache.invalidateAll()).isTrue();
    }

    @AfterEach
    void cleanup() {
        reset(query, cache);
        assertThat(cache.invalidateAll()).isTrue();
        cleanUp.deleteAllEntities();
    }

    protected Product product() {
        return products.createProduct(brands.createBrand("캐시 브랜드").getId(), "상품", 1_000, 5);
    }

    protected ProductListKey key(Long brandId, String sort, int page, int size) {
        return new ProductListKey(brandId, ProductSort.from(sort), page, size);
    }

    protected void expire(ProductListKey key) {
        // 실설정 25~30초는 별도 검증. 실패/경쟁 테스트에서는 실제 Redis 만료만 가속한다.
        assertThat(redis.expire(key.value(), Duration.ofMillis(1))).isTrue();
        await().atMost(Duration.ofSeconds(3)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key.value())));
    }

    protected Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    protected TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }
}
