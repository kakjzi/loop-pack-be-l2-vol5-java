package com.loopers.infrastructure.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.product.query.ProductListCache;
import com.loopers.application.product.query.ProductListKey;
import com.loopers.application.product.query.ProductListPage;

import io.micrometer.core.instrument.MeterRegistry;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.NestedMultiOutput;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

@Repository
public class RedisProductListCache implements ProductListCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final long ttlSeconds;
    private final long jitterSeconds;
    private final LongUnaryOperator randomOffset;

    @Autowired
    public RedisProductListCache(
            @Qualifier("productListRedisTemplate") StringRedisTemplate redis,
            ObjectMapper mapper, MeterRegistry meters,
            @Value("${product-list-cache.ttl-seconds:30}") long ttlSeconds,
            @Value("${product-list-cache.ttl-jitter-seconds:5}") long jitterSeconds) {
        this(redis, mapper, meters, ttlSeconds, jitterSeconds,
                bound -> ThreadLocalRandom.current().nextLong(bound));
    }

    RedisProductListCache(StringRedisTemplate redis, ObjectMapper mapper, MeterRegistry meters,
            long ttlSeconds, long jitterSeconds, LongUnaryOperator randomOffset) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("캐시 TTL은 양수여야 합니다.");
        }
        if (jitterSeconds < 0 || jitterSeconds >= ttlSeconds) {
            throw new IllegalArgumentException("TTL 무작위 차감 범위는 0 이상 TTL 미만이어야 합니다.");
        }
        this.redis = redis;
        this.mapper = mapper;
        this.meters = meters;
        this.ttlSeconds = ttlSeconds;
        this.jitterSeconds = jitterSeconds;
        this.randomOffset = randomOffset;
    }

    @Override
    public Optional<ProductListPage> get(ProductListKey key) {
        try {
            String value = redis.opsForValue().get(key.value());
            if (value == null) {
                outcome("miss");
                return Optional.empty();
            }
            ProductListPage page = mapper.readValue(value, ProductListPage.class);
            if (page == null) {
                outcome("miss");
                return Optional.empty();
            }
            outcome("hit");
            return Optional.of(page);
        } catch (DataAccessException | JsonProcessingException | IllegalArgumentException failure) {
            error("get");
            return Optional.empty();
        }
    }

    @Override
    public void put(ProductListKey key, ProductListPage page) {
        try {
            // 저장마다 만료를 분산하되 ttlSeconds 상한은 넘기지 않는다. hit에서는 연장하지 않는다.
            Duration ttl = Duration.ofSeconds(ttlSeconds - randomOffset.applyAsLong(jitterSeconds + 1));
            redis.opsForValue().set(key.value(), mapper.writeValueAsString(page), ttl);
        } catch (DataAccessException | JsonProcessingException failure) {
            error("put");
        }
    }

    @Override
    public boolean invalidateAll() {
        // 최초 시도와 재시도 1회가 전체 예산을 공유한다. 지속 장애/키 증가에 무한 대기하지 않는다.
        long deadline = System.nanoTime() + Duration.ofMillis(500).toNanos();
        for (int attempt = 0; attempt < 2 && System.nanoTime() < deadline; attempt++) {
            try (var connection = (LettuceConnection) redis.getConnectionFactory().getConnection()) {
                if (deletePages(connection, deadline)) {
                    return true;
                }
            } catch (DataAccessException failure) {
                error("invalidate");
            }
        }
        meters.counter("product.list.cache.invalidation.incomplete").increment();
        return false;
    }

    private boolean deletePages(LettuceConnection connection, long deadline) {
        String cursor = "0";
        for (int batch = 0; batch < 20 && System.nanoTime() < deadline; batch++) {
            // Cursor.hasNext 내부의 빈 SCAN 반복까지 제한하기 위해 명령을 한 단계씩 실행한다.
            var result = (List<?>) connection.execute("SCAN",
                    new NestedMultiOutput<>(ByteArrayCodec.INSTANCE), bytes(cursor), bytes("MATCH"),
                    bytes("product-list:v1:*"), bytes("COUNT"), bytes("100"));
            if (result == null) {
                return false;
            }
            cursor = new String((byte[]) result.get(0), StandardCharsets.UTF_8);
            var keys = (List<?>) result.get(1);
            if (System.nanoTime() >= deadline) {
                return false;
            }
            if (!keys.isEmpty()) {
                byte[][] raw = keys.stream().map(byte[].class::cast).toArray(byte[][]::new);
                connection.keyCommands().del(raw);
            }
            if ("0".equals(cursor)) {
                return true;
            }
        }
        return false;
    }

    private byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private void outcome(String outcome) {
        meters.counter("product.list.cache.requests", "outcome", outcome).increment();
    }

    private void error(String operation) {
        meters.counter("product.list.cache.errors", "operation", operation).increment();
    }
}
