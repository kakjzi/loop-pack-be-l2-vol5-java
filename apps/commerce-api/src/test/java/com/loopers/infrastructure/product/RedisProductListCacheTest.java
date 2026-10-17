package com.loopers.infrastructure.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.product.query.ProductListKey;
import com.loopers.application.product.query.ProductListPage;
import com.loopers.application.product.query.ProductSort;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.function.LongUnaryOperator;

class RedisProductListCacheTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ProductListPage page = new ProductListPage(List.of(), 0);

    @Test
    @SuppressWarnings("unchecked")
    void 저장마다_TTL을_새로_뽑고_25초와_30초_양끝을_포함한다() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var randomOffset = mock(LongUnaryOperator.class);
        // 난수 추출의 양끝·중간을 고정해 확률에 의존하지 않고 Redis에 전달되는 만료를 검증한다.
        when(randomOffset.applyAsLong(6)).thenReturn(0L, 5L, 2L);
        var cache = new RedisProductListCache(redis, mapper, meters, 30, 5, randomOffset);
        for (int index = 0; index < 3; index++) {
            cache.put(new ProductListKey(null, ProductSort.LATEST, index, 20), page);
        }
        var ttls = ArgumentCaptor.forClass(Duration.class);
        verify(values, times(3)).set(anyString(), anyString(), ttls.capture());
        assertThat(ttls.getAllValues()).containsExactly(Duration.ofSeconds(30), Duration.ofSeconds(25), Duration.ofSeconds(28));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 무작위_차감폭이_0이면_설정한_TTL을_그대로_사용한다() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var cache = new RedisProductListCache(redis, mapper, meters, 30, 0);
        cache.put(new ProductListKey(null, ProductSort.LATEST, 0, 20), page);
        var ttl = ArgumentCaptor.forClass(Duration.class);
        verify(values).set(anyString(), anyString(), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(30));
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "30, -1", "30, 30", "30, 31"})
    void TTL이_양수가_아니거나_차감폭이_TTL이상이면_시작시_거절한다(long ttlSeconds, long jitterSeconds) {
        assertThatThrownBy(() -> new RedisProductListCache(redis, mapper, meters, ttlSeconds, jitterSeconds))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
