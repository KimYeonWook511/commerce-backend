# Step 1: expiration-unknown-guard (A)

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/unknown-reconciliation/prd.md`
- `/docs/tasks/unknown-reconciliation/architecture.md`
- `/docs/tasks/unknown-reconciliation/adr.md` (특히 ADR-L3)

의존 역전 선례(반드시 이 패턴을 따른다):

- `/src/main/java/com/commerce/order/application/port/CartItemRemover.java` (port를 order가 소유)
- `/src/main/java/com/commerce/cart/infrastructure/CartItemRemoverAdapter.java` (구현은 cart adapter)

변경 대상:

- `/src/main/java/com/commerce/order/batch/OrderExpirationBatchConfig.java` (`expiredOrderReader`)
- `/src/main/java/com/commerce/order/domain/repository/OrderRepository.java` (`findExpiredOrdersAfterId(status, cutoff, lastId, limit)`)
- `/src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java` 및 `/src/main/java/com/commerce/payment/infrastructure/PaymentRepositoryAdapter.java` (phase 1 step 2에서 차단 조회를 `UNKNOWN` ∪ `MANUAL_REVIEW`로 확장한 상태)

## 작업

주문 만료 배치가 **차단 결제(UNKNOWN ∪ MANUAL_REVIEW)가 걸린 주문을 만료 대상에서 제외**하도록 한다 (ADR-L3). order→payment 직접 의존을 만들지 않고 의존 역전으로 푼다.

### 1. query port (order 소유)

- `order.application.port`에 결제 상태 조회 port를 신설한다. order가 "이 주문들 중 결제가 미확정이라 만료하면 안 되는 것"을 묻는 의미의 인터페이스다.
  - 메서드 예: `Set<Long> findOrderIdsWithBlockingPayment(Collection<Long> orderIds)` — 입력 orderId들 중 차단 결제가 걸린 것만 반환. **orderId 컬렉션을 받아 한 번에 조회**(IN)해 N+1을 피한다.
  - port 이름·메서드명은 `CartItemRemover` 톤(도메인 의미 중심)으로 둔다.

### 2. adapter (payment 구현)

- `payment.infrastructure`에 위 port를 구현하는 adapter를 둔다. `PaymentRepository`에 orderId IN 배치 조회 메서드(APPROVE + status IN (`UNKNOWN`, `MANUAL_REVIEW`))를 추가하고 그 결과 orderId 집합을 반환한다.

### 3. 만료 reader 필터

- `expiredOrderReader`가 port를 주입받아, `findExpiredOrdersAfterId`로 읽은 한 페이지(chunk 크기)의 orderId들을 port로 조회한 뒤 **차단 결제가 걸린 주문을 제외**하고 iterator를 구성한다.
- 커서 페이징의 `lastId`는 **조회한 마지막 주문 id 기준으로 유지**한다(제외 여부와 무관). 제외 때문에 페이징이 멈추거나 건너뛰지 않게 한다.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. order 만료 배치 통합 테스트가 있으면 함께 확인한다.
   ```bash
   rg "OrderExpiration" src/test/java
   ```
3. 아래를 확인한다.
   - UNKNOWN(또는 MANUAL_REVIEW) 결제가 걸린 INIT 주문이 만료 대상에서 제외되는가?
   - 차단 결제가 풀린(FAILED 확정 등) 주문은 다음 사이클에서 정상 만료되는가?
   - chunk orderId 조회가 IN 한 번으로 수행되어 N+1이 아닌가?
   - order가 payment를 직접 import하지 않고 port로만 의존하는가?
4. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- order에서 payment 패키지를 직접 import하지 마라. 이유: 의존 방향을 보존해야 한다(`CartItemRemover` 선례, ADR-L3).
- 만료 reader 쿼리에서 `tbl_payment`를 직접 join하지 마라. 이유: Order↔Payment aggregate 경계·ADR-020 위반이다.
- 주문별 단건 조회로 차단 여부를 묻지 마라(N+1). 이유: 만료 배치는 chunk 단위이며 IN 배치 조회로 묶어야 한다.
- 기존 테스트를 깨뜨리지 마라.
