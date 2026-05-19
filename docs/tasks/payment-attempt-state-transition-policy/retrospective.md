# 회고록: payment-attempt-state-transition-policy

## 1. 작업 요약

### 무엇을 변경했는가

`PaymentAttempt` 도메인 모델에 상태 전이 선조건 검증을 추가하고, 보상 흐름의 race window를 보호했다.

변경 범위:
- `PaymentErrorCode`에 `PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED`(500-1), `PAYMENT_ATTEMPT_TYPE_MISMATCH`(500-2) 추가
- `PaymentAttempt` 4개 mark 메서드에 type 정합성 + status REQUESTED 선조건 검증 추가
- `NaverPayApprovalService.failApproveAndCancelApprovedPayment` 내 `failApprove` 호출을 try-catch로 보호
- `PaymentAttemptService.succeedApproveAttempt`에 SUCCEEDED 멱등 skip 추가 (복구 경로 보호)
- `PaymentAttemptTest`에 전이/type 위반 케이스 9개 추가
- `docs/ADR.md`에 ADR-012 추가

### 왜 변경했는가

기존 mark 메서드는 호출 시점에 attempt의 `status`/`type`을 검증하지 않고 상태를 무조건 덮어쓴다. 정상 흐름은 application 계층의 switch 분기가 막아주지만, 도메인 모델 자체에는 라이프사이클 보호가 없었다. 대표 위험 시나리오는 `FAILED(failCode=TIMEOUT)` 상태의 attempt에 `markApproveSucceeded()`가 호출되면 failCode가 null로 초기화되어 실패 사유가 흔적 없이 사라지는 것이다. 도메인이 자기 라이프사이클을 스스로 보호하도록 하고, Order 도메인의 명시적 선조건 검증 패턴과 일관성을 맞추는 것이 목적이다.

---

## 2. 설계 결정 요약

### ADR-A: 상태 전이 — 엄격한 검증 (멱등 자기 전이 거부)

REQUESTED → SUCCEEDED/FAILED만 허용. 멱등 자기 전이(SUCCEEDED → SUCCEEDED)도 거부.

멱등성은 이미 상위 레이어(`PaymentAttemptService.getOrCreateApproveAttempt` + `NaverPayApprovalService.processApproveAttempt` switch)에서 처리되어 mark가 멱등을 책임질 필요가 없다는 것이 핵심 근거다. FAILED → SUCCEEDED 시 failCode=null 초기화로 실패 사유가 사라지는 문제를 원천 차단한다.

멱등 자기 전이 허용(옵션 B)은 재시도 대비 측면에서 검토됐으나, 상위 레이어에서 이미 처리되므로 mark 수준의 멱등 허용은 불필요한 안전망 중복이다.

### ADR-B: type 정합성 검증 포함

`markApprove*`는 `type == APPROVE`, `markCancel*`는 `type == CANCEL`만 허용.

DB unique 제약 `(merchant_pay_key, provider, payment_id, type)`으로 APPROVE/CANCEL attempt가 별도 행으로 분리되어 실제 위반 가능성은 낮다. 그러나 도메인 모델이 메서드 이름이 약속하는 의도를 강제하는 것은 무결성 측면에서 가치가 있고, 향후 호출처 추가 시 방어선 역할을 한다. type 검증 없음(옵션 B)은 현실적 위반 경로가 거의 없다는 점에서 검토됐으나, 도메인 무결성 원칙을 우선했다.

### ADR-C: 신규 에러 코드 HTTP 500

`PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED`, `PAYMENT_ATTEMPT_TYPE_MISMATCH` 모두 HTTP 500.

ADR-010의 `PAYMENT_ATTEMPT_AMOUNT_MISMATCH`(409 Conflict)는 **호출자의 잘못된 입력**(외부 원인)이어서 4xx가 적절했다. 새 코드는 **내부 코드 버그 또는 race window**(내부 결함)이므로 5xx가 적절하다. 운영 대시보드에서 "호출자 4xx"와 "내부 5xx"를 명확히 구분하기 위한 결정이다. 409(옵션 B)도 검토됐으나, 외부 입력 mismatch와 내부 결함을 같은 카테고리로 묶으면 모니터링 알람 분리가 어렵다.

### ADR-D: catch swallow 회귀 방지 — failApproveAndCancelApprovedPayment 내 try-catch

`failApproveAndCancelApprovedPayment` 내부의 `failApprove` 호출 한 곳만 try-catch로 감쌈. return 없이 PG cancel은 무조건 진행.

race 시나리오: `completeApprovedPayment`에서 `succeedApproveAttempt(markApproveSucceeded)` → 메모리상 SUCCEEDED → `order.completePayment()` race throw → catch 블록 → `failApproveAndCancelApprovedPayment` → `failApprove(markApproveFailed)` → 새 검증 throw → PG cancel 흐름 중단. PG cancel이 중단되면 PG 결제 승인됨 + 우리 시스템 미반영으로 외부 정합성이 깨진다. **데이터 정합성 보존이 최우선**이므로 mark 실패 시에도 PG cancel은 무조건 시도한다.

ADR에만 명시(옵션 B)는 코드 변경 없이 문서화만 하는 것으로, 발생 가능성은 낮지만 운영 risk가 남는다. catch 분기 수정(옵션 C)은 변경 범위가 크고 로깅 보강과 함께 후속 PR에서 처리하는 것이 더 적합하다.

---

## 3. 발견한 것

### `PaymentAttemptService.succeedApproveAttempt` SUCCEEDED 멱등 skip

PRD에 명시되지 않았으나, 구현 중 추가 보호가 필요한 경로가 발견됐다. `failApproveAndCancelApprovedPayment` 내 `failApprove` try-catch를 추가했을 때, `succeedApproveAttempt`에도 유사한 보호가 필요한지 검토했다.

`completeApprovedPayment`의 흐름에서 `succeedApproveAttempt`가 먼저 실행되어 attempt가 SUCCEEDED 상태가 된 뒤, `order.completePayment()`가 race로 throw하면 트랜잭션이 rollback된다. rollback 후 재시도 경로에서 동일 attempt에 `succeedApproveAttempt`가 다시 호출되면 새 검증이 throw하는 상황이 생긴다. 이를 방지하기 위해 `succeedApproveAttempt`에 SUCCEEDED 상태인 경우 skip(return)하는 멱등 처리를 추가했다.

이 skip은 "이미 SUCCEEDED → 재호출 시 pass"로 ADR-A의 엄격한 검증 방향(도메인 mark 수준)과 충돌할 수 있지만, `PaymentAttemptService` 레이어의 멱등 처리이므로 도메인 mark 검증과 별개 계층의 판단이다.

### `failApproveAndCancelApprovedPayment` 호출처 3곳

ADR-D에서 `failApprove` try-catch 보호 대상은 `failApproveAndCancelApprovedPayment` 내부 한 곳으로 범위를 최소화했다. 그러나 `completeVerifiedApproval`의 catch 블록을 살펴보면 `failApproveAndCancelApprovedPayment`를 호출하는 경로가 3곳(PAYMENT_AMOUNT_MISMATCH, PAYMENT_DUPLICATE, default)이다. 이 중 race window에서 mark throw가 발생할 수 있는 경로는 `default`(APPROVE_PROCESS_FAILED)와 PAYMENT_DUPLICATE인데, 이 경우에도 `failApproveAndCancelApprovedPayment` 내부 try-catch가 공통으로 보호한다. 호출처마다 개별 try-catch를 추가하지 않아도 한 곳에서 보호된다는 점을 확인했다.

### 상위 catch 블록 log.error 누락

`completeVerifiedApproval`의 `PaymentException` catch(라인 130)와 `CustomException` catch(라인 145) 블록에 `log.error`가 없다. 1차 예외가 로깅 없이 전파되어 운영 모니터링에서 인지하기 어렵다. 이번 범위에서는 수정하지 않고 Issue #111로 분리했다.

---

## 4. 미결 과제

후속 Issue #111로 분리된 작업:

- **보상 catch 2차 예외 처리 일반 원칙 문서화**: catch 블록 안에서 보상 작업을 수행할 때 2차 예외가 발생하는 경우의 일반 판단 기준(의사결정 트리)을 `docs/architecture.md` 예외 처리 섹션과 ADR-013으로 정의한다. 이번 작업에서 `failApprove`의 try-catch 방식을 결정했지만, 다른 보상 catch 경로에도 동일 원칙이 적용되어야 한다.
- **`NaverPayApprovalService` 라인 130, 145 catch 블록 `log.error` 누락 보강**: `PaymentException` catch와 `CustomException` catch 블록에 1차 예외 로깅을 추가한다. 현재는 예외가 로깅 없이 전파되어 운영에서 인지하기 어렵다.

---

## 5. 개선 제안

### 도메인 상태 전이 표를 문서화

`PaymentAttempt`의 상태 전이 규칙이 mark 메서드 내부 코드로만 표현되어 있다. `PaymentAttemptStatus` enum이나 별도 문서에 허용/거부 전이 표를 명시하면, 향후 새 mark 메서드 추가 시 설계 기준이 명확해진다. Order 도메인의 상태 전이 규칙과 함께 정리하면 일관성이 높아진다.

### `PaymentAttemptService` 레이어 멱등 처리 위치 재검토

이번 작업에서 `succeedApproveAttempt`에 SUCCEEDED skip을 추가했다. 동일 패턴이 `failApproveAttempt`에도 필요한지 여부가 불명확하다. 복구 경로에서 `failApproveAttempt`도 재호출될 수 있는 시나리오가 있다면 일관되게 적용해야 한다.

### NaverPayApprovalService 보상 흐름 통합 테스트

race window 시나리오(`order.completePayment()` race throw → `failApproveAndCancelApprovedPayment` → `failApprove` mark throw → PG cancel 진행)는 단위 테스트로는 재현이 어렵다. 통합 테스트나 race condition 테스트가 보강되면 보상 흐름의 정확성을 더 높은 신뢰도로 검증할 수 있다.
