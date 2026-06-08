# Step 1: remove-completed-guard

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/compensation-completed-guard-removal/prd.md`
- `/docs/tasks/compensation-completed-guard-removal/adr.md`

Task 문서만으로 부족한 공통 맥락이 있으면 아래 루트 문서를 추가로 읽는다.

- `/docs/adr.md` (ADR-014, ADR-015, ADR-033 항목)

변경 대상 코드와 테스트:

- `src/main/java/com/commerce/payment/application/PaymentApprovalCompensationService.java`
- `src/main/java/com/commerce/payment/application/PaymentApprovalService.java`
- `src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java`
- `src/main/java/com/commerce/payment/infrastructure/PaymentRepositoryAdapter.java`
- `src/main/java/com/commerce/payment/infrastructure/JpaPaymentRepository.java`
- `src/test/java/com/commerce/payment/application/PaymentApprovalCompensationServiceTest.java`
- `src/test/java/com/commerce/payment/application/PaymentApprovalServiceTest.java`
- `src/test/java/com/commerce/payment/infrastructure/PaymentRepositoryJpaAdapterTest.java`

## 작업

보상 PG 취소 경로의 `hasCompletedPayment` 완료 가드를 제거하고, 그로 인해 사용처가 사라지는 메서드 체인을 정리한다.

배경: `runPgCancel`의 `hasCompletedPayment(merchantPayKey)` 가드는 merchantPayKey 단위라, 새 결제-주문 모델(한 merchantPayKey에 여러 pgPaymentId)에서 보상 대상 pgPaymentId 자신이 아니라 형제 pgPaymentId의 성공을 잡아 중복 pgPaymentId의 PG 취소를 항상 잘못 skip한다. 보상이 취소하는 cancel payment는 항상 보상 대상 pgPaymentId(실패한 결제)로 생성되므로, 형제 성공 여부와 무관하게 무조건 취소하는 것이 옳다. 자세한 근거는 task adr.md의 ADR-L1을 따른다.

1. `PaymentApprovalCompensationService.runPgCancel`에서 `hasCompletedPayment` 가드 블록(`if (paymentApprovalService.hasCompletedPayment(...)) { log.warn(...); return; }`)을 제거한다.
   - `cancelPayment.getStatus() != PaymentStatus.REQUESTED`일 때 return하는 skip은 멱등 안전망이므로 **유지한다**.
   - `failIfRequested` 호출과 그 이후 PG 취소 흐름은 그대로 둔다.
   - 가드 제거로 `paymentApprovalService` 의존이 더 이상 쓰이지 않으면 필드와 생성자 주입에서 제거한다(사용처가 남아 있으면 유지).
2. 가드 제거로 dead code가 되는 완료 조회 메서드 체인을 제거한다.
   - `PaymentApprovalService.hasCompletedPayment(String)`
   - `PaymentRepository.existsApproveSucceeded(String)` (도메인 인터페이스)
   - `PaymentRepositoryAdapter.existsApproveSucceeded(String)` (어댑터 구현)
   - `JpaPaymentRepository.existsByMerchantPayKeyAndTypeAndStatus(...)` (다른 사용처가 없을 때만 제거. 제거 전 `rg`로 사용처를 반드시 확인한다)
3. 깨지는 단위 테스트를 갱신한다.
   - `PaymentApprovalCompensationServiceTest`: `hasCompletedPayment` stub(`given(...).willReturn(...)`)을 모두 제거하고, "`hasCompletedPayment=true`이면 cancel 미호출" 케이스는 삭제한다. 나머지 케이스는 가드 없이도 동일 결과가 나오도록 정리한다.
   - `PaymentApprovalServiceTest`: `hasCompletedPayment` 관련 테스트를 제거한다.
   - `PaymentRepositoryJpaAdapterTest`: `existsApproveSucceeded` 관련 테스트를 제거한다.

내부 구현 방식은 위 제약을 지키는 선에서 자유롭게 정한다. 새로운 추상화를 추가하지 않는다.

## Acceptance Criteria

```bash
./gradlew test
```

(repository 조회 메서드 제거와 보상 정책 동작 변경이 포함되므로 전체 단위/슬라이스 테스트를 돌린다.)

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 메서드 제거 전 `rg "hasCompletedPayment|existsApproveSucceeded|existsByMerchantPayKeyAndTypeAndStatus" src/main src/test`로 잔존 사용처가 없는지 확인한다.
3. 아래를 확인한다.
   - 보상 흐름에서 `cancelPayment` 상태 가드(REQUESTED skip)가 유지되는가?
   - ADR-L1의 결정과 어긋나지 않는가?
4. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `cancelPayment.getStatus() != REQUESTED` skip을 제거하지 마라. 이유: 이미 처리된 cancel을 재취소하는 멱등성 위반을 막는 안전망이다.
- 완료 가드를 다른 단위(pgPaymentId 등)로 바꿔 되살리지 마라. 이유: 보상 대상 pgPaymentId 자신은 SUCCEEDED로 커밋될 수 없어 실질 dead 가드이며, ADR-L1은 가드 제거로 결정됐다.
- 사용처가 사라진 메서드를 "혹시 몰라서" 남기지 마라. 이유: 사용처 없는 코드를 남기지 않는다.
- 기존 테스트를 깨뜨리지 마라.
