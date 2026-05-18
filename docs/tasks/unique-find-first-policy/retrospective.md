# 회고록: unique-find-first-policy

## 1. 작업 요약

PR #106 의 후속 부채(Application 계층이 `DuplicateKeyException` 에 직접 의존) 를 해소하기 위해 5곳의 unique 처리 정책을 단일 본질 흐름 — `DB find → 없으면 insert → 충돌 시 500` — 으로 통일했다. Application 과 Adapter 어디서도 Spring DAO 예외를 catch 하지 않으며, 정상 멱등/중복 흐름은 사전 `find` 가 흡수하고 race window unique 충돌은 안전망 500 으로 가시화된다.

`MemberRegistrationService`, `PaymentApprovalService`, `PaymentAttemptService`(2 메서드), `OrderCreateService.attemptCreateOrder`, `StockRestoreOutboxConsumeService.markProcessed` 5곳에서 `DuplicateKeyException` 임포트와 catch 블록을 모두 제거했다. `OrderCreateService` 만 TTL 만료 후 정당한 멱등 재요청을 보존하기 위해 Redis reserve 뒤에 DB `findByMemberIdAndIdempotencyKey` 사전 체크를 추가했다.

안전망 보강으로 `GlobalExceptionHandler` 에 `DataAccessException` 부모 핸들러를 등록하고 `CommonErrorCode.DATA_ACCESS_ERROR(COMMON-500-2)` 를 신설해, `BadSqlGrammarException` / `CannotAcquireLockException` 등 다른 DAO 예외도 stack trace 와 함께 500 으로 처리되도록 분류 카테고리를 분리했다.

목적: Application 의 `org.springframework.dao.*` 직접 의존을 제거하고 5곳의 분기(멱등 흡수 / 도메인 예외 변환 / silent skip) 를 단일 본질 흐름으로 통합한다.

---

## 2. 결정한 정책

### 본질 흐름 (5곳 모두 동일)

```
DB find → 없으면 insert → 충돌 시 500
```

- 사전 `find` 가 정상 멱등/중복 시나리오를 흡수한다 (200 또는 도메인 4xx).
- `insert` 시점의 unique 위반은 race window 뿐이며, 코드 안전망 500 으로 가시화한다.
- Application 도 Adapter 도 Spring DAO 예외를 catch 하지 않는다.

### 적용 조건과 한계

| 구분 | 기준 |
|---|---|
| **적용** | ① 트랜잭션이 짧다 ② 정상 흐름에서 동시 충돌 확률이 낮다 (사용자 입력 식별자, idempotency key, ULID 등) |
| **비적용** | 충돌이 잦은 시나리오 (예: 캐시 미스 후 동시 다발 insert, 대규모 일괄 처리 race). 이 경우 try-save-catch 패턴이 더 적합하며 catch 위치/처리 동작은 별도 ADR 로 결정한다 |

본 5곳은 모두 적용 조건을 만족한다. 향후 새 unique 제약 도입 시 위 기준으로 패턴 선택 가능.

### 5곳 매핑

| 위치 | 사전 find | 변경 패턴 | 행위 변경 (race window 한정) |
|---|---|---|---|
| `MemberRegistrationService` | `existsByEmail` | catch 제거 | 4xx `DUPLICATE_EMAIL` → 500 `COMMON-500-1` |
| `PaymentApprovalService` | `findByMerchantPayKey` + `validateCompletedPaymentOrThrow` | catch 제거 | 4xx `PAYMENT_DUPLICATE` → 500 `COMMON-500-1` |
| `PaymentAttemptService.getOrCreateApproveAttempt` | `findApproveAttempt` | try-save-catch → find-first | 200 (멱등 흡수) → 500 `COMMON-500-1` |
| `PaymentAttemptService.getOrCreateCancelAttempt` | `findCancelAttempt` | try-save-catch → find-first | 200 (멱등 흡수) → 500 `COMMON-500-1` |
| `OrderCreateService.attemptCreateOrder` | Redis `reserve` + DB `findByMemberIdAndIdempotencyKey` | try-save-catch → find-first + DB 사전 체크 추가 | 변경 없음 (DB find 가 TTL 만료 후 정당 재요청 보존, race/ULID 충돌은 `RuntimeException` catch → Redis 정리 → rethrow → 안전망) |
| `StockRestoreOutboxConsumeService.markProcessed` | `existsByEventIdAndConsumerType` (신규) | try-save-catch → find-first | 200 (silent skip) → 500 (Kafka consumer 재시도로 복구) |

### DataAccessException 안전망 계층

```
DataAccessException (신규 부모, COMMON-500-2 + stack trace)
├─ DataIntegrityViolationException (기존, COMMON-500-1 + stack trace)   ← unique/NOT NULL/FK/CHECK
│  └─ DuplicateKeyException                                              ← 자동 흡수
└─ OptimisticLockingFailureException (기존, COMMON-409-1)                ← 409
```

Spring `@ExceptionHandler` 가 가장 구체적인 타입을 먼저 매칭하므로 기존 두 구체 핸들러는 그대로 우선 매칭되고, 부모 핸들러는 그 외 DAO 예외(`BadSqlGrammarException`, `CannotAcquireLockException`, `DataAccessResourceFailureException` 등) 만 받는다. `DuplicateKeyException` 은 별도 등록 없이 `DataIntegrityViolationException` 핸들러가 흡수한다.

`JpaConfig` 의 `SQLErrorCodeSQLExceptionTranslator` 빈은 그대로 유지한다. 코드가 `DuplicateKeyException` 을 catch 하지 않더라도 안전망 로깅의 분류 정확도를 위해 unique 위반이 `DuplicateKeyException` 으로 정확히 변환되는 것은 여전히 가치 있다.

---

## 3. 주요 발견 및 논의

### 옵션 A/B/C 비교와 옵션 B 선택 근거

회고록 §2 부채 해소 방향으로 세 옵션을 검토했다.

| 옵션 | 내용 | 평가 |
|---|---|---|
| A | `DuplicateKeyException` catch 를 Application 에서 Adapter 계층으로 이동 | 5곳 처리 동작이 달라(멱등 재조회/도메인 예외 변환/silent skip) Adapter 가 도메인 매핑 지식을 알게 됨. 부채가 Application → Adapter 로 이동할 뿐 |
| B | 5곳 모두 `DB find → insert → 500` 본질 흐름으로 통일하고 catch 자체를 제거 | Application 과 Adapter 양쪽에서 인프라 예외 의존을 동시 해소. 5곳의 처리 분기가 정책상 흡수됨 |
| C | `Exception.class` fallback 의 stack trace 로깅만 보강 | DAO 카테고리 분리 가치 포기. 운영 모니터링 카테고리 미세 분리 불가 |

옵션 B 를 채택했다. 5곳의 처리 동작 차이는 모두 사전 `find` 가 흡수하고 race 는 안전망 500 으로 통합되므로 분기 자체가 필요 없다. 안전망 보강은 옵션 C 가 아닌 별도 핸들러 추가(`DataAccessException` 부모) 로 처리해 운영 모니터링에서 `COMMON-500-2` 카테고리를 일반 fallback 과 구분 가능하게 했다.

### OrderCreate 의 두 unique 제약 분기 불필요 결론

`tbl_order` 는 `(member_id, idempotency_key)` (비즈니스 키) 와 `orderNumber` (ULID 시스템 키) 두 개의 unique 제약을 갖는다. PR #106 에서는 `DuplicateKeyException` 만으로는 어느 제약이 터졌는지 구분할 수 없어 fallback 재조회 결과로 분기했다.

새 정책에서는 두 제약 모두 동일하게 안전망 500 으로 통합된다. 멱등키 정상 흐름은 DB find 사전 체크가 흡수하고, 멱등키 race 와 ULID 충돌(확률 1조분의 1) 은 어차피 동시성 코드 버그 또는 시스템 이상 신호이므로 가시화가 정답이다. catch 분기 로직 자체가 사라지면서 두 제약 구분 필요성도 함께 사라졌다.

### DataAccessException 부모 핸들러 vs Exception fallback 보강 비교

`SQLErrorCodeSQLExceptionTranslator` 는 SQL 에러 코드 기반으로 다양한 `DataAccessException` 하위 타입을 생성한다. 기존 핸들러 구성에서 `DataIntegrityViolationException` 과 `OptimisticLockingFailureException` 외 DAO 예외(`BadSqlGrammar`, `CannotAcquireLock`, `DataAccessResourceFailure` 등) 는 `Exception.class` fallback 으로 떨어져 stack trace 없이 message-only 로 처리됐다.

부모 핸들러를 추가해 DAO 카테고리만 stack trace + 500 + `COMMON-500-2` 로 처리하면 운영 모니터링에서 일반 fallback(`COMMON-500`) 과 구분 가능하다. Spring 다형성 매칭 덕분에 기존 구체 핸들러는 우선 매칭되어 회귀 없이 적용됐다. `Exception.class` fallback 자체의 stack trace 로깅 누락은 NPE 등 일반 예외에 대해 여전히 남아 있는 문제이며 별도 과제로 분리했다.

### spy 제거 가능성 검증 결과 (Step 5)

PR #106 에서 `NaverPayServiceIntegrationTest` 는 H2 + JPA 환경의 `DuplicateKeyException` 미발생을 우회하려 `@MockitoSpyBean PaymentAttemptService` 에 `doReturn` 스텁을 4건(라인 444-446, 503-505, 538-540, 828-830) 추가했다. 새 find-first 패턴은 H2 환경에서도 멱등 흐름이 사전 `find` 분기로 정상 통과되므로 4건 스텁을 모두 제거할 수 있었다. spy 어노테이션은 `succeedCancelAttempt` / `failCancelAttempt` 강제 예외 주입 용도로만 유지하고 그 이유를 주석으로 명시했다.

### 동시성 테스트 단언 완화

race window 한정 행위 변경에 따라 동시성 통합 테스트의 단언을 갱신했다.

- `OrderCreateServiceIdempotencyTest`, `NaverPayServiceConcurrencyTest`: race 발생 시 도메인 예외(`PaymentException`) 또는 안전망 500 (`DataIntegrityViolationException`) 을 모두 허용하도록 단언을 완화. `NaverPayServiceConcurrencyTest` 6 개 케이스에는 공통 헬퍼 `assertRaceOrPaymentError` 를 도입.
- `PaymentAttemptServiceConcurrencyTest`: 동시 create 시나리오는 unique INSERT lock 경쟁으로 MySQL 데드락이 가끔 발생해 검증이 불안정해질 수 있어, 사전 attempt 생성 + 멱등 재요청으로 단순화하고 사전 `find` 분기 자체를 검증하는 형태로 변경.

`DuplicateKeyExceptionMappingTest` 는 JPA Repository 직접 호출로 `SQLErrorCodeSQLExceptionTranslator` 빈 동작만 검증하므로 새 정책과 호환되어 그대로 두었다.

---

## 4. 변경 범위 정리

### Production

| 파일 | 변경 내용 |
|---|---|
| `CommonErrorCode.java` | `DATA_ACCESS_ERROR(COMMON-500-2)` 신설 |
| `GlobalExceptionHandler.java` | `DataAccessException` 부모 핸들러 추가 (stack trace + 500) |
| `MemberRegistrationService.java` | `DuplicateKeyException` import / catch 블록 제거 |
| `PaymentApprovalService.java` | `DuplicateKeyException` import / catch 블록 제거 |
| `PaymentAttemptService.java` | 두 메서드 try-save-catch → find-first 리팩토링. `PAYMENT_ATTEMPT_NOT_FOUND` throw 분기 제거 |
| `OrderCreateService.java` | catch 블록 제거 + Redis reserve 후 DB find 사전 체크 추가. unused `@Slf4j` 제거 |
| `StockRestoreOutboxConsumeService.java` | try-save-catch → `existsByEventIdAndConsumerType` 사전 체크 + save |
| `ProcessedEventRepository.java` (domain) | `existsByEventIdAndConsumerType` 메서드 추가 |
| `ProcessedEventRepositoryAdapter.java` | 위 메서드 위임 추가 |
| `JpaProcessedEventRepository.java` | Spring Data `existsByEventIdAndConsumerType` 메서드 추가 |

### Test

| 파일 | 변경 내용 |
|---|---|
| `MemberRegistrationServiceTest.java` | `DuplicateKeyException` race 케이스 제거 |
| `PaymentApprovalServiceTest.java` | `DuplicateKeyException` race 케이스 제거 |
| `PaymentApprovalServiceIntegrationTest.java` | 파일 전체 삭제 (race 단일 케이스 검증만 있었음) |
| `PaymentAttemptServiceTest.java` | 신규/멱등/amount mismatch 3 케이스 × approve/cancel 로 전면 재구성 |
| `OrderApplicationServiceTest.java` | `DuplicateKeyException` race 케이스 2 건 제거, Redis reserve 성공 + DB present (complete 갱신) / Redis reserve 실패 + completed orderId 시나리오로 교체 |
| `StockRestoreOutboxConsumeServiceTest.java` | 중복 시나리오를 `existsByEventIdAndConsumerType` mock 으로 교체 |
| `NaverPayServiceIntegrationTest.java` | spy 우회 스텁 4건 제거, spy 어노테이션 유지 사유 주석 추가 |
| `PaymentAttemptServiceConcurrencyTest.java` | 동시 create 시나리오를 사전 attempt + 멱등 재요청으로 단순화 |
| `OrderCreateServiceIdempotencyTest.java` | race window 단언을 도메인 예외 또는 안전망 500 모두 허용으로 완화 |
| `NaverPayServiceConcurrencyTest.java` | 6 개 케이스 단언을 `assertRaceOrPaymentError` 헬퍼로 갱신 |

### Docs

| 파일 | 변경 내용 |
|---|---|
| `docs/architecture.md` | 예외 처리 섹션 교체 (본질 흐름, 적용 조건/비적용 상황, `DataAccessException` 부모 핸들러, `JpaConfig` 빈 등록 목적 재기술) |
| `docs/ADR.md` | ADR-011 (find-first 패턴 통일) 추가 |
| `CLAUDE.md` | 구현 규칙 — 사전 find + 안전망 500 위임 + try-save-catch 비적용 조건 명시 |
| `docs/tasks/db-constraint-violation-handling/{prd,adr,api-spec,architecture}.md` | 폐기 anchor 4건 추가 (본문 미수정) |
| `docs/tasks/unique-find-first-policy/` | prd / architecture / adr / api-spec / db-schema / phases 전체 신규 작성 |

---

## 5. 미결 과제

### Exception.class fallback 의 stack trace 누락

`Exception.class` fallback 핸들러는 여전히 message-only 로깅이다. DAO 카테고리는 부모 핸들러로 해결됐지만 `NullPointerException` 등 일반 예외는 stack trace 없이 운영 로그에 남는다. 별도 과제로 분리.

### workspace docs (api-contract.md) 동기화

본 태스크의 race window 행위 변경(4xx → 500, 200 → 500) 이 `commerce-workspace/docs/api-contract.md` 에 반영되어 있는지 확인이 필요하다. backend 서브모듈 세션은 workspace 문서를 수정하지 않으며, 워크스페이스 CLAUDE.md 의 "계약 싱크" 역할에 따라 Frontend 세션에서 처리한다. api-spec.md 의 grep 결과로 보아 race window 응답 코드는 계약에 명시되지 않았을 가능성이 높으나 최종 확인은 Frontend 세션의 몫이다.

### PaymentAttempt 동시 생성 시나리오의 통합 테스트 보강

Step 5 에서 `PaymentAttemptServiceConcurrencyTest` 의 동시 create 시나리오는 unique INSERT lock 데드락 가능성으로 인해 사전 attempt + 멱등 재요청으로 단순화했다. 실제 race window 에서 안전망 500 이 도달하는지를 직접 검증하는 시나리오는 본 PR 범위에서 빠졌다. Testcontainers 환경에서 인위적으로 race 를 발생시키는 별도 통합 테스트가 필요하다면 후속 과제로 분리한다.

### harness 개선 제안

- **action plan 의 step 분할 기준 명문화**: 본 작업은 Step 1 (Member + PaymentApproval) 같이 변경 패턴이 동일한 곳을 묶고, Step 2/3/4 (PaymentAttempt / OrderCreate / Outbox) 같이 변경 패턴이 다른 곳은 분리하는 방식으로 step 을 나눴다. 이 분할 기준이 SKILL.md 에 명시되어 있지 않아 비슷한 정책 변경 시 매번 즉흥 판단이 필요했다. 변경 패턴 동일 여부 / 단위 테스트 영향 범위 / 통합 테스트 setup 비용을 분할 기준으로 SKILL.md 에 추가하는 것이 좋다.
- **회고록 작성 step 의 acceptance criteria**: 본 step 의 acceptance criteria 는 `./gradlew test` 통과뿐이지만 회고록의 사실 정확성은 모델 단독 검증이 어렵다. 회고록 작성 step 의 검증 체크리스트(이전 회고록 형식 부합 / step 매핑 표 일치 / 행위 변경 누락 여부) 를 SKILL.md 의 표준 step 으로 둘 가치가 있다.

---

## 6. 회고

### 잘된 점

- **정책 단순화로 분기 제거**: PR #106 의 "3계층 책임 분리 + Unique 처리 모드 A/B + 5곳 분류" 표가 새 정책에서는 "5곳 모두 동일 본질 흐름" 한 줄로 흡수됐다. 도메인 매핑 지식이 어디에도 누설되지 않고, 모드 분류 자체가 사라졌다.
- **계층 의존 부채 동시 해소**: 옵션 A(Adapter 이동) 는 Application 의 인프라 예외 의존을 해소하지만 Adapter 가 도메인 매핑을 알게 되는 문제가 있었다. 옵션 B 는 양쪽 모두에서 의존을 제거했고, Application 의 `org.springframework.dao.*` 임포트도 사라졌다.
- **운영 모니터링 카테고리 분리**: `COMMON-500-2` 신설로 `DataAccessException` 부모 핸들러가 DAO 예외 카테고리를 일반 fallback 과 분리한다. unique race 는 `COMMON-500-1`, 다른 DAO 예외는 `COMMON-500-2`, 그 외 일반 예외는 `COMMON-500` 으로 알람과 대시보드에서 구분 가능해졌다.
- **OrderCreate 의 본질 흐름 유지**: Redis 캐시 레이어와 DB 사전 체크라는 두 단계 fallback 구조가 본질 흐름의 변형이 아니라 latency 최적화 레이어임을 ADR 에 명시했다. 다른 4곳과 동일한 본질 흐름을 갖되 OrderCreate 만 Redis 가 추가로 얹혀 있을 뿐이라는 위치가 명확해졌다.
- **spy 제거 시도가 정책 검증을 겸함**: Step 5 에서 `NaverPayServiceIntegrationTest` 의 spy 스텁 4건 제거는 새 패턴이 H2 환경에서도 정상 동작함을 입증하는 부가 효과를 냈다. 의도하지 않았지만 H2 환경 한계가 더 이상 우회 코드를 요구하지 않는다는 신호가 됐다.
- **이전 태스크 문서 보존**: PR #106 폐기 anchor 를 prd/adr/api-spec/architecture 4 개 문서 상단에 한 줄씩만 추가하고 본문과 phases/retrospective 는 immutable 로 유지. 정책 변경 이력이 추적 가능한 형태로 누적됐다.

### 개선할 점

- **Step 분할 결정에 즉흥 판단 비중이 컸다**: 어떤 변경을 묶고 어떤 변경을 분리할지(예: PaymentAttempt 의 approve/cancel 두 메서드는 하나의 step / OrderCreate 와 Outbox 는 각자 step) 가 SKILL.md 에 명시되어 있지 않아 매번 판단했다. 위 "harness 개선 제안" 의 분할 기준 명문화 항목으로 분리.
- **동시 create 데드락 가능성을 사전에 예측하지 못했다**: `PaymentAttemptServiceConcurrencyTest` 의 동시 create 시나리오에서 MySQL unique INSERT lock 경쟁으로 데드락이 발생할 수 있다는 것을 Step 5 실행 중에 발견했다. 테스트 단순화로 우회했지만 race window 의 실제 안전망 도달 검증이 빠졌다. PRD/architecture 의 "테스트 포인트" 섹션에 동시 create 와 사전 attempt + 멱등 재요청을 구분해 명시했더라면 step 재설계를 줄일 수 있었다.
- **race window 행위 변경 표는 PR 본문에 한 번 더 정리되어야 한다**: 본 회고와 ADR-011 에 모두 적혀 있지만 PR 본문에서도 race window 한정 변경임을 명시해야 리뷰어가 운영 영향을 즉시 판단할 수 있다.
