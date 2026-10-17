package com.loopers.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.loopers.application.brand.fixture.BrandFixture;
import com.loopers.application.like.fixture.LikeFixture;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.infrastructure.user.fixture.UserFixture;
import com.loopers.utils.DatabaseCleanUp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

@SpringBootTest(properties = "spring.jpa.show-sql=false")
class LocalProductSeedIntegrationTest {
    @Autowired private LocalProductSeedService service;
    @Autowired private BrandFixture brands;
    @Autowired private ProductFixture products;
    @Autowired private UserFixture users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanUp;
    @MockitoSpyBean private LikeFixture likes;

    @Test
    @Transactional
    void 실습_상품_만개를_두_브랜드에_나누고_가격과_좋아요를_분산한다() {
        var existingBrand = brands.createBrand("기존 브랜드");
        var existingProduct = products.createProduct(existingBrand.getId(), "기존 상품", 2_000L, 10);
        users.createUser(100L);

        var result = service.seed();

        assertThat(result.brands()).hasSize(2);
        assertThat(result.insertedProducts()).isEqualTo(10_000);
        assertThat(result.insertedLikes()).isEqualTo(likes.rowCount());
        assertThat(products.rowCount()).isEqualTo(10_001);
        assertThat(brands.rowCount()).isEqualTo(3);
        assertThat(products.product(existingProduct.getId())).usingRecursiveComparison().isEqualTo(existingProduct);
        assertThat(jdbc.queryForList("select id from users order by id", Long.class))
                .containsExactly(100L, 101L, 102L, 103L, 104L);
        assertThat(jdbc.queryForObject("select count(distinct user_id) from product_likes", Long.class)).isEqualTo(4L);
        for (var brand : result.brands()) {
            assertThat(brand.products()).isEqualTo(5_000);
            assertThat(products.productCount(brand.brandId())).isEqualTo(5_000);
            assertThat(jdbc.queryForObject("select count(distinct price) from products where brand_id=?",
                    Long.class, brand.brandId())).isEqualTo(6L);
            var likeCounts = jdbc.queryForList("""
                    select coalesce(l.cnt, 0) from products p
                    left join (select product_id, count(*) cnt from product_likes group by product_id) l
                      on l.product_id=p.id where p.brand_id=?
                    """, Long.class, brand.brandId());
            assertThat(likeCounts).hasSize(5_000).contains(0L, 1L, 2L, 3L, 4L);
            assertThat(likeCounts.stream().filter(count -> count == 2L).count()).isGreaterThan(1L);
        }
    }

    @Test
    void 좋아요_저장이_실패하면_생성한_사용자_브랜드_상품도_롤백한다() {
        doThrow(new IllegalStateException("좋아요 저장 실패")).when(likes).createLike(anyLong(), anyLong());

        assertThatThrownBy(service::seed).isInstanceOf(IllegalStateException.class).hasMessage("좋아요 저장 실패");

        assertThat(products.rowCount()).isZero();
        assertThat(brands.rowCount()).isZero();
        assertThat(likes.rowCount()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isZero();
    }

    @Test
    void 시드_실행_설정은_테이블을_초기화하지_않고_배치_저장을_사용한다() throws Exception {
        try (var context = new SpringApplicationBuilder(LocalProductSeedConfiguration.class)
                .run("--spring.config.name=local-product-seed")) {
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(environment.getProperty("datasource.mysql-jpa.main.data-source-properties.rewriteBatchedStatements"))
                    .isEqualTo("true");
            assertThat(context.getBean(LocalProductSeedService.class)).isNotNull();
            try (var connection = context.getBean(DataSource.class).getConnection()) {
                assertThat(connection.getMetaData().getURL())
                        .isEqualTo(System.getProperty("datasource.mysql-jpa.main.jdbc-url"));
            }
        }
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
