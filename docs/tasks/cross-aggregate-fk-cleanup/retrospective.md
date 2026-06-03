# Cross-Aggregate FK Cleanup Retrospective

## 개요

본 트랙은 ADR-020 후속 시리즈 (Stock #199 / Order #200 / Payment #202 / 본 FK cleanup) 의 마지막 작업이다. 선행 세 sub-PR이 JPA 매핑 차원에서 cross-aggregate association 을 모두 해제한 상태에서, DB schema 에 남아있던 cross-aggregate FK 5건을 단일 Flyway V4 migration 으로 일괄 제거했다.

변경 면적이 의도적으로 좁다. 코드 변경 0건, 자바 파일 1줄도 바뀌지 않았다. 산출물은 `V4__drop_cross_aggregate_fk_constraints.sql` 1개 + 루트 docs 3개 (`docs/ADR.md`, `docs/db-schema.md`, `docs/architecture.md`) 갱신이 전부다. 선행 세 sub-PR에서 정책 결정이 이미 완료된 덕분에 본 트랙의 결정 사항이 schema 전환 1건 + 운영 배포 분리 1건으로 수렴할 수 있었다.

본 PR 머지 시점에 series 4 트랙 전체가 완전히 종료됐다. 코드 차원 cross-aggregate association 0건 + DB cross-aggregate FK 0건 — 두 축이 일치하는 정합성이 회복됐다.

---

## 결정 흐름

### 1. cross-aggregate FK 5건을 단일 V4 파일로 일괄 제거한다

도메인별 V 파일 3개로 분리하는 안 (Stock 2건 / Order 2건 / Payment 1건) 과 단일 V4 파일에 5건을 묶는 안을 비교했다.

도메인별 분리의 장점은 운영 배포 시 ALTER 단위를 도메인 경계로 쪼갤 수 있다는 점이다. 그러나 본 트랙의 정책 단위가 "series 마무리 한 건" 이고, 5건이 모두 `V1__init.sql` 한 파일에서 함께 정의됐던 origin 도 단일 V4 와 일관된다. 더 중요하게는 본 PR 의 범위가 "Flyway 파일 추가 + local/test 검증까지" 이고 운영 배포 절차는 별도 결정 (결정 4) 이므로, 본 PR 에서 도메인별 V 파일을 분리해서 얻는 실익이 없었다.

단일 V4 파일로 묶음으로써 PR 의 정책 메시지가 "cross-aggregate FK 일괄 제거" 로 단일하게 표현된다.

### 2. UNIQUE 제약과 잔류 KEY index 는 유지한다

FK 를 제거할 때 같은 테이블의 UNIQUE 제약이나 FK 와 동명 KEY index 를 함께 DROP 하는 안도 검토했다.

UNIQUE 제약 (`uk_stock_product_id`, `uk_payment_order_id`) 은 FK 와 서로 다른 제약이다. "Stock 1:1 Product / Payment 1:1 Order" 라는 도메인 invariant 를 DB 차원에서 보증하는 역할이고, FK 제거로 약해지지 않는다. JPA `@Table(uniqueConstraints = ...)` 매핑도 함께 UNIQUE 를 선언하고 있어, schema 와 코드가 일치한 채 유지된다.

FK 와 동명으로 잔류하는 KEY index (`KEY fk_stock_history_stock_id (stock_id)`, `KEY fk_order_item_product_id (product_id)` 등) 는 조회 보조용으로 유지해도 무해하고, 이를 함께 DROP 하면 ALTER 횟수가 늘어나 운영 lock 영향이 커진다. 본 PR 에서 불필요한 ALTER 를 추가할 이유가 없었다.

결과적으로 V4 SQL 은 `DROP FOREIGN KEY` 5건만 포함하고 `DROP INDEX` / `DROP KEY` 명령은 0건이다.

### 3. same-aggregate FK (`fk_order_item_order_id`) 는 제외 범위다

`tbl_order_item.order_id` 의 FK 가 schema 에 남아있다는 사실을 알면서도 이를 포함하지 않았다. 이유는 단순하다. ADR-020 의 적용 범위가 cross-aggregate 에 한정되고, "같은 aggregate 내 root-child 관계는 객체 참조 허용" 이라고 명시한다. Order ↔ OrderItem 관계는 Order sub-PR (#200) 결정 3 에서 lifecycle 결합이 강한 same-aggregate 로 판정했고, 코드 차원에서도 `@OneToMany`/`@ManyToOne` 객체 참조가 유지됐다. schema 가 코드 결정과 일관하게 유지되는 것이 옳다.

### 4. 운영 DB 의 FK 제거 적용 절차는 본 PR 범위 밖이다

본 PR 의 범위 옵션이 두 가지였다. (A) Flyway 파일 추가 + local/test 검증까지. (B) 운영 DB 배포 절차 (lock 영향 분석 / 무중단 절차 / 롤백 정책) 까지 문서화.

(A) 를 선택한 이유는 Issue #195 본문이 명시적으로 분리했기 때문이기도 하지만, 운영 배포 절차는 운영 DB 현황 (트래픽 / 정비 윈도우 / 무중단 절차 표준) 에 의존하는 결정이라 본 PR 의 "schema 정합성 회복" 목적과 다른 축이다. 두 결정을 한 PR 에 묶으면 코드·schema 정합성 회복이라는 단일 메시지가 흐려진다.

기술적 사실만 짧게 남긴다. MySQL InnoDB 의 `ALTER TABLE ... DROP FOREIGN KEY` 는 데이터 복사가 없어 metadata lock 만 잡고 끝나는 DDL 이다. 운영 배포 단계에서 트랜잭션 경쟁 / replication topology 에 맞는 구체적 절차를 점검하면 된다.

### 5. 완료된 task 폴더의 ADR / 회고는 사후 수정하지 않는다

본 트랙이 series 마무리이기 때문에 선행 sub-PR 의 ADR / 회고에 "FK 제거 완료" 같은 후속 노트를 부착하고 싶은 유혹이 있었다. 그러나 CLAUDE.md / `docs/tasks/README.md` 의 "완료된 tasks 불변 원칙" 이 이를 금지한다. 머지 완료된 task 폴더 문서는 그 시점 결정의 기록이며, 이후 변경은 루트 `docs/` 문서에서 표현한다.

series 마무리 사실은 본 트랙의 이 회고와 `docs/ADR.md` ADR-020 후속 노트로만 표현했다.

---

## 기각된 옵션

| 옵션 | 검토 이유 | 기각 사유 |
|---|---|---|
| 도메인별 V 파일 3개 분리 | 운영 배포 ALTER 단위 분리 | 본 트랙의 정책 단위가 "series 마무리 한 건". V1 origin 도 단일 파일. 운영 배포는 결정 4로 분리됨 |
| FK + 잔류 KEY index 함께 DROP | schema 정리 완결성 | UNIQUE 는 도메인 invariant 표현으로 유지해야 함. KEY index DROP 추가 시 ALTER 횟수 증가 → 운영 lock 단위 확대 |
| same-aggregate FK (`fk_order_item_order_id`) 포함 | FK 일괄 제거 완결성 | ADR-020 범위 밖. Order ↔ OrderItem 은 same-aggregate, Order sub-PR 결정 3 그대로 유지 |
| 운영 배포 절차 포함 | 한 PR 에서 완결 | Issue #195 본문이 분리 명시. 운영 배포는 DB 현황 의존적 결정으로 다른 축 |
| lag 허용 기간 표준 ADR 정립 | 향후 series 가이드 | 표본 1건으로 표준화하지 않기로 결정. 다른 series 에서 lag 가 반복 등장하면 그때 ADR 정립 |
| 선행 sub-PR task 폴더에 후속 노트 부착 | series 추적 가시성 | 완료된 tasks 불변 원칙 위반. series 마무리 사실은 본 회고 + ADR-020 후속 노트로만 표현 |

---

## series 전체 baseline 정리

### 코드-schema lag 의 사실 기록

4 트랙 series 가 진행되는 동안 *과도기 상태* 가 있었다. Stock sub-PR (#199, 2026-06-03 머지) 이후 코드는 `Stock.product` association 을 해제했지만 DB 의 `fk_stock_product_id` 는 그대로였다. Order sub-PR (#200) / Payment sub-PR (#202) 를 거치며 코드 차원 cross-aggregate association 이 모두 0건이 됐음에도 DB FK 5건은 schema 에 남아있었다. 이 lag — 코드에서 association 해제, schema 에 FK 존재 — 는 본 트랙 (#203, 2026-06-03 머지) 에서 종료됐다.

Stock sub-PR 이 series 의 메타 원칙으로 "schema 변경 0건 — 모든 sub-PR 완료 후 일괄 제거" 를 명시한 이유는 이 lag 를 의도적으로 허용한 결과다. 부분 제거는 일부 도메인만 FK 없는 불균형 schema 상태를 만들고, Flyway V 파일을 각 sub-PR 에 흩어두면 schema 변경의 정책 단위가 분산된다는 판단이 있었다.

lag 기간 동안 기술적 위험은 낮았다. Hibernate `validate` 는 FK 제약 존재 여부를 검사하지 않고 컬럼 이름·타입·nullable 여부만 비교한다. FK 가 schema 에 남아있는 동안은 부모 row 삭제 시도 시 DB 가 FK violation 으로 거부하며 GlobalExceptionHandler 500 안전망으로 위임된다 — 이는 의도된 동작이었다.

### lag 표준 정책 미정의

본 트랙에서 "향후 다른 series 의 코드-schema lag 허용 기간 표준" 을 ADR 로 박지 않기로 결정했다. 이유는 표본 1건으로 일반화하는 것이 이르다고 판단했기 때문이다. Stock (#199) ~ FK cleanup (#203) 의 lag 기간은 약 하루였고, 이는 의도적으로 단기간에 연속 머지한 결과다. 서로 다른 타임라인을 가진 다른 series 에서는 lag 기간이나 허용 조건이 달라질 수 있다. 다른 series 에서 lag 가 반복 등장하면 그때 패턴을 모아 ADR 을 정립한다.

### 메타 원칙 — 4 트랙 전체에서 유지됨

- **schema 변경 0건 (Stock / Order / Payment sub-PR)**: Flyway V 파일 추가 없음. 컬럼·FK·unique 제약 그대로 유지. Hibernate `validate` 통과를 매번 integrationTest (Testcontainers MySQL) 로 확인.
- **응답 계약 무변경 (Stock / Order / Payment sub-PR)**: 응답 DTO 시그니처·필드 건드리지 않음. 내부 조립 방식만 교체. "응답 echo 정리는 별도 트랙" 정책이 series 전체를 관통.
- **완료된 task 폴더 불변 (본 FK cleanup)**: 선행 sub-PR 의 task 폴더 문서 수정 없음. series 마무리 사실은 루트 docs 와 본 회고로만 표현.

### 도메인 시그니처 Long ID 패턴 — Order PR 에서 정립, Payment PR 에서 계승

Order sub-PR 이 `Order.create(Long memberId)` / `addOrderItem(Long productId, int quantity, int unitPrice)` 전환으로 Long ID 시그니처 패턴을 처음 정립했다. Payment sub-PR 이 `Payment.createCompleted(Long orderId, int amount, ...)` 전환으로 동일 패턴을 계승했다. 핵심 원칙은 "도메인은 ID + 단순 값만 받고, 외부 객체 의존 없이 동작한다" 이다. 향후 신규 도메인 팩토리 메서드를 설계할 때 이 패턴을 참조할 수 있다.

### fetch join 대체 일반 원칙 — Order PR 에서 정립, Payment PR 에서 인용

Order sub-PR 이 네 가지 사용처 분석을 통해 일반 원칙을 처음 명문화했다 — "same-aggregate fetch join 유지 / cross-aggregate fetch 제거. 필요한 cross-aggregate 데이터는 사용처별 양상에 맞게 컬럼 직접 사용 또는 `findAllById` batch 1회 + 응답 DTO 외부 주입". Payment 도메인은 fetch join JPQL 이 없어 신규 결정 사항이 없었다. 이 일반 원칙은 `docs/tasks/order-jpa-association-decouple/adr.md` 결정 2 에서 단일 관리된다.

### 응답 DTO 외부 주입 패턴 — Stock PR 에서 시작, Order PR 에서 확장

Stock sub-PR 의 `StockHistoryResult.from(history, productId)` 가 외부 컨텍스트 주입의 최초 사례였다. Order sub-PR 이 `PaymentReadyResult.from(order, productNameByProductId)` 로 자연스럽게 확장했다. Payment sub-PR 에서는 Payment 응답이 Payment 자신의 필드로 완결되어 신규 적용 사례가 없었다. 이 패턴의 원칙은 "응답 DTO 가 여러 aggregate 의 데이터를 조립해야 할 때 entity 객체 traversal 대신 application 계층이 명시적으로 조립한다" 이다.

### 별도 트랙으로 분리된 항목들

series 4 트랙이 진행되면서 범위 밖으로 분리된 항목들이 있다. 이 항목들은 series 가 진행되면서 가시화됐지만 메타 원칙 ("schema 변경 0건", "응답 계약 무변경", "한 PR 한 메시지") 을 지키기 위해 의도적으로 분리했다.

- **응답 echo 정리** (`StockHistoryResult.productId`, `PaymentReady` 의 orderId/merchantPayKey echo 등) — 3개 sub-PR 에서 연달아 분리. 적절한 시점에 별도 트랙에서 정리.
- **결제 시점 가격 snapshot** (`OrderItem` 단가 컬럼 미저장 문제) — Order sub-PR 결정 중 파악. Issue #201 별도 트랙.
- **`Payment` ↔ `PaymentAttempt` aggregate 경계 명시** — `merchantPayKey` 기반 결합이 도메인 레이어에서 명시적으로 표현되지 않음. Payment sub-PR 회고에서 언급된 후속 정비.
- **운영 DB 배포 절차** — 본 트랙에서 schema 만 변경. 운영 DB 의 FK 제거 적용 시점·절차·무중단 여부는 별도 결정.

---

## 운영 점검

본 PR 머지 후 local 개발 환경 / CI (Testcontainers integrationTest) 에서는 Flyway V4 migration 이 즉시 적용되어 FK 5건 없는 schema 로 진입한다.

운영 DB 의 FK 제거 적용은 본 PR 범위 밖이다. Issue #195 본문에서 "운영 DB 의 FK 제거 적용 — schema 변경 적용은 별도 결정" 으로 명시적으로 분리됐다. 운영 DB 배포 시점·절차·무중단 여부·롤백 정책은 본 PR 머지 후 별도로 결정된다.

기술적 참고 사항: MySQL InnoDB 의 `ALTER TABLE ... DROP FOREIGN KEY` 는 테이블 데이터 복사가 없어 짧은 metadata lock 만 잡는 DDL 이다. 그러나 운영 DB 의 동시 트랜잭션 경쟁도 / replication topology / 정비 윈도우는 운영 배포 결정 단계에서 별도 점검이 필요하다.

application 차원의 정합성 방어는 선행 series 에서 이미 갖춰진 상태다. `productRepository.findById(productId).orElseThrow(PRODUCT_NOT_FOUND)` / `orderRepository.findByMerchantPayKeyForUpdate(...).orElseThrow(ORDER_NOT_FOUND)` / `memberRepository.findById(memberId).orElseThrow(MEMBER_NOT_FOUND)` 가 1차 방어선이다. DB unique 위반 (UNIQUE 제약 잔존) 은 안전망 500 으로 위임 (ADR-011).

---

## 자기 평가

### 잘된 점 — series 전체

- **schema 변경 0건 원칙의 일관된 유지**: Stock / Order / Payment 세 sub-PR 모두 Flyway V 파일 없이 JPA 매핑만 교체했고, Hibernate `validate` 통과를 integrationTest (Testcontainers MySQL) 로 매번 실행 결과로 확인했다. "schema 와 코드가 일시적으로 불일치한 상태" 를 의도적 lag 로 설계하고 본 FK cleanup 트랙에서 한 번에 정리함으로써 각 sub-PR 의 정책 단위가 단일하게 유지됐다.
- **결정 사항이 순차적으로 누적됐다**: Stock sub-PR 이 메타 원칙과 외부 주입 패턴을 정립하고, Order sub-PR 이 fetch join 대체 일반 원칙과 Long ID 시그니처를 정립했으며, Payment sub-PR 은 선행 두 sub-PR 의 원칙을 인용하며 변경 면적을 최소화했다. 마지막 FK cleanup 은 코드 변경 없이 schema 정합성만 회복했다. 4 트랙의 결정 경로가 ADR 간 cross-reference 로 추적 가능하다.
- **본 트랙의 변경 면적을 의도적으로 좁게 유지했다**: 코드 변경 0건이라는 제약을 지킴으로써 "schema 정합성 회복" 이라는 단일 메시지가 PR 전체를 관통했다. 회귀 위험도 사실상 0이었다 — `./gradlew test` 와 `./gradlew integrationTest` 모두 통과.

### 아쉬운 점 — series 전체

- **코드-schema lag 기간이 문서화됐지만 표준으로 정착되지 않았다**: lag 허용 결정의 근거 (도메인별 부분 제거의 schema 불균형, V 파일 분산의 정책 단위 훼손) 가 Stock sub-PR ADR 에 명시돼 있어 이번에는 합리적이었다. 그러나 다른 series 에서 동일한 lag 패턴이 등장할 때 이 결정을 찾아야 하는 탐색 비용은 여전히 있다. 표본이 2–3건 쌓이면 그때 ADR 으로 일반화하는 것이 적절한 타이밍이라고 본다.
- **별도 트랙으로 분리된 항목들이 미해결로 남아있다**: 응답 echo 정리 / 결제 시점 가격 snapshot (Issue #201) / Payment-PaymentAttempt aggregate 경계 명시 / 운영 DB 배포 절차가 series 전체 진행 중 파악됐지만 아직 미해결이다. 분리 결정 자체는 옳았으나, 이 항목들이 실제로 트랙으로 이어지지 않으면 기술 부채로 쌓인다.
- **운영 DB 배포 절차의 미결 상태**: 본 PR 머지 후 운영 DB 에는 여전히 FK 5건이 남아있다. 운영 배포 결정을 별도로 분리한 것은 옳은 판단이지만, "별도 결정" 이 명확한 담당자와 시점을 가진 action item 이 되어야 한다. 이 점이 회고 이후 follow-up 이 필요한 가장 중요한 항목이다.
