# 회고록: sql-exception-translator-removal

## 1. 작업 요약

`JpaConfig` 의 `SQLErrorCodeSQLExceptionTranslator` 빈을 제거하고, 이중결제 제약 식별(`PaymentRepositoryAdapter.isApprovedOrderKeyViolation`)을 `SQLException` 메시지 정규식에서 Hibernate `ConstraintViolationException.getConstraintName()` 기반으로 전환했다. 이중결제 식별 **동작 자체는 보존**했다. 빈 제거로 unique 위반은 `DuplicateKeyException` 이 아니라 `DataIntegrityViolationException`(cause=Hibernate `ConstraintViolationException`) 으로 올라온다. 깨지는 테스트(`DuplicateKeyExceptionMappingTest`)는 새 계약을 단언하는 회귀 가드(`UniqueViolationExceptionShapeTest`)로 갱신했다.

목적: ADR-011(find-first)로 폐기된 빈의 죽은 정당화를 제거해 전역 예외 분류를 단순화하고, 제약 식별의 free-form 메시지 의존을 줄인다 (#227, ADR-034).

---

## 2. 결정한 정책 (ADR-034)

- 빈을 제거한다(`@EnableJpaAuditing` 은 유지).
- 제약명은 Hibernate `getConstraintName()` 의 마지막 `.` 이후 세그먼트를 `uk_payment_approved_order_key` 와 비교한다. null-safe, 식별 실패 시 false 반환(원 예외 전파).

핵심 근거: 제약명을 소비하는 `PaymentRepositoryAdapter` 는 이미 JPA 전용 infra adapter다. adapter 는 본래 구현체(JPA/Mybatis/JDBC)별로 작성되므로 JPA 에만 translator 를 끼워 Spring DAO 예외로 정규화하는 것은 추상화 이득이 없었다. adapter 가 Hibernate API 에 의존하는 편이 레이어상 자연스럽다.

---

## 3. 주요 발견 및 논의

### 실측: `getConstraintName()` 은 테이블 prefix 를 포함한다

방향 결정 전, translator 빈 부재 시 `getConstraintName()` 이 무엇을 반환하는지 일회성 통합 테스트로 실측했다. `EntityManager.flush()` 로 Spring Data repository 프록시의 translation 레이어를 우회해 Hibernate 원본 예외를 관찰했다.

- 결과: MySQL 8 Testcontainers 에서 `getConstraintName()` = `tbl_payment.uk_payment_approved_order_key` (**테이블 prefix 포함**). SQLState=23000, errorCode=1062.
- 함의: bare name `equals` 비교라는 "깔끔한 단순화" 는 불가능하다. 마지막 dot-세그먼트 추출이 여전히 필요해 단순화 효과가 반감된다. 이 사실을 알고 방향(B)을 택했고, dot-세그먼트 비교로 prefix·bare 양형을 모두 흡수하도록 설계했다(dialect/버전 차이 대비).

### 빈의 "남은 정당화" 는 거의 무가치였다

이슈가 지목한 유일한 정당화("운영 로그에서 unique 위반을 `DuplicateKeyException` 타입으로 구분")를 검증한 결과, 빈 유무와 무관하게 (1) 같은 핸들러(`handleDataIntegrityViolationException`)·같은 error code(`COMMON-500-1`) 로 분류되고, (2) `Duplicate entry ... for key 'tbl_payment.uk_...'` `SQLException` 메시지가 cause 체인에 동일하게 남는다. 빈이 더하는 것은 최상위 wrapper 클래스명 하나뿐이며 error code 로 필터 불가하다.

### PR review 로 식별 비교 방식을 dot-세그먼트 → 전체 제약명으로 변경

초안 구현은 `getConstraintName()` 의 마지막 dot-세그먼트를 `uk_payment_approved_order_key` 와 `equals` 비교했다. Gemini review 가 (1) `equalsIgnoreCase` 로 대소문자 비구분, (2) cause 체인 끝까지 순회를 제안했다. 논의 결과 `tbl_payment.uk_payment_approved_order_key` 전체 이름을 `equalsIgnoreCase` 로 비교하고 체인을 끝까지 순회하는 형태로 단순화했다. trade-off: 식별이 MySQL 반환 형태(테이블 prefix 포함)에 결합된다. 형식이 바뀌면 통합 테스트(`PaymentRepositoryDuplicatePaymentTest`)가 회귀를 잡는다는 점이 안전망이다. dot-세그먼트 추출이 prefix/bare 양형에 더 견고했으나, 현재 단일 환경(MySQL)에서는 전체 비교의 단순함을 택했다.

### blast radius 가 좁았다

프로덕션 코드는 `DuplicateKeyException` 을 catch/사용하지 않았다(ADR-011 로 이미 제거). 실제로 `DuplicateKeyException` 을 단언하는 테스트는 1개(`DuplicateKeyExceptionMappingTest`)뿐이었고 나머지 2곳은 주석이었다. `saveApproved` 의 `catch (DataIntegrityViolationException ex)` 는 빈 유무와 무관하게 유효(하위 타입 흡수).

### auditing 보존 주의

`JpaConfig` 는 빈 외에 `@EnableJpaAuditing` 도 갖는다. 빈만 제거하고 어노테이션은 유지해야 한다. 실측 실험 초기에 auditing 없는 설정으로 테스트하다 `created_at cannot be null` 로 실패한 것이 이 의존을 드러냈다.

---

## 4. 변경 범위 정리

| 파일 | 변경 내용 |
|---|---|
| `JpaConfig.java` | `SQLErrorCodeSQLExceptionTranslator` 빈 + 관련 import 제거 (`@EnableJpaAuditing` 유지) |
| `PaymentRepositoryAdapter.java` | `isApprovedOrderKeyViolation` 을 `getConstraintName()` 마지막 dot-세그먼트 비교로 전환, `Pattern` 제거 |
| `DuplicateKeyExceptionMappingTest.java` → `UniqueViolationExceptionShapeTest.java` | rename + `DataIntegrityViolationException`(cause=`ConstraintViolationException`) 계약 단언 |
| `MemberRepositoryJpaAdapterTest.java`, `JpaProcessedEventRepositoryTest.java` | MySQL 동작 주석을 `DataIntegrityViolationException` 기준으로 동기화 |
| `docs/adr.md` | ADR-034 append (ADR-033 제약명 식별 메커니즘 supersede) |
| `docs/exception-strategy.md` | 안전망 diagram + "JpaConfig 빈 등록 목적" 섹션 갱신 |

---

## 5. 미결 과제

- 없음. 이중결제 식별 동작은 보존됐고, `./gradlew test integrationTest` 통과.

---

## 6. 회고

### 잘된 점

- 방향을 정하기 전에 핵심 불확실성(`getConstraintName()` 의 prefix 여부)을 일회성 통합 테스트로 먼저 실측했다. "translator 제거 = 깔끔한 bare name" 이라는 가설이 틀렸음을 결정 전에 확인해, 헛된 단순화 기대 없이 dot-세그먼트 비교로 현실적인 설계를 택할 수 있었다.
- 빈의 "남은 정당화" 를 말로 받아들이지 않고 핸들러·error code·cause 체인을 실제로 대조해 무가치함을 확인했다. 덕분에 제거 결정의 근거가 분명했다.
- blast radius 를 먼저 조사(프로덕션 catch 없음, 단언 테스트 1개)해 작은 변경임을 확인하고 진행했다.

### 개선할 점

- 실측 실험에서 auditing 의존을 처음에 빠뜨려 `created_at` NOT NULL 위반으로 한 번 실패했다. `JpaConfig` 의 `@EnableJpaAuditing` ↔ translator 빈이 한 클래스에 묶여 있다는 점을 먼저 인지했다면 우회 설정을 한 번에 맞출 수 있었다.
- `getConstraintName()` 의 prefix 형태는 dialect/버전 의존이다. 현재 dot-세그먼트 비교로 흡수하지만, MySQL 외 환경으로 확장하거나 Hibernate 메이저 업그레이드 시 회귀 테스트(`PaymentRepositoryDuplicatePaymentTest`)로 재확인이 필요하다.
