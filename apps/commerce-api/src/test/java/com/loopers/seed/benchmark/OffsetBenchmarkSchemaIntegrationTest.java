package com.loopers.seed.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;

@Testcontainers
@SpringJUnitConfig(OffsetBenchmarkSeedConfiguration.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OffsetBenchmarkSchemaIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("loopers_offset_bench").withUsername("test").withPassword("test");

    @BeforeAll
    static void resetOwnedDatabase() throws Exception {
        // 저장소 SQL을 그대로 적용한다. 이 클래스가 만든 컨테이너의 DB만 삭제·재생성한다.
        var script = new FileSystemResource(Path.of("../../docs/week4/sql/reset-offset-benchmark.sql"));
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            ScriptUtils.executeSqlScript(connection, script);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("datasource.mysql-jpa.main.jdbc-url", MYSQL::getJdbcUrl);
        registry.add("datasource.mysql-jpa.main.username", MYSQL::getUsername);
        registry.add("datasource.mysql-jpa.main.password", MYSQL::getPassword);
    }

    @Autowired private OffsetBenchmarkSeedWriter writer;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void 초기화_SQL의_스키마를_validate한_뒤_시드와_카운터를_저장한다() {
        writer.seed(60);
        assertThat(jdbc.queryForObject("select count(*) from products", Long.class)).isEqualTo(60);
        assertThat(jdbc.queryForObject("select count(*) from product_likes", Long.class)).isEqualTo(120);
        assertThat(jdbc.queryForObject("select count(*) from product_like_counts", Long.class)).isEqualTo(60);
        assertThat(jdbc.queryForObject("select sum(like_count) from product_like_counts", Long.class)).isEqualTo(120);
        assertThat(jdbc.queryForObject("""
                select count(*) from product_like_counts c
                left join (select product_id,count(*) n from product_likes group by product_id) l
                    on l.product_id=c.product_id
                where c.like_count<>coalesce(l.n,0)
                """, Long.class)).isZero();
        assertThat(jdbc.queryForList("""
                select distinct index_name from information_schema.statistics
                where table_schema=database() and table_name='products'
                """, String.class)).containsExactly("PRIMARY");
        assertThat(jdbc.queryForList("""
                select distinct index_name from information_schema.statistics
                where table_schema=database() and table_name='product_likes'
                """, String.class)).containsExactlyInAnyOrder("PRIMARY", "UK712hmqku6f5l2q28jql7vj0f5");
        assertThatThrownBy(() -> jdbc.update("update product_like_counts set like_count=-1 where product_id=1"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(3819))
                .hasMessageContaining("ck_product_like_counts_nonnegative");
        assertThat(jdbc.queryForObject("select like_count from product_like_counts where product_id=1", Long.class))
                .isZero();
    }
}
