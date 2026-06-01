# Step 1: add-flyway-dependency

## 읽어야 할 파일

먼저 아래 파일들을 읽고 task의 의도와 제약을 파악하라:

- `/docs/tasks/flyway-introduction/prd.md`
- `/docs/tasks/flyway-introduction/architecture.md`
- `/docs/tasks/flyway-introduction/adr.md`
- `/docs/tasks/flyway-introduction/db-schema.md`
- `/build.gradle`

## 작업

`build.gradle`의 `dependencies` 블록에 Flyway 의존성을 추가한다.

추가 위치는 기존 의존성 블록 안에서 도메인 그룹 적절한 곳(예: mysql 의존성 인근)에 배치한다. 주석은 한 줄로 의도를 표시한다.

```gradle
// flyway
implementation 'org.flywaydb:flyway-core'
implementation 'org.flywaydb:flyway-mysql'   // MySQL 8 별도 모듈 (Flyway 10부터 분리)
```

버전은 Spring Boot 3.5.9 BOM이 관리한다. 명시 버전을 부여하지 않는다.

이후 의존성 해석이 두 모듈 모두 한 버전으로 정확히 이루어지는지 확인한다.

## Acceptance Criteria

```bash
./gradlew dependencyInsight --dependency flyway-core --configuration runtimeClasspath
./gradlew dependencyInsight --dependency flyway-mysql --configuration runtimeClasspath
```

두 명령 모두 정상 종료(exit 0)하고 출력에 다음이 모두 포함되어야 한다:
- `org.flywaydb:flyway-core:<version>` 한 줄 (단일 버전)
- `org.flywaydb:flyway-mysql:<version>` 한 줄 (단일 버전)
- 두 버전이 동일한 major.minor 라인에 속해야 한다 (Flyway 10.x)

```bash
./gradlew build -x test
```

빌드가 성공해야 한다. (테스트는 다음 step의 설정 변경 없이는 의미가 없으므로 제외)

## 검증 절차

1. 위 `dependencyInsight` 명령 두 개를 실행해 두 모듈이 모두 해석되는지 확인한다.
2. 만약 `flyway-mysql`이 "Could not resolve" 등으로 실패하면, Spring Boot 3.5 BOM이 이 모듈을 관리하지 않는 것이므로 `flyway-core`와 동일한 버전을 명시 부여한다 (예: `implementation 'org.flywaydb:flyway-mysql:10.x.y'`).
3. `./gradlew build -x test`로 컴파일/패키징이 정상인지 확인한다.
4. `application-*.yml`에 별도 `spring.flyway` 설정이 없는 상태이므로 이 시점에 부팅하면 Flyway가 기본 활성으로 동작하려 할 수 있다. 이 step에서는 부팅을 시도하지 않는다 (Step 3에서 일괄 처리).

## 금지사항

- 명시 버전을 함부로 부여하지 마라. 이유: Spring Boot BOM이 관리하면 BOM 우선이며, 명시 버전은 BOM과 어긋날 때만 의도적으로 부여한다. 해석 실패 시에만 명시 버전을 사용한다.
- `application-*.yml`을 수정하지 마라. 이유: 설정 변경은 Step 3에서 일괄 처리한다. 이 step의 범위는 의존성 추가 단독.
- `src/main/resources/db/migration/`을 만들거나 `V1__init.sql`을 작성하지 마라. 이유: V1 생성은 Step 2의 범위.
- 기존 의존성 줄을 옮기거나 정렬을 임의로 바꾸지 마라. 이유: 의도와 무관한 diff를 만든다.
