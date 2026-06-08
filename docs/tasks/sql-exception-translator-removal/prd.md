# Task PRD

> 이 문서는 이 기능의 **의도를 담은 정본**이며, 작업 종료 후 동결한다.
> 이후 다른 Task에서 이 결정이 바뀌면 이 문서를 거슬러 수정하지 않고,
> 아래 상태/포인터 한 줄만 추가한다. 본문은 당시 기록으로 보존한다.

- 상태: active
- 변경 포인터: 없음

---

## Task명

- `sql-exception-translator-removal`

## 배경

- `JpaConfig`의 `SQLErrorCodeSQLExceptionTranslator` 빈은 `db-constraint-violation-handling` task에서 application의 `DuplicateKeyException` 직접 catch를 위해 등록됐다. 그 catch 방식은 ADR-011(find-first 전환)로 폐기됐다.
- 남은 유일한 정당화(`exception-strategy.md` "JpaConfig 빈 등록 목적")는 "운영 로그에서 unique 위반을 `DuplicateKeyException` 타입으로 구분"이다.
- PR #226(#225)에서 이중결제 식별(`PaymentRepositoryAdapter.isApprovedOrderKeyViolation`)을 구현하며, 이 빈 때문에 unique 위반이 `DuplicateKeyException`(cause=JDBC `SQLException`)으로 변환되어 cause 체인에 Hibernate `ConstraintViolationException`이 남지 않음을 발견했다. 그래서 제약명을 구조적 API(`getConstraintName()`)가 아니라 `SQLException` 메시지 정규식 매칭으로 식별하고 있다(메시지 포맷 의존, 취약).
- #225 범위에서 분리해 #227로 남긴 후속 과제다.

## 목표

- 죽은 정당화의 `SQLErrorCodeSQLExceptionTranslator` 빈을 제거해 전역 예외 분류를 단순화한다.
- 제약 위반 식별을 `SQLException` 메시지 정규식에서 Hibernate `ConstraintViolationException.getConstraintName()` 기반으로 전환해 메시지 포맷 의존을 줄인다.

## 범위

- 포함 범위
  - `JpaConfig`에서 `SQLErrorCodeSQLExceptionTranslator` 빈 제거 (`@EnableJpaAuditing`은 유지).
  - `PaymentRepositoryAdapter.isApprovedOrderKeyViolation`을 `getConstraintName()` 기반으로 단순화.
  - 빈 제거로 깨지거나 의미가 바뀌는 테스트/주석 동기화.
  - 루트 문서 반영: `exception-strategy.md`, `adr.md`(ADR append). (Stage 8에서 수행)
- 제외 범위
  - 이중결제 식별 **동작 자체**의 변경. 현재 동작(=`uk_payment_approved_order_key` 위반 → `PaymentException(PAYMENT_DUPLICATE)`)은 그대로 보존한다.
  - `GlobalExceptionHandler` 핸들러 구조 변경. (unique 위반은 빈 유무와 무관하게 `handleDataIntegrityViolationException`/COMMON-500-1로 분류된다)
  - API/DB 스키마 변경.

## 주요 시나리오

- 같은 `orderId`의 두 번째 APPROVE+SUCCEEDED 결제 저장(`saveApproved`) 시 `uk_payment_approved_order_key` 위반 → `PaymentException(PAYMENT_DUPLICATE)`로 매핑(보존).
- 그 외 무결성 위반(`uk_payment_merchant_pay_key_provider_pg_payment_id_type` 등)은 원 예외(`DataIntegrityViolationException`)를 그대로 전파(보존).

## 요구사항

- 빈 제거 후 unique 위반은 `DataIntegrityViolationException`(cause=Hibernate `ConstraintViolationException`(cause=`SQLException`))으로 올라온다.
- 제약명 식별은 `getConstraintName()`을 사용하되, 실측상 값이 테이블 prefix를 포함(`tbl_payment.uk_payment_approved_order_key`)하므로 **마지막 dot-세그먼트**를 `uk_payment_approved_order_key`와 비교한다. prefix·bare 양형 모두 대응하고 null-safe하게 처리한다.
- 식별 실패(Hibernate `ConstraintViolationException` 부재, `getConstraintName()` null 등) 시 false를 반환해 원 예외를 그대로 전파한다(보수적 원칙 유지).
- `./gradlew test integrationTest` 통과.

## 제약사항

- `@EnableJpaAuditing`은 `JpaConfig`에 그대로 유지해야 한다(빈만 제거). 제거 시 `created_at`/`updated_at` auditing이 깨진다.
- 실측 근거: MySQL 8 Testcontainers에서 `getConstraintName()`은 `tbl_payment.uk_payment_approved_order_key`(prefix 포함)를 반환한다. SQLState=23000, errorCode=1062.
- 빈 제거의 운영 관측 영향: unique 위반 스택트레이스의 최상위 예외 클래스명이 `DuplicateKeyException` → `DataIntegrityViolationException`으로 바뀐다. error code(COMMON-500-1)와 SQLException의 `Duplicate entry ... for key ...` 메시지는 양쪽 동일하게 보존된다.
