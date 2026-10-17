package com.loopers.infrastructure.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import com.loopers.application.product.query.ProductListPage;
import com.loopers.application.product.query.ProductSort;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

@Testcontainers
class ProductCacheRedisFailureTest extends ProductCacheTestSupport {
    // 이 클래스가 만든 Redis만 중단한다. 재시작 시 예전 snapshot을 복원하지 않는다.
    private static final int ownedRedisPort = availablePort();

    @Container
    private static final GenericContainer<?> ownedRedis = new GenericContainer<>("redis:latest")
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(ownedRedisPort), ExposedPort.tcp(6379))))
            .withCommand("redis-server", "--save", "", "--appendonly", "no");

    private static int availablePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", ownedRedis::getHost);
        registry.add("datasource.redis.master.port", () -> ownedRedis.getMappedPort(6379));
        registry.add("datasource.redis.replicas[0].host", ownedRedis::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> ownedRedis.getMappedPort(6379));
    }

    @Autowired private MeterRegistry meters;

    @Test
    void 정상_Redis도_키가_많으면_무효화를_제한하고_남은_표시는_만료로_회복한다() {
        var product = product();
        String prefix = "scan-boundary-test:" + UUID.randomUUID() + ":";
        List<String> noise = IntStream.range(0, 10_000).mapToObj(i -> prefix + i).toList();
        try {
            // 이 테스트가 만든 Redis에만 비대상 키를 넣는다. 상품 목록 이외의 새 캐시는 구현하지 않는다.
            for (int start = 0; start < noise.size(); start += 250) {
                List<String> batch = noise.subList(start, Math.min(start + 250, noise.size()));
                redis.executePipelined((RedisCallback<Object>) connection -> {
                    for (String name : batch) {
                        connection.stringCommands().setEx(name.getBytes(StandardCharsets.UTF_8), 60, new byte[] {1});
                    }
                    return null;
                });
            }
            var page = ProductListPage.from(query.list(null, PageRequest.of(0, 100), ProductSort.LATEST));
            var cachedKeys = IntStream.rangeClosed(1, 100)
                    .mapToObj(size -> key(null, "latest", 0, size)).toList();
            cachedKeys.forEach(key -> cache.put(key, page));
            double incompleteBefore = meters.counter("product.list.cache.invalidation.incomplete").count();

            long started = System.nanoTime();
            updateProduct.update(product.getId(), "변경", 900L);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            assertThat(meters.counter("product.list.cache.invalidation.incomplete").count())
                    .isEqualTo(incompleteBefore + 1);
            assertThat(redis.hasKey(noise.getFirst())).isTrue();
            var remaining = cachedKeys.stream()
                    .filter(key -> Boolean.TRUE.equals(redis.hasKey(key.value())))
                    .findFirst().orElseThrow(() -> new AssertionError("제한 뒤 남은 키가 필요합니다."));
            assertThat(facade.list(null, 0, remaining.size(), "latest").getContent().getFirst().price())
                    .isEqualTo(1_000);
            expire(remaining);
            assertThat(facade.list(null, 0, remaining.size(), "latest").getContent().getFirst().price())
                    .isEqualTo(900);
        } finally {
            for (int start = 0; start < noise.size(); start += 250) {
                redis.delete(noise.subList(start, Math.min(start + 250, noise.size())));
            }
        }
    }

    @Test
    void 실제_Redis_중단중에도_DB로_우회하고_동일_Redis_재시작뒤_재채움한다() {
        var product = product();
        var key = key(null, "latest", 0, 20);
        facade.list(null, 0, 20, "latest");
        assertThat(cache.get(key)).isPresent();
        int originalPort = ownedRedis.getMappedPort(6379);
        ownedRedis.getDockerClient().stopContainerCmd(ownedRedis.getContainerId()).withTimeout(1).exec();
        try {
            long started = System.nanoTime();
            updateProduct.update(product.getId(), "장애중 변경", 900L);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            started = System.nanoTime();
            assertThat(facade.list(null, 0, 20, "latest").getContent().getFirst().price()).isEqualTo(900);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            assertThat(meters.counter("product.list.cache.errors", "operation", "get").count()).isPositive();
            assertThat(meters.counter("product.list.cache.errors", "operation", "put").count()).isPositive();
            assertThat(meters.counter("product.list.cache.errors", "operation", "invalidate").count()).isPositive();
        } finally {
            ownedRedis.getDockerClient().startContainerCmd(ownedRedis.getContainerId()).exec();
            var bindings = ownedRedis.getDockerClient().inspectContainerCmd(ownedRedis.getContainerId())
                    .exec().getNetworkSettings().getPorts().getBindings()
                    .get(ExposedPort.tcp(6379));
            assertThat(Integer.parseInt(bindings[0].getHostPortSpec())).isEqualTo(originalPort);
            await().atMost(Duration.ofSeconds(15)).ignoreExceptions()
                    .until(() -> {
                        try (var connection = redis.getConnectionFactory().getConnection()) {
                            return "PONG".equals(connection.ping());
                        }
                    });
        }
        assertThat(facade.list(null, 0, 20, "latest").getContent().getFirst().price()).isEqualTo(900);
        assertThat(cache.get(key).orElseThrow().items().getFirst().price()).isEqualTo(900);
        statistics().clear();
        assertThat(facade.list(null, 0, 20, "latest").getContent().getFirst().price()).isEqualTo(900);
        assertThat(statistics().getQueryExecutionCount()).isEqualTo(1);
    }
}
