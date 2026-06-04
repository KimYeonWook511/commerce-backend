# 태스크 PRD

## 태스크명

- `payment-naming-cleanup`

## 배경

- payment 도메인 Order↔결제 경계 재설계(#174, PR #205)에서 엔티티 `PaymentAttempt` 를 `Payment` 로 통합했으나, 변수·파라미터·메서드·서비스 클래스·에러코드 식별자가 옛 `attempt` 네이밍을 그대로 끌고 온 잔재가 남았다.
- 재설계 과정에서 새로 도입한 도메인 메서드(`markUsed`/`markExpired`/`markUnknown`)가 기존 동사형 메서드(`succeed`/`fail`)와 패턴이 어긋난다.
- 이슈 #209 로 추적한다.

## 목표

- 옛 `PaymentAttempt` 엔티티를 가리키던 `attempt` 식별자 잔재를 제거해 코드가 현재 도메인 모델(`Payment`)과 일치하게 한다.
- 도메인 결과/상태 반영 메서드의 네이밍 패턴을 일관화한다.
- 외부 동작·API·DB 스키마는 일절 바꾸지 않는다 (순수 refactor).

## 범위

- 포함 범위
  - `PaymentReservation.markUsed` → `use`, `markExpired` → `expire` 동사화 (호출처·테스트 포함).
  - `Payment` 타입을 가리키는 `attempt`/`approveAttempt`/`cancelAttempt` 식별자(변수·파라미터·필드) 정리.
  - repository 메서드 `findApproveAttempt`/`findCancelAttempt`, 처리 메서드 `processApproveAttempt` 정리.
  - 서비스 클래스 `PaymentApprovalAttemptService`/`PaymentCancellationAttemptService` rename (+ 주입 필드명).
  - 에러코드 enum 식별자 `PAYMENT_ATTEMPT_*` 3건 rename (code 문자열·한국어 메시지는 보존).
  - `Payment` 를 가리키던 주석·로그 텍스트 정리.
  - `Payment.succeed()` 의 `failCode`/`failDetail` null 리셋 dead code 제거.
  - 테스트 클래스명·헬퍼·변수·메서드명 중 `Payment` 를 가리키는 식별자 정리.
  - 현재 구조를 기술하는 루트 docs(`architecture.md`, `exception-strategy.md`, `testing-conventions.md`) 동기화.
- 제외 범위
  - 외부 동작/시그니처 의미 변경, DB 스키마/마이그레이션 변경.
  - 서비스 클래스명 verb/noun 컨벤션 전면 정리 (별도 후속 이슈).
  - 진짜 "시도(try)" 를 뜻하는 식별자(`attackerAttempt`, concurrent/retry attempt 등)와 한국어 "시도" 표현.
  - test-only `postprocess` 패키지 (배치 도입 시 일괄 정비 예정).
  - 역사 기록 문서: `docs/ADR.md` 과거 ADR 서술, 머지된 task 폴더, migration 파일, `logging-conventions.md`/`db-schema.md` 의 outbox `attempt_count`.

## 주요 시나리오

- 순수 네이밍/구조 정리 작업이라 신규 사용자 흐름은 없다. 기존 reserve/approve/cancel 흐름의 외부 동작은 전부 동일하게 유지된다.

## 요구사항

- payment 패키지에 `Payment` 타입을 가리키는 `attempt` 식별자가 남지 않는다.
- 도메인 결과/상태 반영 메서드의 네이밍 패턴이 일관된다 (`succeed`/`fail`/`markUnknown`/`use`/`expire`).
- 기존 테스트(`./gradlew test`, 영향 시 `./gradlew integrationTest`)가 네이밍 변경분만 반영해 그대로 통과한다.

## 제약사항

- 동작 변경 금지. 기존 코드베이스 컨벤션 우선, 과한 추상화 도입 금지.
- NULL 트릭 캡슐화(상태 컬럼과 트릭 컬럼을 한 도메인 메서드 호출에서 동시 set) 의미를 깨지 않는다.
- `saveAndFlush` 즉시 flush에 의존하는 `succeed`/`succeedApproval` 의 명시 `save()` 호출은 보존한다 (이중결제 보상의 `DataIntegrityViolationException` catch가 flush 타이밍에 의존).
- 단독 PR로 빠르게 마무리해 머지 충돌을 최소화한다.
