# Step 3: enable-flyway-and-switch-validate

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설정 변경 의도와 부팅 흐름 변화를 정확히 파악하라:

- `/docs/tasks/flyway-introduction/prd.md`
- `/docs/tasks/flyway-introduction/architecture.md` ← 프로파일별 적용 매트릭스
- `/src/main/resources/application-local.yml`
- `/src/main/resources/application-prod.yml`
- `/src/main/resources/application-test.yml`
- `/src/test/java/com/commerce/support/TestcontainersSupport.java`
- `/src/main/resources/db/migration/V1__init.sql` (Step 2 결과)
- `/build.gradle` (Step 1 결과)

## 작업

### 변경 1: `src/main/resources/application-local.yml`

- `spring.jpa.hibernate.ddl-auto: update` → `validate`
- `spring` 아래에 `flyway` 섹션 추가:
  ```yaml
  flyway:
    enabled: true
    locations: classpath:db/migration
    validate-on-migrate: true
    clean-disabled: true
  ```

### 변경 2: `src/main/resources/application-prod.yml`

- `spring.jpa.hibernate.ddl-auto: update` → `validate`
- 기존 줄 옆 주석 `# 추후 DB 마이그레이션 학습 후 validate로 변경할 것 (운영 서버는 명시적 DB 마이그레이션 하기)` 제거
- `spring` 아래에 `flyway` 섹션 추가 (local과 동일 내용):
  ```yaml
  flyway:
    enabled: true
    locations: classpath:db/migration
    validate-on-migrate: true
    clean-disabled: true
  ```

### 변경 3: `src/main/resources/application-test.yml`

- ddl-auto 유지 (`create-drop`)
- `spring` 아래에 `flyway.enabled: false` 추가 (H2 + Flyway 비활성, 단위/슬라이스 테스트 보호):
  ```yaml
  flyway:
    enabled: false
  ```

### 변경 4: `src/test/java/com/commerce/support/TestcontainersSupport.java`

`registerMySql(DynamicPropertyRegistry registry)` 메서드 내 마지막 줄 부근(현재 `registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");`):

- ddl-auto override 값을 `"create-drop"` → `"validate"`로 변경
- 바로 아래 줄에 `registry.add("spring.flyway.enabled", () -> true);` 추가

변경 의도 주석을 한 줄로 추가한다 (예: `// dockerTest는 Flyway가 V1 적용 — test profile의 flyway.enabled: false override 무효화`).

## Acceptance Criteria

AC executor는 한 줄 명령만 안정적으로 실행한다. 복잡한 부팅 시나리오는 "검증 절차" 섹션에서 사람이 수동으로 수행한다. AC에는 텍스트 변경 검증 + 자동 테스트만 둔다.

```bash
grep -q 'ddl-auto: validate' src/main/resources/application-local.yml
grep -q 'ddl-auto: validate' src/main/resources/application-prod.yml
grep -q 'create-drop' src/main/resources/application-test.yml
! grep -q '추후 DB 마이그레이션 학습' src/main/resources/application-prod.yml
grep -qE '^[[:space:]]+flyway:' src/main/resources/application-local.yml
grep -qE '^[[:space:]]+flyway:' src/main/resources/application-prod.yml
grep -q 'clean-disabled: true' src/main/resources/application-local.yml
grep -q 'clean-disabled: true' src/main/resources/application-prod.yml
grep -q 'enabled: false' src/main/resources/application-test.yml
grep -q 'spring.flyway.enabled' src/test/java/com/commerce/support/TestcontainersSupport.java
grep -q '"validate"' src/test/java/com/commerce/support/TestcontainersSupport.java
! grep -q '"create-drop"' src/test/java/com/commerce/support/TestcontainersSupport.java
./gradlew test
./gradlew dockerTest
```

위 모든 명령이 exit 0이어야 한다. `./gradlew dockerTest`가 통과한다는 것은 Testcontainers MySQL에 Flyway가 V1을 적용해 schema를 만들고 ddl-auto: validate가 통과했다는 것이므로, 부팅 + Flyway 동작은 dockerTest로 자동 검증된다.

## 검증 절차

### 자동 검증 (AC)
위 grep 명령과 `./gradlew test`, `./gradlew dockerTest`로 자동 검증한다.

### 수동 검증 (사용자가 PR 이전에 실행 권장)

다음은 worker가 자동 실행하지 않는다. AC executor가 multi-line 셸 블록을 지원하지 않고, bootRun 백그라운드 + 로그 polling 패턴이 한 줄로 표현하기 어렵기 때문이다. PR review 또는 머지 전 사용자가 직접 수행한다.

1. **빈 DB 부팅 검증**
   ```bash
   rm -rf mysql-data-local
   docker compose -f docker-compose.local.yml up -d mysql
   # MySQL ready 대기 후
   ./gradlew bootRun --args='--spring.profiles.active=local'
   ```
   기대 로그:
   - `Migrating schema ... to version "1 - init"` 또는 `Successfully applied 1 migration to schema "commerce_db"`
   - `Started CommerceApplication`
   - `flyway_schema_history` 테이블 생성

2. **재부팅 — "up to date" 확인**
   ```bash
   # 위 부팅을 Ctrl+C로 종료한 뒤
   ./gradlew bootRun --args='--spring.profiles.active=local'
   ```
   기대 로그: `Schema "commerce_db" is up to date. No migration necessary.` 또는 동등 메시지.

3. **의도적 불일치 시나리오 (선택)**
   - 엔티티에 임시 필드 추가 → bootRun → `SchemaManagementException` 류로 실패하는지 → 변경 되돌림.

4. `./gradlew dockerTest` 로그 확인 (`build/reports/tests/dockerTest/`에서 첫 컨텍스트 Flyway 적용 로그 + 이후 컨텍스트 "up to date" 로그).

## 금지사항

- `ddl-auto: validate` 외의 다른 값(none, update 등)으로 바꾸지 마라. 이유: 본 task의 핵심 결정.
- `spring.flyway.baseline-on-migrate: true`를 추가하지 마라. 이유: 운영 DB가 없으므로 baseline-on-migrate는 불필요하고 추후 운영 DB에서 의도하지 않은 baseline을 만들 수 있다.
- `application-test.yml`의 ddl-auto를 `validate`로 바꾸지 마라. 이유: H2는 V1__init.sql(MySQL 문법)을 적용할 수 없다. H2 + create-drop은 단위/슬라이스 테스트 부팅 속도 자산.
- `TestcontainersSupport`에서 `flyway.enabled: false` override를 두지 마라. 이유: dockerTest에서 Flyway 적용이 본 task의 검증 가치.
- 부팅 검증을 위해 `application-*.yml`에 임시 설정을 박지 마라. 이유: env var 또는 `--args`로 override한다.
- 빈 MySQL 부팅 검증을 위해 다른 worktree의 mysql-data-local을 건드리지 마라. 이유: worktree 격리.
- 임시 로그 파일(`/tmp/step3-bootrun-*.log`)을 커밋하지 마라.
