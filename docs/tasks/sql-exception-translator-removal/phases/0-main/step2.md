# Step 2: simplify-constraint-identification

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/sql-exception-translator-removal/prd.md`
- `/docs/tasks/sql-exception-translator-removal/adr.md`
- `/src/main/java/com/commerce/payment/infrastructure/PaymentRepositoryAdapter.java`
- `/src/test/java/com/commerce/payment/infrastructure/PaymentRepositoryDuplicatePaymentTest.java`
- `/src/main/java/com/commerce/common/jpa/JpaConfig.java` (step1에서 빈이 제거된 상태)

Task 문서만으로 부족한 공통 맥락이 있으면 아래처럼 루트 문서를 추가로 읽는다.

- `/docs/adr.md` (ADR-011, ADR-033)

step1에서 `SQLErrorCodeSQLExceptionTranslator` 빈이 제거되어, unique 위반이 이제 cause 체인에 Hibernate `ConstraintViolationException`을 포함한다는 점을 이해한 뒤 작업하라.

## 작업

`PaymentRepositoryAdapter.isApprovedOrderKeyViolation`의 제약 식별을 `SQLException` 메시지 정규식에서 Hibernate `ConstraintViolationException.getConstraintName()` 기반으로 전환한다. **이중결제 식별 동작은 그대로 보존한다.**

### 현재 구현 (전환 대상)

- `APPROVED_ORDER_KEY_CONSTRAINT` `Pattern`(`\buk_payment_approved_order_key\b`, CASE_INSENSITIVE)으로 cause 체인의 `SQLException.getMessage()`를 매칭한다.

### 전환 후 구현

- cause 체인을 순회하며 `org.hibernate.exception.ConstraintViolationException`을 찾는다.
- 찾으면 `getConstraintName()`을 읽는다. 실측상 값은 테이블 prefix를 포함한다(`tbl_payment.uk_payment_approved_order_key`). 따라서 **마지막 `.` 이후 세그먼트**를 추출해 `uk_payment_approved_order_key`와 정확히 비교한다.
  - 마지막 dot-세그먼트 비교는 prefix가 있는 형태(`tbl_payment.uk_...`)와 없는 형태(`uk_...`) 양쪽을 모두 흡수한다(dialect/버전 차이 대비).
  - 비교 대상 상수 `uk_payment_approved_order_key`는 명명 상수로 둔다.
- `getConstraintName()`이 null이거나, cause 체인에 Hibernate `ConstraintViolationException`이 없으면 false를 반환해 원 예외를 그대로 전파한다(보수적 원칙 보존).
- 더 이상 쓰이지 않는 `Pattern`/정규식 import를 제거한다.
- 메서드/필드의 load-bearing 주석을 새 식별 방식(translator 제거 → `getConstraintName()` 사용, prefix 때문에 dot-세그먼트 추출)에 맞게 갱신한다. 기존 주석을 무의미하게 삭제하지 말고 현재 사실로 갱신한다.

`saveApproved`의 `catch (DataIntegrityViolationException ex)` 구조는 그대로 둔다(빈 제거 후에도 unique 위반은 `DataIntegrityViolationException`으로 잡힌다).

## Acceptance Criteria

```bash
./gradlew test integrationTest
```

repository 조회/식별 로직과 공통 예외 처리에 닿으므로 단위 테스트와 Testcontainers 통합 테스트를 모두 포함한다.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `PaymentRepositoryDuplicatePaymentTest`(integrationTest)의 세 테스트가 모두 통과하는가?
     - `saveApproved` + `uk_payment_approved_order_key` 위반 → `PaymentException(PAYMENT_DUPLICATE)` (보존)
     - `saveApproved` + 다른 unique 위반 → `DataIntegrityViolationException` 전파 (보존)
     - `save` + unique 위반 → `DataIntegrityViolationException` 전파 (보존)
   - 메시지 정규식(`Pattern`)이 완전히 제거됐는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 이중결제 식별 동작(어떤 제약이 `PAYMENT_DUPLICATE`로 매핑되고 어떤 게 전파되는지)을 바꾸지 마라. 이유: 동작 보존이 이 task의 범위 조건이다.
- `getConstraintName()` 값을 prefix 포함 전체 문자열과 하드코딩 비교하지 마라(예: `equals("tbl_payment.uk_...")`). 이유: 테이블 prefix는 dialect/버전 의존이라 깨지기 쉽다. 마지막 dot-세그먼트 비교로 양형을 흡수하라.
- 식별 실패 시 추측으로 `PAYMENT_DUPLICATE`를 던지지 마라. 이유: 다른 무결성 위반을 오매핑하면 안 된다. 보수적으로 false 반환 후 원 예외 전파.
- 루트 문서를 이 step에서 수정하지 마라. 이유: 루트 동기화는 Stage 8에서 수행한다.
- 기존 테스트를 깨뜨리지 마라.
