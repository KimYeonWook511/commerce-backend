# 테스트 컨벤션

## 기본 원칙

- 새로운 기능을 추가하면 반드시 테스트를 함께 작성한다.
- 기존 동작을 수정하면 관련 테스트를 함께 갱신한다.
- 명시적으로 지시받지 않았다면 테스트를 생략하지 않는다.
- `@SpringBootTest`는 전체 컨텍스트를 로드하므로 사용을 지양하고, 목적에 맞는 슬라이스 테스트나 단위 테스트를 우선 사용한다.

---

## 테스트 패키지 구조

```
src/test/java/
└── com/commerce/
    ├── support/                                          ← 공통 인프라
    │   ├── TestcontainersSupport.java                    ← 컨테이너 싱글턴 관리
    │   ├── PersistenceTestSupport.java                   ← 도메인별 cleanup 계약 (interface)
    │   ├── PersistenceCleanupTestSupport.java            ← FK 순서대로 정렬 후 일괄 삭제
    │   └── CleanupOrder.java                             ← FK 안전 삭제 순서 (enum)
    │
    └── order/
        ├── domain/
        │   └── OrderTest.java                            ← 단위 테스트
        ├── application/
        │   ├── CreateOrderServiceTest.java               ← 단위 테스트
        │   └── CreateOrderServiceIntegrationTest.java    ← 통합 테스트
        ├── presentation/
        │   └── OrderControllerTest.java                  ← 슬라이스 테스트
        └── infrastructure/
            ├── persistence/
            │   ├── support/
            │   │   └── OrderPersistenceTestSupport.java  ← 도메인별 삭제 + 테스트 데이터 헬퍼
            │   └── OrderRepositoryJpaAdapterTest.java    ← 슬라이스 테스트 or 통합 테스트
            └── cache/
                └── RedisIdempotencyStoreTest.java        ← 통합 테스트
```

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
├── AFTER_COMMIT 이후 Redis에 실제로 저장되는가
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
then(pgCanceller).should().cancel(eq(cancelAttempt), eq("취소 이유"));
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
├── 동시성 (Optimistic/Pessimistic Lock)
├── 트랜잭션 격리 수준
├── DB 전용 문법/타입 (JSON 컬럼, 전용 함수 등)
├── Flyway/Liquibase 마이그레이션
└── Redis TTL, 직렬화/역직렬화
```

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

통합 테스트에서 `@Transactional`을 사용하면 커밋이 발생하지 않아 `AFTER_COMMIT` 동작 검증이 불가능하다. 따라서 각 테스트가 `tearDown`에서 직접 데이터를 삭제한다.

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

---

## 태그 규칙

`@Tag`로 테스트를 분류하고, `build.gradle`에서 태그별로 실행을 분리한다.

| 태그 | 대상 | 실행 조건 |
|---|---|---|
| `docker` | Testcontainers 사용 통합 테스트 | Docker 환경 필요 |
| `concurrency` | 동시성 / 데드락 테스트 | 별도 실행 |
| `batch` | Spring Batch 통합 테스트 | 별도 실행 |
| `sandbox` | 외부 API 샌드박스 테스트 | 별도 실행 |

기본 `test` 태스크는 위 태그를 제외하고 실행한다 (단위 테스트, 슬라이스 테스트만 포함).

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
