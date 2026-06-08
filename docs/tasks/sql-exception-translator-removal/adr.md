# Task ADR (staging)

> 이 파일은 이번 Task에서 **새로 채택된** 결정만 쌓는 staging 로그다.
> 루트 ADR을 복사해 오지 않는다. 여기 번호(L1, L2…)는 task 내 임시 번호이며,
> Stage 8(Root Sync)에서 루트 전역 번호(ADR-XXXX)로 다시 부여하며 루트에 append한다.
> 결정이 없으면 이 파일은 헤더만 두고 비워둔다.
> 탐색만 하고 채택하지 않은 안은 별도 레코드로 만들지 않고, 채택된 결정의 `고려한 대안`에 적는다.

---

## ADR-L1: `SQLErrorCodeSQLExceptionTranslator` 빈을 제거하고 제약 위반 식별을 `getConstraintName()` 기반으로 전환한다

- 상태: accepted
- supersedes: ADR-033 의 "제약명 식별" 메커니즘 (translator 유지 하 `SQLException` 메시지 매칭)
- superseded-by: 없음

### 배경

- `JpaConfig`의 `SQLErrorCodeSQLExceptionTranslator` 빈은 `db-constraint-violation-handling`에서 application의 `DuplicateKeyException` 직접 catch를 위해 등록됐으나, 그 catch 방식은 ADR-011(find-first)로 폐기됐다.
- 빈의 남은 정당화는 "운영 로그에서 unique 위반을 `DuplicateKeyException` 타입으로 구분"뿐이었다.
- 이 빈 때문에 unique 위반이 `DuplicateKeyException`(cause=JDBC `SQLException`)으로 변환되어, cause 체인에 Hibernate `ConstraintViolationException`이 남지 않는다(ADR-033). 그 결과 이중결제 식별(`PaymentRepositoryAdapter.isApprovedOrderKeyViolation`)이 구조적 API `getConstraintName()`을 쓰지 못하고 `SQLException` 메시지 정규식에 의존했다.

### 고려한 대안

- **빈 유지 + 문서화만(A)**: 현행 메시지 정규식을 유지하고 ADR로 "재검토 후 유지"만 기록. 실측상 `getConstraintName()`이 테이블 prefix를 포함(`tbl_payment.uk_payment_approved_order_key`)해 제거의 단순화 이점이 반감된다는 점이 유지 논거였다. 그러나 빈의 정당화가 거의 무가치(아래 근거)해 기각.
- **translator를 모든 adapter에 주입해 예외를 정규화**: adapter는 본래 구현체(JPA/Mybatis/JDBC)별로 작성되므로 구현체별 예외 처리 코드가 adapter마다 생긴다. JPA에만 translator를 주입해 Spring DAO 예외로 정규화하는 것은 추상화 이득이 없다. 기각.

### 결정 내용

- `JpaConfig`에서 `SQLErrorCodeSQLExceptionTranslator`(`jdbcExceptionTranslator`) 빈을 제거한다. `@Configuration`/`@EnableJpaAuditing`은 유지한다.
- 제거 후 unique 위반은 `DataIntegrityViolationException`(cause=Hibernate `ConstraintViolationException`(cause=`SQLException`))으로 올라온다.
- `isApprovedOrderKeyViolation`을 cause 체인의 Hibernate `ConstraintViolationException.getConstraintName()` 기반으로 전환한다. 실측상 값이 테이블 prefix를 포함하므로 **마지막 dot-세그먼트**를 `uk_payment_approved_order_key`와 비교한다(prefix·bare 양형 대응, null-safe). 식별 실패 시 false를 반환해 원 예외를 전파한다(보수적 원칙 보존).

### 근거

- **빈 정당화의 무가치**: 빈 유무와 무관하게 unique 위반은 (1) 같은 핸들러(`handleDataIntegrityViolationException`)·같은 error code(COMMON-500-1)로 분류되고, (2) `Duplicate entry ... for key 'tbl_payment.uk_...'` `SQLException` 메시지가 cause 체인에 동일하게 남는다. 빈이 더하는 것은 최상위 wrapper 클래스명(`DuplicateKeyException`) 하나뿐이며, error code로 필터 불가하다.
- **레이어 정합**: 제약명을 소비하는 `PaymentRepositoryAdapter`는 이미 JPA 전용 infra adapter다. adapter가 구현체(Hibernate)의 `getConstraintName()`에 의존하는 것은 자연스럽고, 오히려 JPA에만 translator를 끼워 Spring DAO 예외로 정규화하던 것이 부자연스러운 의존이었다.
- **견고성**: free-form `SQLException` 메시지 전체에 대한 자체 정규식보다, Hibernate가 dialect별로 파싱·유지하는 `getConstraintName()` 접근자가 메시지 포맷 변동에 더 견고하다.

### 결과

- 죽은 정당화의 config 빈이 제거되어 전역 예외 분류가 단순해진다.
- 제약 식별이 메시지 정규식에서 구조적 접근자로 전환된다(테이블 prefix 때문에 dot-세그먼트 추출은 남지만, free-form 메시지 의존은 해소).
- trade-off: unique 위반 스택트레이스의 최상위 예외 클래스명이 `DuplicateKeyException` → `DataIntegrityViolationException`으로 바뀐다. 운영 모니터링 영향은 error code(COMMON-500-1)·SQLException 메시지가 보존되므로 무시 가능하다. `getConstraintName()` 값이 dialect/버전에 따라 prefix 유무가 달라질 수 있어 dot-세그먼트 비교로 양형을 흡수한다.
- 연계: ADR-011, ADR-033, #225, #227, PR #226.
