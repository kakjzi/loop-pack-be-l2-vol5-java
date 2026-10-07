package com.loopers.domain.brand;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface BrandRepository {
    Optional<Brand> findById(long id);

    // 브랜드를 확인한 뒤 변경하는 쓰기 유스케이스에서 사용한다. 잠금은 트랜잭션 종료까지 유지한다.
    Optional<Brand> findByIdForUpdate(long id);

    Brand save(Brand brand);

    Page<Brand> findAll(Pageable pageable);
}
