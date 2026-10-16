package com.loopers.seed.benchmark;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

// 전용 빈 DB에만 저장한다. 각 묶음의 상품과 좋아요는 함께 commit한다.
public class OffsetBenchmarkSeedWriter {
    private static final LocalDateTime CREATED_AT = LocalDateTime.parse("2026-10-01T00:00:00");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public OffsetBenchmarkSeedWriter(DataSource source, org.springframework.transaction.PlatformTransactionManager manager) {
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(manager);
    }

    public void seed(int total) {
        if (total <= 0 || total > 1_000_000 || total % 10 != 0) {
            throw new IllegalArgumentException("상품 수는 100만 이하의 양수이며 10의 배수여야 합니다.");
        }
        // 다른 DB에서 생성하거나 재실행해 기존 자료에 덧붙이지 않는다.
        String database = jdbc.queryForObject("select database()", String.class);
        if (!"loopers_offset_bench".equals(database)) {
            throw new IllegalStateException("loopers_offset_bench 전용 DB에서만 실행합니다.");
        }
        for (String table : List.of("products", "brands", "product_likes", "product_like_counts", "users")) {
            if (jdbc.queryForObject("select count(*) from " + table, Long.class) != 0) {
                throw new IllegalStateException("빈 DB만 허용합니다. 이미 데이터가 있습니다: " + table);
            }
        }
        transactions.executeWithoutResult(status -> {
            jdbc.update("insert into brands (id,name,created_at,updated_at) values (1,?,?,?),(2,?,?,?)",
                    "offset-brand-1", CREATED_AT, CREATED_AT, "offset-brand-2", CREATED_AT, CREATED_AT);
            jdbc.update("insert into users (id) values (1),(2),(3),(4)");
        });
        OffsetBenchmarkData generator = new OffsetBenchmarkData();
        for (int offset = 0; offset < total; offset += 500) {
            var rows = generator.next(offset, Math.min(500, total - offset));
            transactions.executeWithoutResult(status -> insert(rows));
        }
    }

    private void insert(List<OffsetBenchmarkData.ProductRow> rows) {
        jdbc.batchUpdate("""
                insert into products (id,brand_id,name,price,stock,created_at,updated_at)
                values (?,?,?,?,?,?,?)
                """, rows, 500, (statement, row) -> {
                    statement.setLong(1, row.id());
                    statement.setLong(2, row.brandId());
                    statement.setString(3, row.name());
                    statement.setLong(4, row.price());
                    statement.setInt(5, row.stock());
                    statement.setObject(6, CREATED_AT);
                    statement.setObject(7, CREATED_AT);
                });
        jdbc.batchUpdate("insert into product_like_counts (product_id,like_count) values (?,?)",
                rows, 500, (statement, row) -> {
                    statement.setLong(1, row.id());
                    statement.setLong(2, row.likeCount());
                });
        List<long[]> likes = new ArrayList<>();
        for (var row : rows) {
            for (long user = 1; user <= row.likeCount(); user++) {
                likes.add(new long[]{row.id(), user});
            }
        }
        if (!likes.isEmpty()) {
            jdbc.batchUpdate("""
                    insert into product_likes (product_id,user_id,created_at,updated_at) values (?,?,?,?)
                    """, likes, 500, (statement, row) -> {
                        statement.setLong(1, row[0]);
                        statement.setLong(2, row[1]);
                        statement.setObject(3, CREATED_AT);
                        statement.setObject(4, CREATED_AT);
                    });
        }
    }
}
