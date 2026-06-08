# Step 1: remove-translator-bean

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/sql-exception-translator-removal/prd.md`
- `/docs/tasks/sql-exception-translator-removal/adr.md`
- `/src/main/java/com/commerce/common/jpa/JpaConfig.java`
- `/src/test/java/com/commerce/member/infrastructure/DuplicateKeyExceptionMappingTest.java`
- `/src/test/java/com/commerce/member/infrastructure/MemberRepositoryJpaAdapterTest.java`
- `/src/test/java/com/commerce/outbox/infrastructure/JpaProcessedEventRepositoryTest.java`

Task 문서만으로 부족한 공통 맥락이 있으면 아래처럼 루트 문서를 추가로 읽는다.

- `/docs/exception-strategy.md`
- `/docs/adr.md` (ADR-011, ADR-033)

## 작업

이 step의 목적은 죽은 정당화의 `SQLErrorCodeSQLExceptionTranslator` 빈을 제거하고, 그로 인해 깨지거나 의미가 바뀌는 테스트/주석을 동기화하는 것이다. 이 step에서는 `PaymentRepositoryAdapter`는 건드리지 않는다(step2 담당).

### 배경 사실 (실측 근거)

빈을 제거하면 MySQL 환경에서 unique 위반은 다음 형태로 올라온다.

```
org.springframework.dao.DataIntegrityViolationException
  cause = org.hibernate.exception.ConstraintViolationException  (getConstraintName() = "tbl_payment.uk_payment_approved_order_key")
    cause = java.sql.SQLIntegrityConstraintViolationException  (SQLState=23000, errorCode=1062, msg="Duplicate entry ... for key 'tbl_payment.uk_...'")
```

빈이 있을 때는 최상위가 `DuplicateKeyException`(= `DataIntegrityViolationException`의 하위)이고 cause 체인에 Hibernate `ConstraintViolationException`이 없었다.

### 1) `JpaConfig` 빈 제거

`src/main/java/com/commerce/common/jpa/JpaConfig.java`:

- `jdbcExceptionTranslator(DataSource)` 빈 메서드와 그에 딸린 주석(빈 등록 목적 설명)을 제거한다.
- 더 이상 쓰이지 않는 import(`SQLErrorCodeSQLExceptionTranslator`, `SQLExceptionTranslator`, `javax.sql.DataSource`)를 제거한다.
- `@Configuration`과 `@EnableJpaAuditing`은 **반드시 유지**한다. 이유: 제거하면 `created_at`/`updated_at` JPA auditing이 동작하지 않는다.
- 기존 주석 처리된 `auditorProvider` 블록은 그대로 둔다(이번 변경 범위 아님).

### 2) `DuplicateKeyExceptionMappingTest` 갱신

`src/test/java/com/commerce/member/infrastructure/DuplicateKeyExceptionMappingTest.java`:

- 이 테스트는 빈이 있을 때 member email unique 위반이 `DuplicateKeyException`으로 변환됨을 검증했다. 빈 제거 후에는 `DataIntegrityViolationException`(cause=Hibernate `ConstraintViolationException`)으로 온다.
- 테스트를 새 계약의 회귀 가드로 갱신한다:
  - 단언을 `DuplicateKeyException` → `org.springframework.dao.DataIntegrityViolationException`으로 바꾼다.
  - cause 체인에 `org.hibernate.exception.ConstraintViolationException`이 존재함을 추가로 단언한다.
- 클래스명이 더 이상 동작과 맞지 않으므로 의미에 맞게 rename한다(예: `UniqueViolationExceptionShapeTest`). 파일명도 함께 변경한다. `@DisplayName`/테스트 메서드명도 새 계약을 반영해 갱신한다.
- `@Import(JpaConfig.class)`는 그대로 유지한다(auditing 필요). `@Tag("docker")`, `@DataJpaTest`, Testcontainers 설정도 유지한다.

### 3) 주석 동기화

아래 두 곳의 주석은 "실제 MySQL 환경에서는 `DuplicateKeyException`이 발생함"이라고 적혀 있다. 빈 제거 후 사실과 맞도록 "`DataIntegrityViolationException`이 발생함"으로 갱신한다.

- `src/test/java/com/commerce/member/infrastructure/MemberRepositoryJpaAdapterTest.java` (해당 주석 줄)
- `src/test/java/com/commerce/outbox/infrastructure/JpaProcessedEventRepositoryTest.java` (해당 주석 줄)

주석이 가리키던 보완 테스트 이름(`UniqueConstraintViolationIntegrationTest`)이 현재 코드에 존재하지 않으면, 실제 존재하는 회귀 테스트를 가리키도록 함께 정리하거나 해당 참조를 제거한다(억지로 만들지 말 것).

## Acceptance Criteria

```bash
./gradlew test integrationTest
```

이 step은 JPA auditing 및 공통 예외 분류에 영향을 주므로 단위 테스트와 Testcontainers 통합 테스트를 모두 포함한다.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `JpaConfig`에 `@EnableJpaAuditing`이 남아 있는가? (auditing 회귀 방지)
   - `PaymentRepositoryDuplicatePaymentTest`(integrationTest)가 그대로 통과하는가? (이 step에서 adapter는 미변경이며, cause 체인에 `SQLException`이 남아 기존 메시지 매칭이 여전히 동작해야 한다)
   - 갱신한 `DuplicateKeyExceptionMappingTest`(rename 후)가 새 계약을 단언하는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `JpaConfig`의 `@EnableJpaAuditing`을 제거하지 마라. 이유: `created_at`/`updated_at` auditing이 깨져 INSERT가 NOT NULL 위반으로 실패한다.
- `PaymentRepositoryAdapter`를 이 step에서 수정하지 마라. 이유: 제약 식별 단순화는 step2의 책임이며, 목적이 다른 변경을 같은 커밋에 섞지 않는다.
- 이중결제 식별 동작을 바꾸지 마라. 이유: 동작 보존이 이 task의 범위 조건이다.
- 루트 문서(`exception-strategy.md`, `adr.md`)를 이 step에서 수정하지 마라. 이유: 루트 동기화는 Stage 8에서 수행한다.
- 기존 테스트를 깨뜨리지 마라.
