# 태스크 DB 스키마

## 개요

- 본 태스크는 **DB schema 변경 없음**. Flyway migration 도 추가하지 않는다.
- JPA 매핑 차원에서 cross-aggregate association 만 해제하고, 컬럼·FK·unique 제약은 그대로 유지한다.
- 선행 sub-PR (`stock-jpa-association-decouple`, `order-jpa-association-decouple`) 의 메타 원칙 동일.

## 신규 테이블

- 없음.

## 변경 테이블

- 없음.

## 인덱스

- 변경 없음. 기존 unique / FK 인덱스 그대로 유지.

## 데이터 무결성

### 유지되는 schema 제약

- `tbl_payment.order_id BIGINT NOT NULL` — 컬럼 그대로.
- `uk_payment_order_id` (`tbl_payment.order_id` unique) — Payment 1:1 Order 보장. 그대로 유지.
- `fk_payment_order_id` (`tbl_payment.order_id → tbl_order.id`) — FK 그대로 유지.
- `uk_payment_merchant_pay_key`, `uk_payment_pg_payment_id`, `tbl_payment_attempt` 의 컬럼·unique 제약 — 변경 대상 아님. 그대로 유지.

### JPA 매핑과 schema 의 관계

- 본 태스크 후 JPA entity 는 `Payment.orderId: Long` 으로 매핑된다. `@OneToOne Order` association 은 사라진다.
- DB schema 에는 FK 제약이 남아있고, JPA 가 더 이상 그 정보를 인식하지 않을 뿐이다. DB 차원의 referential integrity / unique 제약은 그대로 보장된다.
- Hibernate `validate` 는 컬럼 단위 (이름 / 타입 / nullable) 검증이므로 association 매핑 제거 후에도 validate 통과 가능하다. unique 제약은 `@Table(uniqueConstraints = ...)` 매핑이 유지되므로 명시적 검증 대상으로 남는다.

### test 프로파일

- `application-test.yml` 의 `ddl-auto: create-drop` 에서는 Hibernate 가 schema 를 새로 생성. `@OneToOne` 제거 후 새 schema 에는 FK 제약이 생기지 않으나, unique 제약 (`uk_payment_order_id`) 은 `@Table` 매핑 그대로 유지된다. 테스트 동작에 영향 없다.

## 마이그레이션 고려사항

- **배포 순서**: DB schema 변경 없음 → 무중단 배포 가능. 코드 배포 한 번으로 완료.
- **백필**: 불필요. 기존 데이터 그대로 사용.
- **롤백**: 코드 롤백만으로 이전 상태 복원. schema 변경 없으므로 schema 롤백 불필요.
- **FK 제거 트랙**: 본 sub-PR 머지로 series (Stock / Order / Payment) 완료. 별도 issue 로 Stock / Order / Payment 의 FK (`fk_stock_product_id`, `fk_stock_history_stock_id`, `fk_order_member_id`, `fk_order_item_product_id`, `fk_payment_order_id`) 일괄 제거 Flyway migration 발행. 본 태스크에서 다루지 않는다.
