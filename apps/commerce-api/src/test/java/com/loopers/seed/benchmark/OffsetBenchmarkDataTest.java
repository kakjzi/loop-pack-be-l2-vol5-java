package com.loopers.seed.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OffsetBenchmarkDataTest {
    @Test
    void 같은_시드와_호출_순서로_같은_상품을_만든다() {
        var first = new OffsetBenchmarkData();
        var second = new OffsetBenchmarkData();

        assertThat(first.next(0, 60)).isEqualTo(second.next(0, 60));
        assertThat(first.next(60, 60)).isEqualTo(second.next(60, 60));
    }

    @Test
    void 브랜드를_섞고_가격과_좋아요를_브랜드마다_고르게_배정한다() {
        var rows = new OffsetBenchmarkData().next(0, 60);

        assertThat(rows).hasSize(60);
        assertThat(rows.stream().map(OffsetBenchmarkData.ProductRow::id).toList())
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 60).boxed().toList());
        assertThat(rows.subList(0, 10).stream().map(OffsetBenchmarkData.ProductRow::brandId).toList())
                .containsExactly(1L, 1L, 1L, 1L, 1L, 2L, 2L, 2L, 2L, 2L);
        for (long brand : new long[]{1, 2}) {
            var selected = rows.stream().filter(row -> row.brandId() == brand).toList();
            assertThat(selected).hasSize(30);
            assertThat(selected.stream().map(OffsetBenchmarkData.ProductRow::price).distinct()).hasSize(6);
            for (int count = 0; count <= 4; count++) {
                int expectedCount = count;
                assertThat(selected.stream().filter(row -> row.likeCount() == expectedCount).count()).isEqualTo(6);
            }
        }
    }
}
