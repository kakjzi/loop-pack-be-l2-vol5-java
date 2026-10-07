package com.loopers.domain.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    Optional<Order> findById(long id);

    Order save(Order order);

    /** confirm()을 마친 주문의 상태·결제 정보를, DB가 DRAFT일 때만 저장한다. */
    boolean confirmIfDraft(Order order);

    List<Order> findByUserId(long userId);

    Page<Order> findAll(Long userId, Pageable pageable);
}
