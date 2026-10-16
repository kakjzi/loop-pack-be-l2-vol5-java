-- 기존 데이터가 있는 DB: 모든 앱/seed/직접SQL 쓰기를 중단한 뒤 적용한다.
-- 상품/원본 좋아요/기존 인덱스는 변경하지 않는다. 운영 중 자동 실행하지 않는다.
CREATE TABLE IF NOT EXISTS product_like_counts (
    product_id BIGINT NOT NULL,
    like_count BIGINT NOT NULL,
    PRIMARY KEY (product_id),
    CONSTRAINT ck_product_like_counts_nonnegative CHECK (like_count >= 0)
) ENGINE=InnoDB;

-- 쓰기가 중단된 동안 재실행 가능: 누적 증가가 아니라 원본에서 계산한 값으로 대체한다.
START TRANSACTION;
INSERT INTO product_like_counts (product_id, like_count)
SELECT p.id, COALESCE(l.n, 0)
FROM products p
LEFT JOIN (
    SELECT product_id, COUNT(*) AS n FROM product_likes GROUP BY product_id
) l ON l.product_id = p.id
ON DUPLICATE KEY UPDATE like_count = VALUES(like_count);
COMMIT;

-- 불일치/고아 카운터가 모두 0이어야 새 앱으로 쓰기를 재개할 수 있다.
SELECT COUNT(*) AS mismatched_products FROM products p
LEFT JOIN product_like_counts c ON c.product_id=p.id
LEFT JOIN (SELECT product_id,COUNT(*) n FROM product_likes GROUP BY product_id) l
    ON l.product_id=p.id
WHERE c.product_id IS NULL OR c.like_count<>COALESCE(l.n,0);
SELECT COUNT(*) AS orphan_counters FROM product_like_counts c
LEFT JOIN products p ON p.id=c.product_id WHERE p.id IS NULL;
SELECT COUNT(*) AS orphan_likes FROM product_likes l
LEFT JOIN products p ON p.id=l.product_id WHERE p.id IS NULL;
SELECT (SELECT COUNT(*) FROM product_likes) AS actual_likes,
       (SELECT SUM(like_count) FROM product_like_counts) AS stored_likes;
