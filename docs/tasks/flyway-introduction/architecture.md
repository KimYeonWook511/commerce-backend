# 태스크 아키텍처

## 개요

JPA `ddl-auto: update` 기반 암묵적 스키마 관리를 명시적 마이그레이션 도구(Flyway)로 전환한다. 부팅 시점에 Flyway가 `db/migration/V*__*.sql`을 순서대로 적용하고, 이후 Hibernate가 `validate`로 엔티티 ↔ 스키마 일치를 검사한다. 두 검사 어느 단계에서든 실패하면 부팅 자체가 차단되어 silent drift 가능성을 제거한다.

## 변경 대상

- **Build**: `build.gradle` 의존성에 `flyway-core`, `flyway-mysql` 추가
- **Resources**:
  - `src/main/resources/db/migration/V1__init.sql` (신규)
  - `src/main/resources/application-local.yml` — ddl-auto, flyway 섹션
  - `src/main/resources/application-prod.yml` — ddl-auto, flyway 섹션, 기존 주석 제거
  - `src/main/resources/application-test.yml` — flyway.enabled: false 명시
- **Test support**: `src/test/java/com/commerce/support/TestcontainersSupport.java` — `registerMySql()` 내 ddl-auto override 변경, flyway.enabled true override 추가
- **Docs**:
  - `docs/ADR.md` — ADR-024 신규 추가
  - `docs/db-schema.md` — 마이그레이션 위치/네이밍 안내 추가

## 설계 방향

### 프로파일별 적용 매트릭스

| 프로파일/환경 | DB | ddl-auto | Flyway | 비고 |
|---|---|---|---|---|
| `local` | docker MySQL 8 | `validate` | 활성 | 운영과 동일 흐름 |
| `prod` | MySQL 8 | `validate` | 활성 (clean-disabled) | 운영 안전망 명시 |
| `test` | H2 인메모리 | `create-drop` | 비활성 | 단위/슬라이스 테스트 부팅 속도 자산 보호 |
| dockerTest (Testcontainers MySQL) | MySQL 8 컨테이너 | `validate` | 활성 (override) | `@ActiveProfiles("test")`이지만 dynamic property로 override |

### test 프로파일과 dockerTest 충돌 해소

dockerTest 클래스들은 `@ActiveProfiles("test")`를 그대로 사용한다. `application-test.yml`에 `spring.flyway.enabled: false`를 추가하면 dockerTest에서도 Flyway가 꺼진 채 시작되므로, `TestcontainersSupport.registerMySql()`에서 `spring.flyway.enabled: true`를 명시적으로 override 한다. 단순 가시성이 아니라 동작 보장을 위해 필수.

### Spring Batch 메타테이블

Flyway 관리 대상에서 제외하고 Spring Batch 자체 `initialize-schema: always` 메커니즘을 유지한다. Spring Batch 버전업 시 메타테이블 스키마 변경 책임을 우리 쪽으로 옮겨오지 않기 위함. V1__init.sql 생성 단계에서도 일시적으로 `initialize-schema: never`로 부팅해 Batch 메타테이블이 dump에 섞이지 않도록 한다.

## 데이터 흐름

### 부팅 시점

```
JVM 시작
  → Spring Boot context load
  → Flyway auto-configure (의존성 감지)
    → flyway_schema_history 테이블 조회/생성
    → V*__*.sql 적용 순서 결정
    → 미적용 마이그레이션 실행
  → Hibernate EntityManagerFactory 초기화
    → ddl-auto: validate → 엔티티 ↔ 실제 스키마 비교
    → 불일치 시 SchemaManagementException → 부팅 실패
  → Spring Batch initialize-schema (메타테이블 없으면 생성)
  → ApplicationContext ready
```

### V1__init.sql 생성 시점 (Step 2)

```
mysql-data-local/ 삭제 (destructive — 사용자 사전 확인)
  → docker compose up -d mysql (빈 MySQL 컨테이너)
  → ./gradlew bootRun (임시 override)
    SPRING_FLYWAY_ENABLED=false
    SPRING_JPA_HIBERNATE_DDL_AUTO=create
    SPRING_BATCH_JDBC_INITIALIZE_SCHEMA=never
    → Hibernate가 엔티티 12개 → DDL → MySQL 적용
  → 부팅 안정 후 종료
  → docker exec mysqldump --no-data --skip-comments commerce_db
  → dump 정리 (10개 체크리스트)
  → src/main/resources/db/migration/V1__init.sql 저장
```

### dockerTest 시점

```
첫 docker 테스트 클래스 실행
  → TestcontainersSupport.registerMySql() 호출
    → static 싱글톤 MySQL 컨테이너 start
    → DynamicPropertyRegistry로 jdbc-url/user/pwd + ddl-auto: validate + flyway.enabled: true 설정
  → Spring 컨텍스트 시작
    → Flyway가 컨테이너에 V1 적용 (flyway_schema_history 생성 + V1__init.sql 실행)
    → Hibernate validate 통과
  → @AfterEach마다 PersistenceCleanupTestSupport.deleteAllInBatch() 호출 (기존 격리 모델)

두 번째 docker 테스트 클래스 실행 (같은 컨텍스트 캐시 재사용)
  → 같은 컨테이너, 같은 스키마
  → Flyway는 schema_history만 보고 "up to date" skip (drop 없음)
  → @AfterEach 격리 그대로
```

## 예외 및 실패 처리

- **Flyway 적용 실패**: 마이그레이션 스크립트 SQL 오류, 무결성 위반 등. 부팅 차단. 운영에서는 알람.
- **Hibernate validate 실패**: 엔티티-스키마 불일치. 부팅 차단. 가장 흔한 시나리오는 엔티티 변경 후 마이그레이션 스크립트 누락.
- **Flyway clean 호출 시도**: `clean-disabled: true`로 거부. 운영 사고 차단 안전망.
- **dockerTest에서 Flyway 비활성으로 시작될 위험**: `application-test.yml`의 `spring.flyway.enabled: false`가 dockerTest 클래스에도 적용되는 위험. `TestcontainersSupport`에서 `true`로 override해 해소.

## 테스트 포인트

- 빈 MySQL에서 `./gradlew bootRun`이 Flyway V1 적용 후 정상 부팅한다.
- 이미 V1 적용된 DB에서 재부팅 시 "up to date" 로그 후 정상 부팅한다.
- 엔티티에 임의 필드 추가 후 부팅 시 `SchemaManagementException`으로 실패한다.
- `./gradlew test` (H2 + Flyway 비활성) 전체 통과한다.
- `./gradlew dockerTest` (Testcontainers MySQL + Flyway 활성)에서 첫 컨텍스트는 V1 적용 로그가, 이후 컨텍스트는 "up to date" 로그가 찍히고 전체 통과한다.
- Spring Batch 메타테이블이 V1__init.sql에 포함되지 않고, Batch job 통합 테스트는 정상 동작한다.
