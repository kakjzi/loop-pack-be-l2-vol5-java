package com.loopers.infrastructure.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.brand.fixture.BrandFixture;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.utils.DatabaseCleanUp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@SpringBootTest(properties = "spring.jpa.show-sql=false")
class ProductRepositoryIntegrationTest {
    @Autowired private BrandFixture brands;
    @Autowired private ProductRepository products;
    @Autowired private ProductFixture fixture;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void 이전_재고를_가진_객체로_정보를_수정해도_차감_결과를_덮어쓰지_않는다() {
        // arrange
        Brand brand = brands.createBrand();
        Product product = fixture.createProduct(brand.getId(), "상품", 1_000, 5);
        Product previous = products.findById(product.getId()).orElseThrow();
        products.deductStock(product.getId(), 1);
        previous.update("변경 이름", 2_000);

        // act
        products.updateInformation(previous);

        // assert
        Product stored = products.findById(product.getId()).orElseThrow();
        assertThat(stored.getStock()).isEqualTo(4);
        assertThat(stored.getName()).isEqualTo("변경 이름");
        assertThat(stored.getPrice()).isEqualTo(2_000);
    }

    @Test
    void 상품을_삭제해도_최신_재고를_이전_값으로_덮어쓰지_않는다() {
        // arrange
        Brand brand = brands.createBrand();
        Product product = fixture.createProduct(brand.getId(), "상품", 1_000, 5);
        products.deductStock(product.getId(), 1);

        // act
        products.delete(product.getId());

        // assert
        Product stored = products.findById(product.getId()).orElseThrow();
        assertThat(stored.isDeleted()).isTrue();
        assertThat(stored.getStock()).isEqualTo(4);
    }

    @Test
    void 생성_조건에_맞는_상품을_실제_브랜드에_연결해_저장한다() {
        Brand brand = brands.createBrand();

        Product generated = fixture.createProduct(brand.getId());

        assertThat(generated.getId()).isPositive();
        assertThat(generated.getBrandId()).isEqualTo(brand.getId());
        assertThat(generated.getName()).hasSizeBetween(5, 30);
        assertThat(generated.getPrice()).isBetween(1_000L, 100_000L);
        assertThat(generated.getStock()).isBetween(0, 100);
        assertThat(generated.isDeleted()).isFalse();
        Product stored = products.findById(generated.getId()).orElseThrow();
        assertThat(stored.getBrandId()).isEqualTo(brand.getId());
        assertThat(stored.getName()).isEqualTo(generated.getName());
        assertThat(stored.getPrice()).isEqualTo(generated.getPrice());
        assertThat(stored.getStock()).isEqualTo(generated.getStock());
    }

    @Test
    void 같은_시드로_상품을_생성하면_내용이_같고_DB에_모두_저장한다() {
        Brand brand = brands.createBrand();

        var first = fixture.createProducts(brand.getId(), 5, 42L);
        var second = fixture.createProducts(brand.getId(), 5, 42L);

        assertThat(first).hasSize(5);
        assertThat(second).usingRecursiveComparison().ignoringFields("id").isEqualTo(first);
        assertThat(first).allSatisfy(p -> {
            assertThat(p.getBrandId()).isEqualTo(brand.getId());
            assertThat(p.getPrice()).isIn(1_000L, 5_000L, 10_000L, 30_000L, 50_000L, 100_000L);
        });
        assertThat(fixture.productCount(brand.getId())).isEqualTo(10);
    }

    @Test
    void 상품_만개를_배치_INSERT하고_생성된_ID와_롤백을_확인한다() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            status.setRollbackOnly();
            Brand brand = brands.createBrand();
            long before = insertCount();

            var saved = fixture.createProducts(brand.getId(), 10_000, 42L);

            assertThat(insertCount() - before).isBetween(1L, 20L);
            assertThat(saved).hasSize(10_000);
            assertThat(saved).extracting(Product::getId).doesNotHaveDuplicates().allMatch(id -> id > 0);
            assertThat(fixture.productCount(brand.getId())).isEqualTo(10_000);
            for (Product sample : List.of(saved.getFirst(), saved.getLast())) {
                assertThat(products.findById(sample.getId()).orElseThrow())
                        .usingRecursiveComparison().isEqualTo(sample);
            }
        });
        assertThat(fixture.rowCount()).isZero();
    }

    private long insertCount() {
        return jdbc.queryForObject("show session status like 'Com_insert'",
                (rs, row) -> rs.getLong(2));
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
