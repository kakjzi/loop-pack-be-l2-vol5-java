package com.loopers.config;

import com.loopers.testcontainers.RedisTestContainersConfig;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

// RedisProperties 바인딩보다 먼저 실제 테스트 컨테이너 endpoint를 등록한다.
public class RedisTestInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        new RedisTestContainersConfig();
    }
}
