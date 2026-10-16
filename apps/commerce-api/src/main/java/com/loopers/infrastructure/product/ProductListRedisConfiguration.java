package com.loopers.infrastructure.product;

import com.loopers.config.redis.RedisProperties;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class ProductListRedisConfiguration {
    @Bean("productListRedisConnection")
    public LettuceConnectionFactory productListRedisConnection(
            RedisProperties redis, @Value("${product-list-cache.timeout-ms:200}") long timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("캐시 대기 시간은 양수여야 합니다.");
        }
        Duration timeout = Duration.ofMillis(timeoutMillis);
        var endpoint = new RedisStandaloneConfiguration(redis.master().host(), redis.master().port());
        endpoint.setDatabase(redis.database());
        var options = ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build();
        var client = LettuceClientConfiguration.builder()
                .clientOptions(options).commandTimeout(timeout).shutdownTimeout(Duration.ZERO).build();
        return new LettuceConnectionFactory(endpoint, client);
    }

    @Bean("productListRedisTemplate")
    public StringRedisTemplate productListRedisTemplate(
            @Qualifier("productListRedisConnection") LettuceConnectionFactory connection) {
        return new StringRedisTemplate(connection);
    }
}
