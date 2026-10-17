package com.loopers.infrastructure.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class ProductCacheConcurrencyTest extends ProductCacheTestSupport {
    @ParameterizedTest
    @ValueSource(strings = {"price", "product-delete", "brand-delete"})
    void 변경_commit_뒤_이전_DB결과가_늦게_채워지는_상황을_재현한다(String change) throws Exception {
        var product = product();
        var key = key(null, "latest", 0, 20);
        var oldResultRead = new CountDownLatch(1);
        var allowCacheFill = new CountDownLatch(1);
        var interceptOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object oldPage = invocation.callRealMethod();
            if (interceptOnce.getAndSet(false)) {
                oldResultRead.countDown();
                if (!allowCacheFill.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("늦은 채움 테스트 대기 초과");
                }
            }
            return oldPage;
        }).when(query).list(isNull(), any(), any());

        var worker = Executors.newSingleThreadExecutor();
        try {
            var oldRequest = worker.submit(() -> facade.list(null, 0, 20, "latest"));
            assertThat(oldResultRead.await(10, TimeUnit.SECONDS)).isTrue();
            switch (change) {
                case "price" -> updateProduct.update(product.getId(), "변경", 900L);
                case "product-delete" -> deleteProduct.delete(product.getId());
                case "brand-delete" -> deleteBrand.delete(product.getBrandId());
                default -> throw new IllegalArgumentException(change);
            }
            allowCacheFill.countDown();
            assertThat(oldRequest.get(10, TimeUnit.SECONDS).getContent().getFirst().price()).isEqualTo(1_000);
            // 늦게 시작한 cache.put이 실제 Redis에 과거 값을 재삽입했다.
            assertThat(cache.get(key).orElseThrow().items().getFirst().price()).isEqualTo(1_000);

            var next = facade.list(null, 0, 20, "latest");
            if ("price".equals(change)) {
                assertThat(next.getContent().getFirst().price()).isEqualTo(1_000);
                expire(key);
                assertThat(facade.list(null, 0, 20, "latest").getContent().getFirst().price()).isEqualTo(900);
            } else {
                // 이미 시작한 oldRequest와 달리 변경 완료 뒤 시작한 요청은 삭제 상품을 숨긴다.
                assertThat(next.getContent()).isEmpty();
                assertThat(next.getTotalElements()).isZero();
                assertThat(cache.get(key).orElseThrow().items()).isEmpty();
            }
        } finally {
            allowCacheFill.countDown();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void 쓰기_TX의_cache_miss도_미커밋_결과를_게시하지_않는다() {
        var product = product();
        var key = key(null, "latest", 0, 20);
        transaction().executeWithoutResult(status -> {
            updateProduct.update(product.getId(), "미커밋", 900L);
            assertThat(facade.list(null, 0, 20, "latest").getContent().getFirst().price()).isEqualTo(900);
            assertThat(cache.get(key)).isEmpty();
            status.setRollbackOnly();
        });
        assertThat(query.find(product.getId()).orElseThrow().price()).isEqualTo(1_000);
        assertThat(cache.get(key)).isEmpty();
    }
}
