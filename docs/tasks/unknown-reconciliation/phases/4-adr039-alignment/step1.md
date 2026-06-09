# Step 1: revert-manual-review-status

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도를 파악하라:

- `/docs/tasks/unknown-reconciliation/prd.md`
- `/docs/tasks/unknown-reconciliation/adr.md` (특히 ADR-L5 — MANUAL_REVIEW 철회 결정)
- 루트 `/docs/adr.md`의 **ADR-039** (보상된 APPROVE는 FAILED+failCode, 새 상태 도입 기각)

변경 대상:

- `/src/main/java/com/commerce/payment/domain/PaymentStatus.java`
- `/src/main/java/com/commerce/payment/domain/Payment.java` (`markManualReview`)
- `/src/main/java/com/commerce/payment/application/PaymentApprovalRecordService.java` (`markManualReview`)
- `/src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java` / `/src/main/java/com/commerce/payment/infrastructure/PaymentRepositoryAdapter.java` (`existsBlockingApproveByOrderId`)
- 사용처: `/src/main/java/com/commerce/payment/application/ReservePaymentService.java`, `/src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`
- 대사 서비스의 escalation 분기: `/src/main/java/com/commerce/payment/application/PaymentReconciliationService.java`
- 영향받는 테스트: `PaymentTest`, `PaymentReconciliationServiceTest`, `PaymentReconciliationIntegrationTest`, 차단 가드 관련 테스트

## 작업

ADR-039(보상된 APPROVE는 `FAILED`+failCode, 새 상태 도입 기각)와 충돌하는 `PaymentStatus.MANUAL_REVIEW` 도입을 **철회**한다.

1. `PaymentStatus`에서 `MANUAL_REVIEW`를 제거한다 (`REQUESTED`/`SUCCEEDED`/`FAILED`/`UNKNOWN`만 남긴다).
2. `Payment.markManualReview(...)` 도메인 메서드를 제거한다.
3. `PaymentApprovalRecordService.markManualReview(...)`를 제거한다.
4. **차단 가드 복원**: `existsBlockingApproveByOrderId`(APPROVE + `UNKNOWN`∪`MANUAL_REVIEW`)를 원래의 `existsUnknownByOrderId`(APPROVE + `UNKNOWN`)로 되돌린다. 메서드명·쿼리·사용처(`ReservePaymentService`, `NaverPayApprovalService`)를 함께 복원한다.
5. **대사 서비스의 escalation 분기 정리**: `PaymentReconciliationService`에서 target이 `MANUAL_REVIEW`일 때 `markManualReview`로 승급하던 처리를 제거한다. escalation(6시간 초과)의 자동 제외는 step 2의 스캔 윈도우 상한으로 처리하므로, 이 분기는 **로그만 남기고 상태를 바꾸지 않는다**(escalation 운영 종착은 후속 #238). target enum(`PaymentPostProcessTarget.MANUAL_REVIEW`) 자체는 정책 분류값이므로 제거하지 않는다 — status가 아니라 분류 결과다.
6. 위 변경으로 깨지는 단위·통합 테스트를 수정한다. `MANUAL_REVIEW` status를 검증하던 케이스는 제거하거나 해당 step(2·3)에서 새 동작으로 대체될 것을 전제로 정리한다. (C 보상 FAILED 검증은 step 2에서 다룬다)

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다. (test 소스 전체가 컴파일되어야 하므로 통합 테스트 파일의 `MANUAL_REVIEW` 참조도 함께 정리돼야 한다)
2. `MANUAL_REVIEW` 잔재가 없는지 확인한다.
   ```bash
   rg "MANUAL_REVIEW|markManualReview|existsBlockingApproveByOrderId" src/main src/test
   ```
3. 차단 가드가 `UNKNOWN`만 보도록 복원됐고 `ReservePaymentService`·`NaverPayApprovalService`가 정상인지 확인한다.
4. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `PaymentPostProcessTarget.MANUAL_REVIEW`(정책 enum)를 제거하지 마라. 이유: 그건 status가 아니라 정책의 분류 결과값이다. 제거 대상은 `PaymentStatus.MANUAL_REVIEW`(상태)뿐이다.
- escalation 운영 종착(통지·종착 표시)을 이 step에서 새로 구현하지 마라. 이유: 후속 #238 범위이며, 이번엔 스캔 윈도우(step 2)로 자동 대사에서 빠지게만 한다.
- 기존 정상 동작(REQUESTED→SUCCEEDED/FAILED/UNKNOWN 전이)을 깨뜨리지 마라.
