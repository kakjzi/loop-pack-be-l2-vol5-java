package com.loopers.domain.point;

import java.util.Optional;

public interface PointBalanceRepository {
    Optional<PointBalance> findByUserId(long userId);

    PointBalance save(PointBalance balance);

    PointBalance charge(long userId, long amount);

    void deduct(long userId, long amount);
}
