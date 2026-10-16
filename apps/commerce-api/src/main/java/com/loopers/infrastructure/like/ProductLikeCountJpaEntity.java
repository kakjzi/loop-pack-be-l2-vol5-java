package com.loopers.infrastructure.like;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Check;

@Entity
@Table(name = "product_like_counts")
@Check(constraints = "like_count >= 0")
public class ProductLikeCountJpaEntity {
    @Id
    @Column(name = "product_id")
    private long productId;

    @Column(name = "like_count", nullable = false)
    private long likeCount;

    protected ProductLikeCountJpaEntity() {
    }
}
