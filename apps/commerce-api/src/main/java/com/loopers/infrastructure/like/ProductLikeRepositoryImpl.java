package com.loopers.infrastructure.like;

import com.loopers.domain.like.ProductLike;
import com.loopers.domain.like.ProductLikeRepository;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional
public class ProductLikeRepositoryImpl implements ProductLikeRepository {
    private final EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public boolean exists(long userId, long productId) {
        return entityManager
                        .createQuery(
                                "select count(l) from ProductLikeJpaEntity l where l.userId=:userId"
                                    + " and l.productId=:productId",
                                Long.class)
                        .setParameter("userId", userId)
                        .setParameter("productId", productId)
                        .getSingleResult()
                > 0;
    }

    @Override
    public void save(ProductLike like) {
        lockCounter(like.productId());
        persistRelation(like);
        incrementCounter(like.productId());
    }

    @Override
    public void registerIfAbsent(ProductLike like) {
        lockCounter(like.productId());
        // INSERT IGNORE의 신규 1/중복 0은 ON DUPLICATE KEY UPDATE의 found-rows와 다르다.
        // 없는 관계를 먼저 FOR UPDATE로 읽으면 다른 상품끼리도 UK gap 잠금이 교착할 수 있다.
        int inserted = entityManager
                .createNativeQuery(
                        "insert ignore into product_likes "
                                + "(user_id, product_id, created_at, updated_at) "
                                + "values (:userId, :productId, CURRENT_TIMESTAMP(6), "
                                + "CURRENT_TIMESTAMP(6))")
                .setParameter("userId", like.userId())
                .setParameter("productId", like.productId())
                .executeUpdate();
        if (inserted == 1) {
            incrementCounter(like.productId());
        } else if (inserted != 0 || !hasCurrentRelation(like)) {
            // IGNORE가 중복 이외의 오류를 숨겼다면 성공으로 취급하지 않는다.
            throw new IllegalStateException("좋아요 관계를 등록하지 못했습니다.");
        }
    }

    private boolean hasCurrentRelation(ProductLike like) {
        // 앞선 상품 조회가 만든 RR 스냅샷 대신 최신 관계를 확인한다.
        return !entityManager
                .createNativeQuery(
                        "select id from product_likes where user_id = :userId "
                                + "and product_id = :productId for update")
                .setParameter("userId", like.userId())
                .setParameter("productId", like.productId())
                .getResultList()
                .isEmpty();
    }

    @Override
    public void delete(long userId, long productId) {
        boolean counterExists = !entityManager
                .createNativeQuery(
                        "select product_id from product_like_counts "
                                + "where product_id = :productId for update")
                .setParameter("productId", productId)
                .getResultList()
                .isEmpty();
        if (!counterExists) {
            return;
        }
        int deleted = entityManager
                .createQuery(
                        "delete from ProductLikeJpaEntity l where l.userId=:userId and"
                            + " l.productId=:productId")
                .setParameter("userId", userId)
                .setParameter("productId", productId)
                .executeUpdate();
        if (deleted > 0) {
            int updated = entityManager
                    .createNativeQuery(
                            "update product_like_counts set like_count = like_count - 1 "
                                    + "where product_id = :productId and like_count > 0")
                    .setParameter("productId", productId)
                    .executeUpdate();
            if (updated != 1) {
                throw new IllegalStateException("좋아요 관계와 카운터가 일치하지 않습니다.");
            }
        }
    }

    private void lockCounter(long productId) {
        // 상품 정보 행은 잠그지 않고 동일 상품의 좋아요 변경만 직렬화한다.
        // 이 upsert의 affected-row 수는 Connector/J 설정에 따라 달라지므로 사용하지 않는다.
        entityManager
                .createNativeQuery(
                        "insert into product_like_counts (product_id, like_count) "
                                + "values (:productId, 0) on duplicate key update "
                                + "product_id = product_id")
                .setParameter("productId", productId)
                .executeUpdate();
    }

    private void persistRelation(ProductLike like) {
        entityManager.persist(new ProductLikeJpaEntity(like));
        entityManager.flush();
    }

    private void incrementCounter(long productId) {
        entityManager
                .createNativeQuery(
                        "update product_like_counts set like_count = like_count + 1 "
                                + "where product_id = :productId")
                .setParameter("productId", productId)
                .executeUpdate();
    }
}
