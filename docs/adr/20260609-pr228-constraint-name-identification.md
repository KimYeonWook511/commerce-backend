# `SQLErrorCodeSQLExceptionTranslator` 빈을 제거하고 제약 위반 식별을 `getConstraintName()` 기반으로 전환한다

- Status: accepted
- Date: 2026-06-09

## Context

이 결정은 이중결제 탐지를 adapter 도메인 예외 매핑으로 전환한 기존 결정(→ PR#226)의 "제약명 식별" 메커니즘(translator 유지 하 `SQLException` 메시지 단어 경계 매칭)을 대체한다. 그 결정의 carve-out 자체(adapter 도메인 예외 매핑·fail-first 보상)는 유효하다.

#227. 이 빈은 `db-constraint-violation-handling`에서 application의 `DuplicateKeyException` 직접 catch를 위해 등록됐으나 그 catch는 find-first 결정(→ PR#109)으로 폐기됐다. 남은 정당화는 "운영 로그에서 unique 위반을 `DuplicateKeyException` 타입으로 구분"뿐이었다.

고려한 대안: (A) 빈 유지 + 문서화만 — 실측상 `getConstraintName()`이 테이블 prefix를 포함(`tbl_payment.uk_payment_approved_order_key`)해 단순화 이점이 반감된다는 점이 유지 논거였으나 빈 정당화가 거의 무가치해 기각. (B) translator를 모든 adapter에 주입해 예외 정규화 — adapter는 본래 구현체(JPA/Mybatis/JDBC)별로 작성되므로 JPA에만 translator를 끼워 Spring DAO 예외로 정규화하는 것은 추상화 이득이 없어 기각.

빈 유무와 무관하게 unique 위반은 같은 핸들러(`handleDataIntegrityViolationException`)·같은 error code(`COMMON-500-1`)로 분류되고 `Duplicate entry ... for key 'tbl_payment.uk_...'` `SQLException` 메시지가 cause 체인에 동일하게 남는다. 빈이 더하는 것은 최상위 wrapper 클래스명(`DuplicateKeyException`) 하나뿐이며 error code로 필터 불가하다. 제약명을 소비하는 `PaymentRepositoryAdapter`는 이미 JPA 전용 infra adapter라 Hibernate `getConstraintName()` 의존이 자연스럽다. free-form 메시지 자체 정규식보다 Hibernate가 dialect별로 파싱·유지하는 접근자가 메시지 포맷 변동에 견고하다.

## Decision

`JpaConfig`에서 `SQLErrorCodeSQLExceptionTranslator`(`jdbcExceptionTranslator`) 빈을 제거한다(`@EnableJpaAuditing`은 유지). 제거 후 unique 위반은 `DataIntegrityViolationException`(cause=Hibernate `ConstraintViolationException`(cause=`SQLException`))으로 올라온다. `PaymentRepositoryAdapter.isApprovedOrderKeyViolation`은 cause 체인을 순회하며 Hibernate `ConstraintViolationException.getConstraintName()` 값을 MySQL 반환 형태인 `tbl_payment.uk_payment_approved_order_key`와 대소문자 무시(`equalsIgnoreCase`) 비교한다. 일치 제약을 찾지 못하면 false를 반환해 원 예외를 전파한다(보수적 원칙 보존). 이중결제 식별 동작 자체는 보존된다.

## Consequences

죽은 정당화의 config 빈이 제거되어 전역 예외 분류가 단순해진다. 제약 식별이 메시지 정규식에서 구조적 접근자로 전환된다(테이블 prefix 때문에 dot-세그먼트 추출은 남지만 free-form 메시지 의존은 해소). trade-off: unique 위반 스택트레이스 최상위 클래스명이 `DuplicateKeyException` → `DataIntegrityViolationException`으로 바뀌나 error code와 SQLException 메시지는 보존되어 운영 영향은 무시 가능하다. 제약명 비교는 MySQL 반환 형태(`tbl_payment.` prefix 포함)에 결합되며 대소문자는 `equalsIgnoreCase`로 흡수한다. 형식이 바뀌면 통합 테스트(`PaymentRepositoryDuplicatePaymentTest`)가 회귀를 잡는다.

연계: find-first 패턴 결정(→ PR#109), adapter 도메인 예외 매핑 결정(→ PR#226), #225, #227, PR #228.
