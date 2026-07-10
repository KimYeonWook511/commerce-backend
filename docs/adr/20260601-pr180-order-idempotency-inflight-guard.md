# 주문 생성 멱등은 Redis in-flight 차단 + DB unique 제약 최종 보장으로 단순화한다

- Status: accepted
- Date: 2026-06-01

## Context

- 주문 생성 멱등을 Redis 1차 + RDB 최종 이중 보장·AFTER_COMMIT 결과 캐싱으로 처리하던 기존 결정(→ PR#59)을 대체한다. 기존 결정은 사용처 0건으로 폐기됐다.
- **이유**: 결과 캐싱 (COMPLETED / FAILED) 은 DB unique index find 대비 latency 차이 ms 미만이고, 캐시-DB 정합성 위험만 추가. 캐시 책임을 *in-flight 차단* 한 가지로 좁히면 인터페이스가 2개 메서드로 단순해진다.

## Decision

- 주문 생성 요청은 멱등 키를 요구하며, Redis 는 in-flight 차단 전용, RDB unique 제약이 멱등성 진실의 단일 원천이다. `idempotencyKey` 는 클라이언트가 생성한 UUID 이며 HTTP Header (`Idempotency-Key`) 로 전달한다.
- **흐름**: Redis `reserve()` 성공 시 주문 생성 → finally `clear()` 로 마커 즉시 정리. Redis `reserve()` 실패 (다른 요청 처리 중) 시 409 `ORDER_IDEMPOTENCY_IN_PROGRESS` 응답. 클라이언트는 backoff 재시도.
- **Redis 장애 처리**: `reserve()` 의 `DataAccessException` 은 Infrastructure adapter 에서 `OrderIdempotencyStoreUnavailableException` 으로 변환 (log.error). `OrderCreateService` 가 catch 해 DB unique 제약 안전망 경로(`findOrExecute`)로 fallback 진행 (log.warn, 정상 응답 가능). marker 미생성 경로이므로 `clear()` 호출하지 않는다. `clear()` 의 `DataAccessException` 은 Infrastructure 에서 warn 만 (마커 잔존은 60초 TTL 만료로 자가 회복).
- **PROCESSING TTL**: 60초. MySQL `innodb_lock_wait_timeout` (50초) + α.

## Consequences

같은 키 재시도 시점에 DB 상태가 바뀌면 다른 응답이 나올 수 있음 (멱등성은 DB 상태 기준). Redis timeout 시 응답 latency 영향 (비동기 listener 도입은 별도 작업).
