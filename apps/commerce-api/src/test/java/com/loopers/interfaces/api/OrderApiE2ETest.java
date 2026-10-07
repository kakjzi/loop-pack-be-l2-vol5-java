package com.loopers.interfaces.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.point.PointBalance;
import com.loopers.domain.point.PointBalanceRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.order.OrderJpaEntity;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.interfaces.api.order.OrderDto;
import com.loopers.interfaces.api.point.ChargePointController.ChargeRequest;
import com.loopers.utils.DatabaseCleanUp;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderApiE2ETest {
    @Autowired private BrandRepository brands;

    @Autowired private ProductRepository products;

    @Autowired private PointBalanceRepository points;

    @Autowired private TestRestTemplate rest;

    @Autowired private MockMvc mvc;

    @PersistenceContext private EntityManager entityManager;

    @Autowired private TransactionTemplate transactions;

    @Autowired private DatabaseCleanUp cleanUp;

    @Autowired private OrderRepository orders;

    @MockitoSpyBean private OrderJpaRepository orderJpaRepository;

    @Test
    void 주문_생성은_품목을_합산해_저장하고_재고_잔액을_유지한다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        Product secondProduct = Product.create(brand.getId(), "secondProduct", 1_000);
        secondProduct.setStock(5);
        secondProduct = products.save(secondProduct);
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        OrderDto.Create input =
                new OrderDto.Create(
                        List.of(
                                new OrderDto.ItemRequest(product.getId(), 2),
                                new OrderDto.ItemRequest(product.getId(), 1),
                                new OrderDto.ItemRequest(secondProduct.getId(), 1)));
        var request = new HttpEntity<>(input, headers);

        // act
        var response = rest.exchange("/api/v1/orders", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode data = response.getBody().requiredAt("/data");
        assertThat(data.required("status").asText()).isEqualTo("DRAFT");
        assertThat(data.has("paidAmount")).isFalse();
        assertThat(data.has("paymentResult")).isFalse();
        Order stored = orders.findById(data.required("orderId").longValue()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stored.getPaidAmount()).isNull();
        assertThat(stored.getItems()).hasSize(2);
        assertThat(stored.getItems().getFirst().getQuantity()).isEqualTo(3);
        assertThat(stored.getItems().getFirst().getUnitPrice()).isEqualTo(2_000);
        assertThat(stored.getTotalAmount()).isEqualTo(7_000);
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(products.findById(secondProduct.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(10_000);
    }

    @Test
    void 충전_10000원부터_여러_품목_7000원_결제와_잔액_조회까지_연결된다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        Product secondProduct = Product.create(brand.getId(), "secondProduct", 1_000);
        secondProduct.setStock(5);
        secondProduct = products.save(secondProduct);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        var chargeRequest = new HttpEntity<>(new ChargeRequest(10_000L), headers);
        var orderRequest =
                new HttpEntity<>(
                        new OrderDto.Create(
                                List.of(
                                        new OrderDto.ItemRequest(product.getId(), 2),
                                        new OrderDto.ItemRequest(secondProduct.getId(), 3))),
                        headers);
        HttpEntity<Void> identifiedRequest = new HttpEntity<>(headers);

        // act
        var charged =
                rest.exchange(
                        "/api/v1/points/charge", HttpMethod.POST, chargeRequest, JsonNode.class);
        var created =
                rest.exchange("/api/v1/orders", HttpMethod.POST, orderRequest, JsonNode.class);
        long orderId = created.getBody().requiredAt("/data/orderId").longValue();
        var confirmed =
                rest.exchange(
                        "/api/v1/orders/" + orderId + "/confirm",
                        HttpMethod.POST,
                        identifiedRequest,
                        JsonNode.class);
        var detail =
                rest.exchange(
                        "/api/v1/orders/" + orderId,
                        HttpMethod.GET,
                        identifiedRequest,
                        JsonNode.class);
        var list =
                rest.exchange("/api/v1/orders", HttpMethod.GET, identifiedRequest, JsonNode.class);
        var remaining =
                rest.exchange("/api/v1/points", HttpMethod.GET, identifiedRequest, JsonNode.class);

        // assert
        assertThat(charged.getStatusCode().value()).isEqualTo(200);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody().requiredAt("/data/status").asText()).isEqualTo("DRAFT");
        assertThat(created.getBody().requiredAt("/data").has("paidAmount")).isFalse();
        assertThat(created.getBody().requiredAt("/data").has("paymentResult")).isFalse();
        assertThat(confirmed.getStatusCode().value()).isEqualTo(200);
        assertThat(confirmed.getBody().requiredAt("/data/status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.getBody().requiredAt("/data/paidAmount").longValue()).isEqualTo(7_000);
        assertThat(confirmed.getBody().requiredAt("/data/paymentResult").asText())
                .isEqualTo("SUCCESS");
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        assertThat(detail.getBody()).isEqualTo(confirmed.getBody());
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody().requiredAt("/data/items/0"))
                .isEqualTo(confirmed.getBody().requiredAt("/data"));
        assertThat(remaining.getStatusCode().value()).isEqualTo(200);
        assertThat(remaining.getBody().requiredAt("/data/balance").longValue()).isEqualTo(3_000);
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(3);
        assertThat(products.findById(secondProduct.getId()).orElseThrow().getStock()).isEqualTo(2);
        assertThat(orders.findById(orderId).orElseThrow().getPaidAmount()).isEqualTo(7_000);
    }

    @Test
    void 현재_상품_가격이_바뀌어도_주문_생성_당시_단가로_결제한다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        Order order =
                orders.save(
                        Order.create(
                                1, List.of(new Order.RequestedItem(product.getId(), 2, 2_000))));
        product.update("변경", 3_000);
        products.save(product);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response =
                rest.exchange(
                        "/api/v1/orders/" + order.getId() + "/confirm",
                        HttpMethod.POST,
                        request,
                        JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().requiredAt("/data/paidAmount").longValue()).isEqualTo(4_000);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(6_000);
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(3);
    }

    @Test
    void 확정_후_상품이_삭제되어도_같은_결제_결과를_반환하고_추가_차감하지_않는다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        Order order =
                orders.save(
                        Order.create(
                                1, List.of(new Order.RequestedItem(product.getId(), 2, 2_000))));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);
        var first =
                rest.exchange(
                        "/api/v1/orders/" + order.getId() + "/confirm",
                        HttpMethod.POST,
                        request,
                        JsonNode.class);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        product = products.findById(product.getId()).orElseThrow();
        product.delete();
        products.save(product);

        // act
        var response =
                rest.exchange(
                        "/api/v1/orders/" + order.getId() + "/confirm",
                        HttpMethod.POST,
                        request,
                        JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(first.getBody());
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(3);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(6_000);
    }

    @Test
    void 확정된_주문도_다른_사용자에게_결제_결과를_반환하지_않는다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(2L)));
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        Order order =
                orders.save(
                        Order.create(
                                1, List.of(new Order.RequestedItem(product.getId(), 2, 2_000))));
        order.confirm();
        orders.save(order);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "2");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response =
                rest.exchange(
                        "/api/v1/orders/" + order.getId() + "/confirm",
                        HttpMethod.POST,
                        request,
                        JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText())
                .isEqualTo("ORDER_NOT_FOUND");
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(10_000);
    }

    @Test
    void 확정_전_상품이_삭제되면_주문_재고_잔액을_유지한다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        Order order =
                orders.save(
                        Order.create(
                                1, List.of(new Order.RequestedItem(product.getId(), 2, 2_000))));
        product.delete();
        products.save(product);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response =
                rest.exchange(
                        "/api/v1/orders/" + order.getId() + "/confirm",
                        HttpMethod.POST,
                        request,
                        JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText())
                .isEqualTo("PRODUCT_NOT_FOUND");
        Order stored = orders.findById(order.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stored.getPaidAmount()).isNull();
        assertThat(stored.getPaymentResult()).isNull();
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(10_000);
    }

    @Test
    void 두_번째_상품_재고가_부족하면_첫_번째_상품_차감도_롤백한다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        Product secondProduct = Product.create(brand.getId(), "secondProduct", 1_000);
        secondProduct.setStock(1);
        secondProduct = products.save(secondProduct);
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        Order order =
                orders.save(
                        Order.create(
                                1,
                                List.of(
                                        new Order.RequestedItem(product.getId(), 2, 2_000),
                                        new Order.RequestedItem(secondProduct.getId(), 2, 1_000))));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response =
                rest.exchange(
                        "/api/v1/orders/" + order.getId() + "/confirm",
                        HttpMethod.POST,
                        request,
                        JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText())
                .isEqualTo("INSUFFICIENT_STOCK");
        Order stored = orders.findById(order.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stored.getPaidAmount()).isNull();
        assertThat(stored.getPaymentResult()).isNull();
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(10_000);
        assertThat(products.findById(secondProduct.getId()).orElseThrow().getStock()).isEqualTo(1);
    }

    @Test
    void 잔액_부족은_상품_차감을_롤백하고_기존_잔액을_유지한다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        PointBalance point = PointBalance.empty(1);
        point.charge(3_000);
        points.save(point);
        Order order =
                orders.save(
                        Order.create(
                                1, List.of(new Order.RequestedItem(product.getId(), 2, 2_000))));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response =
                rest.exchange(
                        "/api/v1/orders/" + order.getId() + "/confirm",
                        HttpMethod.POST,
                        request,
                        JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText())
                .isEqualTo("INSUFFICIENT_POINTS");
        Order stored = orders.findById(order.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stored.getPaidAmount()).isNull();
        assertThat(stored.getPaymentResult()).isNull();
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(3_000);
    }

    @Test
    void 잔액_행이_없으면_결제를_거절하고_행을_새로_만들지_않는다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        Order order =
                orders.save(
                        Order.create(
                                1, List.of(new Order.RequestedItem(product.getId(), 2, 2_000))));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response =
                rest.exchange(
                        "/api/v1/orders/" + order.getId() + "/confirm",
                        HttpMethod.POST,
                        request,
                        JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText())
                .isEqualTo("INSUFFICIENT_POINTS");
        Order stored = orders.findById(order.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stored.getPaidAmount()).isNull();
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(points.findByUserId(1)).isEmpty();
    }

    @Test
    void 타인_주문과_없는_주문의_상세_조회_거절_응답은_동일하다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(2L)));
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        Order order =
                orders.save(
                        Order.create(
                                1, List.of(new Order.RequestedItem(product.getId(), 2, 2_000))));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "2");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response =
                rest.exchange(
                        "/api/v1/orders/" + order.getId() + "",
                        HttpMethod.GET,
                        request,
                        JsonNode.class);
        var missing =
                rest.exchange("/api/v1/orders/999999", HttpMethod.GET, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText())
                .isEqualTo("ORDER_NOT_FOUND");
        assertThat(response.getStatusCode()).isEqualTo(missing.getStatusCode());
        assertThat(response.getBody()).isEqualTo(missing.getBody());
        assertThat(response.getBody().has("data")).isFalse();
        Order stored = orders.findById(order.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stored.getPaidAmount()).isNull();
        assertThat(stored.getPaymentResult()).isNull();
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(10_000);
    }

    @Test
    void 타인_주문과_없는_주문의_확정_거절_응답은_동일하다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(2L)));
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        Order order =
                orders.save(
                        Order.create(
                                1, List.of(new Order.RequestedItem(product.getId(), 2, 2_000))));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "2");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response =
                rest.exchange(
                        "/api/v1/orders/" + order.getId() + "/confirm",
                        HttpMethod.POST,
                        request,
                        JsonNode.class);
        var missing =
                rest.exchange(
                        "/api/v1/orders/999999/confirm", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText())
                .isEqualTo("ORDER_NOT_FOUND");
        assertThat(response.getStatusCode()).isEqualTo(missing.getStatusCode());
        assertThat(response.getBody()).isEqualTo(missing.getBody());
        assertThat(response.getBody().has("data")).isFalse();
        Order stored = orders.findById(order.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stored.getPaidAmount()).isNull();
        assertThat(stored.getPaymentResult()).isNull();
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(10_000);
    }

    @Test
    void 고객_목록에는_본인_주문만_반환하고_구매자_식별자를_노출하지_않는다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(2L)));
        Order order =
                orders.save(
                        Order.create(
                                1, List.of(new Order.RequestedItem(product.getId(), 1, 2_000))));
        Order other =
                orders.save(
                        Order.create(
                                2, List.of(new Order.RequestedItem(product.getId(), 1, 2_000))));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // act
        var response = rest.exchange("/api/v1/orders", HttpMethod.GET, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.findValues("orderId"))
                .extracting(JsonNode::longValue)
                .containsExactly(order.getId());
        assertThat(items.required(0).has("userId")).isFalse();
    }

    @Test
    void 관리자는_구매자별_주문_목록을_조회한다() throws Exception {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(2L)));
        Order order =
                orders.save(
                        Order.create(
                                1, List.of(new Order.RequestedItem(product.getId(), 1, 2_000))));
        Order other =
                orders.save(
                        Order.create(
                                2, List.of(new Order.RequestedItem(product.getId(), 1, 2_000))));
        var request =
                get("/api-admin/v1/orders?userId=2")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].orderId").value(other.getId()));
    }

    @Test
    void 구매자에_해당하는_주문이_없으면_빈_배열을_반환한다() throws Exception {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        Order order =
                orders.save(
                        Order.create(
                                1, List.of(new Order.RequestedItem(product.getId(), 1, 2_000))));
        var request =
                get("/api-admin/v1/orders?userId=999")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 관리자_상세는_구매자_품목_결제_결과를_반환한다() throws Exception {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        Order order =
                orders.save(
                        Order.create(
                                1, List.of(new Order.RequestedItem(product.getId(), 1, 2_000))));
        order.confirm();
        orders.save(order);
        var request =
                get("/api-admin/v1/orders/" + order.getId())
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.items[0].quantity").value(1))
                .andExpect(jsonPath("$.data.paidAmount").value(2_000))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.paymentResult").value("SUCCESS"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"items\":[]}",
                "{\"items\":null}",
                "{\"items\":[null]}",
                "{\"items\":[{\"productId\":PRODUCT,\"quantity\":0}]}",
                "{\"items\":[{\"productId\":PRODUCT,\"quantity\":null}]}",
                "{\"items\":[{\"productId\":PRODUCT,\"quantity\":3},{\"productId\":PRODUCT,\"quantity\":-1}]}",
                "{\"items\":[{\"productId\":PRODUCT,\"quantity\":2147483648}]}",
                "{\"items\":[{\"productId\":PRODUCT,\"quantity\":2147483647},{\"productId\":PRODUCT,\"quantity\":1}]}",
                "{\"items\":[{\"productId\":PRODUCT,\"quantity\":1.5}]}"
            })
    void 잘못된_주문_입력은_주문과_품목을_저장하지_않는다(String invalidBody) {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        String body = invalidBody.replace("PRODUCT", product.getId().toString());
        var request = new HttpEntity<>(body, headers);

        // act
        var response = rest.exchange("/api/v1/orders", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(
                        entityManager
                                .createQuery("select count(e) from OrderJpaEntity e", Long.class)
                                .getSingleResult())
                .isZero();
        assertThat(
                        entityManager
                                .createQuery(
                                        "select count(item.productId) from OrderJpaEntity o join o.items item",
                                        Long.class)
                                .getSingleResult())
                .isZero();
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(10_000);
    }

    @Test
    void 주문_금액이_long_범위를_넘으면_주문과_품목을_저장하지_않는다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", Long.MAX_VALUE);
        product.setStock(0);
        product = products.save(product);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        OrderDto.Create input =
                new OrderDto.Create(List.of(new OrderDto.ItemRequest(product.getId(), 2)));
        var request = new HttpEntity<>(input, headers);

        // act
        var response = rest.exchange("/api/v1/orders", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText())
                .isEqualTo("ORDER_AMOUNT_OVERFLOW");
        assertThat(
                        entityManager
                                .createQuery("select count(e) from OrderJpaEntity e", Long.class)
                                .getSingleResult())
                .isZero();
        assertThat(
                        entityManager
                                .createQuery(
                                        "select count(item.productId) from OrderJpaEntity o join o.items item",
                                        Long.class)
                                .getSingleResult())
                .isZero();
    }

    @Test
    void 삭제한_상품으로_주문을_생성할_수_없다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        product.delete();
        products.save(product);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        var input = new OrderDto.Create(List.of(new OrderDto.ItemRequest(product.getId(), 1)));
        var request = new HttpEntity<>(input, headers);

        // act
        var response = rest.exchange("/api/v1/orders", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText())
                .isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(
                        entityManager
                                .createQuery("select count(e) from OrderJpaEntity e", Long.class)
                                .getSingleResult())
                .isZero();
        assertThat(
                        entityManager
                                .createQuery(
                                        "select count(item.productId) from OrderJpaEntity o join o.items item",
                                        Long.class)
                                .getSingleResult())
                .isZero();
    }

    @Test
    void 없는_상품으로_주문을_생성할_수_없다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        var input = new OrderDto.Create(List.of(new OrderDto.ItemRequest(999L, 1)));
        var request = new HttpEntity<>(input, headers);

        // act
        var response = rest.exchange("/api/v1/orders", HttpMethod.POST, request, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText())
                .isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(
                        entityManager
                                .createQuery("select count(e) from OrderJpaEntity e", Long.class)
                                .getSingleResult())
                .isZero();
        assertThat(
                        entityManager
                                .createQuery(
                                        "select count(item.productId) from OrderJpaEntity o join o.items item",
                                        Long.class)
                                .getSingleResult())
                .isZero();
    }

    @Test
    void 주문_저장_중_DB_오류가_발생하면_모든_결제_변경을_롤백한다() {
        // arrange
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 2_000);
        product.setStock(5);
        product = products.save(product);
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);
        points.save(point);
        Order order =
                orders.save(
                        Order.create(
                                1, List.of(new Order.RequestedItem(product.getId(), 2, 2_000))));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", "1");
        HttpEntity<Void> request = new HttpEntity<>(headers);
        AtomicReference<PersistenceException> databaseFailure = new AtomicReference<>();
        // 관리 중인 주문의 컬럼 길이를 넘겨 실제 DB 저장 오류를 발생시킨다.
        doAnswer(
                        invocation -> {
                            OrderJpaEntity entity = invocation.getArgument(0);
                            ReflectionTestUtils.setField(entity, "paymentResult", "x".repeat(256));
                            try {
                                entityManager.flush();
                            } catch (PersistenceException exception) {
                                databaseFailure.set(exception);
                                throw exception;
                            }
                            return entity;
                        })
                .when(orderJpaRepository)
                .save(any(OrderJpaEntity.class));

        // act
        var response =
                rest.exchange(
                        "/api/v1/orders/" + order.getId() + "/confirm",
                        HttpMethod.POST,
                        request,
                        JsonNode.class);

        // assert
        assertThat(databaseFailure.get()).isNotNull().hasMessageContaining("payment_result");
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        Order stored = orders.findById(order.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stored.getPaidAmount()).isNull();
        assertThat(stored.getPaymentResult()).isNull();
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(points.findByUserId(1).orElseThrow().getBalance()).isEqualTo(10_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=-1", "?size=0", "?size=101", "?userId=0"})
    void 관리자_주문_목록의_잘못된_조회_조건을_거절한다(String query) throws Exception {
        // arrange
        var request =
                get("/api-admin/v1/orders" + query).with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("INVALID_REQUEST"));
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
