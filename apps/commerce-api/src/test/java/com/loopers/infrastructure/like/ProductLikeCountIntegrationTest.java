package com.loopers.infrastructure.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.application.brand.fixture.BrandFixture;
import com.loopers.domain.like.ProductLike;
import com.loopers.domain.like.ProductLikeRepository;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.support.ConcurrentRequests;
import com.loopers.utils.DatabaseCleanUp;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "com.loopers.infrastructure.like.ProductLikeCountIntegrationTest$RelationInsertBarrier")
class ProductLikeCountIntegrationTest {
    @Autowired private ProductLikeRepository likes;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transaction;
    @Autowired private BrandFixture brands;
    @Autowired private ProductFixture products;
    @Autowired private DatabaseCleanUp cleanUp;

    @Test
    void 관계를_저장하면_상품별_카운터도_함께_증가한다() {
        likes.save(new ProductLike(1, 10));
        likes.save(new ProductLike(2, 10));

        assertThat(likes.exists(1, 10)).isTrue();
        assertThat(likes.exists(2, 10)).isTrue();
        assertThat(counter(10)).isEqualTo(2);
    }

    @Test
    void 중복_등록은_관계와_카운터를_증가시키지_않는다() {
        likes.registerIfAbsent(new ProductLike(1, 10));
        likes.registerIfAbsent(new ProductLike(1, 10));

        assertThat(relationCount(10)).isEqualTo(1);
        assertThat(counter(10)).isEqualTo(1);
    }

    @Test
    void 엄격한_저장의_중복_오류는_카운터도_보존한다() {
        likes.save(new ProductLike(1, 10));

        assertThatThrownBy(() -> likes.save(new ProductLike(1, 10)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(relationCount(10)).isEqualTo(1);
        assertThat(counter(10)).isEqualTo(1);
    }

    @Test
    void 취소는_실제_관계가_삭제될_때만_카운터를_줄인다() {
        likes.registerIfAbsent(new ProductLike(1, 10));
        likes.delete(2, 10);
        assertThat(counter(10)).isEqualTo(1);

        likes.delete(1, 10);
        likes.delete(1, 10);

        assertThat(relationCount(10)).isZero();
        assertThat(counter(10)).isZero();
    }

    @Test
    void 상위_트랜잭션이_실패하면_관계와_카운터가_함께_롤백된다() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            likes.registerIfAbsent(new ProductLike(1, 10));
            throw new IllegalStateException("상위 작업 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(relationCount(10)).isZero();
        assertThat(counter(10)).isZero();

        likes.save(new ProductLike(1, 10));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            likes.delete(1, 10);
            throw new IllegalStateException("상위 작업 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(relationCount(10)).isEqualTo(1);
        assertThat(counter(10)).isEqualTo(1);
    }

    @Test
    void 여러_사용자의_동시_좋아요를_누락_없이_센다() throws Exception {
        List<Callable<Void>> requests = IntStream.rangeClosed(1, 20)
                .mapToObj(userId -> registration(userId, 10))
                .toList();

        var result = ConcurrentRequests.run(requests);

        assertThat(result.failures()).isEmpty();
        assertThat(result.successes()).hasSize(20);
        assertThat(relationCount(10)).isEqualTo(20);
        assertThat(counter(10)).isEqualTo(20);
    }

    @Test
    void 이미_스냅샷을_읽은_동시_중복_등록도_한_번만_증가한다() throws Exception {
        CyclicBarrier snapshotsRead = new CyclicBarrier(2);
        Callable<Void> request = () -> {
            transaction.executeWithoutResult(status -> {
                assertThat(likes.exists(1, 10)).isFalse();
                await(snapshotsRead);
                likes.registerIfAbsent(new ProductLike(1, 10));
            });
            return null;
        };

        var result = ConcurrentRequests.run(List.of(request, request));

        assertThat(result.failures()).isEmpty();
        assertThat(result.successes()).hasSize(2);
        assertThat(relationCount(10)).isEqualTo(1);
        assertThat(counter(10)).isEqualTo(1);
    }

    @Test
    void 등록과_취소가_동시에_들어와도_관계수와_카운터가_일치한다() throws Exception {
        List<Callable<Void>> requests = IntStream.range(0, 20)
                .mapToObj(index -> (Callable<Void>) () -> {
                    if (index % 2 == 0) {
                        likes.registerIfAbsent(new ProductLike(1, 10));
                    } else {
                        likes.delete(1, 10);
                    }
                    return null;
                }).toList();

        var result = ConcurrentRequests.run(requests);

        assertThat(result.failures()).isEmpty();
        assertThat(result.successes()).hasSize(20);
        assertThat(counter(10)).isBetween(0L, 1L).isEqualTo(relationCount(10));
    }

    @Test
    void 음수_카운터를_거부하고_불일치한_취소는_관계를_롤백한다() {
        likes.save(new ProductLike(1, 10));
        assertThatThrownBy(() -> jdbc.update(
                "update product_like_counts set like_count = -1 where product_id = 10"))
                .rootCause()
                .isInstanceOfSatisfying(java.sql.SQLException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(3819));
        jdbc.update("update product_like_counts set like_count = 0 where product_id = 10");

        assertThatThrownBy(() -> likes.delete(1, 10))
                .hasMessageContaining("좋아요 관계와 카운터가 일치하지 않습니다.");
        assertThat(likes.exists(1, 10)).isTrue();
        assertThat(counter(10)).isZero();
    }

    @Test
    void 카운터_잠금_중에도_상품_가격은_수정할_수_있다() throws Exception {
        long brandId = brands.createBrand().getId();
        long productId = products.createProduct(brandId, "상품", 1_000, 0).getId();
        CountDownLatch counterLocked = new CountDownLatch(1);
        CountDownLatch releaseCounter = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var mutation = executor.submit(() -> transaction.executeWithoutResult(status -> {
                likes.registerIfAbsent(new ProductLike(1, productId));
                counterLocked.countDown();
                await(releaseCounter);
            }));
            assertThat(counterLocked.await(5, TimeUnit.SECONDS)).isTrue();
            var priceChange = executor.submit(() -> jdbc.update(
                    "update products set price = 2000 where id = ?", productId));

            // 카운터 트랜잭션을 풀기 전에 상품 수정이 완료되어야 한다.
            assertThat(priceChange.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            releaseCounter.countDown();
            mutation.get(5, TimeUnit.SECONDS);
            assertThat(counter(productId)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "select price from products where id = ?", Long.class, productId))
                    .isEqualTo(2_000);
        } finally {
            releaseCounter.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void 서로_다른_상품의_최초_좋아요도_동시에_등록할_수_있다() throws Exception {
        jdbc.update("insert into product_like_counts (product_id, like_count) values (10, 0), (20, 0)");
        RelationInsertBarrier.barrier = new CyclicBarrier(2);
        try {
            var result = ConcurrentRequests.run(List.of(registration(1, 10), registration(1, 20)));

            assertThat(result.failures()).isEmpty();
            assertThat(result.successes()).hasSize(2);
            assertThat(counter(10)).isEqualTo(1);
            assertThat(counter(20)).isEqualTo(1);
        } finally {
            RelationInsertBarrier.barrier = null;
        }
    }

    @Test
    void 존재하지_않는_상품의_취소는_카운터_행을_만들지_않는다() {
        likes.delete(1, 999);

        assertThat(jdbc.queryForObject(
                "select count(*) from product_like_counts where product_id = 999", Long.class))
                .isZero();
    }

    @Test
    void 중복이_아닌_제약_위반을_등록_성공으로_숨기지_않는다() {
        jdbc.execute("alter table product_likes add constraint chk_like_counter_test_user "
                + "check (user_id <> 2)");
        try {
            assertThatThrownBy(() -> likes.registerIfAbsent(new ProductLike(2, 10)))
                    .hasMessageContaining("좋아요 관계를 등록하지 못했습니다.");

            assertThat(relationCount(10)).isZero();
            assertThat(jdbc.queryForObject(
                    "select count(*) from product_like_counts where product_id = 10", Long.class))
                    .isZero();
        } finally {
            jdbc.execute("alter table product_likes drop check chk_like_counter_test_user");
        }
    }

    public static final class RelationInsertBarrier implements StatementInspector {
        private static volatile CyclicBarrier barrier;

        @Override
        public String inspect(String sql) {
            CyclicBarrier current = barrier;
            String normalized = sql.toLowerCase(Locale.ROOT);
            if (current != null && normalized.startsWith("insert")
                    && normalized.contains("into product_likes ")) {
                await(current);
            }
            return sql;
        }
    }

    private Callable<Void> registration(long userId, long productId) {
        return () -> {
            likes.registerIfAbsent(new ProductLike(userId, productId));
            return null;
        };
    }

    private long counter(long productId) {
        return jdbc.queryForObject(
                "select coalesce(sum(like_count), 0) from product_like_counts where product_id = ?",
                Long.class, productId);
    }

    private long relationCount(long productId) {
        return jdbc.queryForObject(
                "select count(*) from product_likes where product_id = ?", Long.class, productId);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("스냅샷 준비 시간 초과", exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("카운터 잠금 해제 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("카운터 잠금 대기 중 중단", exception);
        }
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
