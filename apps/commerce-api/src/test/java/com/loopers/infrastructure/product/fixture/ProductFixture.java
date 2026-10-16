package com.loopers.infrastructure.product.fixture;

import com.loopers.config.FixtureMonkeyConfig;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.navercorp.fixturemonkey.ArbitraryBuilder;
import com.navercorp.fixturemonkey.FixtureMonkey;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import net.jqwik.api.Arbitraries;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

@Component
@Transactional
public class ProductFixture {
    private final FixtureMonkey fixtureMonkey = FixtureMonkeyConfig.create();

    @Autowired private ProductRepository products;
    @Autowired private ProductJpaRepository productRows;
    @Autowired private ProductBatchInserter batchInserter;
    @PersistenceContext private EntityManager entityManager;

    public Product createProduct(long brandId) {
        ProductData data = productDataBuilder(brandId, fixtureMonkey).sample();
        return createProduct(data.brandId(), data.name(), data.price(), data.stock());
    }

    public List<Product> createProducts(long brandId, int count, long seed) {
        if (count <= 0) {
            throw new IllegalArgumentException("상품 개수는 양수여야 합니다.");
        }
        List<Product> generated = productDataBuilder(brandId, FixtureMonkeyConfig.create(seed))
                .set("price", Arbitraries.of(1_000L, 5_000L, 10_000L, 30_000L, 50_000L, 100_000L))
                .sampleList(count)
                .stream()
                .map(data -> {
                    Product product = Product.create(data.brandId(), data.name(), data.price());
                    product.setStock(data.stock());
                    return product;
                })
                .toList();
        return batchInserter.insert(generated);
    }

    private ArbitraryBuilder<ProductData> productDataBuilder(long brandId, FixtureMonkey monkey) {
        return monkey.giveMeBuilder(ProductData.class)
                .set("brandId", brandId)
                .set("name", Arbitraries.strings().withCharRange('a', 'z').ofMinLength(5).ofMaxLength(30))
                .set("price", Arbitraries.longs().between(1_000L, 100_000L))
                .set("stock", Arbitraries.integers().between(0, 100));
    }

    public Product createProduct(long brandId, String name, long price, int stock) {
        Product product = Product.create(brandId, name, price);
        product.setStock(stock);
        return products.save(product);
    }

    public Product updateProduct(long id, String name, long price) {
        Product product = products.findById(id).orElseThrow();
        product.update(name, price);
        return products.save(product);
    }

    public void deleteProduct(long id) {
        Product product = products.findById(id).orElseThrow();
        product.delete();
        products.save(product);
    }

    @Transactional(readOnly = true)
    public Product product(long id) {
        return products.findById(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public long rowCount() {
        return productRows.count();
    }

    @Transactional(readOnly = true)
    public List<Long> activeProductIds(long brandId) {
        return products.findActiveIdsByBrandId(brandId);
    }

    @Transactional(readOnly = true)
    public long productCount(long brandId) {
        return entityManager
                .createQuery(
                        "select count(p) from ProductJpaEntity p where p.brandId = :brandId",
                        Long.class)
                .setParameter("brandId", brandId)
                .getSingleResult();
    }

    public void createdAt(long productId, ZonedDateTime time) {
        entityManager
                .createQuery("update ProductJpaEntity p set p.createdAt = :time where p.id = :id")
                .setParameter("time", time)
                .setParameter("id", productId)
                .executeUpdate();
    }

    public void createdAtForBrand(long brandId, ZonedDateTime time) {
        entityManager
                .createQuery(
                        "update ProductJpaEntity p set p.createdAt = :time where p.brandId ="
                                + " :brandId")
                .setParameter("time", time)
                .setParameter("brandId", brandId)
                .executeUpdate();
    }

    public record ProductData(long brandId, String name, long price, int stock) {}
}
