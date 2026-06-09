# Step 2: approved-order-entry-guard

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/approval-concurrency-guard/prd.md`
- `/docs/tasks/approval-concurrency-guard/architecture.md`
- `/docs/tasks/approval-concurrency-guard/adr.md`
- `/docs/tasks/approval-concurrency-guard/api-spec.md`

작업 대상 코드와 테스트:

- `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`
- `src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java`
- `src/main/java/com/commerce/payment/infrastructure/PaymentRepositoryAdapter.java`
- `src/main/java/com/commerce/payment/infrastructure/JpaPaymentRepository.java`
- `src/main/java/com/commerce/payment/exception/PaymentErrorCode.java`
- `src/test/java/com/commerce/payment/naverpay/application/NaverPayServiceIntegrationTest.java`

Step 1에서 추가된 코드(낙관적 락 가드)도 함께 읽고 진입 차단과 충돌하지 않는지 확인한다.

## 작업

이미 성공한 APPROVE 결제가 있는 주문에 새 승인이 들어왔을 때, PG 호출 전 진입 단계에서 차단한다. 설계 근거는 ADR-L2다. 기존 `existsUnknownByOrderId` 차단과 동형으로 추가한다.

1. **존재 조회 메서드 추가**: `PaymentRepository`
   - `boolean existsApprovedByOrderId(Long orderId)`를 추가한다. 의미: 해당 주문에 APPROVE·SUCCEEDED payment가 존재하는지.

2. **어댑터 구현**: `PaymentRepositoryAdapter` / `JpaPaymentRepository`
   - 기존 `existsByOrderIdAndTypeAndStatus(orderId, PaymentType.APPROVE, PaymentStatus.SUCCEEDED)` 패턴을 그대로 사용한다(`existsUnknownByOrderId`와 동형).

3. **진입 차단 추가**: `NaverPayApprovalService.approve()`
   - 기존 `existsUnknownByOrderId` 차단 바로 옆(같은 진입 검증 구간, USED/RESERVED 분기 전)에서 `existsApprovedByOrderId`로 차단한다.
   - 존재하면 `PaymentException`을 던진다(에러코드 `PAYMENT_DUPLICATE` 제안 — 의미: 주문에 이미 성공 결제 존재. 최종 코드는 `PaymentErrorCode` 컨벤션에 맞춰 확정).

4. **통합 테스트**: 이미 성공 결제가 있는 주문에 새 승인 요청이 진입 단계에서 차단되고, PG approve가 호출되지 않음을 검증한다. 기존 `NaverPayServiceIntegrationTest` 패턴·태그(`@Tag("docker")`)를 따른다.

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - 차단이 PG approve 호출보다 앞(진입 검증 구간)에서 일어나는가?
   - `existsUnknownByOrderId`와 동형 패턴을 따르는가?
   - 정상 단건 승인 흐름이 깨지지 않는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 최종 보루(`uk_payment_approved_order_key`, `succeedApproval`) 로직을 진입 차단으로 대체하지 마라. 이유: 진짜 동시 race는 진입 차단으로 100% 막을 수 없어 #230 최종 net이 그대로 필요하다.
- 진입 차단을 PG approve 호출 이후에 두지 마라. 이유: PG 청구 전 차단이 이 step의 목적이다.
- 기존 테스트를 깨뜨리지 마라.
