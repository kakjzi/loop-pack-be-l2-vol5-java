package com.loopers.application.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loopers.application.product.query.ProductView;
import com.loopers.application.user.IdentifyUser;
import com.loopers.domain.like.ProductLike;
import com.loopers.domain.like.ProductLikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

class LikeFacadeTest {
    @Test
    void 등록하면_요청자와_상품의_관계가_생긴다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        FakeLikes likes = new FakeLikes();
        FakeProducts products = new FakeProducts();
        products.values.put(10L, Product.restore(10, 1, "상품", 1_000, 0, false));
        CreateLikeFacade facade = new CreateLikeFacade(users, likes, products);

        // act
        facade.create(1L, 10);

        // assert
        assertThat(likes.values).containsExactly(new ProductLike(1, 10));
    }

    @Test
    void 등록된_관계의_재요청은_관계를_추가하지_않는다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        FakeLikes likes = new FakeLikes();
        FakeProducts products = new FakeProducts();
        products.values.put(10L, Product.restore(10, 1, "상품", 1_000, 0, false));
        CreateLikeFacade facade = new CreateLikeFacade(users, likes, products);
        likes.save(new ProductLike(1, 10));

        // act
        facade.create(1L, 10);

        // assert
        assertThat(likes.values).containsExactly(new ProductLike(1, 10));
    }

    @Test
    void 취소하면_본인_관계만_제거한다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        FakeLikes likes = new FakeLikes();
        DeleteLikeFacade facade = new DeleteLikeFacade(users, likes);
        likes.save(new ProductLike(1, 10));
        likes.save(new ProductLike(2, 10));

        // act
        facade.delete(1L, 10);

        // assert
        assertThat(likes.values).containsExactly(new ProductLike(2, 10));
    }

    @Test
    void 본인_관계가_없어도_다른_사용자의_관계를_유지한다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        FakeLikes likes = new FakeLikes();
        DeleteLikeFacade facade = new DeleteLikeFacade(users, likes);
        likes.save(new ProductLike(2, 10));

        // act
        facade.delete(1L, 10);

        // assert
        assertThat(likes.values).containsExactly(new ProductLike(2, 10));
    }

    @Test
    void 삭제된_상품은_기존_관계가_있어도_등록을_거절한다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        FakeLikes likes = new FakeLikes();
        FakeProducts products = new FakeProducts();
        products.values.put(10L, Product.restore(10, 1, "상품", 1_000, 0, false));
        CreateLikeFacade facade = new CreateLikeFacade(users, likes, products);
        likes.save(new ProductLike(1, 10));
        products.values.get(10L).delete();

        // act
        CoreException error = assertThrows(CoreException.class, () -> facade.create(1L, 10));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.PRODUCT_NOT_FOUND);
        assertThat(likes.values).containsExactly(new ProductLike(1, 10));
    }

    @Test
    void 취소는_상품_조회_없이_저장된_본인_관계를_제거한다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        FakeLikes likes = new FakeLikes();
        DeleteLikeFacade facade = new DeleteLikeFacade(users, likes);
        likes.save(new ProductLike(1, 10));

        // act
        facade.delete(1L, 10);

        // assert
        assertThat(likes.values).isEmpty();
    }

    @Test
    void 없는_상품은_등록할_수_없다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        FakeLikes likes = new FakeLikes();
        FakeProducts products = new FakeProducts();
        products.values.put(10L, Product.restore(10, 1, "상품", 1_000, 0, false));
        CreateLikeFacade facade = new CreateLikeFacade(users, likes, products);

        // act
        CoreException error = assertThrows(CoreException.class, () -> facade.create(1L, 999));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.PRODUCT_NOT_FOUND);
        assertThat(likes.values).isEmpty();
    }

    @Test
    void 없는_사용자는_등록할_수_없다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        FakeLikes likes = new FakeLikes();
        FakeProducts products = new FakeProducts();
        products.values.put(10L, Product.restore(10, 1, "상품", 1_000, 0, false));
        CreateLikeFacade facade = new CreateLikeFacade(users, likes, products);

        // act
        CoreException error = assertThrows(CoreException.class, () -> facade.create(999L, 10));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.USER_NOT_FOUND);
        assertThat(likes.values).isEmpty();
    }

    @Test
    void 식별_누락은_등록할_수_없다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        FakeLikes likes = new FakeLikes();
        FakeProducts products = new FakeProducts();
        products.values.put(10L, Product.restore(10, 1, "상품", 1_000, 0, false));
        CreateLikeFacade facade = new CreateLikeFacade(users, likes, products);

        // act
        CoreException error = assertThrows(CoreException.class, () -> facade.create(null, 10));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
        assertThat(likes.values).isEmpty();
    }

    @Test
    void 본인의_좋아요_목록을_반환한다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1);
        ProductView expected =
                new ProductView(10, "상품", 1_000, new ProductView.BrandView(1, "브랜드"), 1);
        GetLikeFacade facade =
                new GetLikeFacade(users, id -> id == 1 ? List.of(expected) : List.of());

        // act
        var result = facade.get(1L, 1);

        // assert
        assertThat(result).containsExactly(expected);
    }

    @Test
    void 본인_좋아요가_없으면_빈_목록을_반환한다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1);
        GetLikeFacade facade = new GetLikeFacade(users, id -> List.of());

        // act
        var result = facade.get(1L, 1);

        // assert
        assertThat(result).isEmpty();
    }

    @Test
    void 타인_목록_접근은_권한_오류로_거절한다() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        GetLikeFacade facade = new GetLikeFacade(users, id -> List.of());

        // act
        CoreException error = assertThrows(CoreException.class, () -> facade.get(1L, 2));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.ACCESS_DENIED);
    }

    private static class FakeLikes implements ProductLikeRepository {
        private final Set<ProductLike> values = new HashSet<>();

        @Override
        public boolean exists(long userId, long productId) {
            return values.contains(new ProductLike(userId, productId));
        }

        @Override
        public void save(ProductLike like) {
            if (!values.add(like)) {
                throw new IllegalStateException("duplicate relation");
            }
        }

        @Override
        public void delete(long userId, long productId) {
            values.remove(new ProductLike(userId, productId));
        }
    }

    private static class FakeProducts implements ProductRepository {
        private final Map<Long, Product> values = new HashMap<>();

        @Override
        public Optional<Product> findById(long id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public Product save(Product product) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Page<Product> findAll(Pageable pageable) {
            throw new UnsupportedOperationException();
        }
    }
}
