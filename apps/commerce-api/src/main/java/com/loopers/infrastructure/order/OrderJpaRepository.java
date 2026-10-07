package com.loopers.infrastructure.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderJpaRepository extends JpaRepository<OrderJpaEntity, Long> {
    List<OrderJpaEntity> findByUserIdOrderByCreatedAtDescIdDesc(long userId);

    Page<OrderJpaEntity> findByUserId(long userId, Pageable pageable);
}
