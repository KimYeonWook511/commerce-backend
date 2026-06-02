# 태스크 ADR

## 결정 1: Stock·StockHistory 의 JPA cross-aggregate association 을 해제하고 Long ID 로 전환한다 (ADR-020 후속 트랙)

### 배경

- ADR-020 은 신규 도메인 (cart) 부터 cross-aggregate 를 `Long` ID 로 참조한다고 결정했으나, 기존 도메인은 호환성 부담으로 별도 트랙으로 분리됐다.
- Issue #195 가 별도 트랙을 진행한다. 본 태스크는 그 첫 sub-PR.
- 진행 단위 옵션:
  - (A) 모든 도메인 (Stock / Order / Payment) 을 한 PR 에 묶기
  - (B) 도메인 경계 단위로 sub-PR 분리

### 결정 내용

- 도메인 경계 단위로 sub-PR 을 분리한다 (B).
- 본 태스크는 Stock / StockHistory aggregate 한정.
- Stock 과 StockHistory 는 별도 aggregate 로 다룬다. StockHistory 의 `@ManyToOne Stock` 도 cross-aggregate association 으로 보고 해제한다.

### 근거

- 각 도메인이 별도 설계 결정을 가짐 — Stock 은 fetch join 없음 / 단순 , Order 는 fetch join 2 쿼리의 대체 패턴 결정 필요, Payment 는 보상 흐름과 얽혀 있다. 한 PR 에 묶으면 정책 목적이 분산.
- 본 코드베이스의 PR 평균 단위가 좁다 (commit-conventions 의 "역할이 다른 변경을 이유 없이 하나로 묶지 않는다").
- 점진 진행으로 회귀 추적이 쉽다.
- StockHistory 는 audit 도메인으로 Stock 의 lifecycle 에 종속되지 않는다. 별도 aggregate 로 다루는 것이 DDD audit 분리 패턴과 일치.

### 결과

- Stock / StockHistory aggregate 가 cross-aggregate 를 ID 로 참조.
- Issue #195 의 후속 sub-PR 로 `order-jpa-association-decouple`, `payment-jpa-association-decouple` 이 이어진다.
- 모든 sub-PR 머지 후 #195 close. DB FK 일괄 제거는 별도 트랙 (별도 issue / PR).

## 결정 2: StockHistory 는 stockId 만 들고, productId 는 application 에서 외부 주입한다

### 배경

- 현재 `StockHistoryResult.from(history)` 가 응답에 `productId` 를 노출하기 위해 `history.getStock().getProduct().getId()` 객체 traversal 을 사용.
- JPA association 해제 후 이 traversal 은 불가능하다. 대체 방안:
  - (A) `StockHistoryResult` 에서 `productId` 필드 자체를 제거 (응답 계약 변경)
  - (B) `StockHistoryResult.from(history, productId)` 시그니처로 application 이 path productId 를 외부 주입
  - (C) `StockHistory.productId` 컬럼을 신설 (Flyway migration 동반)

### 결정 내용

- (B) `from(history, productId)` 외부 주입 패턴을 채택한다.
- StockHistory entity 는 stockId 만 유지하고 productId 컬럼을 추가하지 않는다.
- `AdminStockResult` 는 Stock entity 가 productId 컬럼을 가지므로 `from(Stock)` 시그니처를 유지하고 내부에서 `stock.getProductId()` 를 직접 사용한다.

### 근거

- StockHistory aggregate 의 본질은 "어떤 stock 에 어떤 변경이 일어났는지" 이고 stockId 가 invariant 다. productId 는 audit 본질이 아닌 외부 컨텍스트.
- 본 sub-PR 의 정책 목적은 cross-aggregate ID 참조 통일이지 응답 계약 정비가 아니다. (A) 응답 필드 제거는 별도 정책 결정 항목으로 분리하는 것이 commit-conventions ("역할이 다른 변경을 이유 없이 하나로 묶지 않는다") 정신과 부합.
- (C) 컬럼 신설은 본 sub-PR series 의 메타 원칙 ("코드 차원 association 해제만, schema 변경 0건") 을 깨고 도메인 본질 아닌 정보를 schema 에 박는 부담이 있다.
- (B) 는 application 조립 패턴으로 데이터 소스 (audit row + path 컨텍스트) 가 응답에 어떻게 합쳐지는지 코드 표면에 드러난다. ADR-020 통증 #1 ("편한 탐색 오용") 해소.

### 결과

- API 응답 계약 유지. frontend 영향 없음.
- application 계층의 조립 책임이 명시적으로 코드에 드러남.
- 응답 echo (`StockHistoryResult.productId`, `AdminStockResult.productId`) 정리는 후속 별도 트랙으로 분리.

## 결정 3: DB schema 변경 / Flyway migration 없이 진행한다

### 배경

- JPA association 해제 후 schema 변경 필요 여부:
  - 컬럼 (`product_id`, `stock_id`) 자체는 그대로 사용 → 컬럼 변경 불필요.
  - DB FK 제약 (`fk_stock_product_id`, `fk_stock_history_stock_id`) 은 schema 에 남아있고 JPA 가 더 이상 인식하지 않을 뿐.
- 옵션:
  - (A) FK 제약을 본 sub-PR 에서 제거 (Flyway V 파일 1개)
  - (B) FK 제약 유지, schema 무변경

### 결정 내용

- (B) FK 제약 유지, Flyway migration 없이 진행한다.
- DB FK 일괄 제거는 본 issue (#195) 의 모든 sub-PR 완료 후 별도 트랙에서 진행한다.

### 근거

- Issue #195 본문 "DB FK 제약조건 일괄 제거 — 모든 코드 마이그레이션 완료 후 별도 PR/Issue 에서 진행" 명시.
- 도메인별 sub-PR 마다 schema 변경을 동반하면 Flyway V 파일이 흩어지고 schema 변경의 정책 단위가 분산된다.
- Hibernate `validate` 는 컬럼 단위 (이름 / 타입 / nullable) 검증이 기본이고 FK 제약 존재 여부는 검증 대상이 아니다. JPA 매핑에서 `@OneToOne` / `@ManyToOne` 제거 후에도 `product_id BIGINT NOT NULL` / `stock_id BIGINT NOT NULL` 매핑은 유지되므로 validate 통과 가능.

### 결과

- 본 sub-PR series 의 메타 원칙: "코드 차원 association 해제만, schema 변경 0건".
- 후속 Order / Payment sub-PR 도 동일 원칙 적용 (컬럼은 이미 `member_id`, `product_id`, `order_id` 등 Long 으로 존재).
- DB FK 제거 트랙은 별도 issue 로 발행해 Flyway migration 1개로 일괄 정리.

## 결정 4: fetch join 대체 패턴은 본 sub-PR 의 범위가 아니다

### 배경

- 후속 Order sub-PR 에서 `JpaOrderRepository` 의 fetch join 2 쿼리 (`join fetch o.member`, `join fetch oi.product`) 가 깨질 예정.
- 대체 패턴 선택지: (P1) JPQL 명시 join + DTO projection, (P2) batch composition (cart 패턴), (P3) read 전용 QueryService 분리.

### 결정 내용

- 본 sub-PR (Stock / StockHistory) 은 fetch join 사용처가 없으므로 대체 패턴 결정을 다루지 않는다.
- 본 sub-PR 의 ADR 에서 fetch join 대체 정책을 미리 선언하지 않는다. 후속 Order sub-PR 에서 사용처별로 (P1/P2/P3) 결정하고 그 PR 의 ADR / architecture 에 정리한다.

### 근거

- Stock / StockHistory 는 derived query 1개 + JPQL 2개로 단순하다. fetch join 대체 정책 사전 선언은 본 sub-PR 의 작업 단위와 무관.
- 일반 원칙 ("hot path = projection, 그 외 = batch composition") 을 미리 박으면 후속 PR 의 자유도가 좁아진다. 사용처별 분석 후 결정이 더 합리적.

### 결과

- 본 ADR 에는 fetch join 대체 정책이 포함되지 않는다.
- Order sub-PR 에서 fetch join 대체 패턴 ADR 을 처음 정립한다.
