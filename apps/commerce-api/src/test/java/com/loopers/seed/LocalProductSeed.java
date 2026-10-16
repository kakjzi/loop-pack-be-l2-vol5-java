package com.loopers.seed;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.builder.SpringApplicationBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

public final class LocalProductSeed {
    private LocalProductSeed() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("결과 JSON 경로를 지정해야 합니다.");
        }
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        try (var context = new SpringApplicationBuilder(LocalProductSeedConfiguration.class)
                .run("--spring.config.name=local-product-seed")) {
            var result = context.getBean(LocalProductSeedService.class).seed();
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
        }
    }
}
