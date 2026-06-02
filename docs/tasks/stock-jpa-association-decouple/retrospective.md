# Stock JPA Association Decouple Retrospective

## 개요

본 sub-PR은 ADR-020의 "신규 도메인 cross-aggregate ID 참조 원칙"을 기존 도메인에 소급 적용하는 후속 트랙의 첫 번째 작업이다. `Stock.product(@OneToOne)`과 `StockHistory.stock(@ManyToOne)` 객체 참조를 각각 `productId(Long)`, `stockId(Long)` ID 필드로 전환하고, application 계층의 객체 traversal과 test fixture를 함께 정리했다. DB schema 변경 없이 JPA 매핑 차원에서만 association을 해제하는 것이 이번 series의 메타 원칙이다.

---

## 결정 흐름

### 1. 도메인별 sub-PR 분리 vs. 한 번에 처리

ADR-020 후속 트랙을 설계할 때 첫 번째 갈림길은 "범위를 어떻게 잡을 것인가"였다. Stock / Order / Payment 세 도메인을 한 PR에 묶는 안(A)과 도메인 경계 단위로 sub-PR을 분리하는 안(B) 사이에서 (B)를 선택했다.

(A)를 검토하면서 가장 빨리 기각된 이유는 **각 도메인이 서로 다른 복잡도를 가진다**는 점이었다. Stock은 fetch join이 없는 단순한 구조라 JPQL 수정과 ID 필드 교체만으로 끝나지만, Order는 `join fetch o.member`, `join fetch oi.product` 두 쿼리의 대체 패턴을 새로 결정해야 하고 Payment는 보상 흐름과 얽혀 있어 정책 단위가 섞인다. 세 도메인을 하나로 묶으면 PR 리뷰가 각기 다른 정책 결정을 같은 diff에서 읽게 되어 검토 비용이 크게 올라간다.

또 한 가지 실용적인 이유는 **점진 진행이 회귀 추적을 쉽게 만든다**는 것이다. Stock 단독 변경이 통과된 뒤 Order를 시작하면, Order에서 테스트가 깨졌을 때 원인이 Order 변경에 있다고 바로 좁혀진다. 한 번에 묶으면 같은 상황에서 원인을 세 도메인 중 어디서 찾아야 하는지 모호해진다.

### 2. StockHistory의 productId 처리 옵션 비교

JPA association을 해제하면 기존 `history.getStock().getProduct().getId()` traversal이 불가능해지는데, 이 traversal이 API 응답의 `productId` 필드를 채우는 유일한 경로였다. 대체 방안 세 가지를 검토했다.

**A안: 응답에서 productId 필드 제거** — 가장 단순한 선택처럼 보였지만 API 계약 변경이 동반된다. 본 sub-PR의 정책 목적은 "JPA association 해제"이지 "응답 계약 정비"가 아니다. 두 가지를 한 PR에 묶으면 PR의 메시지가 흐려지고, 혹시라도 응답 정비 결정이 번복됐을 때 association 해제와 얽혀 되돌리기 복잡해진다.

**B안: `from(history, productId)` 외부 주입** — 채택한 방안이다. StockHistory entity는 audit 목적의 aggregate로, "어떤 stock에 어떤 변경이 일어났는가"가 invariant이고 `stockId`가 그 핵심이다. `productId`는 현재 API endpoint의 path 컨텍스트이지 audit row의 본질이 아니다. application 계층이 audit row와 path 컨텍스트를 명시적으로 조립한다는 의도가 `from(history, productId)` 시그니처에 드러난다는 점도 매력이었다. ADR-020이 지적한 "편한 탐색 오용" 패턴(`history.getStock().getProduct().getId()`)을 코드 표면에서 제거하는 부수 효과도 있다.

**C안: `StockHistory.productId` 컬럼 신설** — Flyway migration이 동반되고 본 sub-PR series의 메타 원칙("schema 변경 0건")을 위반한다. 무엇보다 도메인 본질이 아닌 정보를 entity에 박는 것이 불편했다. `productId`는 productId-stockId 매핑이 바뀌는 경우(현재 unique 제약으로 불변이지만) 컬럼 정합성 관리 부담을 새로 만든다.

실용적 관점에서 C안을 기각한 추가 이유 하나: audit 이력이 쌓여 있는 테이블에 컬럼을 더하면 `ALTER TABLE`이 필요하고, stock volume이 작은 현재도 향후 데이터가 쌓인 뒤 운영 환경에서 동일 작업을 반복하게 된다면 부담이 커진다.

### 3. schema 변경 없이 진행하는 메타 원칙

DB FK 제약(`fk_stock_product_id`, `fk_stock_history_stock_id`)을 본 sub-PR에서 즉시 제거하는 안도 검토했다. Flyway V 파일 하나로 끝나고 schema가 코드 의도와 일치해진다는 장점이 있다.

그러나 **모든 sub-PR 완료 전까지 일부 도메인만 FK 없는 상태**가 되는 것은 불균형한 schema 상태를 만든다. Issue #195 원문이 "모든 코드 마이그레이션 완료 후 일괄 정리"를 명시했고, Flyway V 파일을 sub-PR마다 흩어두면 schema 변경의 정책 단위가 분산된다는 점에서 기각했다.

기술적으로 핵심인 부분은 Hibernate `validate` 통과 가능성이었다. `@OneToOne`/`@ManyToOne`을 제거하더라도 `@Column(name = "product_id", nullable = false)`와 `@Column(name = "stock_id", nullable = false)` 매핑은 남아있기 때문에, Hibernate는 컬럼 이름·타입·nullable 여부를 기준으로 validate하고 FK 제약 존재 여부는 검증 대상이 아니다. 실제로 `./gradlew integrationTest`(Testcontainers로 실제 MySQL 기동)를 통과하며 이를 확인했다.

또 한 가지 주목할 점은 test 프로파일(`ddl-auto: create-drop`)에서 Hibernate가 schema를 새로 생성하면 `@OneToOne`/`@ManyToOne` 없는 entity에서 FK 제약이 생성되지 않는다는 것이다. 테스트가 FK violation에 의존하지 않으므로 동작에 영향이 없고, 실제로도 모든 테스트가 통과했다.

### 4. fetch join 대체 패턴을 본 sub-PR ADR에 미리 박지 않은 이유

후속 Order sub-PR에서 `join fetch o.member`, `join fetch oi.product`가 깨지면 대체 패턴(P1: JPQL DTO projection, P2: batch composition, P3: QueryService 분리)을 결정해야 한다는 것을 인지했다. 그러나 Stock 도메인에는 fetch join 사용처가 없으므로 본 sub-PR에서 미리 대체 패턴을 선언하는 것은 자연스럽지 않다.

한발 더 나아가 미리 선언하는 것이 오히려 해롭다고 판단했다. 일반 원칙을 지금 박아두면 Order sub-PR에서 사용처별 분석 결과가 원칙과 맞지 않을 때 원칙을 억지로 따르거나 원칙을 소급 수정하는 비용이 생긴다. Order sub-PR 시점에 실제 사용처를 보고 패턴을 정하는 것이 더 합리적이다.

---

## 기각된 옵션

| 옵션 | 검토 이유 | 기각 사유 |
|---|---|---|
| 세 도메인 한 PR 처리 | 작업 단위 단순화 | 도메인별 설계 복잡도 차이 큼. Order의 fetch join 대체 패턴, Payment의 보상 흐름 얽힘이 정책 단위를 분산시킴 |
| `StockHistoryResult`에서 `productId` 필드 제거 | 응답 계약 단순화 | 본 sub-PR의 정책 목적(association 해제)과 다른 축의 결정. 혼재 시 PR 메시지 흐림 |
| `StockHistory.productId` 컬럼 신설 | audit row 자체 조회 완결성 | schema 변경 0건 원칙 위반. 도메인 본질 아닌 정보를 컬럼으로 박는 부담 |
| FK 제약 즉시 제거 | schema 일관성 | Issue #195에서 일괄 처리 명시. 부분 제거 시 schema 불균형 상태 |
| fetch join 대체 패턴 ADR 선언 | 후속 Order sub-PR 사전 가이드 | Stock에 사용처 없음. 선언 후 Order에서 사용처별 분석 결과가 어긋날 경우 소급 수정 비용 발생 |

---

## 후속 트랙으로 넘기는 baseline

### Order / Payment sub-PR이 본 sub-PR을 참조하는 방법

본 sub-PR이 확립한 패턴과 원칙을 그대로 따른다.

- **ID 필드 전환 패턴**: `@ManyToOne` → `@Column(name = "xxx_id", nullable = false) Long xxxId` 교체. builder 시그니처 갱신.
- **application 외부 주입 패턴**: 응답 DTO가 여러 컨텍스트에서 데이터를 조립해야 하면 `from(entity, externalContext)` 시그니처를 사용한다. entity 객체 traversal에 의존하지 않는다.
- **schema 변경 0건 원칙**: Flyway V 파일 추가 없음. FK 제약 유지. 모든 sub-PR 완료 후 Issue #195 후속으로 FK 일괄 제거.
- **Hibernate validate 통과 기준**: ID 필드에 `@Column(name = "xxx_id", nullable = false)` 유지만으로 validate 통과 확인됨.

### Order sub-PR의 신규 결정 사항

본 sub-PR에서 의도적으로 미루어 둔 결정이 있다.

- **fetch join 대체 패턴**: `join fetch o.member`, `join fetch oi.product` 제거 후 대체 방안(JPQL DTO projection / batch composition / QueryService 분리)을 Order sub-PR에서 사용처별 분석하여 처음으로 정립한다. 이 결정이 Order sub-PR의 핵심 ADR이 된다.
- **응답 echo 정리**: `StockHistoryResult.productId`, `AdminStockResult.productId` 같이 path productId를 echo하는 응답 필드 정비는 별도 트랙으로 분리됐다. Order / Payment도 같은 정책을 따른다.

---

## 운영 점검

JPA association 해제 후 DB schema에는 `fk_stock_product_id`와 `fk_stock_history_stock_id` FK 제약이 그대로 남아있다. JPA가 이 제약을 더 이상 인식하지 않을 뿐이며, DB 차원의 referential integrity는 유지된다.

운영 환경에서 모니터링 시 유의할 사항은 두 가지다.

첫째, FK 제약이 schema에 남아있는 동안은 부모 row 삭제 시도 시 DB가 FK violation으로 거부한다. 이는 의도한 동작이다. Product 삭제나 Stock 삭제 경로에서 FK violation이 발생하면 기존과 동일하게 GlobalExceptionHandler 500 안전망으로 위임된다.

둘째, 이 상태(코드에서 association 해제, schema에 FK 존재)는 모든 sub-PR 완료 후 별도 FK 제거 트랙이 진행될 때까지 유지된다. Order sub-PR, Payment sub-PR 머지 후 Issue #195를 close하고, 이어서 FK 일괄 제거 Flyway migration을 별도 issue/PR로 발행한다.

---

## 자기 평가

### 잘된 점

- **test fixture 전수 갱신**: `Stock.builder().product(...)`, `StockHistory.builder().stock(...)` 호출부를 stock 도메인뿐 아니라 order 도메인 테스트(`OrderCreateServiceConcurrencyTest` 등)까지 컴파일 오류를 따라가며 전수 갱신했다. 도메인 경계를 넘는 fixture 의존이 얼마나 퍼져 있는지가 이 작업에서 가시화됐다.
- **Hibernate validate 검증**: Testcontainers로 실제 MySQL을 기동하는 `integrationTest`를 포함시켜 "validate 통과 가능하다"는 주장이 모델 진술이 아닌 실행 결과로 확인됐다.
- **ADR 결정 4가지가 사전에 명확히 정립되어 있어 구현 중 판단 비용이 낮았다**: 어떤 패턴을 쓸지, schema를 건드릴지 말지, 응답 계약을 변경할지 말지가 구현 시작 전에 모두 결정되어 있어 코딩 도중 판단 분기가 없었다.

### 아쉬운 점

- **test fixture 변경 면적이 예상보다 넓었다**: `Stock.builder().product(...)` 호출부가 stock 도메인 외부(order, product)까지 퍼져 있어 변경 면적이 컸다. 이는 cross-aggregate 객체 참조가 test fixture에서도 도메인 경계를 넘어 침투한 결과다. ADR-020 원칙을 신규 도메인부터 적용한 효과를 역으로 보여주는 사례이기도 하다.
- **응답 echo 정리를 별도 트랙으로 미룬 점**: `StockHistoryResult.productId`와 `AdminStockResult.productId`가 path productId를 그대로 되돌려주는 구조는 이번 리팩토링으로 오히려 코드에서 더 명시적으로 드러났다. 적절한 시점에 별도 트랙으로 정리가 필요하다.
- **StockHistory와 Stock을 별도 aggregate로 다루는 결정의 문서화**: `StockHistory.@ManyToOne Stock`도 cross-aggregate association으로 보고 해제하기로 결정했지만, 이 결정의 근거("StockHistory는 audit 도메인이고 Stock lifecycle에 종속되지 않는다")가 ADR에는 있어도 architecture.md에서 충분히 드러나지 않을 수 있다. 후속 Order sub-PR에서 유사한 판단이 필요할 때 참조 포인트로 활용할 수 있도록 architecture.md를 보강할 여지가 있다.
