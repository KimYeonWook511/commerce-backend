# 회고록: payment-naming-cleanup

## 1. 작업 요약

payment 도메인 Order↔결제 경계 재설계(#174, PR #205)에서 엔티티 `PaymentAttempt`를 `Payment`로 통합하면서 남긴 식별자 잔재를 전면 제거하고, 도메인 상태 반영 메서드의 네이밍 패턴을 일관화했다. 구체적으로는 `PaymentReservation.markUsed()`/`markExpired()`를 동사형 `use()`/`expire()`로 축약하고, `Payment` 타입을 가리키던 `attempt`/`approveAttempt`/`cancelAttempt` 변수·파라미터·필드명, `findApproveAttempt`/`findCancelAttempt` repo 메서드, `processApproveAttempt` 처리 메서드, `PaymentApprovalAttemptService`/`PaymentCancellationAttemptService` 서비스 클래스, `PAYMENT_ATTEMPT_*` 에러코드 enum 식별자 3건을 현재 도메인 모델에 맞춰 정돈했다. `Payment.succeed()`의 `failCode`/`failDetail` null 리셋 dead code 제거, 루트 docs 동기화를 포함해 총 4 step으로 완료했으며, 외부 동작·API·DB 스키마는 일절 변경하지 않았다.

---

## 2. 설계 결정

자세한 결정 본문은 [task ADR](./adr.md) 참조.

| ADR | 핵심 결정 |
|---|---|
| ADR-1 | `markUsed`/`markExpired` → `use`/`expire` 동사화. `succeed`/`fail`은 이미 동사라 유지. `markUnknown`은 적절한 동사가 없어 `mark` 유지. |
| ADR-2 | `Payment` 타입을 가리키는 `attempt` 식별자 전면 제거. 서비스 rename: `PaymentApprovalAttemptService` → `PaymentApprovalRecordService`(기존 `PaymentApprovalService`와 충돌 방지), `PaymentCancellationAttemptService` → `PaymentCancellationService`. 에러코드 enum 식별자 rename(code 문자열·메시지는 외부 계약 보존). |
| ADR-3(보존) | `saveAndFlush` 즉시 flush에 의존하는 `succeed`/`succeedApproval`의 명시 `save()` 호출은 이중결제 보상 `DataIntegrityViolationException` catch의 load-bearing 요소라 손대지 않는다. |
| ADR-3(경계) | `attackerAttempt`·concurrent/retry attempt·한국어 "시도" 표현·`postprocess` 패키지·역사 기록 문서는 보존한다. `Payment.succeed()`의 `failCode`/`failDetail` null 리셋 2줄은 증명 가능한 no-op dead code라 제거한다. |

---

## 3. 발견

### `@Enumerated(EnumType.STRING)` 매핑 때문에 상태 enum 값 rename은 순수 refactor가 아니다

`PaymentStatus`(`REQUESTED`/`SUCCEEDED`/`FAILED`/`UNKNOWN`), `PaymentReservationStatus`(`RESERVED`/`USED`/`EXPIRED`) 등 상태 enum은 `@Enumerated(EnumType.STRING)`으로 저장된다. enum 클래스명·식별자를 rename하면 DB에 저장된 문자열과 불일치가 발생해 데이터 정합성이 깨진다. 이번 작업에서 rename한 `PaymentErrorCode`의 enum 식별자들은 영속 대상이 아니라 정리가 가능했지만, 상태 enum 값 rename은 DB 마이그레이션과 반드시 짝을 이뤄야 하는 별도 task다.

### `saveAndFlush` 즉시 flush는 이중결제 보상의 load-bearing 요소다

`NaverPayApprovalService.succeedApproval()` 안의 명시적 `save()` 호출은 단순한 관성 코드가 아니다. `saveAndFlush`가 트랜잭션 경계 전에 즉시 flush해 `uk_payment_approved_order_key` unique 제약 위반을 조기에 발생시키고, 그 `DataIntegrityViolationException`을 같은 트랜잭션 catch에서 잡아 이중결제 보상을 수행한다. 이 호출을 제거하거나 `save()`로 교체하면 flush 타이밍이 달라져 catch가 실패할 수 있다. "명시적 save는 중복이다"라는 단순한 판단으로 손대면 안 된다.

### "attempt"는 옛 엔티티 잔재와 진짜 "시도(try)"가 섞여 있어 무차별 치환이 위험하다

`payment` 패키지 안에서 `attempt`라는 단어는 두 가지 의미를 동시에 담고 있었다. 하나는 옛 `PaymentAttempt` 엔티티를 가리키는 잔재(정리 대상)이고, 다른 하나는 `attackerAttempt`(공격 시도), concurrent retry attempt 등 실제 "시도(try)" 의미의 식별자(보존 대상)다. `s/attempt/payment/g` 식의 전역 치환은 의미를 훼손한다. 각 식별자가 무엇을 가리키는지를 기준으로 case-by-case로 판단했다.

---

## 4. 미결 과제

| 항목 | 상태 | 승격/결정 조건 |
|---|---|---|
| 서비스 클래스명 verb/noun 컨벤션 전면 정리 (`PaymentApprovalRecordService` 등 네이밍 체계 재검토) | 별도 후속 이슈 | 서비스 클래스 명명 컨벤션 정책 확정 후 |
| `postprocess` 패키지 테스트 정비 | 보류 | 배치 도입 시 일괄 정비 예정 |
| `PaymentStatus`/`PaymentReservationStatus` enum 값 rename | 미착수 | DB 마이그레이션 작업과 함께 별도 task로 처리 |

---

## 5. 개선 제안

**엔티티 rename 시 변수·메서드·서비스·에러코드 식별자까지 같은 PR에서 함께 정리한다.** 이번 task는 PR #205에서 `PaymentAttempt → Payment` rename이 완료됐음에도 연관 식별자 정리가 후속으로 남겨져 별도 cleanup PR이 필요했다. 엔티티 rename의 파급 범위(변수·파라미터·repo 메서드·서비스 클래스·에러코드·테스트)를 rename PR 단계에서 함께 정리했다면 이번 4 step 작업이 불필요했다. 도메인 모델명이 바뀌는 PR에는 식별자 일괄 정리를 체크리스트로 포함한다.

**"attempt"처럼 의미가 두 개인 단어는 어원 인접 식별자를 먼저 목록화한 뒤 rename한다.** 무차별 치환이 아니라 "이 식별자가 정리 대상 엔티티를 가리키는가, 진짜 의미의 단어인가"를 한 번씩 판단해야 한다. 도메인 ADR에 `entity-reference rule`처럼 정리 기준을 명문화해두면 판단 비용이 줄어든다.
