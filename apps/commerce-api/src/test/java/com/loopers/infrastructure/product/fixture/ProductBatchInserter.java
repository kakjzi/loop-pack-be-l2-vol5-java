package com.loopers.infrastructure.product.fixture;

import com.loopers.domain.product.Product;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Component
@Transactional
public class ProductBatchInserter {
    private static final int BATCH_SIZE = 500;
    private static final String INSERT = """
            insert into products (brand_id, name, price, stock, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    @PersistenceContext private EntityManager entityManager;

    public ProductBatchInserter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Product> insert(List<Product> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        if (rows.stream().anyMatch(row -> row.getId() != null || row.isDeleted())) {
            throw new IllegalArgumentException("배치 INSERT는 신규 상품만 저장합니다.");
        }
        // JDBC도 같은 JPA 트랜잭션의 연결을 사용한다. 먼저 대기 중인 JPA 변경을 반영한다.
        entityManager.flush();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<Product> saved = new ArrayList<>(rows.size());
        for (int offset = 0; offset < rows.size(); offset += BATCH_SIZE) {
            List<Product> batch = rows.subList(offset, Math.min(offset + BATCH_SIZE, rows.size()));
            var keys = new GeneratedKeyHolder();
            jdbc.batchUpdate(connection -> connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS),
                    new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement statement, int index) throws SQLException {
                            Product row = batch.get(index);
                            statement.setLong(1, row.getBrandId());
                            statement.setString(2, row.getName());
                            statement.setLong(3, row.getPrice());
                            statement.setInt(4, row.getStock());
                            statement.setObject(5, now);
                            statement.setObject(6, now);
                        }

                        @Override
                        public int getBatchSize() {
                            return batch.size();
                        }
                    }, keys);
            if (keys.getKeyList().size() != batch.size()) {
                throw new IllegalStateException("저장된 상품의 생성 ID 개수가 일치하지 않습니다.");
            }
            for (int index = 0; index < batch.size(); index++) {
                Product row = batch.get(index);
                long id = ((Number) keys.getKeyList().get(index).values().iterator().next()).longValue();
                saved.add(Product.restore(id, row.getBrandId(), row.getName(), row.getPrice(), row.getStock(), false));
            }
        }
        return saved;
    }
}
