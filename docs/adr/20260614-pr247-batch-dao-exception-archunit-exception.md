# Spring Batch fault-tolerance의 DAO 예외 참조를 ArchUnit 규칙 예외처로 인정한다

- Status: accepted
- Date: 2026-06-14

## Context

구조 마이그레이션(structure-migration task)으로 batch가 `presentation/batch/`로 이동하면서, `OrderExpirationBatchConfig`의 `.retry(OptimisticLockingFailureException.class)`/`.skip(...)`이 두 ArchUnit 규칙(persistence 밖 참조 금지 + presentation의 낙관 락 예외 의존 금지)에 걸렸다.

- **고려한 대안 (a)** persistence로 이동 — 진입점(inbound adapter)인데 persistence로 내리면 레이어가 무너져 기각.
- **고려한 대안 (b)** 도메인 예외로 변환(`Payment.saveChecked` 패턴 미러링) — 변환은 예외를 직접 catch하는 위치에서만 가능한데 `.retry(...)`는 catch가 아니라 프레임워크에 예외 타입을 선언적으로 신고하는 코드라 변환 대상 자체가 없어 적용 불가, 기각.
- **고려한 대안 (c)** freeze 유지 — 마이그레이션 종료(freeze 제거)와 모순, 기각.

`GlobalExceptionHandler`(HTTP 매핑)와 `OrderExpirationBatchConfig`(batch fault-tolerance)는 둘 다 DAO 예외를 비즈니스 분기로 catch하는 곳이 아니라 **프레임워크 경계에 예외 타입을 선언적으로 넘기는 곳**이다. 같은 부류라 같은 방식(규칙 예외처)으로 다룬다. 임시방편이 아니라 영구 예외처이며, batch fault-tolerance를 도메인 예외로 바꾸는 후속 작업은 불필요하다(런타임 흐름과 무관).

## Decision

`daoExceptionsConfinedToPersistence`(JPA/DAO 예외는 `infrastructure.persistence` 밖에서 참조 금지)와 `controllersDoNotCatchConflictExceptions`(presentation은 낙관 락 예외에 의존 금지) 두 규칙에서, `OrderExpirationBatchConfig`를 `GlobalExceptionHandler`와 동일하게 `areNotAssignableTo(...)`로 명시적 예외처로 제외한다. 예외 범위는 batch 패키지 전체가 아니라 해당 한 클래스로 좁게 잡는다.

## Consequences

예외처를 한 클래스로 좁게 잡아 batch listener 등 다른 곳의 DAO 예외 누수는 규칙이 계속 차단한다.

관련: structure-migration task adr, DAO 예외 격리 기존 결정(→ PR#109, PR#228).
