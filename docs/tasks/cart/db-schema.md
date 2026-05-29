# 태스크 DB 스키마

## 개요

- 신규 테이블 `tbl_cart_item` 1개를 추가한다.
- 기존 테이블은 변경하지 않는다.

## 신규 테이블

### `tbl_cart_item`

- **목적**: 회원이 장바구니에 담은 상품 항목을 저장한다. cart aggregate root이며 사용자별 cart는 `member_id` 필터링된 row 집합으로 표현된다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | 식별자 |
| `member_id` | BIGINT | NOT NULL | 회원 ID (FK 없음, ID 참조) |
| `product_id` | BIGINT | NOT NULL | 상품 ID (FK 없음, ID 참조) |
| `quantity` | INT | NOT NULL | 1~99 (도메인 invariant + DTO Bean Validation) |
| `version` | BIGINT | NOT NULL DEFAULT 0 | JPA `@Version` 낙관적 락 (결정 8) |
| `created_at` | DATETIME | NOT NULL | `BaseTimeEntity` |
| `updated_at` | DATETIME | NOT NULL | `BaseTimeEntity` |

## 변경 테이블

- 없음

## 인덱스

| 인덱스 | 컬럼 | 종류 | 목적 |
|---|---|---|---|
| `uk_cart_item_member_product` | `(member_id, product_id)` | UNIQUE | 같은 회원이 같은 상품을 두 번 담지 못하게 보장. UPSERT 의미를 DB 레벨에서 강제. `findAllByMemberIdOrderByCreatedAtDesc`, `findByMemberIdAndProductId`, `deleteByMemberIdAndProductIdIn` 조회 인덱스도 함께 제공. |

별도의 단독 `member_id` 인덱스는 추가하지 않는다. UNIQUE 복합 인덱스가 prefix(`member_id`) 조회를 동일하게 커버한다.

## 데이터 무결성

- `(member_id, product_id)` UNIQUE 제약으로 같은 회원의 같은 상품 중복 row를 차단한다.
- `version` 컬럼은 결정 8(낙관적 락 + retry + Processor 분리)을 따른다. JPA `@Version`이 UPDATE 시점에 version 비교로 update race를 감지하고, 응용 Service의 retry loop(`MAX_RETRY = 3`)가 `ObjectOptimisticLockingFailureException`을 흡수한다.
- 신규 항목 동시 insert race window의 UNIQUE 충돌은 ADR-011 find-first 패턴 + 안전망 500으로 위임한다. retry catch에는 포함하지 않는다.
- `member_id`, `product_id`는 FK 제약을 두지 않는다(ID 참조 정책, ADR-020).
- Application 레이어가 다음을 책임진다.
  - 회원/상품 삭제 시 cart row 정리 정책(현재 phase에서 회원/상품 hard delete는 다루지 않음)
  - 주문 성공 시 주문된 productId만 일괄 삭제(`deleteByMemberIdAndProductIdIn`)
- `quantity`는 도메인 invariant(`MIN=1, MAX=99`)와 DTO Bean Validation(`@Min(1) @Max(99)`)이 이중 가드한다.

## 마이그레이션 고려사항

- Hibernate `ddl-auto: update` 정책으로 신규 테이블이 자동 생성된다. 별도 마이그레이션 스크립트는 없다.
- ADR-018에 따라 향후 Flyway 도입 시 본 테이블도 마이그레이션 스크립트로 옮긴다.
- 기존 데이터 백필/이관 없음(신규 테이블).
- 운영 배포 시 ENUM 컬럼 함정(ADR-018)은 본 테이블에 enum이 없어 영향이 없다.
