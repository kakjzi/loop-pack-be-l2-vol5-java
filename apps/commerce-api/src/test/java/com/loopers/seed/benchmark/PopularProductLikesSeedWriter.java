package com.loopers.seed.benchmark;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import javax.sql.DataSource;

// 기존 상품·관계는 보존하고, 예약 사용자들의 좋아요만 전용 DB에 추가한다.
public class PopularProductLikesSeedWriter {
    private static final long FIRST_USER_ID = 1_000_001L;
    private static final int MAX_USERS = 400_000;
    private static final int BATCH_SIZE = 1_000;
    private static final LocalDateTime CREATED_AT = LocalDateTime.parse("2026-10-01T00:00:00");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PopularProductLikesSeedWriter(DataSource source, PlatformTransactionManager manager) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(60);
        transactions = new TransactionTemplate(manager);
        transactions.setTimeout(60);
    }

    public Result addLikes(int usersPerProduct) {
        if (usersPerProduct <= 0 || usersPerProduct > MAX_USERS) {
            throw new IllegalArgumentException("추가 사용자 수는 1~400000명이어야 합니다.");
        }
        if (!"loopers_offset_bench".equals(jdbc.queryForObject("select database()", String.class))) {
            throw new IllegalStateException("loopers_offset_bench 전용 DB에서만 실행합니다.");
        }
        for (String table : List.of("users", "product_likes")) {
            String column = table.equals("users") ? "id" : "user_id";
            long reserved = jdbc.queryForObject("select count(*) from " + table
                    + " where " + column + ">=? and " + column + "<?", Long.class,
                    FIRST_USER_ID, FIRST_USER_ID + MAX_USERS);
            if (reserved != 0) {
                throw new IllegalStateException("예약 사용자 범위가 이미 사용 중입니다. 재실행하지 않습니다: " + table);
            }
        }
        List<Long> productIds = new ArrayList<>();
        for (long brandId : new long[]{1, 2}) {
            List<Long> selected = jdbc.queryForList("""
                    select p.id from products p join brands b on b.id=p.brand_id
                    where p.deleted_at is null and b.deleted_at is null and p.brand_id=?
                    order by p.price asc,p.id desc limit 10
                    """, Long.class, brandId);
            if (selected.size() != 10) {
                throw new IllegalStateException("브랜드별 노출 가능한 상품이 10개 이상 필요합니다.");
            }
            productIds.addAll(selected);
        }
        // 직접 생성한 원본에도 초기 이행이 선행되어야 delta 증가가 맞는다.
        String placeholders = String.join(",", java.util.Collections.nCopies(productIds.size(), "?"));
        long mismatches = jdbc.queryForObject("""
                select count(*) from products p
                left join product_like_counts c on c.product_id=p.id
                where p.id in (
                """ + placeholders + ") and coalesce(c.like_count,0) <> "
                + "(select count(*) from product_likes l where l.product_id=p.id)",
                Long.class, productIds.toArray());
        if (mismatches != 0) {
            throw new IllegalStateException("초기 카운터 이행을 먼저 완료해야 합니다. 대상 상품 집계가 다릅니다.");
        }
        long originalLikes = jdbc.queryForObject("select count(*) from product_likes", Long.class);
        for (int offset = 0; offset < usersPerProduct; offset += BATCH_SIZE) {
            long first = FIRST_USER_ID + offset;
            long end = first + Math.min(BATCH_SIZE, usersPerProduct - offset);
            transactions.executeWithoutResult(status -> insertBatch(productIds, first, end));
        }
        long addedLikes = (long) productIds.size() * usersPerProduct;
        long totalLikes = jdbc.queryForObject("select count(*) from product_likes", Long.class);
        if (totalLikes != originalLikes + addedLikes) {
            throw new IllegalStateException("예상한 좋아요 수와 다릅니다. 다른 쓰기 작업 여부를 확인하세요.");
        }
        return new Result(List.copyOf(productIds), usersPerProduct, addedLikes, totalLikes);
    }

    private void insertBatch(List<Long> productIds, long firstUser, long endUser) {
        List<Long> users = LongStream.range(firstUser, endUser).boxed().toList();
        jdbc.batchUpdate("insert into users (id) values (?)", users, BATCH_SIZE,
                (statement, id) -> statement.setLong(1, id));
        // 상품별 INSERT SELECT로 이 묶음의 사용자 관계를 추가한다. 20,000개 객체를 만들지 않는다.
        jdbc.batchUpdate("""
                insert into product_likes (product_id,user_id,created_at,updated_at)
                select ?,u.id,?,? from users u where u.id>=? and u.id<?
                """, productIds, productIds.size(), (statement, productId) -> {
                    statement.setLong(1, productId);
                    statement.setObject(2, CREATED_AT);
                    statement.setObject(3, CREATED_AT);
                    statement.setLong(4, firstUser);
                    statement.setLong(5, endUser);
                });
        // 원본 관계와 같은 묶음/트랜잭션에서 저장된 집계도 증가한다.
        jdbc.batchUpdate("""
                insert into product_like_counts (product_id,like_count) values (?,?)
                on duplicate key update like_count=like_count+values(like_count)
                """, productIds, productIds.size(), (statement, productId) -> {
                    statement.setLong(1, productId);
                    statement.setLong(2, endUser - firstUser);
                });
    }

    public record Result(List<Long> productIds, int addedUsers, long addedLikes, long totalLikes) {}
}
