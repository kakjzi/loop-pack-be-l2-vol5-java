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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ProductQueryIntegrationTest {
    @Autowired private ProductQuery query;
    @Autowired private ProductFixture products;
    @Autowired private BrandFixture brands;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private DatabaseCleanUp cleanUp;

    @Test
    void 전체_개수가_확정되는_페이지에서만_COUNT를_생략하고_상품별_추가_조회가_없다() {
        Brand brand = brands.createBrand("브랜드");
        for (int index = 0; index < 3; index++) {
            products.createProduct(brand.getId(), "상품" + index, 1_000 + index, 0);
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        statistics.clear();
        var fullPage = query.list(null, PageRequest.of(0, 2), ProductSort.PRICE_ASC);
        assertThat(fullPage.getContent()).hasSize(2);
        assertThat(fullPage.getTotalElements()).isEqualTo(3);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(2);

        statistics.clear();
        var lastPage = query.list(brand.getId(), PageRequest.of(1, 2), ProductSort.PRICE_ASC);
        assertThat(lastPage.getContent()).hasSize(1);
        assertThat(lastPage.getTotalElements()).isEqualTo(3);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);

        statistics.clear();
        var emptyPage = query.list(null, PageRequest.of(2, 2), ProductSort.PRICE_ASC);
        assertThat(emptyPage.getContent()).isEmpty();
        assertThat(emptyPage.getTotalElements()).isEqualTo(3);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(2);

        statistics.clear();
        var firstPage = query.list(brand.getId(), PageRequest.of(0, 20), ProductSort.PRICE_ASC);
        assertThat(firstPage.getContent()).hasSize(3);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
