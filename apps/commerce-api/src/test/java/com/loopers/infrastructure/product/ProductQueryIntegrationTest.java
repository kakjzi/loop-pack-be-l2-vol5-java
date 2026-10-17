package com.loopers.infrastructure.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.brand.fixture.BrandFixture;
import com.loopers.application.product.query.ProductQuery;
import com.loopers.application.product.query.ProductSort;
import com.loopers.domain.brand.Brand;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.utils.DatabaseCleanUp;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
class ProductQueryIntegrationTest {
    @Autowired private ProductQuery query;
    @Autowired private ProductFixture products;
    @Autowired private BrandFixture brands;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private DatabaseCleanUp cleanUp;

    private Brand brand;
    private Statistics statistics;

    @BeforeEach
    void 상품_3개를_준비한다() {
        brand = brands.createBrand("브랜드");
        products.createProduct(brand.getId(), "상품0", 1_000, 0);
        products.createProduct(brand.getId(), "상품1", 1_001, 0);
        products.createProduct(brand.getId(), "상품2", 1_002, 0);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        // 데이터 준비에 사용한 조회는 빼고, 테스트의 상품 조회만 센다.
        statistics.clear();
    }

    @Test
    void 첫_페이지_조회() {
        var page = query.list(null, PageRequest.of(0, 2), ProductSort.PRICE_ASC);

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(2);
    }

    @Test
    void 마지막_페이지_조회() {
        var page = query.list(brand.getId(), PageRequest.of(1, 2), ProductSort.PRICE_ASC);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
    }

    @Test
    void 범위_밖_페이지_조회() {
        var page = query.list(null, PageRequest.of(2, 2), ProductSort.PRICE_ASC);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(2);
    }

    @Test
    void 전체보다_큰_페이지_조회() {
        var page = query.list(brand.getId(), PageRequest.of(0, 20), ProductSort.PRICE_ASC);

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
