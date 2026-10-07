package com.loopers.interfaces.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.interfaces.api.brand.BrandDto;
import com.loopers.utils.DatabaseCleanUp;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrandApiE2ETest {
    @Autowired private BrandRepository brands;

    @Autowired private ProductRepository products;

    @Autowired private TestRestTemplate rest;

    @Autowired private MockMvc mvc;

    @Autowired private ObjectMapper mapper;

    @PersistenceContext private EntityManager entityManager;

    @Autowired private DatabaseCleanUp cleanUp;

    private static final String ADMIN_BRANDS = "/api-admin/v1/brands";

    @Test
    void 브랜드_등록_결과와_저장한_이름이_일치한다() throws Exception {
        // arrange
        BrandDto.Request input = new BrandDto.Request(" 브랜드 ");
        var request =
                post(ADMIN_BRANDS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isCreated()).andExpect(jsonPath("$.data.name").value("브랜드"));
        long brandId =
                mapper.readTree(response.andReturn().getResponse().getContentAsByteArray())
                        .requiredAt("/data/brandId")
                        .longValue();
        assertThat(brands.findById(brandId).orElseThrow().getName()).isEqualTo("브랜드");
    }

    @Test
    void 고객은_식별_없이_브랜드_이름을_조회한다() {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));

        // act
        var response = rest.getForEntity("/api/v1/brands/" + brand.getId(), JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().requiredAt("/data/name").asText()).isEqualTo("브랜드");
        assertThat(response.getBody().requiredAt("/data").has("deleted")).isFalse();
    }

    @Test
    void 브랜드_수정_결과를_저장한다() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("기존"));
        BrandDto.Request input = new BrandDto.Request("변경");
        var request =
                put(ADMIN_BRANDS + "/" + brand.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("변경"));
        assertThat(brands.findById(brand.getId()).orElseThrow().getName()).isEqualTo("변경");
    }

    @Test
    void 브랜드_삭제는_행을_보존하고_삭제_상태를_저장한다() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        var request =
                delete(ADMIN_BRANDS + "/" + brand.getId())
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());
        assertThat(brands.findById(brand.getId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    void 삭제된_브랜드는_고객_상세_조회를_거절한다() {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        brand.delete(false);
        brands.save(brand);

        // act
        var response = rest.getForEntity("/api/v1/brands/" + brand.getId(), JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText())
                .isEqualTo("BRAND_NOT_FOUND");
    }

    @Test
    void 관리자는_삭제한_브랜드_상세를_조회한다() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        brand.delete(false);
        brands.save(brand);
        var request =
                get(ADMIN_BRANDS + "/" + brand.getId())
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk()).andExpect(jsonPath("$.data.deleted").value(true));
    }

    @Test
    void 관리자_목록에는_삭제한_브랜드가_포함된다() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        brand.delete(false);
        brands.save(brand);
        var request = get(ADMIN_BRANDS).with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].deleted").value(true));
    }

    @Test
    void 이미_삭제한_브랜드의_삭제_재요청은_성공한다() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        brand.delete(false);
        brands.save(brand);
        var request =
                delete(ADMIN_BRANDS + "/" + brand.getId())
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk());
        assertThat(brands.findById(brand.getId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    void 삭제한_브랜드의_수정은_이름을_바꾸지_않는다() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        brand.delete(false);
        brands.save(brand);
        BrandDto.Request input = new BrandDto.Request("변경");
        var request =
                put(ADMIN_BRANDS + "/" + brand.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
        assertThat(brands.findById(brand.getId()).orElseThrow().getName()).isEqualTo("브랜드");
    }

    @Test
    void 재고가_0이어도_미삭제_상품이_있으면_브랜드를_삭제할_수_없다() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        var request =
                delete(ADMIN_BRANDS + "/" + brand.getId())
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_HAS_ACTIVE_PRODUCTS"));
        assertThat(brands.findById(brand.getId()).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    void 연결_상품이_모두_삭제되면_브랜드를_삭제할_수_있다() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(0);
        product = products.save(product);
        product.delete();
        products.save(product);
        var request =
                delete(ADMIN_BRANDS + "/" + brand.getId())
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk());
        assertThat(brands.findById(brand.getId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    void 브랜드가_없으면_관리자_목록은_빈_배열이다() throws Exception {
        // arrange
        var request = get(ADMIN_BRANDS).with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 미식별_요청은_관리자_목록을_조회할_수_없다() throws Exception {
        // arrange
        var request = get(ADMIN_BRANDS).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.meta.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    void 일반_사용자는_관리자_목록을_조회할_수_없다() throws Exception {
        // arrange
        var request = get(ADMIN_BRANDS).with(csrf()).with(user("customer").roles("USER"));

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.meta.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    void CSRF가_있어도_미식별_요청은_브랜드를_등록할_수_없다() throws Exception {
        // arrange
        var request =
                post(ADMIN_BRANDS)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new BrandDto.Request("거절")));

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.meta.errorCode").value("ACCESS_DENIED"));
        assertThat(
                        entityManager
                                .createQuery("select count(e) from BrandJpaEntity e", Long.class)
                                .getSingleResult())
                .isZero();
    }

    @Test
    void CSRF가_있어도_일반_사용자는_브랜드를_등록할_수_없다() throws Exception {
        // arrange
        var request =
                post(ADMIN_BRANDS)
                        .with(csrf())
                        .with(user("customer").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new BrandDto.Request("거절")));

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.meta.errorCode").value("ACCESS_DENIED"));
        assertThat(
                        entityManager
                                .createQuery("select count(e) from BrandJpaEntity e", Long.class)
                                .getSingleResult())
                .isZero();
    }

    @Test
    void 관리자라도_CSRF가_없으면_브랜드_등록을_거절한다() throws Exception {
        // arrange
        var request =
                post(ADMIN_BRANDS)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new BrandDto.Request("거절")));

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isForbidden());
        assertThat(
                        entityManager
                                .createQuery("select count(e) from BrandJpaEntity e", Long.class)
                                .getSingleResult())
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\" \"}"})
    void 빈_브랜드_이름은_저장하지_않는다(String invalidBody) throws Exception {
        // arrange
        var request =
                post(ADMIN_BRANDS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_REQUEST"));
        assertThat(
                        entityManager
                                .createQuery("select count(e) from BrandJpaEntity e", Long.class)
                                .getSingleResult())
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=-1", "?size=0", "?size=101"})
    void 유효하지_않은_페이지_조건을_거절한다(String query) throws Exception {
        // arrange
        var request = get(ADMIN_BRANDS + query).with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    void 없는_브랜드의_조회를_거절한다() throws Exception {
        // arrange
        var request = get(ADMIN_BRANDS + "/999").with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
    }

    @Test
    void 없는_브랜드의_삭제를_거절한다() throws Exception {
        // arrange
        var request = delete(ADMIN_BRANDS + "/999").with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
