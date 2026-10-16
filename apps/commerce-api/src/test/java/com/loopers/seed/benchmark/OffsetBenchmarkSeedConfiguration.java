package com.loopers.seed.benchmark;

import com.loopers.config.jpa.JpaConfig;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

@TestConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ComponentScan(basePackageClasses = JpaConfig.class)
@Import({OffsetBenchmarkSeedWriter.class, PopularProductLikesSeedWriter.class})
public class OffsetBenchmarkSeedConfiguration {}
