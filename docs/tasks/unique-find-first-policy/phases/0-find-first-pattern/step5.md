# Step 5: integration-tests-update

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/unique-find-first-policy/prd.md`
- `/docs/tasks/unique-find-first-policy/architecture.md`
- `/docs/tasks/unique-find-first-policy/adr.md`
- step 0~4 에서 변경한 모든 코드와 단위 테스트
- `/src/test/java/com/commerce/common/jpa/UniqueConstraintViolationIntegrationTest.java` (또는 `DuplicateKeyExceptionMappingTest.java` — 정확한 파일명은 PR #106 에서 추가된 것을 따른다)
- `/src/test/java/com/commerce/payment/naverpay/application/NaverPayServiceIntegrationTest.java`
- `/src/main/java/com/commerce/common/jpa/JpaConfig.java`
- `/docs/testing-conventions.md`

step 0~4 가 모두 끝나 있어야 한다.

## 작업

본 step 은 통합 테스트를 새 정책에 맞춰 갱신한다. Testcontainers 회귀 방어 테스트와 `NaverPayServiceIntegrationTest` 두 가지가 영향을 받는다.

### 1. Testcontainers 회귀 방어 테스트 갱신

PR #106 에서 추가한 `UniqueConstraintViolationIntegrationTest` (또는 `DuplicateKeyExceptionMappingTest`) 는 "Application 이 `DuplicateKeyException` 을 받는다" 가정으로 작성되어 있다. 새 정책에서는 Application 이 인프라 예외를 catch 하지 않으므로 시나리오를 갱신한다.

다음 중 한 가지 방향으로 갱신:

#### 방향 A — 안전망 도달 검증으로 전환

- Application 호출(예: `memberRepository.save` 또는 Adapter 직접 호출) 시 unique 위반이 발생하면 `DuplicateKeyException` 이 발생하는지 검증한다 (Testcontainers + MySQL 환경에서 빈 등록이 정상 동작하는지 확인하는 회귀 방어).
- 추가로 MVC layer 통합 테스트(`@SpringBootTest` 또는 `MockMvc`)로 race window 시나리오를 시뮬레이션해 안전망 핸들러가 500 + `COMMON-500-1` 응답을 반환하는지 검증해도 된다 (가능한 범위 내에서).

#### 방향 B — 핵심 회귀 방어만 유지

- `SQLErrorCodeSQLExceptionTranslator` 빈이 동작해 unique 위반이 `DuplicateKeyException` 으로 정확히 변환되는지만 검증하고, 안전망 도달은 별도 mocking 단위 테스트로 분리한다.

두 방향 모두 본 step 의 핵심은 **"빈 등록이 동작하고 안전망이 500 으로 응답한다"는 회귀 방어**다. 방향 A 가 더 end-to-end 이지만 setup 비용이 크면 방향 B 로 좁힌다.

### 2. `NaverPayServiceIntegrationTest` 의 spy 스텁 제거

라인 82 의 `@MockitoSpyBean PaymentAttemptService paymentAttemptService;` 와 본문 안의 `Mockito.doReturn(...)` 스텁(예: 라인 444-446) 은 PR #106 에서 H2 + JPA 환경의 `DuplicateKeyException` 미발생 문제를 우회하기 위해 추가됐다.

step 2 에서 `PaymentAttemptService` 가 find-first 패턴으로 리팩토링됐으므로 H2 환경에서도 정상 흐름이 통과된다. spy 제거 가능 여부를 검증한다:

1. spy 어노테이션을 제거하고 스텁 호출을 제거한 뒤 테스트를 실행한다.
2. 실패하면 실패 원인을 분석한다. spy 제거가 본질적으로 불가능하다면 (예: H2 의 다른 한계, 다른 테스트 의도) spy 를 그대로 유지하되 이유를 코드 주석으로 명시한다.
3. spy 제거에 성공하면 관련 import 와 mock setup 도 함께 정리한다.

### 3. 통합 테스트 카테고리

- Testcontainers 사용 테스트는 `@Tag("docker")` 가 붙어 있어 `./gradlew dockerTest` 로만 실행된다 (`docs/testing-conventions.md`).
- 통합 테스트 갱신 시 기존 태그 정책을 그대로 따른다.

## Acceptance Criteria

```bash
./gradlew test
./gradlew dockerTest
```

## 검증 절차

1. 위 두 Acceptance Criteria 커맨드를 순서대로 실행한다.
2. 아래를 확인한다.
   - `UniqueConstraintViolationIntegrationTest` (또는 동등 테스트) 가 새 정책에 맞게 갱신되어 통과하는가?
   - `NaverPayServiceIntegrationTest` 가 spy 제거(혹은 유지 이유 명시) 와 함께 통과하는가?
   - `SQLErrorCodeSQLExceptionTranslator` 빈 등록이 운영 환경에서 안전망 정확도를 유지하는지 회귀 방어 시나리오로 검증되는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `JpaConfig.java` 의 `SQLErrorCodeSQLExceptionTranslator` 빈 정의를 변경하거나 제거하지 마라. 이유: 안전망 정확도의 핵심이며 PR #106 결정대로 유지된다.
- step 2 의 find-first 리팩토링을 본 step 에서 재수정하지 마라. 이유: 본 step 은 통합 테스트 갱신에만 집중한다.
- H2 환경 한계를 우회하기 위해 새 spy / mock 을 추가하지 마라. 이유: spy 제거가 본 step 의 검증 포인트 중 하나다. spy 가 필요하면 그 이유를 명확히 문서화한다.
- 기존 단위 테스트(step 0~4 에서 갱신한 것) 를 깨뜨리지 마라.
