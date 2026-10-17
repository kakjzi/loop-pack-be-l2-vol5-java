# 4주차 조회 실험 재현 안내

실험의 선택 근거와 결과는 [설계 문서](design.md)에 정리했다. 아래는 전용 DB 준비와 재측정 절차다.

## 전용 DB를 처음 준비하는 순서

이하 명령은 **저장소 루트**에서 실행한다. 원래 `3306/loopers`나 공유 DB를 사용하지 않는다. 이미 전용 컨테이너가 있으면 첫 생성 명령을 건너뛰고 `docker start loopers-offset-benchmark-mysql-benchmark-1`로 시작한다. 13306을 다른 컨테이너가 사용한다면 먼저 대상을 확인하고 중단한다. 기존 앱·다른 쓰기와 측정을 겹치지 않는다.

새로 구성할 때만 다음 컨테이너를 만든다. 이미 존재하는 이름·포트는 재사용 여부를 확인하며, 아래 명령으로 기존 컨테이너를 삭제하지 않는다.

```bash
docker run -d --name loopers-offset-benchmark-mysql-benchmark-1 \
  --publish 127.0.0.1:13306:3306 --memory 2g --cpus 4 \
  -e MYSQL_ROOT_PASSWORD=benchmark-root \
  -e MYSQL_DATABASE=loopers_offset_bench \
  -e MYSQL_USER=application -e MYSQL_PASSWORD=application \
  --mount type=volume,source=week4-offset-bench-data,target=/var/lib/mysql \
  mysql:8.0.46 --innodb-buffer-pool-size=536870912

docker exec -e MYSQL_PWD=benchmark-root loopers-offset-benchmark-mysql-benchmark-1 \
  mysqladmin -uroot ping
```

`mysqld is alive`를 확인한 후 진행한다. 다음 초기화 SQL은 **전용 DB의 모든 데이터를 삭제**한다. 새 실험을 시작하기로 결정한 경우에만 백업 후 실행한다. 단순 재측정이라면 초기화·시드를 생략하고 보존된 자료의 총건수부터 확인한다.

```bash
docker exec -i -e MYSQL_PWD=benchmark-root loopers-offset-benchmark-mysql-benchmark-1 \
  mysql -uroot < docs/week4/sql/reset-offset-benchmark.sql
./gradlew :apps:commerce-api:seedOffsetBenchmark

docker exec -i -e MYSQL_PWD=benchmark-root loopers-offset-benchmark-mysql-benchmark-1 \
  mysql -uroot --database=loopers_offset_bench <<'SQL'
SELECT COUNT(*) AS products FROM products;
SELECT COUNT(*) AS likes FROM product_likes;
SELECT COUNT(*) AS counters, SUM(like_count) AS stored_likes FROM product_like_counts;
SELECT brand_id,COUNT(*) AS products FROM products GROUP BY brand_id;
SHOW INDEX FROM products;
SHOW INDEX FROM product_likes;
SQL
```

기대값은 상품1,000,000·좋아요2,000,000·카운터1,000,000/합계2,000,000·브랜드별500,000이다. 초기화 SQL은 카운터를 포함한9개 테이블과 PK·기존 유니크·주문FK를 만들고 비교할 조회 인덱스 두 개는 만들지 않는다. 시드는 앱 서버를 띄우지 않고500상품 단위로 상품·관계·카운터를 같은 TX에 저장한다. 이름·재고는 Fixture Monkey1.2.3, seed `20261015+묶음 시작 offset`, 생성시각은 UTC `2026-10-01T00:00:00`이다. 재현에는 같은 라이브러리 버전·500건 묶음·호출 순서를 유지한다. 중간 실패 시 이미 완료한 묶음은 남으므로 자동 이어쓰기는 하지 않는다.

현재 스키마와의 호환성은 새 테스트 전용 MySQL에서 **이 초기화 SQL→JPA validate→동일 writer의60건 저장**으로 회귀 검사한다. 엔티티 DDL을 대조하려면 `./gradlew :apps:commerce-api:seedOffsetBenchmark -PbenchmarkSchemaOnly`를 실행해 `apps/commerce-api/build/offset-benchmark-schema.sql`을 확인한다. 이 옵션은 DB에 DDL을 실행하지 않는다. 기존 자료에 카운터만 이행할 경우에는 초기화하지 않고 쓰기 중단 후 [카운터 이행 SQL](sql/product-like-counts.sql)을 사용한다.

## 같은 조건으로 인덱스를 다시 비교하기

이 절은 설계 문서의 과거 수치를 재현하기 위해 삭제 열을 포함한 기존 4열 인덱스를 사용한다. 현재 적용 인덱스는 삭제 열을 뺀 3열이며 [현재 인덱스 SQL](sql/product-query-indexes.sql)과 구분한다.

과거 표의 COUNT 방식을 재측정하려면 아래처럼 4주차 분기점의 소스를 임시 폴더로 내보내 실행한다. 이 분기점은 새 브랜치 이력에도 남는다. 당시 인덱스 추가 버전과 조회 SQL이 같고, 인덱스는 별도 DDL로 적용한다. 현재 체크아웃·미커밋 작업은 변경하지 않는다. 새로 만든 전용 DB에는 구버전 앱도 사용할 수 있는 기존 테이블이 함께 있다.

```bash
week4_source=$(mktemp -d /tmp/week4-count-source.XXXXXX)
git archive af5b9cdd16d005fc00b6fc2dd153849929494180 | tar -x -C "$week4_source"
JAVA_TOOL_OPTIONS=-Xmx512m ./gradlew -p "$week4_source" :apps:commerce-api:bootRun --args='--server.port=8082 --management.server.port=8083 --datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://127.0.0.1:13306/loopers_offset_bench --datasource.mysql-jpa.main.username=application --datasource.mysql-jpa.main.password=application --spring.jpa.hibernate.ddl-auto=validate --spring.jpa.show-sql=false --product-list-cache.enabled=false'
```

서버를 실행해둔 채 **다른 터미널의 현재 저장소 루트**에서 아래 순서로 측정한다. 인덱스를 바꾸는 대상은 위 전용 DB뿐이다. ‘인덱스 없음’은 PK·유니크를 없애는 것이 아니라 조회 개선용 두 인덱스가 없는 상태다. 기존 DB를 재사용할 때 두 인덱스가 이미 있다면 이 첫 단계의 조건이 아니므로 그대로 `no-index`로 기록하지 않는다.

```bash
week4_result=$(mktemp -d /tmp/week4-index-result.XXXXXX)
docker exec -e MYSQL_PWD=benchmark-root loopers-offset-benchmark-mysql-benchmark-1 \
  mysql -uroot --database=loopers_offset_bench \
  -e 'ANALYZE TABLE products, brands, product_likes;'
python3 tools/benchmark/product-price-baseline.py --brand-id 1 \
  --output "$week4_result/no-index"

docker exec -e MYSQL_PWD=benchmark-root loopers-offset-benchmark-mysql-benchmark-1 \
  mysql -uroot --database=loopers_offset_bench \
  -e 'CREATE INDEX idx_products_brand_deleted_price_id ON products (brand_id, deleted_at, price ASC, id DESC); ANALYZE TABLE products, brands, product_likes;'
python3 tools/benchmark/product-price-baseline.py --brand-id 1 \
  --compare "$week4_result/no-index/response.json" --output "$week4_result/product-index"

docker exec -e MYSQL_PWD=benchmark-root loopers-offset-benchmark-mysql-benchmark-1 \
  mysql -uroot --database=loopers_offset_bench \
  -e 'CREATE INDEX idx_product_likes_product_id ON product_likes (product_id); ANALYZE TABLE products, brands, product_likes;'
python3 tools/benchmark/product-price-baseline.py --brand-id 1 \
  --compare "$week4_result/no-index/response.json" --output "$week4_result/both-indexes"
```

각 단계에서 `SHOW INDEX`의 실제 상태도 기록한다. `product-price-baseline.py` 자체는 인덱스를 바꾸지 않는다. 출력은 새 폴더를 요구하므로 전체 재실행에는 새 `week4_result` 경로를 사용한다. 전체 필드·배열 순서가 달라지면 도구는 실패한다.

마지막으로 저장한 같은 응답과 [기존 COUNT SQL](sql/product-price-count-baseline.sql)로 EXPLAIN·EXPLAIN ANALYZE·Handler_read를 수집한다. 두 실제 인덱스를 남긴 상태에서 도구가 쿼리마다 `IGNORE INDEX`를 붙여3조건을 비교한다. 데이터·인덱스는 변경하지 않는다.

```bash
mkdir -p "$week4_result/source/http"
cp docs/week4/sql/product-price-count-baseline.sql "$week4_result/source/queries.sql"
cp "$week4_result/no-index/response.json" "$week4_result/source/http/response.json"
python3 tools/benchmark/handler-reads.py \
  --source "$week4_result/source" --output "$week4_result/handler" --samples 3
```

`handler-reads.py`는 상품100만·좋아요200만·위 컨테이너 이름을 전제로 한 과거 COUNT 비교용이다. 현재500만 관계 DB에 그대로 실행하면 전제 검사에서 중단한다. 500만 추가 실험은 이200만 비교를 끝낸 뒤 현재 소스에서 `./gradlew :apps:commerce-api:seedPopularProductLikes`를 실행하면 된다. 새 사용자150,000명·인기 상품20개에 각150,000관계를 더해 총500만이 된다. 이미 실행한 자료에 다시 추가하는 명령은 아니다.

현재 카운터 구현을 측정할 때는 구버전 서버를 Ctrl+C로 종료하고 같은 실행 명령에서 `-p "$week4_source"`만 빼 현재 앱을 실행한다. 캐시 비활성화 인자는 유지한다. 이는 현재 SQL의 별도 비교이며 위 과거 HTTP 수치의 재측정과 구분해 기록한다. 현재 SQL은 [상품 조회](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/product/ProductJpaRepository.java), 인덱스DDL은 [인덱스 SQL](sql/product-query-indexes.sql), 스키마 회귀는 [준비 절차 테스트](../../apps/commerce-api/src/test/java/com/loopers/seed/benchmark/OffsetBenchmarkSchemaIntegrationTest.java)를 참고한다.

## 현재 3열 인덱스 적용

현재 구성은 `(brand_id, price ASC, id DESC)`이며, [적용 SQL](sql/product-query-indexes.sql)을 사용한다. 기존 DB에서는 새 인덱스를 만든 뒤 기존 4열 인덱스를 제거해야 삭제 열을 제외한 상태가 된다. Hibernate `validate`는 기존 인덱스를 교체하지 않는다.

현재 구성의 목록·COUNT 시간을 새로 측정하고, 위의 과거 결과와 구분해 기록한다. `handler-reads.py`는 기존 4열 인덱스 이름을 사용하는 과거 비교용이므로 새 구성에는 그대로 사용하지 않는다.
