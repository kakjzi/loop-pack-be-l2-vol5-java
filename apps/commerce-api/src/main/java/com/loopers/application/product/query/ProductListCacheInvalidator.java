package com.loopers.application.product.query;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class ProductListCacheInvalidator {
    private final ProductListCache cache;
    @Value("${product-list-cache.enabled:true}") private boolean enabled;

    public void afterCommit() {
        if (!enabled) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("목록 무효화는 변경 트랜잭션 안에서 등록해야 합니다.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.invalidateAll();
            }
        });
    }
}
