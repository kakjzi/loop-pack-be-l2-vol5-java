package com.loopers.infrastructure.user.fixture;

import com.loopers.infrastructure.user.UserJpaEntity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.LongStream;

@Component
@Transactional
public class UserFixture {
    @PersistenceContext private EntityManager entityManager;

    public List<Long> createUsers(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("사용자 개수는 양수여야 합니다.");
        }
        // 사용자 ID는 자동 생성 열이 아니므로 로컬 실습에서 사용하지 않은 번호를 할당한다.
        long lastId = entityManager.createQuery("select coalesce(max(u.id), 0) from UserJpaEntity u", Long.class)
                .getSingleResult();
        List<Long> ids = LongStream.rangeClosed(1, count).map(index -> Math.addExact(lastId, index)).boxed().toList();
        ids.forEach(this::createUser);
        return ids;
    }

    public void createUser(long userId) {
        entityManager.persist(new UserJpaEntity(userId));
    }
}
