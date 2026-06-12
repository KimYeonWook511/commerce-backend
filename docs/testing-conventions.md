# 테스트 컨벤션

## 핵심 원칙 (요약)

> 코딩 시 반드시 지킬 보편 원칙이다. 동시성 테스트·TestSupport·태그 등 상세는 아래 본문이 단일 출처다.

- **항상 테스트 동반**: 기능 추가 시 테스트 작성, 동작 수정 시 관련 테스트 갱신. 명시 지시 없으면 생략 금지.
- **레이어별 종류**: Domain=단위(Mock 금지, 순수 객체). Application=단위(Repository·Port만 Mock)+통합 병행. Presentation=`@WebMvcTest` 슬라이스(Service는 `@MockitoBean`). Infrastructure=`@DataJpaTest` 슬라이스+통합. `@SpringBootTest`(전체 컨텍스트)는 지양.
- **우선순위**: 단위·슬라이스를 두텁게, 통합은 핵심 경로만(느리고 비쌈).
- **Mock**: `@ExtendWith(MockitoExtension.class)` + BDD 스타일(`given`/`then().should()`). Spring 빈 Mock은 `@MockitoBean`/`@MockitoSpyBean`(구 `@MockBean`/`@SpyBean` 금지).
- **네이밍**: 영어 메서드명 `행위_조건_결과` + 한국어 `@DisplayName`. Infra 테스트 클래스명은 구현체(Adapter)명 따름.
- **구조**: 모든 테스트 Given-When-Then. 한 테스트 한 행위. AssertJ `assertThat` 체이닝, 예외는 `assertThatThrownBy`.
- **격리**: 테스트 간 영향 없게. 통합은 `@Transactional` 금지 + `tearDown` 삭제. 슬라이스는 `@Transactional` 롤백. 비동기 검증은 Awaitility.
- **동시성**: 타이밍을 운/`Thread.sleep`/latch속도맞추기에 의존 금지. 불변식 단언 또는 결정론적 통제만. race 무대 컴포넌트는 진짜를 쓰고 결과를 stub으로 강제하지 않는다. 낙관 락 충돌은 winner를 단언하지 말고 불변식("성공 전이 정확히 1건" 등)으로 검증 — 충돌 정책 의미는 `docs/optimistic-lock-design.md`.

---

## 기본 원칙

- 새로운 기능을 추가하면 반드시 테스트를 함께 작성한다.
- 기존 동작을 수정하면 관련 테스트를 함께 갱신한다.
- 명시적으로 지시받지 않았다면 테스트를 생략하지 않는다.
- `@SpringBootTest`는 전체 컨텍스트를 로드하므로 사용을 지양하고, 목적에 맞는 슬라이스 테스트나 단위 테스트를 우선 사용한다.

---

## 테스트 패키지 구조

테스트 패키지는 **프로덕션 패키지 구조를 미러링**한다(`application/{usecase,service}`, `infrastructure/{persistence,cache,...}` 등). 아래는 구조 예시이며, 실제 클래스 목록은 코드가 단일 출처다.

```
src/test/java/
└── com/commerce/
    ├── support/                                          ← 공통 인프라
    │   ├── TestcontainersSupport.java                    ← 컨테이너 싱글턴 관리
    │   ├── PersistenceTestSupport.java                   ← 도메인별 cleanup 계약 (interface)
    │   ├── PersistenceCleanupTestSupport.java            ← FK 순서대로 정렬 후 일괄 삭제
    │   └── CleanupOrder.java                             ← FK 안전 삭제 순서 (enum)
    │
    ├── architecture/                                     ← 구조 규칙 검증 (도메인 무관, 전체 검사)
    │   └── ArchitectureRulesTest.java                    ← ArchUnit (규칙 상세는 architecture.md)
    │
    └── order/
        ├── domain/
        │   └── OrderTest.java                            ← 단위 테스트
        ├── application/
        │   ├── usecase/                                  ← orchestrator(흐름/정책) 테스트
        │   │   ├── CreateOrderServiceTest.java           ← 단위 (흐름·정책)
        │   │   └── CreateOrderServiceIntegrationTest.java ← 통합
        │   └── service/                                  ← tx 단위작업 테스트
        │       └── PaymentTransitionServiceTest.java     ← 단위
        ├── presentation/
        │   └── OrderControllerTest.java                  ← 슬라이스 테스트
        └── infrastructure/
            ├── persistence/
            │   ├── support/
            │   │   └── OrderPersistenceTestSupport.java  ← 도메인별 삭제 + 테스트 데이터 헬퍼
            │   └── OrderRepositoryJpaAdapterTest.java    ← 슬라이스 or 통합 테스트
            └── cache/
                └── RedisOrderIdempotencyStoreTest.java   ← 통합 테스트
```

> 범용 helper(예: `support/`의 `OptimisticRetry`)의 테스트는 프로덕션 위치를 따라 `support/` 아래 둔다.

---

## 테스트 종류

| 종류 | 도구 | 특징 |
|---|---|---|
| 단위 테스트 | JUnit5, Mockito | 외부 의존성 없음, 가장 빠름 |
| 슬라이스 테스트 | `@WebMvcTest`, `@DataJpaTest` | 특정 레이어 빈만 로드, 중간 속도 |
| 통합 테스트 | Testcontainers | 실제 외부 시스템 연동, 가장 느림 |

---

## 레이어별 테스트 전략

### Domain Layer — 단위 테스트

- 외부 의존성 없음, Mock 없음, 순수 자바 객체만 사용
- 도메인 로직 자체만 검증
- 가장 빠르고 가장 많아야 한다

### Application Layer — 단위 테스트 + 통합 테스트 병행

**단위 테스트 (Mockito)**
- Repository, Port 등 모든 외부 의존성은 Mock으로 대체
- 흐름(오케스트레이션) 검증에 집중
- "이 순서대로 호출되는가", "캐시 HIT 시 도메인 로직을 실행하지 않는가" 등

**통합 테스트 (Testcontainers)**
- 실제 DB, 실제 Redis를 띄워서 검증
- 단위 테스트로 커버되지 않는 인프라 연계 검증에 집중

```
통합 테스트 검증 대상
├── 트랜잭션 롤백 시 DB가 실제로 롤백되는가
├── AFTER_COMMIT 이후 Redis에 실제로 저장되는가 (향후 @TransactionalEventListener 도입 시 적용)
└── 예외 발생 시 트랜잭션이 의도대로 처리되는가
```

#### PgCanceller functional interface Mock 패턴

`@FunctionalInterface`를 Mockito로 Mock할 때는 일반 인터페이스와 동일하게 처리한다.

```java
@Mock PgCanceller pgCanceller;

// stub 예시
given(pgCanceller.cancel(any(), any())).willReturn(CancelOutcome.success());
given(pgCanceller.cancel(any(), eq("취소 이유"))).willReturn(CancelOutcome.failed(failCode, "실패 상세"));

// 호출 여부 검증
then(pgCanceller).should().cancel(eq(cancelPayment), eq("취소 이유"));
then(pgCanceller).should(never()).cancel(any(), any());
```

### Presentation Layer — 슬라이스 테스트 (`@WebMvcTest`)

- Controller 관련 빈만 로드, Service는 `@MockitoBean`으로 대체
- HTTP 요청/응답 검증에 집중

```
검증 대상
├── 요청 URL, HTTP Method 매핑
├── 요청/응답 직렬화
├── 유효성 검증 (@Valid)
└── 예외 발생 시 HTTP 상태 코드
```

### Infrastructure Layer — 슬라이스 테스트 + 통합 테스트 병행

**슬라이스 테스트 (`@DataJpaTest`)**
- JPA 관련 빈만 로드, H2 사용
- DB 방언에 무관한 검증에 집중

```
검증 대상
├── 연관관계 매핑
├── 영속성 컨텍스트 동작 (Lazy/Eager Loading)
├── JPQL, QueryDSL 단순 쿼리
└── Spring Data JPA 메서드 네이밍 쿼리
```

**통합 테스트 (Testcontainers)**
- 실제 DB 고유 특성에 의존하는 검증에 집중

```
검증 대상
├── 인덱스, 실행 계획
├── 동시성 — 비관 락(FOR UPDATE), 격리 수준, 데드락 (실제 DB 필요)
├── DB 전용 문법/타입 (JSON 컬럼, 전용 함수 등)
├── Flyway/Liquibase 마이그레이션
└── Redis TTL, 직렬화/역직렬화
```

> 낙관 락(`@Version`)은 JPA 레벨 동작이라 슬라이스(H2)에서도 충돌 발생/버전 증가를 검증할 수 있다.
> 비관 락(`FOR UPDATE`)·격리 수준·데드락은 실제 DB 스케줄러에 의존하므로 통합(Testcontainers)으로 검증한다.
> 단, 충돌 정책(skip/retry/전파)이 application 흐름에서 의도대로 갈리는지는 application 동시성 테스트가 본다(아래 동시성 규칙·`docs/optimistic-lock-design.md`).

---

## 테스트 우선순위

```
1순위 — Domain 단위 테스트                (비즈니스 로직 검증, 빠름)
2순위 — Application 단위 테스트           (흐름 검증, 빠름)
3순위 — Presentation 슬라이스 테스트      (HTTP 요청/응답 검증, 중간)
4순위 — Infrastructure 슬라이스 테스트    (JPA 매핑/쿼리 검증, 중간)
5순위 — Application 통합 테스트           (트랜잭션/이벤트 연계 검증, 느림)
6순위 — Infrastructure 통합 테스트        (실제 DB 고유 특성 검증, 느림)
```

통합 테스트는 비용이 크므로 핵심 경로 위주로만 작성하고, 비즈니스 로직 검증은 반드시 단위 테스트로 커버한다.

---

## TestSupport 사용 규칙

통합 테스트는 세 가지 문제를 해결해야 한다.

### 1. 컨테이너 비용 — `TestcontainersSupport`

테스트 클래스마다 컨테이너를 새로 띄우면 속도가 크게 저하된다. `TestcontainersSupport`가 JVM 전체에서 컨테이너를 한 번만 띄우고 공유한다. 각 테스트는 `@DynamicPropertySource`에서 필요한 컨테이너만 등록한다.

```java
@DynamicPropertySource
static void registerProperties(DynamicPropertyRegistry registry) {
    TestcontainersSupport.registerMySql(registry);
    TestcontainersSupport.registerRedis(registry);
}
```

### 2. 테스트 격리 — `PersistenceCleanupTestSupport`

통합 테스트에서 `@Transactional`을 사용하면 커밋이 발생하지 않아 `AFTER_COMMIT` 동작 검증이 불가능하다. 따라서 각 테스트가 `tearDown`에서 직접 데이터를 삭제한다. (`@TransactionalEventListener` 도입 시에도 동일 원칙 적용)

```
테스트 클래스의 tearDown()
  └── PersistenceCleanupTestSupport.deleteAllInBatch(support1, support2, ...)
        └── CleanupOrder 값으로 정렬
              └── XxxPersistenceTestSupport.deleteAllInBatch() × 순서대로
                    └── 도메인 내부 FK 순서대로 테이블 삭제
```

`CleanupOrder` enum이 도메인 간 FK 안전 삭제 순서를 정의한다.

```
PAYMENT(10) → OUTBOX(20) → ORDER(30) → STOCK(40) → PRODUCT(50) → MEMBER(60)
```

테스트는 사용한 도메인의 support만 `tearDown`에 선언하면 되고, 삭제 순서는 신경 쓰지 않아도 된다.

### 3. 구현체 교체 시 테스트 보호 — `XxxPersistenceTestSupport`

`XxxPersistenceTestSupport`는 두 가지 역할을 한다.

**도메인 내부 FK 처리** — 도메인 안의 FK 순서를 캡슐화하여 테스트가 직접 알 필요 없게 한다.

**테스트 데이터 헬퍼** — 도메인 Repository 인터페이스는 도메인 의도 기준 메서드만 있어 테스트 조작이 부족하다. `XxxPersistenceTestSupport`가 JPA Repository를 직접 감싸 테스트용 헬퍼를 제공한다.

```java
orderPersistence.save(order)
orderPersistence.saveAndFlush(order)
orderPersistence.findById(id)
orderPersistence.count()
```

실제 테스트는 구현체가 아닌 `XxxPersistenceTestSupport`만 바라보므로 JPA → MyBatis 교체 시에도 테스트 코드 변경이 없다.

### 새 도메인 추가 시 체크리스트

```
1. {domain}/infrastructure/persistence/support/ 아래에 XxxPersistenceTestSupport 생성
2. PersistenceTestSupport 인터페이스 구현
3. CleanupOrder enum에 FK 순서에 맞게 추가
```

### 베이스 클래스 미도입 사유

슬라이스/통합 테스트 양쪽에서 공통 베이스 클래스를 도입하지 않는다.

- 각 테스트가 `@DynamicPropertySource` 로 필요한 컨테이너(MySQL/Redis/Kafka)만 명시 선언한다
- 공통 베이스 클래스로 통합하면 (a) 모든 통합 테스트가 모든 컨테이너에 묶이거나 (b) 컨테이너 조합별 분기 베이스 클래스 트리가 생긴다 — 둘 다 의존 범위가 흐려진다
- 명시 선언 패턴이 각 테스트의 의존 범위를 한눈에 드러내고, 변경 영향 추적이 쉽다
- `TestcontainersSupport` 의 정적 메서드 (`registerMySql`, `registerRedis`, `registerKafka`) 호출로 반복 비용은 충분히 낮다

---

## 태그 규칙

`@Tag`로 테스트를 분류하고, `build.gradle`에서 태그별로 실행을 분리한다. 태그는 **두 축**으로 구성된다.

**환경 요구 축** — 어떤 환경이 있어야 돌릴 수 있는가
- `docker`: Testcontainers (MySQL/Redis/Kafka) 가 필요한 테스트
- `sandbox`: 외부 sandbox API 가 필요한 테스트

**격리 분류 축** — 어떤 격리 그룹에 속하는가
- `concurrency`: 동시성 / 데드락 테스트
- `batch`: Spring Batch 통합 테스트
- `learning`: 학습/디버그용 테스트 (`@Disabled` 와 함께 사용)

한 클래스에 두 축의 태그를 동시에 부여할 수 있다 (예: `docker + concurrency`). 두 축은 독립이라 한 클래스가 양쪽에 값을 가질 수 있다.

| 태그 | 축 | 부여 대상 | 실행 명령 |
|---|---|---|---|
| `docker` | 환경 | Testcontainers 사용 | `./gradlew integrationTest` |
| `sandbox` | 환경 | 외부 sandbox API | `./gradlew sandboxTest` (수동) |
| `concurrency` | 격리 | 동시성 / 데드락 | `./gradlew concurrencyTest` (수동) |
| `batch` | 격리 | Spring Batch 통합 | `./gradlew batchTest` |
| `learning` | 격리 | 학습/디버그 (`@Disabled`) | 실행 안 됨 |

### task 정의 (disjoint)

한 클래스는 정확히 0 또는 1개 task 에서 실행되도록 `includeTags`/`excludeTags` 가 구성된다.

| task | includeTags | excludeTags |
|---|---|---|
| `test` | — | docker, sandbox, concurrency, batch, learning |
| `integrationTest` | docker | concurrency, batch, sandbox, learning |
| `concurrencyTest` | concurrency | learning |
| `batchTest` | batch | learning |
| `sandboxTest` | sandbox | concurrency, batch, learning |

기본 `test` 태스크는 단위/슬라이스 테스트만 실행한다 (도커 불필요, 가장 빠름).

### CI 자동 검증 범위

CI 는 다음 두 잡을 병렬로 실행한다.

- `unit-slice`: `./gradlew test`
- `integration`: `./gradlew integrationTest batchTest`

`concurrencyTest`, `sandboxTest` 는 CI 미포함 (수동 실행 정책 유지).

`docker + concurrency` 등 격리 축 태그가 함께 부여된 클래스는 격리 task 쪽으로만 매칭되므로 환경 축이 docker 라도 자동 검증에서 빠진다. 동시성/배치/sandbox 관련 코드를 변경하면 영향 범위에 맞춰 수동으로 `./gradlew concurrencyTest` / `./gradlew sandboxTest` 를 직접 실행해 검증한다.

`sandboxTest` 는 외부 PG sandbox 에 실제 API 호출이 발생하므로 동시성 검증보다 더 강한 사전 의도 확인이 필요하다. 새 sandbox 클래스 작성 시 `@Tag("sandbox")` 와 함께 `@EnabledIfEnvironmentVariable` 류의 JUnit 조건 어노테이션으로 필수 환경변수 부재 시 자동 skip 되도록 둔다 — 빌드 task 자체 차단이 약해도 클래스 레벨에서 한 번 더 안전망을 둔다.

---

## 학습용 테스트 격리 정책

학습/실험 목적의 테스트 코드는 운영 검증 대상이 아니지만, 회고와 향후 의사결정 참고용으로 보존한다. 다음 규칙을 따른다.

- `@Tag("learning")` + `@Disabled("학습용 — {간단한 회고 문맥}. 운영 검증 대상 아님")` 부여
- `@Disabled` 메시지에 무엇을 학습/실험했는지, 결론이 무엇이었는지 한 줄로 기록한다 (코드 자체가 회고 문서 역할을 겸함)
- 도커가 필요한 학습용 테스트는 `docker` tag 도 함께 부여한다 (다른 docker 사용 클래스와의 일관성)
- 모든 Test task 의 `excludeTags` 에 `learning` 이 포함되어 있어 `@Disabled` 와 이중 안전망이 된다

학습용 도메인(예: 비동기/락 학습용 엔티티 등, 실제 목록은 코드가 출처)은 별도 모듈로 분리하지 않고 `src/test` 위치를 유지한다. 운영 코드 리팩토링 시 IDE 가 컴파일 깨짐을 알려주는 안전망 역할을 한다.

---

## Schema 생성 실패 감지 (`halt_on_error`)

`hibernate.hbm2ddl.halt_on_error: true`를 `application-local.yml`에만 적용한다.

- **효과**: `ddl-auto`의 schema 생성/alter 실패가 silent로 넘어가지 않고 부팅 단계에서 즉시 실패하여 회귀를 노출한다.
- **적용 환경**: `local`
- **적용 제외 환경**:
  - `test` — Testcontainer fresh MySQL 부팅 시 `ALTER TABLE ... DROP FOREIGN KEY ...`가 `IF EXISTS` 없이 실행되어 무해 실패가 발생하고 `halt_on_error`와 충돌하므로 제외. test 환경의 schema 회귀 감지는 결제 동시성 통합 테스트의 데이터 invariant(예: 동시 승인 후 `성공한 결제 = 정확히 1건`)로 대체한다(해당 테스트 위치는 코드가 단일 출처).
  - `prod` — 운영 미가동 + 추후 Flyway 도입과 함께 `ddl-auto: validate`로 전환되면 의미가 사라진다.
- **Fragility**: local의 `halt_on_error` 적용은 `ddl-auto: update` 전제에 의존한다. local ddl-auto가 `create-drop`/`create`로 변경되면 같은 ALTER FK DROP 충돌이 재발하므로 `halt_on_error` 적용 여부를 함께 재검토해야 한다.

---

## 테스트 네이밍 규칙

영어 메서드명 + 한국어 `@DisplayName` 조합을 사용한다. IDE/터미널 깨짐 이슈가 없고, 영문 코드베이스와 일관성을 유지하면서 `@DisplayName`으로 가독성을 확보한다.

```java
@Test
@DisplayName("주문 생성 시 재고가 없으면 예외를 던진다")
void createOrder_whenOutOfStock_throwsException()

@Test
@DisplayName("동일한 idempotencyKey로 두 번 요청하면 같은 결과를 반환한다")
void createOrder_withSameIdempotencyKey_returnsSameResult()
```

메서드명은 `행위_조건_결과` 형식을 따른다.

```
createOrder_whenOutOfStock_throwsException
   행위        조건               결과
```

Infrastructure 테스트 클래스명은 구현체(Adapter) 이름을 따른다.

```
OrderRepositoryJpaAdapterTest      (O)
OrderRepositoryMyBatisAdapterTest  (O)
```

---

## Given - When - Then 구조

모든 테스트는 Given - When - Then 구조로 작성한다.

```java
// Given — 테스트 준비
// When  — 행위 실행
// Then  — 결과 검증
```

---

## Mock 사용 원칙

- **Domain 테스트** — Mock 사용 금지, 순수 자바 객체만 사용
- **Application 단위 테스트** — 외부 의존성(Repository, Port)만 Mock 처리
- **Application 통합 테스트** — Mock 사용 금지, Testcontainers로 실제 환경 사용
- **Presentation 슬라이스 테스트** — Service만 `@MockitoBean` 처리
- **Infrastructure 테스트** — Mock 사용 금지, H2 또는 Testcontainers 사용
- Mock은 `@ExtendWith(MockitoExtension.class)` 기반으로 사용한다.
- Mockito는 BDD 스타일(`BDDMockito.given()`, `BDDMockito.then().should()`)을 사용한다.

### Spring 빈 Mock 어노테이션

Spring Boot 3.4 부터 `@MockBean` / `@SpyBean` 이 deprecated 되었고 `@MockitoBean` / `@MockitoSpyBean` 으로 대체되었다.

- 슬라이스/통합 테스트에서 Spring 빈을 Mock 처리할 때 `@MockitoBean` / `@MockitoSpyBean` 을 사용한다
- `@MockBean` / `@SpyBean` (Boot 자체 어노테이션) 은 사용하지 않는다
- 단위 테스트의 순수 Mockito (`@Mock`, `@InjectMocks`) 는 종전과 동일하게 사용한다

---

## 테스트 격리 원칙

- 각 테스트는 서로 영향을 주지 않아야 한다.
- 통합 테스트 — `@Transactional` 사용 금지, `XxxPersistenceTestSupport`의 `tearDown`으로 데이터 삭제
- JPA 슬라이스 테스트 — `@Transactional`로 매 테스트 후 데이터 롤백
- MyBatis 테스트 — `@Sql`로 매 테스트 전후 데이터 초기화
- Redis 테스트 — 매 테스트 전후 `flushAll()` 또는 테스트별 고유 키 사용
- 비동기 동작 검증은 Awaitility(`await().atMost(...).untilAsserted(...)`)를 사용한다.

---

## 검증 원칙

- 하나의 테스트에서 하나의 행위만 검증한다.
- `assertThat` 체이닝으로 가독성 확보 (AssertJ 사용)
- 예외 검증은 `assertThatThrownBy` 사용

---

## 동시성 테스트 작성 규칙

**핵심 원칙: 타이밍을 운에 맡기지 않는다.**

방법은 두 갈래다.

- **결과를 인터리빙과 무관하게 만든다** (패턴 1, "제거")
- **인터리빙을 결정론적으로 확정한다** (패턴 2, "통제")

자연적인 경쟁 상태(race)에 의존하는 것(`Thread.sleep`으로 타이밍 맞추기, latch로 속도 맞추기, "그냥 동시에 돌렸을 때 어쩌다 일어난 일을 단언")은 안티패턴이다.

### 허용 패턴

**1. 불변식 단언 패턴 (기본)** — 광범위 race 검증

- **검증하려는 race가 일어나는 컴포넌트는 진짜를 쓴다.** DB, 락, 트랜잭션 경계, application service의 분기 — 그 race가 거기서 발생한다면 stub하지 않는다. stub하는 순간 race의 무대가 사라져 검증이 무의미해진다.
- **그 race와 무관한 외부 의존만 mock한다.** 보통 외부 서드파티(PG gateway 등). idempotency store 같은 인프라도 race 검증 대상이 아니면 mock 가능. 판단 기준은 "이 컴포넌트가 race의 무대인가?"이지 컴포넌트 종류가 아니다.
- **mock이 race를 *관찰*하면 OK, race의 결과를 *대신 결정*하면 금지.** `willAnswer` 안에서 `AtomicInteger`로 호출 횟수를 세는 건 외부 호출의 *관찰*이라 OK. `doReturn(false).when(service).hasStock()`은 race 무대의 결과를 가짜로 정하는 거라 금지. 같은 `willAnswer`라도 관찰이냐 결정이냐로 갈린다.
- **mock 응답은 thread-safe하게 작성한다.** 외부 상태(`AtomicInteger` 등)를 참조하면 동시 호출 안전성을 직접 챙긴다.
- N thread 동시 진입, 모든 종료 후 **인터리빙 무관 불변식**만 단언한다.
- 예: "재고는 음수 안 됨", "성공은 정확히 1건", "결제 완료 시 PG cancel 0회".
- 낙관 락(`@Version`) 충돌도 같은 패턴으로 검증한다 — 같은 행을 동시 전이할 때 누가 이기는지는 비결정론적이므로 winner를 단언하지 말고, "성공 전이는 정확히 1건", "충돌 시 보상의 마킹은 skip되되 PG 환불은 계속(끊기지 않음)" 같은 **불변식**을 단언한다. 충돌 정책(skip/retry/전파)의 의미는 `docs/optimistic-lock-design.md`가 단일 출처.
- `CountDownLatch`는 "타이밍 맞추기" 가 아니라 "모든 thread 동시 시작 / 종료 대기" 동기화 장치로만 쓴다.

**2. 결정론적 제어 패턴 (예외적)** — 특정 race 시나리오를 회귀로 박제

- 알려진 race 버그를 잠그는 용도에만 사용한다.
- **응답을 강제하지 않는다.** 응답은 진짜 컴포넌트가 결정하고, 외부 통제로는 **순서/타이밍만** 확정한다.
- 도구:
  - `@MockitoSpyBean` + `willAnswer`에서 `callRealMethod()`를 호출하면서 latch로 timing만 통제
  - 트랜잭션 수동 begin/commit으로 commit 순서 확정
- **결과 단언의 한계**: 데드락 / 락 타임아웃처럼 DB 스케줄러에 결과가 달린 경우, 어느 thread가 winner가 될지 결정론적으로 단언할 수 없다. 이때는 **window 자체는 결정론적으로 강제**하되 **결과는 invariant로 단언**한다. "양쪽 다 성공할 수는 없다", "재고 총합은 보존된다" 같은 식. `assertThatThrownBy`로 특정 예외를 결정론적으로 단언하면 flaky가 된다.
- 패턴 형태: latch로 "양쪽 thread가 각자 첫 락을 잡을 때까지 대기"라는 window만 결정론적으로 강제하고, 결과는 `errors.isNotEmpty()` / `orderCount < 2` 같은 invariant로 단언한다(어느 쪽이 이기는지는 단언하지 않는다).

### 금지

- **race 무대 컴포넌트의 분기 결과를 stub으로 강제하기.** `doReturn(false).when(applicationService).someBranch()`, `doThrow(...).when(applicationService).businessMethod()` 같은 형태. 진짜 race의 무대를 가린 상태에서 다중 thread만 돌리는 건 동시성 검증이 아니다.
- **`Thread.sleep`으로 타이밍 맞추기.** 보조 thread의 polling이 필요하면 `Awaitility` 같은 조건 대기 도구를 우선 고려한다.
- **latch로 thread 간 *속도* 맞추기.** latch는 *순서 / 동시 시작 / 종료 대기* 동기화 용도다.
- **인터리빙 의존적 단언.** "Thread A가 먼저 와서 X" 같은 단언은 flaky의 근본 원인이다. 데드락 / DB 스케줄러 의존 결과도 마찬가지.

### 테스트로 해결할 일이 아닌 경우

타이밍에 따라 결과가 달라진다는 건 그 로직이 race에 노출돼 있다는 신호다. 답은 "모든 타이밍을 테스트하자" 가 아니라 **"타이밍과 무관하게 만들자"** 다. 낙관 락(`@Version`) / 비관 락(`FOR UPDATE`) / 유니크 제약 / 원자적·조건부 UPDATE(`SET qty = qty - 1 WHERE qty >= 1`, `SET status='X' WHERE status='Y'`) / 트랜잭션 경계 재설계로 위험한 인터리빙을 **불가능하게 설계**한다(이 선택 기준은 `docs/optimistic-lock-design.md`). 테스트는 그 설계가 작동하는지 불변식으로 확인하는 역할이다.

### stress / soak 테스트

JUnit / Spring 영역이 아니다. 부하 / 성능 측정은 k6, Gatling 같은 전용 도구로 별도 트랙에서 다룬다. 이 컨벤션은 결정론적 동시성 검증만 다룬다.
