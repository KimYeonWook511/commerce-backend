# 태스크 DB 스키마

## 변경 컬럼 목록

신규 환경/test에서 아래 컬럼이 MySQL ENUM이 아닌 VARCHAR로 생성된다. 운영 DB의 기존 컬럼 타입 ALTER는 본 태스크 범위 외 (Flyway 도입 시 일괄 처리).

| 테이블 | 컬럼 | 매핑 enum | 변경 전 (Hibernate 6.x 기본) | 변경 후 |
|---|---|---|---|---|
| `tbl_processed_event` | `consumer_type` | `ProcessedEventConsumerType` | `ENUM(...)` | `VARCHAR(255)` |
| `tbl_outbox_event` | `event_type` | `OutboxEventType` | `ENUM(...)` | `VARCHAR(255)` |
| `tbl_outbox_event` | `status` | `OutboxEventStatus` | `ENUM(...)` | `VARCHAR(255)` |
| `tbl_outbox_event` | `aggregate_type` | `OutboxAggregateType` | `ENUM(...)` | `VARCHAR(255)` |
| `tbl_order` | `status` | `OrderStatus` | `ENUM(...)` | `VARCHAR(255)` |
| `tbl_payment_attempt` | `provider` | `PaymentProvider` | `ENUM(...)` | `VARCHAR(255)` |
| `tbl_payment_attempt` | `type` | `PaymentAttemptType` | `ENUM(...)` | `VARCHAR(255)` |
| `tbl_payment_attempt` | `status` | `PaymentAttemptStatus` | `ENUM(...)` | `VARCHAR(255)` |
| `tbl_payment_attempt` | `fail_code` | `PaymentAttemptFailCode` | `ENUM(...)` | `VARCHAR(255)` (nullable) |
| `tbl_payment` | `status` | `PaymentStatus` | `ENUM(...)` | `VARCHAR(255)` |
| `tbl_payment` | `provider` | `PaymentProvider` | `ENUM(...)` | `VARCHAR(255)` |
| `tbl_member` | `role` | `MemberRole` | `ENUM(...)` | `VARCHAR(255)` |
| `tbl_product` | `status` | `ProductStatus` | `ENUM(...)` | `VARCHAR(255)` |
| `tbl_stock_history` | `reason` | `StockAdjustmentReason` | `ENUM(...)` | `VARCHAR(255)` |

## 비고

- `nullable`, `unique` 등 기존 제약은 그대로 유지된다.
- 운영 DB의 기존 ENUM 컬럼이 `ddl-auto: update`에 의해 자동으로 VARCHAR로 ALTER되지 않을 가능성이 있다. 운영 DB ALTER는 Flyway 도입 시 일괄 처리한다.
