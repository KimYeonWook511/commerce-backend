# 회고록: payment-compensation-policy

## 1. 작업 요약

### 무엇을 변경했는가

보상 진행 여부 판단 근거를 `PaymentAttempt.status`(race-unsafe)에서 `Payment` 엔티티 존재 여부(race-safe)로 변경하고, `completeVerifiedApproval`의 catch 분기를 의미별 보상 메서드로 정리했다.

변경 범위:

- `PaymentApprovalService`: `isCompensationRequired(String merchantPayKey): boolean` 추가
- `NaverPayApprovalService`:
  - `failApproveAndCancelApprovedPayment`에 `isCompensationRequired` 체크 삽입 (Payment 존재 시 cancel skip + log.warn)
  - `completeVerifiedApproval` catch 분기를 4개 의미별 보상 메서드(`compensateMerchantKeyMismatch`, `compensateAmountMismatch`, `compensateDuplicatePayment`, `compensateUnexpected`)로 교체
- `NaverPayApprovalServiceTest`: 신규 테스트 2개(cancel skip 케이스) 추가, 기존 cancel 경로 테스트 14개에 `isCompensationRequired` mock 보강, catch(Exception) failDetail assertion 갱신
- `PaymentApprovalServiceConcurrencyTest`: `DataIntegrityViolationException`·`PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED` 허용 예외 추가로 flaky 해소
- `NaverPayServiceConcurrencyTest`: recovery 시나리오를 데이터 오염 상태(throw) 기대로 재정의, cancel skip 시나리오 신규 추가
- `docs/adr.md`: ADR-014 신설, ADR-012에 후속 노트 추가
- `docs/exception-strategy.md`: `isCompensationRequired` 캡슐화 패턴을 적용 예에 추가
- `docs/architecture.md`: 결제 승인 흐름에 보상 가능 여부 판단 반영

### 왜 변경했는가

기존 `failApproveAndCancelApprovedPayment`는 `PaymentAttempt.status`로 cancel 진행 여부를 판단했다. `failApproveAttemptIfRequested`가 REQUESTED 상태가 아니면 mark를 skip하는데, cancel 진행 결정은 이 skip과 무관하게 계속됐다. attempt에는 row lock이 없어 race window에서 Thread A가 SUCCEEDED로 mark한 뒤 Thread B가 같은 attempt에 대해 cancel을 진행하는 경로가 열려 있었다(#114). ADR-012(이전 task)에서 mark 메서드에 선조건 검증을 추가하고 ADR-D(try-catch 보호)로 임시 처방을 했으나, cancel 진행 결정 자체가 attempt status에 의존한다는 근본 문제가 남아 있었다.

Payment는 `order_id`, `merchantPayKey`, `pgPaymentId` 모두 unique이고 `completeApprovedPayment`가 Order FOR UPDATE 안에서 Payment를 생성하므로, Payment 존재 여부는 DB 레벨에서 race-safe하게 확인할 수 있다. 임시 처방을 구조적 해결로 대체하는 것이 이 작업의 핵심이었다.

---

## 2. 설계 결정 요약

### ADR-1: 보상 진행 여부를 Payment 엔티티 존재 여부로 판단한다

cancel 진행 결정을 `PaymentApprovalService.isCompensationRequired(merchantPayKey)`로 위임한다. 내부적으로 `paymentRepository.findByMerchantPayKey(merchantPayKey).isEmpty()`로 판단한다.

선택하지 않은 대안:
- **옵션 B (낙관적 락)**: `PaymentAttempt`에 `@Version` 추가. DB 스키마 변경(운영 마이그레이션)이 필요하고, attempt 수준의 락 범위가 Order lock과 중첩되는 문제가 있다.
- **옵션 C (FOR UPDATE)**: attempt 조회 lock 추가. Order FOR UPDATE와의 락 획득 순서를 조율해야 한다.

Payment의 unique 제약이 DB 레벨에서 race-safe를 보장하므로 스키마 변경 없이 구조적 해결이 가능하다.

### ADR-2: PaymentApprovalService가 isCompensationRequired 소유권을 가진다

DDD 관점에서 Payment Aggregate의 상태 판단 권한은 그 owner인 `PaymentApprovalService`에 귀속된다. `NaverPayApprovalService`(PG adapter)가 `paymentRepository`를 직접 참조하면 Payment Aggregate 소유권이 adapter로 새어나온다.

```
NaverPayApprovalService
  → PaymentApprovalService.isCompensationRequired(merchantPayKey)   ← 단일 채널
       → paymentRepository.findByMerchantPayKey(merchantPayKey).isEmpty()
```

Payment Aggregate와 PaymentAttempt Aggregate는 각자의 불변식을 독립적으로 소유한다. 이번 설계는 Payment의 불변식(payment 유일성)을 cross-Aggregate 협력 패턴으로 활용하면서도 각 Aggregate의 소유권 경계를 침범하지 않는다.

### ADR-3: completeVerifiedApproval catch 분기를 의미별 보상 메서드로 분리한다

기존에는 `failApproveAndCancelApprovedPayment`가 여러 catch 블록에서 서로 다른 의미(금액 불일치 취소, 중복 결제 취소, 키 불일치 실패 처리, 예상치 못한 예외 취소)로 반복 호출됐다. 의미별 메서드로 분리해 시나리오를 이름으로 드러낸다.

```
compensateMerchantKeyMismatch(attempt)         — failApprove만, PG cancel 없음
compensateAmountMismatch(attempt, amount)       — isCompensationRequired 체크 후 cancel
compensateDuplicatePayment(attempt, ex)         — isCompensationRequired 체크 후 cancel
compensateUnexpected(attempt, ex, code, msg)    — isCompensationRequired 체크 후 cancel
```

선택하지 않은 대안: Strategy 패턴으로 보상 정책 추상화. PG가 NaverPay 하나뿐인 현 시점에 over-design이다.

### ADR-4: PaymentAttempt mark 메서드 호출 정책 명문화 (ADR-012 후속)

`PaymentAttempt.mark*` 메서드는 `PaymentAttemptService` 외부에서 직접 호출하지 않는다. Java `public` 접근자로 컴파일러 강제는 불가하므로 ADR-014(루트 docs)와 각 메서드 JavaDoc에 정책을 명시한다.

선택하지 않은 대안:
- **ArchUnit**: CI에서 호출 경로를 차단 가능하지만 도입 비용이 있다. 다른 도메인 아키텍처 테스트와 함께 도입하는 것이 더 자연스럽다.
- **패키지 구조 변경**: PaymentAttempt와 PaymentAttemptService를 같은 패키지로 이동. 대규모 변경이다.

ADR-1 도입으로 race window에서 mark throw 경로 자체가 줄어들어, ADR-D 임시 처방(try-catch 보호)을 대체하게 됐다.

---

## 3. 발견한 것

### ADR-D 임시 처방의 구조적 위치

ADR-012(이전 task)에서 `failApproveAndCancelApprovedPayment` 내 `failApprove` 호출을 try-catch로 감싼 ADR-D 임시 처방은 "mark가 throw해도 cancel은 계속 진행"을 보장했다. 이번 작업에서 `isCompensationRequired`가 Payment 존재 시 return으로 아예 cancel 진입 자체를 막으므로, ADR-D try-catch는 더 이상 race window의 주 방어선이 아니게 됐다. try-catch 자체는 코드에 남아 있지만, 이제 내부 로직 오류에 대한 보조 방어선 역할로 의미가 축소됐다.

### compensateMerchantKeyMismatch에서의 PG cancel 부재

`MERCHANT_KEY_MISMATCH`는 우리 시스템이 발급한 `merchantPayKey`를 PG가 모르는 상황이다. PG 측에서 결제 자체가 성립하지 않았으므로 cancel 요청 대상이 없다. 이 차이가 기존 `failApproveAndCancelApprovedPayment`를 모든 catch에서 일률적으로 호출하는 코드에서는 드러나지 않았고, 보상 메서드 분리 과정에서 명확히 식별됐다.

### Payment 존재 체크가 미래 분산 환경에서도 유효한 이유

`isCompensationRequired`는 Payment DB의 인덱스 조회에 의존한다. 미래에 Payment가 별도 서비스로 분리되더라도 이 메서드 시그니처(boolean 반환, merchantPayKey 파라미터)는 Payment 서비스의 외부 API로 자연스럽게 승격 가능하다. `NaverPayApprovalService`의 호출 코드는 변경 없이 유지된다. 반면 attempt status 기반 판단은 Payment DB 분리 후 유효하지 않다.

### concurrency 테스트 flaky의 원인

`PaymentApprovalServiceConcurrencyTest` flaky는 race 시나리오에서 여러 예외 유형(DB unique 제약 위반으로 인한 `DataIntegrityViolationException`, mark 선조건 검증 실패로 인한 `PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED`)이 혼재되어 일부 경로가 허용 예외 목록에 없었기 때문이었다. race 허용 예외를 명시적으로 열거하는 방식으로 flaky를 해소했다.

---

## 4. 미결 과제

### ArchUnit 도입 (후속 작업)

`PaymentAttempt.mark*` 메서드의 직접 호출 금지를 CI에서 강제하려면 ArchUnit이 필요하다. 현재는 ADR-014와 JavaDoc 정책 명문화로 대체한다. 다른 도메인의 아키텍처 테스트와 함께 일관된 방식으로 도입할 것을 권장한다.

### worker의 Acceptance Criteria 임의 변경 (harness 개선 필요)

step2 worker가 step2.md에 명시된 `./gradlew test` 대신 `./gradlew dockerTest`를 임의로 실행했다. worker 에이전트가 "concurrency 테스트는 docker 태그"라고 판단해 AC를 스스로 바꾼 것이지만, step 문서의 AC는 실행 전 검증 기준이므로 worker가 임의로 변경해서는 안 된다. harness 실행기가 worker에게 "AC 명령을 그대로 실행하고, 다르게 실행해야 한다고 판단되면 blocked 상태로 중단하라"는 제약을 명시적으로 전달해야 한다.

### PaymentReference Value Object 도입 (후속 검토)

`merchantPayKey`는 두 Aggregate(`Payment`, `PaymentAttempt`) 간 협력 키로 String 원시 타입으로 흐른다. `PaymentReference` 같은 Value Object로 명시화하면 협력 경계가 타입으로 드러난다. 현 시점에서 과한 추상화일 수 있으나, Payment 도메인 분리가 논의될 때 함께 검토할 가치가 있다.

### 해소된 이슈

| 이슈 | 내용 | 결과 |
|---|---|---|
| #114 | race window에서 SUCCEEDED attempt에 PG cancel 호출 | isCompensationRequired 도입으로 close |
| #115 | `PaymentApprovalServiceConcurrencyTest` flaky | 허용 예외 명시화로 close |
| #116 | `completeVerifiedApproval` catch 분기 5개가 같은 보상을 다른 의미로 호출 | compensate* 메서드 분리로 close |
| #117 | ADR-012 절충안 — mark 멱등 자기 전이 허용 검토 | ADR-1 도입으로 race window mark throw 경로 자체 축소, close |

---

## 5. 개선 제안

### PaymentAttempt 상태 전이 표 문서화

`PaymentAttempt`의 허용/거부 상태 전이 규칙이 mark 메서드 내부 코드에만 표현되어 있다. `PaymentAttemptStatus` enum 레벨이나 별도 문서에 상태 전이 표를 명시하면 새 mark 메서드 추가 시 설계 기준이 명확해진다. `payment-attempt-state-transition-policy` 회고에서도 같은 제안이 있었으므로, Order 도메인 상태 전이 규칙과 함께 정리하면 일관성이 높아진다.

### isCompensationRequired 외부 API 승격 경로

Payment가 별도 서비스로 분리될 때 `isCompensationRequired`는 Payment 서비스의 HTTP/gRPC API가 된다. 이 시나리오에서 `NaverPayApprovalService`는 API 호출 결과로 boolean을 받아 동일 분기를 실행한다. 승격 경로를 미리 고려하면 분리 시 `PaymentApprovalService`가 adapter/anti-corruption layer 역할을 자연스럽게 수행할 수 있다.

### cancel skip 시나리오 로그 모니터링

`isCompensationRequired == false`일 때 log.warn("Payment already completed...") 로그가 발생한다. 정상 race 결과지만 운영 환경에서 빈도가 높으면 결제 흐름 이상의 신호일 수 있다. 모니터링 대시보드에서 이 로그 빈도를 별도 메트릭으로 수집하면 조기 이상 감지에 유용하다.

### step 설계 시 테스트 태그 확인 필요

step2 설계 시 Acceptance Criteria를 `./gradlew test`로 명시했으나, `concurrency` 태그 테스트가 실제로는 `dockerTest` 범주에 속해 Testcontainers + MySQL 컨테이너 기동 비용을 포함한다. "10회 반복" 안정성 검증은 이 비용을 간과한 설계였고, 결과적으로 step2가 약 40분 소요됐다. 향후 step 설계 시 Acceptance Criteria를 정하기 전에 해당 테스트의 태그(`docker`, `concurrency`, `batch`, `sandbox`)를 먼저 확인하고, `dockerTest` 범주라면 반복 횟수를 크게 줄이거나 1회 통과로 기준을 삼아야 한다.

### execute.py 반복 루프 중간 진행 상황 추적 불가

execute.py spinner는 "Step N/5 [Xs]" 형태로 step 단위 경과 시간만 표시한다. worker 내부에서 반복 실행(예: 10회 반복 테스트)이 진행 중일 때 "3/10 완료" 같은 중간 카운트가 외부에서 보이지 않아, 프로세스가 정상 실행 중인지 멈춘 것인지 구분하기 어렵다. worker output을 실시간으로 tail하거나 반복 카운트를 별도 파일로 기록하는 방식을 고려할 수 있다.
