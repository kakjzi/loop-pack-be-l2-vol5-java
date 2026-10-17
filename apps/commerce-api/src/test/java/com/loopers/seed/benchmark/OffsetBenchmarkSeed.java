package com.loopers.seed.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class OffsetBenchmarkSeed {
    private OffsetBenchmarkSeed() {}

    public static void main(String[] args) throws Exception {
        boolean popularLikes = args.length == 1 && args[0].startsWith("--popular-likes=");
        if (args.length > 1 || (args.length == 1 && !"--schema-only".equals(args[0]) && !popularLikes)) {
            throw new IllegalArgumentException("기본 실행, --schema-only 또는 --popular-likes=사용자수만 허용합니다.");
        }
        int usersPerProduct = popularLikes ? Integer.parseInt(args[0].substring("--popular-likes=".length())) : 0;
        // CLI 실행 대상은 격리 DB로 고정한다. 환경변수가 원래 loopers DB로 바꾸지 못하게 한다.
        boolean schemaOnly = args.length == 1 && !popularLikes;
        List<String> settings = new ArrayList<>(List.of(
                "--spring.config.name=offset-benchmark-seed",
                "--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://127.0.0.1:13306/loopers_offset_bench",
                "--spring.jpa.hibernate.ddl-auto=" + (schemaOnly ? "none" : "validate")));
        if (schemaOnly) {
            Files.deleteIfExists(Path.of("build/offset-benchmark-schema.sql"));
            settings.add("--spring.jpa.properties.jakarta.persistence.schema-generation.database.action=none");
            settings.add("--spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create");
            settings.add("--spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=./build/offset-benchmark-schema.sql");
        }
        try (var context = new SpringApplicationBuilder(OffsetBenchmarkSeedConfiguration.class)
                .run(settings.toArray(String[]::new))) {
            if (popularLikes) {
                long products = context.getBean(JdbcTemplate.class)
                        .queryForObject("select count(*) from products", Long.class);
                if (products != 1_000_000) {
                    throw new IllegalStateException("상품 100만 건의 기존 전용 실험 DB가 필요합니다.");
                }
                var result = context.getBean(PopularProductLikesSeedWriter.class).addLikes(usersPerProduct);
                Files.createDirectories(Path.of("build"));
                new ObjectMapper().writerWithDefaultPrettyPrinter()
                        .writeValue(Path.of("build/popular-product-likes-result.json").toFile(), result);
            } else if (!schemaOnly) {
                context.getBean(OffsetBenchmarkSeedWriter.class).seed(1_000_000);
            }
        }
    }
}
