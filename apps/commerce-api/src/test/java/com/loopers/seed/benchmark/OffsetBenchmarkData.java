package com.loopers.seed.benchmark;

import com.loopers.config.FixtureMonkeyConfig;
import com.navercorp.fixturemonkey.FixtureMonkey;

import net.jqwik.api.Arbitraries;

import java.util.ArrayList;
import java.util.List;

// 생성만 담당한다. 브랜드·가격·좋아요는 균등 배정, 이름·재고는 고정 난수로 만든다.
public final class OffsetBenchmarkData {
    public static final long SEED = 20261015L;
    private static final long[] PRICES = {1_000, 5_000, 10_000, 30_000, 50_000, 100_000};

    public List<ProductRow> next(int offset, int size) {
        if (offset < 0 || size <= 0) {
            throw new IllegalArgumentException("offset은 0 이상, size는 양수여야 합니다.");
        }
        // Fixture Monkey의 난수 상태는 스레드에서 공유되므로 묶음별로 seed를 다시 고정한다.
        FixtureMonkey monkey = FixtureMonkeyConfig.create(SEED + offset);
        List<RandomFields> fields = monkey.giveMeBuilder(RandomFields.class)
                .set("name", Arbitraries.strings().withCharRange('a', 'z').ofMinLength(5).ofMaxLength(30))
                .set("stock", Arbitraries.integers().between(0, 100))
                .sampleList(size);
        List<ProductRow> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            int ordinal = Math.addExact(offset, index);
            RandomFields random = fields.get(index);
            result.add(new ProductRow(ordinal + 1L, (ordinal / 5) % 2 + 1L,
                    random.name(), PRICES[(ordinal / 10) % PRICES.length], random.stock(), ordinal % 5));
        }
        return result;
    }

    public record RandomFields(String name, int stock) {}
    public record ProductRow(long id, long brandId, String name, long price, int stock, int likeCount) {}
}
