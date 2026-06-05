# Step 3: write-retrospective

## 읽어야 할 파일

먼저 아래 파일들을 읽고 본 태스크의 결정 흐름과 결과를 파악하라:

- `/docs/tasks/auth-refresh-token-store-unavailable/prd.md`
- `/docs/tasks/auth-refresh-token-store-unavailable/architecture.md`
- `/docs/tasks/auth-refresh-token-store-unavailable/adr.md`
- `/docs/tasks/auth-refresh-token-store-unavailable/api-spec.md`
- `/docs/tasks/auth-refresh-token-store-unavailable/db-schema.md`

step 1, 2 결과 변경 사항:

- `/src/main/java/com/commerce/auth/exception/RefreshTokenStoreUnavailableException.java`
- `/src/main/java/com/commerce/auth/exception/AuthExceptionHandler.java`
- `/src/main/java/com/commerce/auth/infrastructure/RedisRefreshTokenStore.java`
- `/src/main/java/com/commerce/auth/application/AuthTokenIssueService.java`
- `/src/main/java/com/commerce/auth/application/AuthTokenReissueService.java`
- `/src/test/java/com/commerce/auth/infrastructure/RedisRefreshTokenStoreTest.java`
- `/src/test/java/com/commerce/auth/exception/AuthExceptionHandlerTest.java`
- `/src/test/java/com/commerce/auth/application/AuthTokenIssueServiceTest.java`
- `/src/test/java/com/commerce/auth/application/AuthTokenReissueServiceTest.java`
- `/docs/exception-strategy.md` (갱신 부분)
- `/docs/adr.md` (갱신 부분)

참고 (기존 결정 맥락):

- `/docs/tasks/order-idempotency-cache-simplification/adr.md`
- `/docs/tasks/order-idempotency-cache-simplification/retrospective.md`

## 작업

본 step의 유일한 산출물은 `docs/tasks/auth-refresh-token-store-unavailable/retrospective.md`다. 코드 변경 없음.

다음 섹션을 포함하여 회고록을 작성한다:

### 1. 배경

- Issue #181 본문 요약 — Order 패턴이 신설된 뒤 Auth가 *예외 케이스* 로 남아 규약 격상이 막혔던 상황.
- 동작 결함은 없었지만 미래 사용처(Payment/Stock) 추가 시 일관성 부담이 누적된다는 우려.

### 2. 결정 과정 요약

대화에서 합의한 결정 순서를 압축 정리한다:

- **(ADR-1) 매핑 패턴 통일 vs 현행 유지** — fallback 가능 여부를 *구조 분기점* 으로 둘지, *application/presentation 정책 결정 분기* 로 격하할지. 후자 채택. 매핑 패턴은 공통 규약으로 격상하고 catch 위치만 도메인별로 분기시킨다.
- **(ADR-2) catch 위치 결정 — application vs presentation** — A 안 (Order와 동일하게 application catch + `AuthException` 변환)과 B 안 (application catch 없이 `@RestControllerAdvice` 위임)을 비교. A 안의 단순 변환 두 줄 보일러플레이트와 *인프라 장애를 비즈니스 예외(`AuthException`)로 감싸는 시멘틱 어색함* 을 회피하기 위해 B 안 채택. 동시에 application의 인프라 장애 `log.error`도 제거.
- **(ADR-3) `@RestControllerAdvice` 위치 결정 — `GlobalExceptionHandler` vs 도메인 advice** — `common.exception.GlobalExceptionHandler`에 핸들러 한 줄 추가하는 (a) 안과 `auth.exception.AuthExceptionHandler`를 신설하는 (b) 안을 비교. (a)는 `common` → `auth` 역의존 발생, 사용처 추가 시 부담 누적. (b)는 도메인-specific advice 컨벤션을 *새로 도입* 하는 부담은 있지만 의존 방향이 정합하고 확장성도 좋아 채택.
- **(ADR-4) 베이스 클래스 추출 여부** — `OrderIdempotencyStoreUnavailableException`과 공통 부모를 만들지. YAGNI — 사용처 2곳뿐, Order는 application catch, Auth는 presentation catch라 공통 catch 시나리오가 없음. Payment/Stock 등장 시 재검토.
- **port 시그니처 변경 여부** — `RefreshTokenStore`에 `throws RefreshTokenStoreUnavailableException`을 선언할지. unchecked라 불필요. port가 도메인 예외 존재를 의식하지 않는 게 추상화 보존에 유리.

### 3. 핵심 트레이드오프

| 결정 | 얻은 것 | 감수한 것 |
| --- | --- | --- |
| 매핑 패턴 통일 (ADR-1) | application의 Spring DAO 의존 제거, 미래 사용처 추가 시 의사결정 비용 감소 | 도메인 예외 클래스 1개 추가, `docs/exception-strategy.md` 재정리 필요 |
| catch 위치를 presentation으로 (ADR-2) | application happy path만 보임, 단순 변환 보일러플레이트 제거, 중복 로그 회피, `AuthException` 의미 정합 | Order와 *catch 위치* 자체가 갈라져 두 패턴을 설명해야 함 |
| 도메인-specific `@RestControllerAdvice` (ADR-3) | `common` → 도메인 역의존 회피, 도메인별 응답 정책 응집, 확장성 명확 | 단일 advice 컨벤션을 새로 도입하는 부담, 미래 advice 우선순위/범위 한정 검토 필요 |
| 베이스 클래스 추출 보류 (ADR-4) | 과한 추상화 회피, 도메인 격리 보존 | 미래 추가 시 부모 추출 + import 조정 비용 |

### 4. 변경 범위

- 신규: `RefreshTokenStoreUnavailableException`, `AuthExceptionHandler`, `RedisRefreshTokenStoreTest`, `AuthExceptionHandlerTest`.
- 수정: `RedisRefreshTokenStore`, `AuthTokenIssueService`, `AuthTokenReissueService`, `AuthTokenIssueServiceTest`, `AuthTokenReissueServiceTest`.
- 문서: `docs/exception-strategy.md` 캐시 장애 처리 섹션 정리, `docs/adr.md` task 표 행 추가, 본 task 폴더 (`prd/architecture/adr/api-spec/db-schema/retrospective`) 신설.

### 5. 부수 이슈 처리

- 본 task 머지 시 Issue #181 close.
- `OrderIdempotencyStoreUnavailableException`과의 공통 베이스 클래스 추출은 Payment/Stock 등 외부 캐시 사용처가 3곳 이상 등장하고 공통 catch 시나리오가 필요해진 시점의 *별도 task* 로 미룬다.
- `AuthExceptionHandler`의 우선순위(`@Order`)와 범위 한정(`basePackages`)은 사용처가 늘어났을 때 별도 검토 (현 시점에는 단일 예외 처리라 충돌 없음).

### 6. 미래 결정 시점

다음 사건이 발생하면 본 task 결정을 재검토한다.

- 외부 캐시 사용처가 3곳 이상 등장 → 베이스 클래스 추출 검토 (ADR-4 재검토).
- Auth 도메인에 *fallback 가능한 캐시 사용처* (예: 토큰 검증 캐시)가 등장 → catch 위치 결정 재검토. fallback 진입은 application catch가 정답이므로 Auth에도 Order와 같은 application catch 패턴을 부분 도입해야 한다 (ADR-2 재검토).
- 다른 도메인(Order/Payment 등)이 도메인-specific 인프라 장애 직접 매핑 필요 → 자체 `@RestControllerAdvice` 신설로 같은 컨벤션 확장 (ADR-3 재검토).
- 도메인 advice가 여러 개 등록되어 우선순위 충돌이 발생 → `@Order` / `basePackages` 정책 도입 검토.
- infra adapter의 ERROR 로그만으로 운영 인지가 부족한 사례 발견 → 핸들러 로그 도입 또는 메트릭 도입 검토.

### 7. 배운 점

- *패턴 분기점은 구조가 아니라 정책 결정 내용일 수 있다.* fallback 가능 여부를 *구조 분기* 로 두면 사용처 추가 시 의사결정 비용이 누적되지만, *catch 위치 결정 분기* 로 격하하면 매핑 구조 자체는 공통 규약으로 격상 가능.
- *인프라 장애를 비즈니스 예외로 감싸지 않는 게 시멘틱적으로 정직하다.* application의 *단순 변환 두 줄* 은 인프라 장애를 `AuthException`이라는 비즈니스 옷에 입혀 GlobalHandler에 보내는 의미였다. 인프라 장애는 도메인 예외 그대로 presentation까지 올려서 매핑하는 게 책임 분리에 부합한다.
- *common 모듈의 도메인 의존을 회피하는 가치는 사용처가 늘수록 커진다.* 한 사례만 보면 `common`에 핸들러 한 줄 추가가 가장 작은 변경이지만, 사용처가 늘어났을 때 `common`이 모든 도메인을 import하게 되는 누적 부담을 피하려면 도메인-specific advice 컨벤션을 일찍 도입하는 게 낫다.
- *베이스 클래스 추출은 사용처 N=2 에서는 과하다.* IDE 지원과 변경 범위가 작아 N≥3 진입 시점에 추출해도 비용 차이가 미미하다.

## Acceptance Criteria

```bash
./gradlew test
```

추가 수동 확인:

```bash
ls -la docs/tasks/auth-refresh-token-store-unavailable/retrospective.md
```

회고록이 신규 생성됐는지 확인한다. 본문이 위 7개 섹션을 모두 포함하는지 사람이 검토한다.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `docs/tasks/auth-refresh-token-store-unavailable/retrospective.md`가 신규 생성됐다.
   - 본문이 *배경 / 결정 과정 / 핵심 트레이드오프 / 변경 범위 / 부수 이슈 / 미래 결정 시점 / 배운 점* 7개 섹션을 포함한다.
   - 결정 과정에 ADR-1 ~ ADR-4의 결정 순서가 모두 반영됐다.
   - 다른 task의 회고 문서를 수정하지 않았다.
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 다른 task의 `retrospective.md`를 수정하지 마라. 이유: 회고 문서는 시점 기록이며 immutable.
- 회고록에 사용자와의 대화 원문 인용을 그대로 붙이지 마라. 이유: 회고는 *결정 흐름과 근거* 의 요약이지 transcript가 아니다.
- 회고 본문에서 *이번 대화에서 합의했듯이* 같은 외부 참조 표현을 쓰지 마라. 이유: 필요한 배경은 본문 안에서 자기완결적으로 적는다.
- 코드를 함께 수정하지 마라. 이유: 본 step은 회고 문서 전용.
- 기존 테스트를 깨뜨리지 마라.
