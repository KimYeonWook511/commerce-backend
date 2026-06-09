# Step 2: scan-window-and-compensation-failed

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도를 파악하라:

- `/docs/tasks/unknown-reconciliation/prd.md`
- `/docs/tasks/unknown-reconciliation/adr.md` (특히 ADR-L4 — C 보상은 FAILED+failCode, ADR-L8 — 스캔 윈도우)
- 루트 `/docs/adr.md`의 **ADR-039**

변경 대상:

- `/src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java` (`findStaleApprovePaymentsForReconciliation(LocalDateTime cutoff, Pageable)`) 및 `JpaPaymentRepository` 쿼리
- `/src/main/java/com/commerce/payment/application/PaymentReconciliationService.java` (`reconcile()` — cutoff 계산)
- `/src/main/java/com/commerce/payment/application/PaymentApprovalCompensationService.java` (`compensateCanceledOrderApproval`, `runPgCancel`)
- `/src/main/java/com/commerce/payment/domain/PaymentFailCode.java` (신규 failCode)
- `/src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java` (`toPaymentErrorCode` switch — 신규 failCode 매핑)
- 영향받는 테스트: `PaymentReconciliationServiceTest`, `ReconciliationScanQueryIntegrationTest`, `PaymentReconciliationIntegrationTest`, `PaymentApprovalCompensationServiceTest`(있으면)

## 작업

### 1. 스캔 윈도우 상한 (escalation 자동 제외 — 무한 재시도 방지)

- `findStaleApprovePaymentsForReconciliation` 후보 조회를 **`1분 < age < 6시간` 윈도우**로 제한한다. 현재는 하한(1분, `UNKNOWN_RECONCILE_DELAY`)만 있다 → 상한(6시간, `ESCALATION_DELAY`)을 추가해 **6시간 초과 건은 스캔에서 제외**한다.
- 즉 자동 대사 대상 = "1분~6시간 사이의 stale UNKNOWN/REQUESTED APPROVE". 6시간 초과는 자동 대사하지 않고 `UNKNOWN`으로 남긴다(운영 종착·통지는 후속 #238).
- UNKNOWN은 `respondedAt`, REQUESTED는 `createdAt` 기준 윈도우를 적용한다(기존 하한 기준과 일관되게). 시그니처에 상·하한 두 cutoff를 받도록 조정한다.

### 2. C 보상을 FAILED + failCode로 (ADR-039 준수)

- `PaymentFailCode`에 취소된 주문 보상용 코드를 추가한다(예: `ORDER_CANCELED`). `NaverPayApprovalService.toPaymentErrorCode` switch에도 대응 매핑을 추가한다(미매핑 시 컴파일 실패).
- `compensateCanceledOrderApproval`에서 **`markManualReview` 호출을 제거**하고, `runPgCancel`에 위 신규 failCode를 전달한다. 그러면 `runPgCancel` 내부의 `failIfRequested`가 approve를 **`FAILED` + 해당 failCode**로 마킹한다(ADR-039: 보상된 APPROVE = FAILED+failCode + CANCEL row). `NotificationPort` 통지는 그대로 유지한다(best-effort).
- 결과적으로 "취소된 주문에 뒤늦은 승인 확정"도 duplicate/amount-mismatch 보상과 동일하게 `FAILED`+failCode+CANCEL row로 일관되게 표현된다.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. phase 종료 후 통합 검증을 위해 아래를 확인한다(스캔 쿼리·보상 경로 변경).
   ```bash
   rg "findStaleApprovePaymentsForReconciliation|ORDER_CANCELED|markManualReview" src/main src/test
   ```
3. 아래를 확인한다.
   - 스캔이 6시간 초과 건을 제외하는가(윈도우 상·하한)? 1분~6시간 건만 후보인가?
   - C 보상 후 approve가 `FAILED` + 신규 failCode이고 CANCEL row가 생성되는가? `markManualReview` 흔적이 없는가?
   - 통지(`NotificationPort`)는 여전히 호출되는가?
4. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 6시간 초과 건을 이 step에서 종착 상태로 바꾸지 마라(통지·종착은 후속 #238). 이유: 스캔 윈도우로 자동 대사에서만 빼고, 상태는 UNKNOWN으로 정직하게 둔다.
- 보상된 approve를 새 상태로 두지 마라. 이유: ADR-039에 따라 FAILED+failCode로 표현한다.
- PG 조회·취소(외부 호출)를 DB 트랜잭션 안에서 호출하지 마라.
- 기존 테스트를 깨뜨리지 마라.
