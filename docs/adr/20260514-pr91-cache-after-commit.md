# Redis 캐싱은 RDB 커밋 이후 실행한다

- Status: accepted
- Date: 2026-05-14

## Context

Redis 장애 시 RDB 롤백을 방지한다. 멱등성 캐싱은 정합성이 아닌 편의 목적이므로 RDB 커밋이 완료된 뒤 별도 실행해도 무방하다. `@TransactionalEventListener`는 DDD 레이어 경계를 유지하며 Application이 Infrastructure를 직접 알지 않아도 되므로 `TransactionSynchronizationManager`보다 자연스럽다.

## Decision

- Redis 작업은 기본적으로 `@TransactionalEventListener(phase = AFTER_COMMIT)`으로 분리하여 RDB 트랜잭션 커밋 이후에 실행한다.
- **기능별 판단 기준**: 기본값은 AFTER_COMMIT 분리다. Redis 장애 시 RDB도 롤백해야 하는 정합성 최우선 상황에서는 동일 트랜잭션을 택하고, 해당 기능 ADR에 이유를 명시한다.
- **주의사항**: AFTER_COMMIT 시점은 트랜잭션이 이미 종료된 이후다. 핸들러 안에서 추가 DB 작업이 필요하다면 `Propagation.REQUIRES_NEW`로 새 트랜잭션을 열어야 한다. Redis만 다루는 경우라면 불필요하다.

## Consequences

- **트레이드오프**: RDB 커밋 완료 ~ Redis 캐싱 완료 사이의 짧은 gap에서 동일 키 요청이 오면 캐시 MISS로 처리되어 중복 실행 가능성이 있다.
- **주문 멱등성 캐시는 본 정책 적용 대상에서 제외** (주문 생성 멱등을 Redis in-flight 차단으로 단순화한 결정(→ PR#180)). `OrderCreateService` 가 `NOT_SUPPORTED` 라 `try-finally` 직접 호출이 자동으로 commit 이후 실행됨. listener 우회 불필요.
