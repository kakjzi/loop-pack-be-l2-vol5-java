-- 기존 DB에 적용할 때는 먼저 SELECT DATABASE(), SHOW INDEX로 대상과 중복을 확인한다.
-- Hibernate ddl-auto=validate는 인덱스를 추가하지 않는다. 새 스키마는 @Index 선언을 사용한다.
-- 각 인덱스가 없을 때만 해당 CREATE 문을 실행한다. PK와 사용자-상품 유니크 제약은 보존한다.
CREATE INDEX idx_products_brand_price_id
    ON products (brand_id, price ASC, id DESC);

CREATE INDEX idx_product_likes_product_id
    ON product_likes (product_id);

-- 기존 4열 인덱스가 있으면 새 인덱스 생성·확인 후 아래 문장을 별도로 실행한다.
-- DROP INDEX idx_products_brand_deleted_price_id ON products;
