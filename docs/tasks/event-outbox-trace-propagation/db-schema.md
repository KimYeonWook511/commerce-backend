# 태스크 DB 스키마

## 개요

`tbl_outbox_event` 테이블에 `trace_id` 컬럼을 신규 추가한다. Outbox 생성 시점의 MDC traceId를 저장하여 relay 시 복원할 수 있게 한다.

## 신규 테이블

- 없음

## 변경 테이블

### `tbl_outbox_event`

**변경 이유:** Outbox 생성 시점의 traceId를 저장하여 relay → Kafka 발행 → consumer 처리 흐름을 원본 HTTP 요청과 동일한 traceId로 추적한다.

**추가 컬럼:**

| 컬럼명 | 타입 | nullable | 설명 |
|--------|------|----------|------|
| `trace_id` | `VARCHAR(64)` | YES | outbox 생성 시점의 MDC traceId. 형식 `^[A-Za-z0-9_-]{1,64}$`. MDC에 유효한 traceId가 없으면 NULL. |

## 인덱스

- `trace_id` 컬럼에 대한 인덱스는 추가하지 않는다.
- 이유: relay 조회는 `eventType + status + nextRetryAt + id` 기존 인덱스(`idx_outbox_event_type_status_next_retry_id`)로 처리된다. `trace_id`는 조회 조건이 아니라 selectlist에만 포함된다.

## 데이터 무결성

- `trace_id`는 nullable로 둔다.
- 기존 outbox 데이터는 trace_id가 NULL이며, relay 시 NULL이면 MDC 조작 없이 진행한다.
- 길이 64자 제한은 `LogContext.isValidTraceId()`의 정규식 `^[A-Za-z0-9_-]{1,64}$`와 일치한다.

## 마이그레이션 고려사항

- ddl-auto=update(prod, local) 환경에서 컬럼이 자동 추가된다.
- ddl-auto=create-drop(test) 환경에서는 매 실행마다 재생성된다.
- 기존 데이터는 trace_id가 NULL인 상태로 유지된다. 별도 백필이 필요하지 않다.
- 롤백 시: 컬럼 제거 가능. 데이터 손실은 trace_id 값(추가 메타데이터)에 한정.
- Flyway 미도입 상태에서는 운영 배포 시 SQL 검토는 운영 절차에 위임한다(범위 밖).
