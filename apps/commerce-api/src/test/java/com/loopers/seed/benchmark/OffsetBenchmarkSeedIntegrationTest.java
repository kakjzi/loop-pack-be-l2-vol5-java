package com.loopers.seed.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringJUnitConfig(OffsetBenchmarkSeedConfiguration.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false"
})
class OffsetBenchmarkSeedIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("loopers_offset_bench").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("datasource.mysql-jpa.main.jdbc-url", MYSQL::getJdbcUrl);
        registry.add("datasource.mysql-jpa.main.username", MYSQL::getUsername);
        registry.add("datasource.mysql-jpa.main.password", MYSQL::getPassword);
    }

    @Autowired private OffsetBenchmarkSeedWriter writer;
    @Autowired private PopularProductLikesSeedWriter popularLikes;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void 상품과_실제_좋아요_관계를_고르게_저장한다() {
        writer.seed(60);

        assertThat(jdbc.queryForObject("select count(*) from products", Long.class)).isEqualTo(60);
        assertThat(jdbc.queryForObject("select count(*) from product_likes", Long.class)).isEqualTo(120);
        assertThat(jdbc.queryForList("select count(*) from products group by brand_id", Long.class))
                .containsExactlyInAnyOrder(30L, 30L);
        assertThat(jdbc.queryForObject("""
                select count(*) from product_likes l
                left join products p on p.id=l.product_id
                left join users u on u.id=l.user_id
                where p.id is null or u.id is null
                """, Long.class)).isZero();
        assertThat(jdbc.queryForList("""
                select count(*) from (
                    select p.id,count(l.id) likes from products p
                    left join product_likes l on l.product_id=p.id group by p.id
                ) counts group by likes order by likes
                """, Long.class)).containsExactly(12L, 12L, 12L, 12L, 12L);
        assertCountersMatchRelations();
    }

    @Test
    void 재실행을_거부하고_기존_데이터를_보존한다() {
        writer.seed(10);

        assertThatThrownBy(() -> writer.seed(10)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 데이터");
        assertThat(jdbc.queryForObject("select count(*) from products", Long.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select count(*) from product_likes", Long.class)).isEqualTo(20);
    }

    @Test
    void 묶음_저장이_실패하면_해당_상품과_좋아요는_함께_롤백한다() {
        jdbc.execute("alter table product_likes add constraint ck_seed_failure check (product_id <> 503)");
        try {
            assertThatThrownBy(() -> writer.seed(510)).isInstanceOf(org.springframework.dao.DataAccessException.class);

            assertThat(jdbc.queryForObject("select count(*) from products", Long.class)).isEqualTo(500);
            assertThat(jdbc.queryForObject("select count(*) from product_likes", Long.class)).isEqualTo(1_000);
            assertCountersMatchRelations();
            assertThat(jdbc.queryForObject("select count(*) from products where id>500", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from product_likes where product_id>500", Long.class)).isZero();
        } finally {
            jdbc.execute("alter table product_likes drop check ck_seed_failure");
        }
    }

    @Test
    void 인기_상품20개에_새사용자의_좋아요를_추가하고_기존자료를_보존한다() {
        writer.seed(60);

        var result = popularLikes.addLikes(3);

        assertThat(result.productIds()).containsExactly(
                5L, 4L, 3L, 2L, 1L, 15L, 14L, 13L, 12L, 11L,
                10L, 9L, 8L, 7L, 6L, 20L, 19L, 18L, 17L, 16L);
        assertThat(result.addedUsers()).isEqualTo(3);
        assertThat(result.addedLikes()).isEqualTo(60);
        assertThat(result.totalLikes()).isEqualTo(180);
        assertCountersMatchRelations();
        assertThatThrownBy(() -> popularLikes.addLikes(3)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("예약 사용자 범위");
        assertThat(jdbc.queryForObject("select count(*) from products", Long.class)).isEqualTo(60);
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("select count(*) from product_likes where user_id<=4", Long.class))
                .isEqualTo(120);
        assertThat(jdbc.queryForList("""
                select count(*) from product_likes where user_id>=1000001 group by product_id
                """, Long.class)).hasSize(20).containsOnly(3L);
        assertThat(jdbc.queryForObject("""
                select count(*) from product_likes l left join users u on u.id=l.user_id
                left join products p on p.id=l.product_id where u.id is null or p.id is null
                """, Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 예약_사용자나_관계가_이미_있으면_어떤_추가도_하지_않는다(boolean existingUser) {
        writer.seed(60);
        if (existingUser) {
            jdbc.update("insert into users (id) values (1000001)");
        } else {
            jdbc.update("""
                    insert into product_likes (product_id,user_id,created_at,updated_at)
                    values (1,1000001,now(6),now(6))
                    """);
        }
        long userCount = jdbc.queryForObject("select count(*) from users", Long.class);
        long likeCount = jdbc.queryForObject("select count(*) from product_likes", Long.class);

        assertThatThrownBy(() -> popularLikes.addLikes(3)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("예약 사용자 범위");

        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(userCount);
        assertThat(jdbc.queryForObject("select count(*) from product_likes", Long.class)).isEqualTo(likeCount);
    }

    @Test
    void 인기_좋아요의_실패한_묶음은_사용자와_관계_모두_롤백한다() {
        writer.seed(60);
        jdbc.execute("alter table product_likes add constraint ck_popular_failure check (user_id <> 1001001)");
        try {
            assertThatThrownBy(() -> popularLikes.addLikes(1_001))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);

            assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(1_004);
            assertThat(jdbc.queryForObject("select count(*) from product_likes", Long.class)).isEqualTo(20_120);
            assertCountersMatchRelations();
            assertThat(jdbc.queryForObject("select count(*) from users where id=1001001", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from product_likes where user_id=1001001", Long.class))
                    .isZero();
            assertThatThrownBy(() -> popularLikes.addLikes(1_001))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("예약 사용자 범위");
        } finally {
            jdbc.execute("alter table product_likes drop check ck_popular_failure");
        }
    }

    @Test
    void 인기_상품이_부족하면_추가하기_전에_거부한다() {
        writer.seed(20);
        jdbc.update("update products set deleted_at=now(6) where id=1");

        assertThatThrownBy(() -> popularLikes.addLikes(3)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("브랜드별 노출");

        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from product_likes", Long.class)).isEqualTo(40);
    }

    @Test
    void 다른_DB에서는_인기_좋아요를_추가하지_않는다() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                MYSQL.getJdbcUrl().replace("/loopers_offset_bench", "/information_schema"),
                MYSQL.getUsername(), MYSQL.getPassword());
        var other = new PopularProductLikesSeedWriter(source,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));

        assertThatThrownBy(() -> other.addLikes(3)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("전용 DB");
    }

    @Test
    void 초기_카운터_이행이_누락되면_인기_좋아요를_추가하지_않는다() {
        writer.seed(60);
        jdbc.update("delete from product_like_counts where product_id=2");

        assertThatThrownBy(() -> popularLikes.addLikes(3)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("초기 카운터 이행");

        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from product_likes", Long.class)).isEqualTo(120);
    }

    private void assertCountersMatchRelations() {
        assertThat(jdbc.queryForObject("""
                select count(*) from products p
                left join product_like_counts c on c.product_id=p.id
                left join (select product_id,count(*) n from product_likes group by product_id) l
                    on l.product_id=p.id
                where coalesce(c.like_count,0)<>coalesce(l.n,0)
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("select coalesce(sum(like_count),0) from product_like_counts", Long.class))
                .isEqualTo(jdbc.queryForObject("select count(*) from product_likes", Long.class));
    }

    @AfterEach
    void cleanDatabase() {
        for (String table : new String[]{"product_like_counts", "product_likes", "products", "brands", "users"}) {
            jdbc.update("delete from " + table);
        }
    }
}
