# 회고록: compensation-completed-guard-removal

## 1. 작업 요약

`PaymentApprovalCompensationService.runPgCancel` 의 `hasCompletedPayment(merchantPayKey)` 완료 가드를 제거하고, 사용처가 사라진 `hasCompletedPayment` / `existsApproveSucceeded` / `existsByMerchantPayKeyAndTypeAndStatus` 메서드 체인을 정리했다. duplicate·amount-mismatch 보상 모두 보상 대상 pgPaymentId 를 무조건 PG 취소하도록 통일했고, `cancelPayment` REQUESTED 상태 가드는 멱등 안전망으로 유지했다.

목적: 같은 reservation(merchantPayKey)·다른 pgPaymentId 경합으로 `PAYMENT_DUPLICATE` 가 발생했을 때, 형제 성공 때문에 중복 pgPaymentId 의 PG 취소가 skip 되어 환불되지 않던 이중청구를 해소한다 (#230, ADR-035).

---

## 2. 결정한 정책 (ADR-035)

- `runPgCancel` 의 완료 가드를 제거하고 보상 대상 pgPaymentId 를 무조건 PG 취소한다(ADR-014 supersede).
- 가드 제거로 dead 가 되는 완료 조회 메서드 체인을 제거한다.
- `cancelPayment` 상태가 REQUESTED 가 아니면 skip 하는 멱등 안전망은 유지한다.

핵심 근거: 보상이 생성·조회하는 cancel payment 는 항상 보상 대상 pgPaymentId(실패한 결제)로 만들어지므로 그 취소는 항상 올바르다. 형제 성공(`pgA`)은 별도 Payment row 이고 보상이 건드리지 않아, 완료 가드 없이도 ADR-014 의 원래 위험("성공한 결제를 잘못 취소")은 발생하지 않는다.

---

## 3. 주요 발견 및 논의

### 가드의 전제가 모델 변경으로 깨져 있었다

ADR-014 의 완료 가드는 "merchantPayKey = 결제 1건" 옛 모델에서 race window 의 자기-성공 보호를 위해 도입됐다. `payment-order-redesign(#205)` 이후 한 merchantPayKey 에 pgPaymentId 가 여럿 가능해지면서 가드가 merchantPayKey 단위라 보상 대상 자신이 아니라 형제 성공을 잡게 됐다. ADR-033(#226)에서 이중결제 보상이 fail-first 단일 경로로 통합되며 `PAYMENT_DUPLICATE` 가 이 가드를 실제 통과하기 시작했고, 형제 성공으로 가드가 항상 발동해 이중청구가 드러났다.

### "pgPaymentId 단위 재정의" 가 아니라 "제거" 를 택한 이유

가드를 형제가 아닌 자기 pgPaymentId 의 SUCCEEDED 여부로 좁히는 대안을 검토했다. 그러나 보상 진입 경로를 따져 보면 보상 대상 pgPaymentId 자신은 SUCCEEDED 로 커밋될 수 없다 — verify 실패는 `saveApproved` 미도달이고, duplicate 는 자기 `succeed` 가 `uk_payment_approved_order_key` 위반으로 롤백된다. 따라서 pgPaymentId 단위 가드는 항상 false 인 dead 코드가 된다. 사용처 없는 코드를 남기지 않는 원칙에 따라 재정의가 아닌 제거를 택했다.

### amount-mismatch 경로도 같은 결함을 공유했다

`runPgCancel` 은 duplicate 와 amount-mismatch 보상이 공유한다. amount-mismatch 도 merchantPayKey 단위 가드라 형제 성공을 오탐할 수 있었고, 보상 대상 pgPaymentId 자신은 SUCCEEDED 가 될 수 없어 가드가 무용했다. 가드 제거가 두 경로 모두에 옳다는 점을 확인하고 함께 정리했다.

### dead 코드 blast radius 가 좁았다

`hasCompletedPayment` → `existsApproveSucceeded` → JPA 메서드 체인은 오직 이 보상 가드에서만 사용됐다(`rg` 로 확인). 가드 제거 시 체인 전체가 dead 가 되어 한 번에 정리할 수 있었고, 깨지는 테스트는 가드 stub·존재 검증 케이스 3개 클래스뿐이었다.

### PR review (Gemini)

추가한 통합 테스트의 단언문을 `isInstanceOfSatisfying` 으로 캐스팅을 제거하고, `getPayment` 중복 호출을 지역 변수로 묶는 가독성 제안을 accept 했다(커밋 `45d7aae`). low priority 가독성 개선으로 회귀 위험이 없었다.

---

## 4. 변경 범위 정리

| 파일 | 변경 내용 |
|---|---|
| `PaymentApprovalCompensationService.java` | `runPgCancel` 의 `hasCompletedPayment` 가드와 `paymentApprovalService` 의존 제거 (REQUESTED skip 유지) |
| `PaymentApprovalService.java` | `hasCompletedPayment` 제거 |
| `PaymentRepository.java` / `PaymentRepositoryAdapter.java` / `JpaPaymentRepository.java` | `existsApproveSucceeded` / `existsByMerchantPayKeyAndTypeAndStatus` 제거 |
| `NaverPayServiceIntegrationTest.java` | 형제 pgPaymentId SUCCEEDED 상태에서 중복 pgPaymentId 의 PG 취소가 수행됨을 검증하는 통합 테스트 추가 |
| `PaymentApprovalCompensationServiceTest.java`, `PaymentApprovalServiceTest.java`, `PaymentRepositoryJpaAdapterTest.java` | 가드 관련 단위 테스트 정리 |
| `docs/adr.md` | ADR-035 append (ADR-014 supersede) |

---

## 5. 미결 과제

- `#118` 보상 동시성 회귀 확인용 `concurrencyTest` 는 머지 전 수동 실행이 필요하다(Docker 필요, CI 미포함). PR #233 본문 테스트 섹션에 명시.

---

## 6. 회고

### 잘된 점

- 방향을 정하기 전에 "보상 대상 pgPaymentId 자신이 SUCCEEDED 로 커밋될 수 있는 경로가 있는가" 를 진입 경로별로 따져, pgPaymentId 단위 재정의가 dead 가드가 됨을 결정 전에 확인했다. 덕분에 "안전장치를 더 남기는" 대안에 끌리지 않고 제거가 옳다는 근거를 분명히 했다.
- 가드 제거가 amount-mismatch 경로에도 동일하게 옳은지 공유 경로를 함께 검토해, 이슈가 요청한 "amount-mismatch 가드 적정성 검토" 를 같은 변경으로 충족했다.
- dead 메서드 체인 사용처를 `rg` 로 먼저 확인해 blast radius 가 좁음을 검증하고 한 번에 정리했다. 두 step 모두 재시도 없이 통과했다.

### 개선할 점

- step1 커밋이 commit agent 자동 판단으로 `refactor:` 로 기록됐으나, 가드 제거는 형제-성공 케이스의 보상 동작을 바꾸는 버그 수정이라 `fix:` 가 정확했다. push 후 force push 로 정정했다 — 동작을 바꾸는 변경에는 commit agent 가 타입을 보수적으로 `refactor` 로 잡을 수 있으니, 실행 전 핵심 step 의 의도 타입을 미리 못박아 두면 정정 비용을 줄일 수 있다.
