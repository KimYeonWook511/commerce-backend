# Step 1: reconciliation-integration-tests

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/unknown-reconciliation/prd.md`
- `/docs/tasks/unknown-reconciliation/architecture.md`
- `/docs/tasks/unknown-reconciliation/adr.md`

검증 대상 구현(phase 1·2 산출물):

- `/src/main/java/com/commerce/payment/application/PaymentReconciliationService.java` (`reconcile()`)
- `/src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java` (`findStaleApprovePaymentsForReconciliation(LocalDateTime cutoff, Pageable pageable)`) 및 `/src/main/java/com/commerce/payment/infrastructure/PaymentRepositoryAdapter.java`, `JpaPaymentRepository`
- `/src/main/java/com/commerce/payment/application/PaymentApprovalService.java` (`succeedApproval`: `findByIdForUpdate` order 락 + `order.completePayment`)
- `/src/main/java/com/commerce/payment/application/PaymentApprovalCompensationService.java` (`compensateCanceledOrderApproval(Payment, PgCanceller)`)
- `/src/main/java/com/commerce/payment/domain/Payment.java`, `PaymentStatus.java`, `/src/main/java/com/commerce/order/domain/Order.java`
- `/src/main/java/com/commerce/payment/naverpay/application/port/NaverPayGateway.java` (+ `result/NaverPayHistoryResult`, `NaverPayCancelResult`)

기존 테스트 패턴(반드시 모방):

- `/src/test/java/com/commerce/payment/naverpay/application/NaverPayServiceIntegrationTest.java` (`@SpringBootTest` + `@ActiveProfiles("test")` + `@MockitoBean NaverPayGateway` + `PersistenceTestSupport`/`PersistenceCleanupTestSupport`)
- `/src/test/java/com/commerce/support/TestcontainersSupport.java` (`registerMySql`/`registerRedis`/`registerKafka` — `@DynamicPropertySource`)
- `/src/test/java/com/commerce/order/application/batch/OrderExpirationBatchTest.java` (Testcontainers MySQL `@DataJpaTest`/통합 패턴, `@Tag` 사용 방식)
- 도메인 저장 헬퍼: `payment/.../PaymentPersistenceTestSupport`, `order/.../OrderPersistenceTestSupport`, `member/.../MemberPersistenceTestSupport`, `product/.../ProductPersistenceTestSupport`

## 작업

phase 1·2의 대사 서비스를 **Testcontainers(MySQL) 기반 통합 테스트**로 검증한다. 단위 mock 테스트(`PaymentReconciliationServiceTest`)가 못 잡는 DB 의존 경로(실제 스캔 쿼리, `findByIdForUpdate` order 락, 실제 상태 전이, 만료-취소-후-지연-승인 정합성)를 커버한다.

세 테스트를 추가한다. 모두 **`integrationTest` 태스크로 실행되도록** 기존 통합 테스트와 동일한 방식(클래스에 `@Tag("docker")` 부여 + Testcontainers MySQL 등록, 또는 기존 `@SpringBootTest` 통합 테스트가 따르는 동일 패턴)을 따른다.

외부·경계 빈 처리:
- `NaverPayGateway`: `@MockitoBean`으로 stub한다(실제 PG 호출 금지).
- `NotificationPort`: `@MockitoBean`(또는 `@MockitoSpyBean`)으로 둔다. 구현체 `LogNotificationAdapter`는 no-op(로그만)이라 부작용 차단 목적은 아니고, **통지 호출 여부를 `then(...).should()`로 verify**하기 위함이다. C/escalation 검증의 핵심 포인트 중 하나가 통지 발생이다.

컨텍스트 구성(부팅 비용 최소화):
- 테스트 1(스캔 쿼리)은 `@DataJpaTest` + Testcontainers MySQL **슬라이스**로 둔다(repository만 필요 → 전체 컨텍스트 불필요). 기존 `PaymentRepositoryJpaAdapterTest`의 `@Import(adapter, persistence 헬퍼)` 보충 패턴을 따르되, H2가 아니라 `@DynamicPropertySource`로 `TestcontainersSupport.registerMySql`을 등록한다.
- 테스트 2·3은 **하나의 `@SpringBootTest` 클래스에 함께** 둔다. 어노테이션·`@Import`·`@MockitoBean`/`@MockitoSpyBean` 조합을 동일하게 맞춰 **컨텍스트를 1개만 띄워 공유**한다(Spring test context 캐싱). 테스트마다 별도 클래스로 컨텍스트를 3개 띄우지 않는다.

### 1. 스캔 쿼리 통합 테스트

- `findStaleApprovePaymentsForReconciliation(cutoff, pageable)`를 MySQL에서 검증한다. H2 슬라이스가 아니라 **Testcontainers MySQL**로 돌려야 한다(운영 쿼리 동작 보장, #189 맥락).
- 검증: APPROVE + (`UNKNOWN`/`REQUESTED`) + 시각이 cutoff보다 과거인 건만 반환한다. `SUCCEEDED`/`FAILED`/`MANUAL_REVIEW`, CANCEL type, cutoff보다 최근 건은 제외된다. `Pageable` limit이 동작한다.

### 2. 대사 정상 흐름 통합 테스트

- 실제 DB에 INIT order + 그 주문의 APPROVE `UNKNOWN` payment를 저장한다.
- `@MockitoBean NaverPayGateway.getApprovalHistory`가 PG 승인 확인(APPROVED, merchantPayKey/amount 일치)을 반환하도록 stub한다.
- `reconcile()` 호출 후: payment가 `SUCCEEDED`로 확정되고, order가 `PAID`로 전이되며, 차단 가드(`existsBlockingApproveByOrderId`)가 해제됨을 확인한다. `succeedApproval`의 `findByIdForUpdate` order 락 경로가 실제 DB에서 동작함을 포함한다.

### 3. C — 만료-취소-후-지연-승인 정합성 통합 테스트 (#222 검증 기준)

- INIT order + APPROVE `UNKNOWN` payment 저장 → 주문을 `CANCELED`로 만든다(`order.cancel()` 또는 만료 경로로 취소된 상태 구성).
- `getApprovalHistory`는 APPROVED, `cancel`은 취소 성공(SUCCESS)을 반환하도록 stub한다.
- `reconcile()` 호출 후: `completePayment`가 거부되어 보상 경로로 분기 → PG 보상 취소(cancel payment 생성·성공) + approve payment가 `MANUAL_REVIEW`로 승급 + order는 `CANCELED` 유지(돈은 환불, 주문은 취소 — 정합성 보장) + `NotificationPort` 통지 호출됨을 확인한다.
- 멱등: `reconcile()`를 2회 호출해도 이중 환불(중복 cancel)이 발생하지 않음을 확인한다.

## Acceptance Criteria

```bash
./gradlew integrationTest
```

이 step은 통합 테스트 추가가 목적이므로 AC를 `integrationTest`로 좁힌다. (Testcontainers MySQL이 필요하며 Docker 데몬이 떠 있어야 한다.)

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 추가한 테스트가 `integrationTest`(`@Tag("docker")`)로 실제 수집·실행되는지 확인한다.
   ```bash
   rg "@Tag\(\"docker\"\)|class .*ReconciliationIntegration|registerMySql" src/test/java/com/commerce/payment
   ```
3. 아래를 확인한다.
   - 스캔 쿼리가 MySQL에서 후보 선별(상태/cutoff/type/limit)을 올바르게 하는가?
   - 대사 정상 흐름이 실제 DB에서 payment SUCCEEDED + order PAID + 차단 해제로 끝나는가?
   - C 시나리오에서 돈/주문 정합성(환불 + 주문 CANCELED 유지 + MANUAL_REVIEW)과 멱등(2회 호출 이중 환불 없음)이 보장되는가?
4. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 실제 PG sandbox API를 호출하지 마라(`@MockitoBean NaverPayGateway`로 stub). 이유: 이 step은 내부 대사 흐름·DB 경로 검증이며 외부 PG 연동 테스트가 아니다.
- 스캔 쿼리를 H2 슬라이스로 검증하지 마라. 이유: 운영은 MySQL이고 H2는 SQLState/락/native 동작이 달라 거짓 통과를 만든다(#189).
- main 코드(구현)를 바꾸지 마라. 이유: 이 step은 테스트 추가만 담당한다. 구현 결함을 발견하면 수정하지 말고 `blocked`로 보고한다.
- 기존 테스트를 깨뜨리지 마라.
