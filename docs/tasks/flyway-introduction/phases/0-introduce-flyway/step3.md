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

```bash
# (a) yml 변경 grep 검증
grep -q 'ddl-auto: validate' src/main/resources/application-local.yml
grep -q 'ddl-auto: validate' src/main/resources/application-prod.yml
grep -q 'create-drop' src/main/resources/application-test.yml
! grep -q '추후 DB 마이그레이션 학습' src/main/resources/application-prod.yml

# (b) flyway 섹션
grep -qE '^[[:space:]]+flyway:' src/main/resources/application-local.yml
grep -qE '^[[:space:]]+flyway:' src/main/resources/application-prod.yml
grep -q 'clean-disabled: true' src/main/resources/application-local.yml
grep -q 'clean-disabled: true' src/main/resources/application-prod.yml
grep -q 'enabled: false' src/main/resources/application-test.yml

# (c) TestcontainersSupport
grep -q 'spring.flyway.enabled' src/test/java/com/commerce/support/TestcontainersSupport.java
grep -q '"validate"' src/test/java/com/commerce/support/TestcontainersSupport.java
! grep -q '"create-drop"' src/test/java/com/commerce/support/TestcontainersSupport.java

# (d) 빈 MySQL에 부팅하면 Flyway가 V1 적용 후 정상 부팅
rm -rf mysql-data-local
docker compose -f docker-compose.local.yml up -d mysql
for i in {1..30}; do
  if docker exec commerce-mysql-local mysqladmin ping -h localhost -uroot -proot0511 --silent 2>/dev/null; then
    break
  fi
  sleep 2
done
./gradlew bootRun --args='--spring.profiles.active=local' > /tmp/step3-bootrun-fresh.log 2>&1 &
BOOTRUN_PID=$!
timeout 180 bash -c 'until grep -q "Started CommerceApplication" /tmp/step3-bootrun-fresh.log 2>/dev/null; do sleep 2; done'
kill $BOOTRUN_PID 2>/dev/null || true
wait $BOOTRUN_PID 2>/dev/null || true
grep -qE 'Migrating schema .* to version "1' /tmp/step3-bootrun-fresh.log
grep -q 'Started CommerceApplication' /tmp/step3-bootrun-fresh.log

# (e) 재부팅 시 "up to date"
./gradlew bootRun --args='--spring.profiles.active=local' > /tmp/step3-bootrun-reboot.log 2>&1 &
BOOTRUN_PID=$!
timeout 180 bash -c 'until grep -q "Started CommerceApplication" /tmp/step3-bootrun-reboot.log 2>/dev/null; do sleep 2; done'
kill $BOOTRUN_PID 2>/dev/null || true
wait $BOOTRUN_PID 2>/dev/null || true
grep -qE 'Schema .* (is up to date|already initialized)' /tmp/step3-bootrun-reboot.log

# (f) 단위/슬라이스 테스트 (H2, Flyway 비활성)
./gradlew test

# (g) Testcontainers MySQL + Flyway
./gradlew dockerTest
```

위 모든 명령이 exit 0이어야 한다.

## 검증 절차

1. yml 3개와 TestcontainersSupport 변경 후 (a)~(c)로 텍스트 변경 확인.
2. (d) 빈 DB 부팅 — Flyway가 V1을 적용했다는 로그(`Migrating schema ... to version "1"` 또는 `Successfully applied 1 migration to schema`)를 명시적으로 확인.
3. (e) 재부팅 — 동일 명령이 "up to date" 또는 "already initialized" 로그를 찍고 부팅 성공.
4. (f) `./gradlew test` 통과 — H2 인메모리 + Flyway 비활성이 정상 동작하는지.
5. (g) `./gradlew dockerTest` 통과 — Testcontainers MySQL + Flyway 활성이 정상 동작하는지. dockerTest 로그 파일(`build/reports/tests/dockerTest/`)을 보고 Flyway 적용 로그 확인.

## 금지사항

- `ddl-auto: validate` 외의 다른 값(none, update 등)으로 바꾸지 마라. 이유: 본 task의 핵심 결정.
- `spring.flyway.baseline-on-migrate: true`를 추가하지 마라. 이유: 운영 DB가 없으므로 baseline-on-migrate는 불필요하고 추후 운영 DB에서 의도하지 않은 baseline을 만들 수 있다.
- `application-test.yml`의 ddl-auto를 `validate`로 바꾸지 마라. 이유: H2는 V1__init.sql(MySQL 문법)을 적용할 수 없다. H2 + create-drop은 단위/슬라이스 테스트 부팅 속도 자산.
- `TestcontainersSupport`에서 `flyway.enabled: false` override를 두지 마라. 이유: dockerTest에서 Flyway 적용이 본 task의 검증 가치.
- 부팅 검증을 위해 `application-*.yml`에 임시 설정을 박지 마라. 이유: env var 또는 `--args`로 override한다.
- 빈 MySQL 부팅 검증을 위해 다른 worktree의 mysql-data-local을 건드리지 마라. 이유: worktree 격리.
- 임시 로그 파일(`/tmp/step3-bootrun-*.log`)을 커밋하지 마라.
