# Step 1: narrow-application-catch

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도를 파악하라:

- `/docs/tasks/db-constraint-violation-handling/prd.md`
- `/docs/tasks/db-constraint-violation-handling/architecture.md`
- `/docs/tasks/db-constraint-violation-handling/adr.md`

변경 대상 main 파일:
- `src/main/java/com/commerce/payment/application/PaymentAttemptService.java`
- `src/main/java/com/commerce/order/application/OrderCreateService.java`
- `src/main/java/com/commerce/payment/application/PaymentApprovalService.java`
- `src/main/java/com/commerce/member/application/MemberRegistrationService.java`
- `src/main/java/com/commerce/outbox/stock/application/StockRestoreOutboxConsumeService.java`

변경 대상 테스트 파일:
- `src/test/java/com/commerce/payment/application/PaymentAttemptServiceTest.java`
- `src/test/java/com/commerce/payment/application/PaymentApprovalServiceTest.java`
- `src/test/java/com/commerce/payment/application/PaymentApprovalServiceIntegrationTest.java`
- `src/test/java/com/commerce/member/application/MemberRegistrationServiceTest.java`
- `src/test/java/com/commerce/outbox/stock/application/StockRestoreOutboxConsumeServiceTest.java`
- `src/test/java/com/commerce/order/application/` (OrderCreateService 관련 테스트)

공통 맥락:
- `/docs/architecture.md` (예외 처리 전략 섹션)

## 작업

### 1. Application 5곳 catch 타입 교체

각 파일에서 import와 catch 타입을 교체한다.

```diff
- import org.springframework.dao.DataIntegrityViolationException;
+ import org.springframework.dao.DuplicateKeyException;

- } catch (DataIntegrityViolationException ex) {
+ } catch (DuplicateKeyException ex) {
```

대상 파일과 라인:
- `PaymentAttemptService.java:42, 73`
- `OrderCreateService.java:64`
- `PaymentApprovalService.java:67`
- `MemberRegistrationService.java:32`
- `StockRestoreOutboxConsumeService.java:46`

catch 블록 안의 기존 fallback 로직은 **그대로 유지**한다.

`MemberRegistrationService`의 `existsByEmail()` 사전 체크 + catch fallback 패턴은 race condition 안전망이므로 그대로 유지한다.

### 2. OrderCreateService fallback 실패 분기 수정

`OrderCreateService.java:72-75`의 `ORDER_NOT_FOUND` throw를 원래 예외 rethrow로 교체한다.

```diff
  .orElseGet(() -> {
-     log.error("멱등키 충돌이 아닌 unique 제약 위반 발생: {}", ex.getMessage());
-     throw new OrderException(OrderErrorCode.ORDER_NOT_FOUND);
+     log.error("멱등키 충돌이 아닌 unique 제약 위반 발생", ex);
+     throw ex;
  });
```

이유: `Order` 엔티티에는 `(member_id, idempotency_key)`와 `orderNumber` 두 unique 제약이 있다.
fallback 재조회 실패는 다른 unique 위반(기술적 unique) 또는 race condition으로 데이터가 사라진 경우다.
둘 다 처리할 의미 있는 흐름이 아니므로 안전망(GlobalExceptionHandler 500)에 위임한다.

### 3. 단위 테스트 mock 타입 교체

각 테스트 파일에서 mock 예외 타입을 교체한다.

```diff
- import org.springframework.dao.DataIntegrityViolationException;
+ import org.springframework.dao.DuplicateKeyException;

- .willThrow(new DataIntegrityViolationException("duplicate key"))
+ .willThrow(new DuplicateKeyException("duplicate key"))

- doThrow(new DataIntegrityViolationException("duplicate key"))
+ doThrow(new DuplicateKeyException("duplicate key"))
```

대상:
- `PaymentAttemptServiceTest.java:67, 127, 147, 167`
- `PaymentApprovalServiceTest.java:142`
- `PaymentApprovalServiceIntegrationTest.java:81`
- `MemberRegistrationServiceTest.java:90`
- `StockRestoreOutboxConsumeServiceTest.java:73`

### 4. OrderCreateService rethrow 관련 테스트 보강

`OrderCreateService` 단위 테스트에서 fallback 재조회 실패 시 `DuplicateKeyException`이 그대로 던져지는지 검증하는 테스트를 추가하거나 갱신한다.

기존에 `ORDER_NOT_FOUND` 응답을 기대하는 테스트가 있다면 rethrow 동작으로 기대값을 변경한다.

## Acceptance Criteria

공통 예외/응답 변경이 포함되므로 전체 테스트를 실행한다.

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `DataIntegrityViolationException`을 catch 타입으로 직접 사용하는 main 코드가 0건인지 확인한다:
     ```bash
     grep -rn "catch (DataIntegrityViolationException" src/main/java/com/commerce
     ```
   - `OrderCreateService`의 fallback 실패 분기가 rethrow로 변경됐는지 확인한다.
3. 결과에 따라 step 상태를 갱신한다.

## 커밋 단위

1. `refactor: application 계층 DB unique 위반 catch 범위를 좁힌다`
   - 5곳 main catch 타입 교체 + 단위 테스트 mock 타입 교체
2. `refactor: unique 위반 fallback 실패 시 원래 예외를 rethrow한다`
   - OrderCreateService:72-75 rethrow 변경 + 관련 테스트 보강

## 금지사항

- `DataIntegrityViolationException`을 catch 타입으로 사용하지 마라. 이유: unique 위반 외 무결성 위반까지 잡아 잘못된 fallback을 탈 위험이 있다.
- catch 안의 기존 fallback 로직을 변경하지 마라. 이유: catch 타입만 좁히는 게 이 step의 범위다.
- rethrow 시 새로운 예외(`new DuplicateKeyException(...)`)를 만들지 마라. 이유: catch 변수 `ex`를 그대로 `throw ex`로 던져야 원본 예외 정보(stack trace 포함)가 유지된다.
- 기존 테스트를 깨뜨리지 마라.
