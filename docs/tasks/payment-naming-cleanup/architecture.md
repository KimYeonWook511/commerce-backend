# 태스크 아키텍처

## 개요

- 순수 네이밍/구조 정리 작업이다. 레이어 구조, 의존 방향, 데이터 흐름, 외부 계약은 변경하지 않는다.
- 옛 `PaymentAttempt` 엔티티 잔재를 현재 `Payment` 모델에 맞춰 식별자만 정돈한다.

## 변경 대상

- `payment/domain`: `Payment`(succeed dead code 제거), `PaymentReservation`(use/expire 동사화).
- `payment/domain/repository`, `payment/infrastructure`: `PaymentRepository`/`PaymentRepositoryAdapter` 의 `findApproveAttempt`/`findCancelAttempt` 메서드명.
- `payment/application`: `PaymentApprovalAttemptService`/`PaymentCancellationAttemptService` 클래스명, `PaymentApprovalService`/`PaymentApprovalCompensationService` 의 변수·주입 필드·주석.
- `payment/naverpay/application`: `NaverPayApprovalService` 의 `processApproveAttempt`·변수·주석·주입 필드.
- `payment/exception`: `PaymentErrorCode` 의 `PAYMENT_ATTEMPT_*` enum 식별자(코드 문자열·메시지 제외).
- `src/test/.../payment`: 위 변경에 대응하는 테스트 클래스명·헬퍼·변수·메서드명·@DisplayName·주석. (단 `postprocess` 패키지 제외)
- 루트 docs: `architecture.md`, `exception-strategy.md`, `testing-conventions.md` 의 현재 구조 기술.

## 설계 방향

- **entity-reference rule**: 식별자/표현이 *`Payment` 엔티티(=옛 PaymentAttempt)* 를 가리키면 정리한다. *진짜 시도(try)* 를 뜻하면 보존한다.
- 서비스 클래스 rename은 "Attempt 제거" 최소 변경 원칙을 따른다.
  - `PaymentCancellationAttemptService` → `PaymentCancellationService` (충돌 없음).
  - `PaymentApprovalAttemptService` → `PaymentApprovalRecordService` (기존 `PaymentApprovalService` 와 충돌하므로 Record 로 구분).
- 도메인 결과/상태 반영 메서드는 동사형으로 일관화한다: `succeed`/`fail`/`markUnknown`(동사 부재로 유지)/`use`/`expire`.

## 데이터 흐름

- 변경 없음. reserve → approve(승인 성공/실패/UNKNOWN) → cancel(보상) 흐름과 트랜잭션 경계, 멱등 흡수, NULL 트릭 unique 방어선은 그대로다.

## 예외 및 실패 처리

- 변경 없음. `PaymentErrorCode` 의 HTTP status·code 문자열·메시지는 그대로 유지하고 enum 식별자만 rename한다.
- `succeed`/`succeedApproval` 의 명시 `save()`(saveAndFlush 즉시 flush)는 이중결제 보상의 `DataIntegrityViolationException` catch가 의존하므로 보존한다.

## 테스트 포인트

- `./gradlew test` 가 네이밍 변경분만 반영해 그대로 통과한다.
- 영향 시 `./gradlew integrationTest` 통과.
- payment 패키지에 `Payment` 타입을 가리키는 `attempt` 식별자가 남지 않는다 (grep 재확인).
- 보존 대상(`attackerAttempt`, concurrent/retry attempt, 한국어 "시도", `postprocess`)은 그대로 남아 있다.
