# Step 2: duplicate-pg-cancel-test

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/compensation-completed-guard-removal/prd.md`
- `/docs/tasks/compensation-completed-guard-removal/adr.md`

Step 1에서 변경된 코드:

- `src/main/java/com/commerce/payment/application/PaymentApprovalCompensationService.java`

테스트 작성 시 참고할 기존 테스트(패턴·fixture·태그):

- `src/test/java/com/commerce/payment/naverpay/application/NaverPayServiceIntegrationTest.java` (PAYMENT_DUPLICATE 보상 통합 테스트 412줄 근처)
- `src/test/java/com/commerce/payment/infrastructure/PaymentRepositoryDuplicatePaymentTest.java` (`uk_payment_approved_order_key` 위반으로 PAYMENT_DUPLICATE 재현)
- `src/test/java/com/commerce/payment/application/concurrency/PaymentApprovalServiceConcurrencyTest.java` (#118 보상 동시성 테스트)

## 작업

Step 1에서 제거한 완료 가드 버그(같은 reservation·다른 pgPaymentId 경합 시 중복 pgPaymentId의 PG 취소가 skip되던 이중청구)를 재현·회귀 방지하는 통합 테스트를 추가한다.

시나리오: 같은 merchantPayKey(reservation)에 서로 다른 pgPaymentId(`pgA`, `pgB`) 승인이 존재한다. `pgA`가 먼저 성공해 `approved_order_key=orderId`로 커밋된 상태에서 `pgB` 승인이 반영되면 `uk_payment_approved_order_key` 위반으로 `PAYMENT_DUPLICATE`가 발생하고 보상이 진입한다. 이때 형제 `pgA`의 성공과 무관하게 **중복 pgPaymentId `pgB`의 CANCEL이 실제 수행**되어야 한다.

1. `NaverPayServiceIntegrationTest`(또는 동일 통합 테스트 슬라이스)에 위 시나리오의 통합 테스트를 추가한다.
   - 같은 merchantPayKey·다른 pgPaymentId로 `pgA`를 먼저 SUCCEEDED로 만들고, `pgB` 승인 반영이 `PAYMENT_DUPLICATE`를 일으키도록 구성한다.
   - 검증: `pgB`의 approve record는 FAILED(DUPLICATE_PAYMENT)로, `pgB`의 cancel payment는 SUCCEEDED(PG cancel 실제 수행)로 남는다. 형제 `pgA`의 SUCCEEDED는 보존된다.
   - 기존 PAYMENT_DUPLICATE 통합 테스트가 단일 결제 전제로 작성돼 있으면, 형제 성공이 있는 멀티 pgPaymentId 케이스를 새 테스트로 추가한다(기존 케이스를 삭제하지 않는다).
2. 기존 테스트가 가드 제거로 의미가 달라진 부분(예: "이미 완료된 결제면 cancel skip"을 검증하던 통합 케이스)이 있으면, 가드 제거 결정(ADR-L1)에 맞게 갱신하거나 새 동작으로 대체한다.

테스트 태그는 대상 슬라이스의 기존 컨벤션(Testcontainers면 `docker`)을 따른다.

## Acceptance Criteria

```bash
./gradlew test integrationTest
```

(추가 테스트가 Testcontainers 통합 테스트이므로 `integrationTest`까지 포함한다.)

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 추가한 통합 테스트가 Step 1 가드 제거 없이는 실패하는 시나리오(중복 pgPaymentId 취소 skip)를 정확히 겨냥하는지 확인한다.
3. #118 보상 동시성 회귀 확인: `./gradlew concurrencyTest`를 수동 실행해 `PaymentApprovalServiceConcurrencyTest`가 통과하는지 확인하고 결과를 step output에 남긴다(이 커맨드는 CI·AC에 포함되지 않는 수동 검증이다).
4. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 기존 PAYMENT_DUPLICATE 보상 통합 테스트를 이유 없이 삭제하지 마라. 이유: 가드 제거 후에도 단일 결제 보상 동작은 회귀 없이 유지돼야 한다.
- 비결정적 sleep/타이밍에 의존하는 통합 테스트를 작성하지 마라. 이유: 통합 테스트는 결정적으로 `pgA` 성공 → `pgB` 위반 순서를 구성해 재현한다.
- 기존 테스트를 깨뜨리지 마라.
