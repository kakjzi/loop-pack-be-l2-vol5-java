package com.loopers.interfaces.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.like.ProductLike;
import com.loopers.domain.like.ProductLikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LikeApiE2ETest {
    @Autowired
    private ProductLikeRepository likes;

    @Autowired
    private BrandRepository brands;

    @Autowired
    private ProductRepository products;

    @Autowired
    private TestRestTemplate rest;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private DatabaseCleanUp cleanUp;

    @Test
    void 좋아요_등록은_요청자와_상품의_관계를_저장한다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/products/" + product.getId() + "/likes", HttpMethod.POST,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().has("data")).isFalse();
        assertThat(likes.exists(1, product.getId())).isTrue();
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isEqualTo(1);
    }

    @Test
    void 이미_등록한_좋아요를_다시_등록해도_관계는_하나다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        likes.save(new ProductLike(1, product.getId()));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/products/" + product.getId() + "/likes", HttpMethod.POST,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().has("data")).isFalse();
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isEqualTo(1);
    }

    @Test
    void 상품의_좋아요_수는_저장된_사용자_관계_수와_같다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(2L)));
        likes.save(new ProductLike(1, product.getId()));
        likes.save(new ProductLike(2, product.getId()));

        // act
        var response = rest.getForEntity("/api/v1/products/" + product.getId(), JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode count = response.getBody().requiredAt("/data/likeCount");
        assertThat(count.isIntegralNumber()).isTrue();
        assertThat(count.longValue()).isEqualTo(2);
    }

    @Test
    void DB는_같은_사용자와_상품의_중복_관계를_거절한다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        likes.save(new ProductLike(1, product.getId()));
        ProductLike duplicate = new ProductLike(1, product.getId());

        // act
        assertThrows(DataIntegrityViolationException.class, () -> likes.save(duplicate));

        // assert
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isEqualTo(1);
    }

    @Test
    void 좋아요_취소는_본인_관계를_제거한다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        likes.save(new ProductLike(1, product.getId()));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/products/" + product.getId() + "/likes", HttpMethod.DELETE,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(likes.exists(1, product.getId())).isFalse();
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isZero();
    }

    @Test
    void 본인_관계가_없는_상품의_취소도_성공한다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/products/" + product.getId() + "/likes", HttpMethod.DELETE,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isZero();
    }

    @Test
    void 좋아요_취소는_다른_사용자의_관계를_유지한다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(2L)));
        likes.save(new ProductLike(1, product.getId()));
        likes.save(new ProductLike(2, product.getId()));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/products/" + product.getId() + "/likes", HttpMethod.DELETE,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(likes.exists(1, product.getId())).isFalse();
        assertThat(likes.exists(2, product.getId())).isTrue();
    }

    @Test
    void 내_좋아요_목록에는_내가_등록한_상품만_나온다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(2L)));
        Product otherProduct = Product.create(brand.getId(), "otherProduct", 2_000);
        otherProduct.setStock(0);
        otherProduct = products.save(otherProduct);
        likes.save(new ProductLike(1, product.getId()));
        likes.save(new ProductLike(2, otherProduct.getId()));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/users/1/likes", HttpMethod.GET, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.findValues("productId")).extracting(JsonNode::longValue).containsExactly(product.getId());
    }

    @Test
    void 다른_사용자의_좋아요_목록은_접근_거절로_응답한다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(2L)));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/users/2/likes", HttpMethod.GET, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void 삭제한_상품에는_기존_관계가_있어도_등록할_수_없다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        likes.save(new ProductLike(1, product.getId()));
        product.delete();
        products.save(product);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/products/" + product.getId() + "/likes", HttpMethod.POST,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isEqualTo(1);
    }

    @Test
    void 삭제한_상품은_내_좋아요_목록에서_제외하고_관계는_보존한다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        likes.save(new ProductLike(1, product.getId()));
        product.delete();
        products.save(product);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/users/1/likes", HttpMethod.GET, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items).isEmpty();
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isEqualTo(1);
    }

    @Test
    void 삭제한_상품에_남은_본인_관계를_취소할_수_있다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        likes.save(new ProductLike(1, product.getId()));
        product.delete();
        products.save(product);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/products/" + product.getId() + "/likes", HttpMethod.DELETE,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(likes.exists(1, product.getId())).isFalse();
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isZero();
    }

    @Test
    void 없는_상품의_좋아요_등록을_거절한다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/products/999/likes", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isZero();
    }

    @Test
    void 유효한_상품이라도_식별_헤더가_없으면_등록할_수_없다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        HttpEntity<Void> request = new HttpEntity<>(new HttpHeaders());

        // act
        var response = rest.exchange("/api/v1/products/" + product.getId() + "/likes", HttpMethod.POST,
            request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isZero();
    }

    @Test
    void 없는_상품의_없는_관계를_취소해도_성공한다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/products/999/likes", HttpMethod.DELETE, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(entityManager.createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
            .getSingleResult()).isZero();
    }

    @Test
    void 좋아요_취소_결과는_공개_상품의_좋아요_수에_반영된다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        likes.save(new ProductLike(1, product.getId()));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/products/" + product.getId() + "/likes", HttpMethod.DELETE,
            request, JsonNode.class);
        var detail = rest.getForEntity("/api/v1/products/" + product.getId(), JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        JsonNode count = detail.getBody().requiredAt("/data/likeCount");
        assertThat(count.isIntegralNumber()).isTrue();
        assertThat(count.longValue()).isZero();
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
