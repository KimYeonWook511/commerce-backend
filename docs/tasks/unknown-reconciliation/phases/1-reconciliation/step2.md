# Step 2: payment-status-and-blocking-guard

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/unknown-reconciliation/prd.md`
- `/docs/tasks/unknown-reconciliation/architecture.md`
- `/docs/tasks/unknown-reconciliation/adr.md` (특히 ADR-L5)
- `/docs/tasks/unknown-reconciliation/db-schema.md`

변경·확인 대상:

- `/src/main/java/com/commerce/payment/domain/PaymentStatus.java`
- `/src/main/java/com/commerce/payment/domain/Payment.java` (succeed/fail/markUnknown 전이)
- `/src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java` (`existsUnknownByOrderId`)
- `/src/main/java/com/commerce/payment/infrastructure/PaymentRepositoryAdapter.java` (`existsByOrderIdAndTypeAndStatus` 위임)
- 차단 가드 사용처: `/src/main/java/com/commerce/payment/application/ReservePaymentService.java`, `/src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`
- 도메인 테스트: `/src/test/java/com/commerce/payment/domain/PaymentTest.java`

## 작업

대사가 UNKNOWN을 확정·승급할 수 있도록 결제 **상태 모델과 차단 가드 계약**을 확장한다. 이 step은 상태 모델 변경에 집중하고, 실제 대사 wiring은 step 3에서 이 계약을 사용한다.

### 1. PaymentStatus.MANUAL_REVIEW (ADR-L5)

- `PaymentStatus`에 `MANUAL_REVIEW`(자동 처리 포기·운영자 확인 대상)를 추가한다.

### 2. 도메인 전이 (`Payment`)

- `succeed(now)`의 전제를 `REQUESTED` → `REQUESTED 또는 UNKNOWN`으로 확장한다. (정상 승인 경로는 REQUESTED로 계속 동작 → 회귀 없음)
- `fail(...)`의 전제도 `REQUESTED 또는 UNKNOWN`으로 확장한다.
- `MANUAL_REVIEW` 종착 전이 메서드를 추가한다 (`REQUESTED`/`UNKNOWN` → `MANUAL_REVIEW`). 이미 `MANUAL_REVIEW`면 멱등(예외 없이 흡수)하게 둔다.
- 이 전이들의 사용처는 **step 3(대사 서비스)** 다. 이 step에서는 전이 계약과 그 단위 테스트까지 둔다(미사용 코드가 아니라 다음 step의 선행 계약임).

### 3. 차단 가드 계약 확장 (ADR-L5)

- reserve/approve를 차단하는 결제 상태를 `UNKNOWN` ∪ `MANUAL_REVIEW`로 통일한다.
- `PaymentRepository.existsUnknownByOrderId`는 현재 APPROVE + `UNKNOWN`만 본다(`existsByOrderIdAndTypeAndStatus`). 이를 APPROVE + (`UNKNOWN` 또는 `MANUAL_REVIEW`)로 확장한다.
  - 메서드명이 의미와 어긋나면 의미를 담은 이름(예: `existsBlockingApproveByOrderId`)으로 정리하고 기존 사용처(`ReservePaymentService`, `NaverPayApprovalService`)를 함께 갱신한다. JPA 쿼리는 `status IN (...)` 형태로 둔다.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 공유 도메인 계약(전이 전제, 차단 조회) 변경의 사용처를 확인한다.
   ```bash
   rg "existsUnknownByOrderId|existsBlockingApproveByOrderId" src/main src/test
   rg "\.succeed\(|\.fail\(|markUnknown|markManualReview" src/main/java/com/commerce/payment
   ```
3. 아래를 확인한다.
   - 정상 승인 경로(REQUESTED → SUCCEEDED/FAILED)가 회귀 없이 동작하는가?
   - UNKNOWN → SUCCEEDED/FAILED/MANUAL_REVIEW 전이가 가능하고, 잘못된 상태 전이는 여전히 막히는가?
   - reserve/approve 차단 가드가 `UNKNOWN`과 `MANUAL_REVIEW` 모두를 차단하는가?
4. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `succeed`/`fail` 전제를 임의 상태까지 열지 마라(예: `SUCCEEDED`에서 다시 `succeed`). 이유: `REQUESTED`/`UNKNOWN`만 확정 진입이며, 그 외는 멱등 흡수 또는 차단이어야 한다.
- 차단 가드를 한 사용처에서만 바꾸지 마라. 이유: `ReservePaymentService`·`NaverPayApprovalService`가 같은 의미의 차단을 공유하므로 함께 갱신해야 한다.
- 대사 서비스/스캔 쿼리/스케줄러를 이 step에서 만들지 마라. 이유: 이 step은 상태 모델·차단 계약에 한정하며 wiring은 step 3이다.
- 기존 테스트를 깨뜨리지 마라.
