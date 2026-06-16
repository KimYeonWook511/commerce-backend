# Step 5: rename-payment-domain

## 읽어야 할 파일

먼저 아래 파일들을 읽고 작업 맥락을 파악하라:

- `docs/tasks/application-layer-rename/prd.md`
- `src/main/java/com/commerce/payment/application/service/PaymentApprovalRecordService.java`
- `src/main/java/com/commerce/payment/application/service/PaymentApprovalService.java`
- `src/main/java/com/commerce/payment/application/service/PaymentCancellationService.java`
- `src/main/java/com/commerce/payment/application/usecase/PaymentApprovalCompensationUseCase.java`
- `src/main/java/com/commerce/payment/application/usecase/PaymentReconciliationUseCase.java`
- `src/main/java/com/commerce/payment/naverpay/application/usecase/NaverPayApprovalUseCase.java`

## 작업

payment 도메인 Service 3개를 메서드별로 분리하고, UseCase 3개를 리네임한다.
동작 변경 없이 파일명·클래스명·주입 변수명·테스트명만 바꾼다.

### Service 분리 목록

| 현재 | 메서드 | 변경 후 |
|---|---|---|
| `PaymentApprovalRecordService` | `create` | `CreateApprovePaymentService` |
| | `fail` | `FailApprovePaymentService` |
| | `markUnknown` | `MarkUnknownApprovePaymentService` |
| | `escalate` | `EscalateApprovePaymentService` |
| `PaymentApprovalService` | `succeedApproval` | `SucceedPaymentApprovalService` |
| | `succeedApprovalRecordOnly` | `SucceedPaymentApprovalRecordService` |
| `PaymentCancellationService` | `getOrCreate` | `GetOrCreateCancelPaymentService` |
| | `succeed` | `SucceedCancelPaymentService` |
| | `fail` | `FailCancelPaymentService` |
| | `markUnknown` | `MarkUnknownCancelPaymentService` |

### UseCase 리네임 목록

| 현재 | 변경 후 |
|---|---|
| `PaymentApprovalCompensationUseCase` | `CompensateApprovalUseCase` |
| `PaymentReconciliationUseCase` | `ReconcilePaymentUseCase` |
| `NaverPayApprovalUseCase` | `ApproveNaverPayUseCase` |

### 절차

1. 각 대상 클래스를 사용하는 모든 파일을 확인한다.

   ```bash
   grep -rl "PaymentApprovalRecordService\|PaymentApprovalService\|PaymentCancellationService" src/
   grep -rl "PaymentApprovalCompensationUseCase\|PaymentReconciliationUseCase\|NaverPayApprovalUseCase" src/
   ```

2. **PaymentApprovalRecordService 분리**: 4개 메서드를 각각 별도 파일로 분리한다. 클래스 레벨의 `SKIPPABLE` 상수와 의존성(`PaymentRepository`, `PaymentReservationRepository`)은 각 클래스에 필요한 것만 포함한다. 기존 파일 삭제.

3. **PaymentApprovalService 분리**: `succeedApproval`과 `succeedApprovalRecordOnly`를 각각 별도 파일로 분리한다. 기존 파일 삭제.

4. **PaymentCancellationService 분리**: 4개 메서드를 각각 별도 파일로 분리한다. 기존 파일 삭제.
   - `GetOrCreateCancelPaymentService` — `getOrCreate` 메서드. `@Transactional(propagation = Propagation.NOT_SUPPORTED)` 어노테이션을 그대로 유지한다.

5. **UseCase 리네임**: 3개 UseCase를 새 이름으로 파일 생성 후 기존 파일 삭제한다.

6. 모든 참조 파일에서 업데이트한다:
   - 단일 Service 주입 → 분리된 개별 Service 주입 (주입 개수 증가)
   - UseCase 타입·변수명 갱신
   - 변수명은 새 클래스명의 camelCase를 따른다

7. 테스트 파일에서 클래스명·메서드명·`@DisplayName`을 새 이름 기준으로 갱신한다.

### 주의사항

- `PaymentApprovalRecordService`의 주석(`별도 빈의 public @Transactional`)은 분리 후에도 각 클래스에 그대로 보존한다. 이유: 낙관 락 설계 근거가 담겨 있다.
- `CompensateApprovalUseCase`(구 `PaymentApprovalCompensationUseCase`)는 `PaymentApprovalRecordService`와 `PaymentCancellationService`를 모두 주입받는다. 분리 후에는 실제로 사용하는 메서드에 해당하는 Service들만 주입한다.
  - `failSkippable` → `FailApprovePaymentService`
  - `markUnknownSkippable` (cancel) → `MarkUnknownCancelPaymentService`
  - `paymentCancellationService.getOrCreate` → `GetOrCreateCancelPaymentService`
  - `paymentCancellationService.succeed` → `SucceedCancelPaymentService`
  - `paymentCancellationService.fail` → `FailCancelPaymentService`
- `ReconcilePaymentUseCase`(구 `PaymentReconciliationUseCase`)도 마찬가지로 실제 호출하는 메서드별 Service로 교체한다.
- `ApproveNaverPayUseCase`(구 `NaverPayApprovalUseCase`)는 패키지 위치(`naverpay/application/usecase/`)를 변경하지 않는다.

### 금지사항

- 분리된 Service 클래스의 메서드 내부 로직을 변경하지 마라. 이유: 동작 불변 원칙.
- `SKIPPABLE` 상수를 공유 클래스로 추출하지 마라. 이유: 각 Service가 자신이 필요한 에러 코드만 갖도록 독립적으로 유지한다.
- 주입 변수가 늘어난다는 이유로 UseCase에 wrapper 메서드를 추가하지 마라. 이유: 구현 불변 원칙.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다:
   - 구 클래스명이 `src/` 하위에 남아 있지 않은가.
     ```bash
     grep -r "PaymentApprovalRecordService\|PaymentApprovalService\b\|PaymentCancellationService\b" src/
     grep -r "PaymentApprovalCompensationUseCase\|PaymentReconciliationUseCase\|NaverPayApprovalUseCase" src/
     ```
   - 분리된 각 Service가 정확히 1개의 public 메서드를 보유하는가.
