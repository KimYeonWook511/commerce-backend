# Step 3: add-testcontainers-regression

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도와 테스트 컨벤션을 파악하라:

- `/docs/tasks/db-constraint-violation-handling/prd.md`
- `/docs/tasks/db-constraint-violation-handling/architecture.md`
- `/docs/testing-conventions.md`

테스트 컨벤션 공통 인프라:
- `src/test/java/com/commerce/support/TestcontainersSupport.java`
- `src/test/java/com/commerce/support/PersistenceTestSupport.java`
- `src/test/java/com/commerce/support/PersistenceCleanupTestSupport.java`

기존 Testcontainers 통합 테스트 패턴 참고:
- `src/test/java/com/commerce/member/infrastructure/MemberRepositoryJpaAdapterTest.java`
- `src/test/java/com/commerce/outbox/infrastructure/JpaProcessedEventRepositoryTest.java`

이전 step에서 변경된 파일:
- `src/main/java/com/commerce/common/exception/CommonErrorCode.java`
- `src/main/java/com/commerce/common/exception/GlobalExceptionHandler.java`

## 작업

### 목적

`DuplicateKeyException`이 DB/드라이버 의존적 매핑임을 Testcontainers로 실제 MySQL에서 검증한다.
이 테스트는 드라이버/스키마 변경 시 회귀 방어 역할을 한다.

### 테스트 파일 위치

기존 Repository 슬라이스 테스트 패턴을 따른다. 아래 두 파일 중 하나를 신규 생성하거나, 기존 파일에 Testcontainers 기반 테스트 클래스를 추가한다.

권장 위치 (기존 패턴 참고 후 프로젝트 관례에 맞게 결정):
- `src/test/java/com/commerce/member/infrastructure/` 또는
- `src/test/java/com/commerce/outbox/infrastructure/`

테스트 클래스는 `@Tag("docker")`로 표시하여 `dockerTest` 태스크로만 실행되게 한다 (CLAUDE.md 테스트 태그 분류 준수).

### 검증 시나리오

**검증 1 (필수)**: unique 위반 시 `DuplicateKeyException` 변환
- 실제 MySQL에 unique 제약이 있는 엔티티를 두 번 저장한다.
- 두 번째 저장 시 `DuplicateKeyException`이 발생하는지 확인한다.
- 도메인: `Member.email` unique 또는 `ProcessedEvent(eventId, consumerType)` unique 중 선택.

**검증 2 (선택)**: NOT NULL 위반은 `DuplicateKeyException`이 아님
- NOT NULL 컬럼에 null을 삽입한다.
- 발생하는 예외가 `DataIntegrityViolationException`이되 `DuplicateKeyException`이 아닌지 확인한다.
- 이를 통해 두 타입의 계층 관계를 실제 MySQL로 검증한다.

### 구현 가이드

기존 `@DataJpaTest` + `TestcontainersSupport` 조합을 따른다. `@SpringBootTest`는 사용하지 않는다.

```java
@Tag("docker")
@DataJpaTest
// 기존 프로젝트의 TestcontainersSupport 사용 방식을 따른다
class DuplicateKeyExceptionMappingIntegrationTest {

    // 검증 1: unique 위반 → DuplicateKeyException
    @Test
    void save_whenEmailDuplicated_throwDuplicateKeyException() { ... }

    // 검증 2 (선택): NOT NULL 위반은 DuplicateKeyException이 아님
    @Test
    void save_whenRequiredFieldIsNull_throwDataIntegrityViolationButNotDuplicateKey() { ... }
}
```

기존 테스트 인프라(`TestcontainersSupport`) 사용법은 위에서 읽은 기존 통합 테스트 파일을 참고한다.

## Acceptance Criteria

```bash
./gradlew dockerTest
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - 신규 테스트가 `@Tag("docker")`로 표시됐는지 확인한다.
   - `./gradlew test`(docker 제외)에서 신규 테스트가 실행되지 않는지 확인한다.
   - 기존 테스트가 깨지지 않았는지 확인한다.
3. 결과에 따라 step 상태를 갱신한다.

## 커밋 단위

1. `test: MySQL unique 위반 시 DuplicateKeyException 변환 회귀 테스트를 추가한다`

## 금지사항

- `@SpringBootTest`를 사용하지 마라. 이유: 전체 컨텍스트 로드로 느려진다. 프로젝트 테스트 컨벤션은 `@DataJpaTest`를 권장한다.
- 기존 테스트를 깨뜨리지 마라.
- `@Tag("docker")` 없이 테스트를 작성하지 마라. 이유: Docker 없는 환경에서 `./gradlew test`를 실행하면 실패한다.
