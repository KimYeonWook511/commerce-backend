# 태스크 DB 스키마

## 개요

- 본 태스크는 **DB schema 변경 없음**. Flyway migration 도 추가하지 않는다.
- JPA 매핑 차원에서 cross-aggregate association 만 해제하고, 컬럼·FK 제약은 그대로 유지한다.
- 선행 stock sub-PR (`stock-jpa-association-decouple`) 의 메타 원칙 (결정 3) 동일.

## 신규 테이블

- 없음.

## 변경 테이블

- 없음.

## 인덱스

- 변경 없음. 기존 FK 인덱스 그대로 유지.

## 데이터 무결성

### 유지되는 schema 제약

- `tbl_order.member_id BIGINT NOT NULL` — 컬럼 그대로.
- `tbl_order_item.product_id BIGINT NOT NULL` — 컬럼 그대로.
- `fk_order_member_id` (`tbl_order.member_id → tbl_member.id`) — FK 그대로 유지.
- `fk_order_item_product_id` (`tbl_order_item.product_id → tbl_product.id`) — FK 그대로 유지.
- `tbl_order_item.order_id` FK / Order ↔ OrderItem same-aggregate 관계 — 변경 대상 아님. 그대로 유지.

### JPA 매핑과 schema 의 관계

- 본 태스크 후 JPA entity 는 `Order.memberId: Long`, `OrderItem.productId: Long` 으로 매핑된다. `@ManyToOne Member`, `@ManyToOne Product` association 은 사라진다.
- DB schema 에는 FK 제약이 남아있고, JPA 가 더 이상 그 정보를 인식하지 않을 뿐이다. DB 차원의 referential integrity 는 그대로 보장된다.
- Hibernate `validate` 는 컬럼 단위 (이름 / 타입 / nullable) 검증이므로 association 매핑 제거 후에도 validate 통과 가능하다 (FK 제약 검증은 기본 비대상).

### test 프로파일

- `application-test.yml` 의 `ddl-auto: create-drop` 에서는 Hibernate 가 schema 를 새로 생성. `@ManyToOne` 제거 후 새 schema 에는 FK 제약이 생기지 않으나, 테스트 동작에 영향 없다 (테스트가 FK violation 에 의존하지 않음).

## 마이그레이션 고려사항

- **배포 순서**: DB schema 변경 없음 → 무중단 배포 가능. 코드 배포 한 번으로 완료.
- **백필**: 불필요. 기존 데이터 그대로 사용.
- **롤백**: 코드 롤백만으로 이전 상태 복원. schema 변경 없으므로 schema 롤백 불필요.
- **FK 제거 트랙**: 본 sub-PR series (Stock / Order / Payment) 모두 머지 후 별도 issue 로 FK 일괄 제거 Flyway migration 발행. 본 태스크에서 다루지 않는다.
