package com.loopers.application.order;

import com.loopers.application.user.IdentifyUser;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.point.PointBalanceRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;

@Component
@RequiredArgsConstructor
@Transactional
public class ConfirmOrderFacade {
    private final IdentifyUser users;
    private final OrderRepository orders;
    private final ProductRepository products;
    private final PointBalanceRepository points;

    public OrderInfo confirm(Long userId, long orderId) {
        long owner = users.require(userId);
        Order order =
                orders.findById(orderId)
                        .filter(value -> value.getUserId() == owner)
                        .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND));
        order.confirm();
        // 같은 주문의 경쟁은 첫 상태 전이에서 걸러낸다. 뒤에서 실패하면 이 변경도 함께 롤백된다.
        if (!orders.confirmIfDraft(order)) {
            throw new CoreException(ErrorType.INVALID_REQUEST);
        }
        for (OrderItem item :
                order.getItems().stream()
                        .sorted(Comparator.comparingLong(OrderItem::getProductId))
                        .toList()) {
            products.deductStock(item.getProductId(), item.getQuantity());
        }
        points.deduct(owner, order.getTotalAmount());
        return OrderInfo.from(order);
    }
}
