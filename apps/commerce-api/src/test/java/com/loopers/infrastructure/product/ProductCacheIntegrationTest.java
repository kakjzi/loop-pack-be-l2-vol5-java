package com.loopers.infrastructure.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.loopers.application.product.query.ProductSort;
import com.loopers.application.product.query.CachedProductList;
import com.loopers.application.product.query.ProductView;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

class ProductCacheIntegrationTest extends ProductCacheTestSupport {
    @Autowired private CachedProductList cachedLists;

    @Test
    void 캐시를_끄면_같은_입력도_DB만_조회하고_결과가_같다() {
        var product = product();
        var expected = facade.list(null, 0, 20, "latest");
        ReflectionTestUtils.setField(cachedLists, "enabled", false);
        try {
            products.updateProduct(product.getId(), "직접DB변경", 900);
            var dbOnly = facade.list(null, 0, 20, "latest");
            assertThat(dbOnly.getContent().getFirst().price()).isEqualTo(900);
            assertThat(dbOnly.getTotalElements()).isEqualTo(expected.getTotalElements());
            assertThat(cache.get(key(null, "latest", 0, 20)).orElseThrow().items().getFirst().price())
                    .isEqualTo(1_000);
        } finally {
            ReflectionTestUtils.setField(cachedLists, "enabled", true);
        }
    }

    @Test
    void 같은_목록의_hit은_노출확인만_남기고_목록과_COUNT를_생략한다() {
        var brand = brands.createBrand("캐시 브랜드");
        for (int index = 0; index < 3; index++) {
            products.createProduct(brand.getId(), "상품" + index, 1_000 + index, 0);
        }
        var statistics = statistics();
        var expected = query.list(brand.getId(), PageRequest.of(0, 2), ProductSort.PRICE_ASC);
        statistics.clear();
        var miss = facade.list(brand.getId(), 0, 2, "price_asc");
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(2);
        assertThat(miss).usingRecursiveComparison().isEqualTo(expected);
        assertThat(redis.opsForValue().get(key(brand.getId(), "price_asc", 0, 2).value())).isNotNull();

        statistics.clear();
        var hit = facade.list(brand.getId(), 0, 2, "price_asc");
        assertThat(hit).usingRecursiveComparison().isEqualTo(expected);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
    }

    @Test
    void 키는_브랜드_정렬_페이지_크기와_응답버전을_구분한다() {
        var first = brands.createBrand("첫 브랜드");
        var second = brands.createBrand("다른 브랜드");
        products.createProduct(first.getId(), "첫상품", 2_000, 0);
        products.createProduct(first.getId(), "둘째상품", 1_000, 0);
        products.createProduct(second.getId(), "다른상품", 3_000, 0);
        for (Long brand : new Long[] {null, first.getId(), second.getId()}) {
            for (String sort : List.of("latest", "price_asc", "likes_desc")) {
                for (int size : List.of(1, 2)) {
                    for (int page : List.of(0, 1, 5)) {
                        var expected = query.list(brand, PageRequest.of(page, size), ProductSort.from(sort));
                        assertThat(facade.list(brand, page, size, sort))
                                .usingRecursiveComparison().isEqualTo(expected);
                        assertThat(facade.list(brand, page, size, sort))
                                .usingRecursiveComparison().isEqualTo(expected);
                    }
                }
            }
        }
    }

    @Test
    void TTL은_25초에서_30초이고_hit은_남은_시간을_연장하지_않는다() {
        var product = product();
        var key = key(product.getBrandId(), "price_asc", 0, 20);
        long started = System.nanoTime();
        facade.list(product.getBrandId(), 0, 20, "price_asc");
        long remainingMillis = redis.getExpire(key.value(), TimeUnit.MILLISECONDS);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        // PTTL은 설정값이 아닌 남은 시간이다. 저장~확인 사이 경과와 ms 반올림 여유1을 반영한다.
        assertThat(remainingMillis).isBetween(Math.max(1L, 25_000L - elapsedMillis - 1), 30_000L);
        redis.expire(key.value(), Duration.ofSeconds(5));
        facade.list(product.getBrandId(), 0, 20, "price_asc");
        assertThat(redis.getExpire(key.value(), TimeUnit.MILLISECONDS)).isBetween(1L, 5_000L);
    }

    @Test
    void 관리자_가격수정_API가_commit되면_모든_페이지와_정렬을_무효화한다() throws Exception {
        var product = product();
        var cheap = createProduct.create(product.getBrandId(), "더싼상품", 900L);
        facade.list(null, 0, 1, "price_asc");
        facade.list(product.getBrandId(), 1, 1, "price_asc");
        facade.list(product.getBrandId(), 0, 20, "latest");

        mvc.perform(put("/api-admin/v1/products/" + product.getId())
                .with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"새상품명\",\"price\":800}"))
                .andExpect(status().isOk());

        assertThat(facade.list(null, 0, 1, "price_asc").getContent())
                .extracting(ProductView::productId).containsExactly(product.getId());
        assertThat(facade.list(product.getBrandId(), 1, 1, "price_asc").getContent())
                .extracting(ProductView::productId).containsExactly(cheap.productId());
        assertThat(facade.list(product.getBrandId(), 0, 20, "latest").getContent())
                .filteredOn(p -> p.productId() == product.getId())
                .extracting(ProductView::price).containsExactly(800L);
    }

    @Test
    void 빈목록도_상품생성_commit과_브랜드수정_뒤_새결과로_바뀐다() {
        var brand = brands.createBrand("기존브랜드");
        assertThat(facade.list(null, 0, 20, "latest")).isEmpty();
        var saved = createProduct.create(brand.getId(), "새상품", 1_000L);
        assertThat(facade.list(null, 0, 20, "latest").getContent())
                .extracting(ProductView::productId).containsExactly(saved.productId());
        updateBrand.update(brand.getId(), "변경브랜드");
        assertThat(facade.list(null, 0, 20, "latest").getContent().getFirst().brand().name())
                .isEqualTo("변경브랜드");
    }

    @Test
    void 좋아요_관계와_카운터는_즉시_변경하고_목록_수와_순서는_만료후_바뀐다() {
        var first = product();
        var second = createProduct.create(first.getBrandId(), "나중상품", 2_000L);
        users.createUser(1L);
        var key = key(first.getBrandId(), "likes_desc", 0, 20);
        var old = facade.list(first.getBrandId(), 0, 20, "likes_desc");
        assertThat(old.getContent()).extracting(ProductView::productId).containsExactly(second.productId(), first.getId());
        createLike.create(1L, first.getId());
        assertThat(query.find(first.getId()).orElseThrow().likeCount()).isEqualTo(1);
        assertThat(facade.list(first.getBrandId(), 0, 20, "likes_desc").getContent())
                .containsExactlyElementsOf(old.getContent());
        expire(key);
        var fresh = facade.list(first.getBrandId(), 0, 20, "likes_desc");
        assertThat(fresh.getContent()).extracting(ProductView::productId).containsExactly(first.getId(), second.productId());
        assertThat(fresh.getContent().getFirst().likeCount()).isEqualTo(1);
        deleteLike.delete(1L, first.getId());
        assertThat(query.find(first.getId()).orElseThrow().likeCount()).isZero();
        assertThat(facade.list(first.getBrandId(), 0, 20, "likes_desc").getContent().getFirst().likeCount()).isEqualTo(1);
        expire(key);
        assertThat(facade.list(first.getBrandId(), 0, 20, "likes_desc").getContent())
                .extracting(ProductView::productId).containsExactly(second.productId(), first.getId());
    }

    @Test
    void rollback된_가격은_캐시에_게시하지_않고_기존캐시도_무효화하지_않는다() {
        var product = product();
        var key = key(null, "latest", 0, 20);
        var old = facade.list(null, 0, 20, "latest");
        String cached = redis.opsForValue().get(key.value());
        transaction().executeWithoutResult(status -> {
            updateProduct.update(product.getId(), "미커밋", 900L);
            assertThat(facade.list(null, 0, 20, "latest").getContent().getFirst().price()).isEqualTo(900);
            assertThat(redis.opsForValue().get(key.value())).isEqualTo(cached);
            status.setRollbackOnly();
        });
        assertThat(redis.opsForValue().get(key.value())).isEqualTo(cached);
        assertThat(facade.list(null, 0, 20, "latest").getContent()).containsExactlyElementsOf(old.getContent());
    }

    @Test
    void 무효화실패에도_DB_commit은_유지하고_표시가격은_만료후_회복한다() {
        var product = product();
        var key = key(null, "latest", 0, 20);
        facade.list(null, 0, 20, "latest");
        doReturn(false).when(cache).invalidateAll();
        updateProduct.update(product.getId(), "변경", 900L);
        assertThat(query.find(product.getId()).orElseThrow().price()).isEqualTo(900);
        assertThat(facade.list(null, 0, 20, "latest").getContent().getFirst().price()).isEqualTo(1_000);
        expire(key);
        assertThat(facade.list(null, 0, 20, "latest").getContent().getFirst().price()).isEqualTo(900);
    }

    @Test
    void 삭제_무효화실패에도_삭제상품과_브랜드상품을_제외하고_페이지를_다시_채운다() {
        var deleted = product();
        var remaining = createProduct.create(deleted.getBrandId(), "남는상품", 2_000L);
        var other = product();
        facade.list(null, 0, 1, "price_asc");
        facade.list(deleted.getBrandId(), 0, 20, "latest");
        doReturn(false).when(cache).invalidateAll();
        deleteProduct.delete(deleted.getId());
        assertThat(facade.list(deleted.getBrandId(), 0, 20, "latest").getContent())
                .extracting(ProductView::productId).containsExactly(remaining.productId());
        deleteBrand.delete(deleted.getBrandId());
        var after = facade.list(null, 0, 20, "latest");
        assertThat(after.getContent()).extracting(ProductView::productId).containsExactly(other.getId());
        assertThat(after.getTotalElements()).isEqualTo(1);
    }

    @Test
    void DB_장애를_빈목록으로_감추지_않고_hit의_노출확인_실패도_전파한다() {
        var product = product();
        facade.list(null, 0, 20, "latest");
        doThrow(new DataAccessResourceFailureException("DB 장애"))
                .when(query).findVisibleIds(any());
        assertThatThrownBy(() -> facade.list(null, 0, 20, "latest"))
                .isInstanceOf(DataAccessResourceFailureException.class);
        doThrow(new DataAccessResourceFailureException("DB 장애"))
                .when(query).list(eq(product.getBrandId()), any(), any());
        assertThatThrownBy(() -> facade.list(product.getBrandId(), 0, 20, "latest"))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void 잘못된_캐시값은_DB로_우회해서_올바르게_다시_저장한다() {
        var product = product();
        var key = key(null, "latest", 0, 20);
        redis.opsForValue().set(key.value(), "broken-json", Duration.ofSeconds(30));
        assertThat(facade.list(null, 0, 20, "latest").getContent())
                .extracting(ProductView::productId).containsExactly(product.getId());
        assertThat(cache.get(key)).isPresent();
    }
}
