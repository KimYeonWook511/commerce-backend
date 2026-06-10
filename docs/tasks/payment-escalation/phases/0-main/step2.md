# Step 2: order-null-notify

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/payment-escalation/prd.md`
- `/docs/tasks/payment-escalation/adr.md`
- `/src/main/java/com/commerce/payment/application/PaymentReconciliationService.java` (step 1에서 `NotificationPort`가 주입됨 — `handleOrderNotCompletable`의 `order == null` 분기)
- `/src/main/java/com/commerce/payment/application/port/NotificationPort.java`
- `/src/main/java/com/commerce/payment/infrastructure/LogNotificationAdapter.java`

Task 문서만으로 부족한 공통 맥락이 있으면 아래를 추가로 읽는다.

- `/docs/tasks/unknown-reconciliation/adr.md` (ADR-L4/L9 — `order == null`을 `fail` 종착으로 두는 기존 결정)

이전 step에서 만들어진 코드와 task 문서를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

대사 승인 확정이 거부됐는데 주문이 없는(`order == null`) 정합성 오류 건에 운영자 통지를 추가한다. 기존 `fail` 종착은 유지한다.

### 1. `handleOrderNotCompletable`의 `order == null` 분기

- 현재 동작: `log.error` + `paymentApprovalRecordService.fail(...)`로 `FAILED` 종착.
- 추가: `fail` 종착 **이후** `notificationPort.notifyManualReviewRequired(orderId, merchantPayKey, reason)`로 운영자에게 통지한다. `reason`은 정합성 오류를 식별할 수 있게 적는다(예: `"주문 없음 - 정합성 오류"`).
- 통지는 best-effort다: try/catch로 감싸 전송 실패가 트랜잭션을 막지 않게 하고 `log.warn`만 남긴다(step 1의 escalation 통지와 동일 패턴).
- `order == null`은 `FAILED`로 종착돼 다음 주기 스캔 대상이 아니므로(스캔은 UNKNOWN/REQUESTED만) 통지는 자연히 한 번만 발생한다. `escalatedAt`을 쓰지 않는다.

### 2. 테스트

- `PaymentReconciliationServiceTest` 또는 통합 테스트에서, `order == null`일 때 `fail` 종착 + `notifyManualReviewRequired` 호출이 일어남을 검증한다(기존 `handleOrderNotCompletable` 테스트 패턴을 따른다).

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - 기존 `fail` 종착이 유지되는가? (통지만 추가)
   - 통지가 best-effort(try/catch)인가?
   - `order == null` 경로에 PG 취소(환불) 호출이 없는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `order == null`에 자동 환불(PG 취소) 로직을 넣지 마라. 이유: 원인 불명의 정합성 붕괴 상태에서 자동 PG 취소는 또 다른 오류를 낳는다. 통지로 운영자에게 위임하고, 환불 실행은 운영자가 원인 확인 후 판단한다.
- 기존 `fail` 종착을 제거하거나 다른 상태로 바꾸지 마라. 이유: 종착은 유지하고 통지만 추가하는 변경이다.
- 통지 전송 실패가 트랜잭션을 막게 하지 마라. 이유: 통지는 best-effort다(ADR-L6).
- 기존 테스트를 깨뜨리지 마라.
