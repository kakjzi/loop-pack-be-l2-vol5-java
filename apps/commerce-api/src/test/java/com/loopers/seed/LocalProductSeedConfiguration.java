package com.loopers.seed;

import com.loopers.application.brand.fixture.BrandFixture;
import com.loopers.application.like.fixture.LikeFixture;
import com.loopers.config.jpa.JpaConfig;
import com.loopers.infrastructure.brand.BrandRepositoryImpl;
import com.loopers.infrastructure.like.ProductLikeRepositoryImpl;
import com.loopers.infrastructure.product.ProductRepositoryImpl;
import com.loopers.infrastructure.product.fixture.ProductBatchInserter;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.infrastructure.user.fixture.UserFixture;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

// 필요한 빈만 구성해 일반 테스트의 Testcontainers 설정을 읽지 않는다.
@TestConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ComponentScan(basePackageClasses = JpaConfig.class)
@Import({
        BrandRepositoryImpl.class, ProductRepositoryImpl.class, ProductLikeRepositoryImpl.class,
        BrandFixture.class, ProductFixture.class, ProductBatchInserter.class, UserFixture.class, LikeFixture.class,
        LocalProductSeedService.class
})
public class LocalProductSeedConfiguration {}
