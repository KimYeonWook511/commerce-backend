# 낙관적 락(@Version) 충돌 처리 설계

> `@Version` 기반 낙관 락 충돌(`OptimisticLockException`)을 트랜잭션 경계·예외 변환·정책과 일관되게
> 처리하기 위한 설계. 원리는 도메인 일반이고, 사례·코드는 결제(Payment) 도메인을 든다
> (Payment 엔티티에 `@Version`을 도입하며 전이 succeed/fail/markUnknown/escalate 시 발생하는 충돌을
> 기존 코드베이스 패턴과 일관되게 처리하는 맥락).
> 대상 스택: Java 21, Spring Boot 3.x, JPA(Hibernate), MySQL(InnoDB).
> 아키텍처: 헥사고날(포트앤어댑터) — `presentation → application → domain ← infrastructure`.

---

## 0. 한 줄 결론

**문제의 본질은 "충돌을 흡수(skip)했다"가 아니라 "흡수를 트랜잭션 안에서 했다"이다.**
catch 자체는 없앨 수 없고(없애면 보상 흐름이 끊긴다), **위치를 트랜잭션 경계 밖으로 옮기면** 코드베이스의
모든 패턴(adapter 변환 / 충돌=전파 / 단계별 독립 commit / 보상 메서드는 예외 안 던짐)과 양립한다.

핵심 규칙 세 줄:

1. **트랜잭션 경계 안**(`application/service/`): 충돌을 절대 catch하지 않는다 → 변환된 도메인 예외를 전파시켜 **깨끗이 rollback**.
2. **트랜잭션 경계 밖**(orchestrator = `application/usecase/`): 도메인 예외를 catch해서 **skip / retry / 전파** 정책을 결정한다.
3. catch가 경계 **안**이면 `REQUIRES_NEW`를 붙여도 `UnexpectedRollbackException`이 난다.

> 용어: 이 문서의 "orchestrator"는 `application/usecase/`에 사는 클래스의 역할 이름이고, "tx 단위작업"은
> `application/service/`(클래스명 `…Service`)다. 패키지 배치 기준은 `package-structure-conventions.md` 참조.

---

## 1. 왜 트랜잭션 안에서 catch하면 깨지나

### 1.1 메커니즘

- `OptimisticLockException`은 **flush 시점**에 던져진다.
- flush가 일어난 순간 그 트랜잭션은 **rollback-only**로 마킹된다.
  (JPA 스펙: `PersistenceException` 이후 영속성 컨텍스트는 undefined, 트랜잭션은 rollback 대상.)
- 이 상태에서 예외를 **메서드 안에서 catch하고 정상 리턴**하면 → 프록시가 commit 시도
  → rollback-only 감지 → **`UnexpectedRollbackException`**.
- `@Transactional(REQUIRES_NEW)`를 붙여도 동일하다. 새 트랜잭션이 rollback-only가 되고,
  그 새 트랜잭션의 인터셉터가 commit을 시도하니 똑같이 터진다. **경계의 위치만 바뀔 뿐,
  catch가 경계 안쪽에 있는 한 결과는 같다.** (리뷰어의 REQUIRES_NEW 제안이 부분적인 이유.)

### 1.2 반대로 전파시키면

변환된 도메인 예외를 **tx 메서드 밖으로 전파**시키면, 인터셉터는 RuntimeException을 보고
**rollback**(commit 아님)을 수행한다 → 깨끗이 롤백되고 도메인 예외가 프록시 밖으로 나온다 →
바깥 레이어가 그걸 잡는다.

> `UnexpectedRollbackException`은 "rollback-only인데 commit하려 할 때"만 난다.
> 전파 경로에선 commit을 안 하므로 안 난다.

### 1.3 save vs saveAndFlush — "save면 실패"가 아니다

`SimpleJpaRepository`의 `save`/`saveAndFlush`에는 `@Transactional`(REQUIRED)이 붙어 있다.
충돌을 adapter가 잡을 수 있느냐는 **flush가 어느 호출 프레임에서 일어나느냐 = 트랜잭션 경계가 어디냐**에 달렸다.

| 상황 | flush 시점 | adapter가 충돌을 잡나? |
|------|-----------|---------------------|
| 바깥에 tx 없음 | `save()`가 자기 tx를 열고 닫으며 commit 시 flush | **예** — plain `save`라도 잡힌다 |
| 바깥에 tx 있음(tx 단위작업이 `@Transactional`) | `save`는 REQUIRED라 기존 tx에 합류만, flush는 바깥 경계에서 | **아니오** — adapter 밖에서 터짐 |

따라서 tx 단위작업(service 패키지)에 `@Transactional`을 둬서 **read + guard + write를 한 tx로 묶고(원자성)**,
그 안에서 충돌을 adapter가 변환하려면 **`saveAndFlush`로 flush를 adapter 프레임 안으로 당겨와야** 한다.

> `saveAndFlush`는 "save가 실패하는 걸 성공시키는" 도구가 아니라,
> **충돌을 잡을 수 있는 위치로 당겨오는** 도구다.
> 선례 `saveUsed`/`saveApproved`가 `saveAndFlush`인 이유 = 이들은 `@Transactional` 메서드 안에서 호출되니까.

만약 tx 단위작업 없이 repository의 자체 `@Transactional`에만 맡기면 plain `save`로도 잡히긴 하지만,
**`find`(읽기)와 `save`(쓰기)가 서로 다른 트랜잭션으로 쪼개져** read-modify-write의 원자성이 깨진다.

---

## 2. 레이어 구조 — 책임을 셋으로 분리

`#243`의 잘못은 "트랜잭션 단위작업"과 "충돌 정책 결정"을 한 메서드에 뭉쳐서
catch가 tx 안으로 들어갈 수밖에 없었던 것이다. 이를 분리한다.

```
[C] Orchestrator / UseCase (tx 없음)  ── 충돌 정책을 소유 (전파/skip/retry 선택)
        │   여기서 도메인 예외를 catch (tx 경계 밖 → rollback-only 무관)
        │     · skip 정책 → 같은 Service 안의 private 메서드로
        │     · retry 정책 → skip과 동일 기준 (한 곳이면 private, 여러 곳이면 helper)
        ▼
┌─────────────────────────────────────────────┐
│ [A] tx 단위작업 Service (@Transactional, 별도 빈) │  ← 트랜잭션 경계
│      find + guard + write, 충돌 시 catch 안 함 │
│        ▼                                       │
│      repo.saveChecked()  (saveAndFlush)        │
└─────────────────────────────────────────────┘
        ▼
[Adapter] OptimisticLockException → PaymentException 변환 throw
```

> 충돌 반응(skip/retry)을 담는 별도 레이어를 **꼭 둘 필요는 없다.** skip은 보통 그것을 쓰는
> Service 안의 private 메서드로 충분하고, retry도 같은 기준이다(한 곳이면 private, 여러 곳이면 helper, 3.3~3.4). orchestrator는
> 그 private 메서드/ helper 를 골라 조립한다.

### 2.1 레이어 정의

| 역할 | 트랜잭션 | 책임 | 충돌을 만나면 |
|------|---------|------|-------------|
| **A. tx 단위작업 Service** | `@Transactional` | read + guard + write 원자 단위 | **절대 catch 안 함** → 도메인 예외 전파 → 깨끗이 rollback |
| **C. Orchestrator / UseCase** | 없음 | 여러 단계를 순서대로 엮음(보상 흐름 등) | 단계마다 **어떤 정책을 쓸지** 결정. skip·retry 모두 한 곳이면 private, 여러 곳이면 helper |
| **Adapter** | — | 기술 예외 → 도메인 예외 변환 | `saveAndFlush` + catch + 변환 throw |

> 충돌 반응(skip/retry)은 별도 정식 레이어가 아니다. **skip**은 그것을 쓰는 Service 안의 private
> 메서드로, **retry**도 같은 기준(한 곳이면 private, 여러 도메인이 공유하면 helper)으로 둔다. orchestrator는 이들을 골라 조립한다.

> **용어 주의**: "Orchestrator / UseCase / tx 단위작업"은 정해진 표준 레이어가 아니라,
> **application 층 내부를 이 문제에 맞게 쪼갠 역할 이름**이다. 현업 표준 골격은
> `presentation → application → domain ← infrastructure`이고, 위 셋은 전부 넓게는 **application service**다.
> 유스케이스(=application 층의 한 단위 작업: "승인한다", "보상한다", "대사를 돌린다")를
> 어떻게 클래스로 쪼갰는지의 결과일 뿐이다.

### 2.2 빈 분리는 필수 (self-invocation 함정)

B의 catch가 tx 밖에 있으려면, A의 `@Transactional` 프록시가 반드시 적용돼야 한다.
이는 **B → A 호출이 Spring 프록시 경계를 넘어야** 한다는 뜻 = A와 B는 **서로 다른 빈**이어야 한다.

> 같은 빈이면 self-invocation으로 `@Transactional`이 무시되어, A가 caller의 tx(또는 무 tx)에서
> 돌아 다시 rollback-only 오염이 일어난다. 같은 이유로 대사 루프의 `processOne`도 `reconcile`과
> **다른 빈**이어야 건별 독립 tx가 된다.

---

## 3. 코드 스케치

### 3.1 A. Transition Service (tx 단위작업) — service 패키지

```java
@Service
class PaymentTransitionService {   // application/service/ — tx 단위작업, 독립 빈

    private final PaymentRepository repo;   // domain port

    @Transactional
    public void markUnknown(Long paymentId, String detail, LocalDateTime at) {
        Payment p = repo.findById(paymentId)
                        .orElseThrow(() -> new PaymentException(PAYMENT_NOT_FOUND));
        p.markUnknown(detail, at);          // 가드 위반 시 PAYMENT_STATUS_TRANSITION_NOT_ALLOWED throw
        repo.saveChecked(p);                // adapter: saveAndFlush, 충돌 → PAYMENT_CONCURRENTLY_MODIFIED 변환 throw
        // ↑ 충돌도 가드 위반도 catch하지 않고 그대로 전파시킨다. tx는 깨끗이 rollback된다.
    }

    @Transactional
    public void fail(Long paymentId, ...)    { /* 동일 형태 */ }

    @Transactional
    public void succeed(Long paymentId, ...) { /* 동일 형태 */ }
}
```

이 빈은 "정책을 모른다." 그냥 일을 하거나 도메인 예외를 던질 뿐이다.
가드 위반(`PAYMENT_STATUS_TRANSITION_NOT_ALLOWED`)과 버전 충돌(`PAYMENT_CONCURRENTLY_MODIFIED`)은
둘 다 "누가 이미 이 결제를 전이시켰다"는 같은 의미라, 양쪽 다 도메인 예외로 겉으로 드러난다.

### 3.2 Adapter — saveAndFlush + 예외 변환

```java
@Component
class PaymentRepositoryAdapter implements PaymentRepository {

    private final PaymentJpaRepository jpa;

    // 분기(skip/retry)가 필요한 경로 전용 — flush를 adapter 프레임 안으로 당겨와 충돌을 변환
    @Override
    public Payment saveChecked(Payment p) {
        try {
            return jpa.saveAndFlush(p);
        } catch (ObjectOptimisticLockingFailureException ex) {
            // 일반 충돌 코드로 변환 (의미 코드로 좁히지 않는다 — 4장 참고)
            throw new PaymentException(PAYMENT_CONCURRENTLY_MODIFIED);
            // 주의: catch 안에서 추가 DB 쓰기 금지 — 이 시점 tx는 rollback-only.
        }
    }

    // 순수 전파→409 경로는 변환 없이 plain save로 두고 GlobalExceptionHandler에 맡겨도 된다.
    @Override
    public Payment save(Payment p) {
        return jpa.save(p);
    }
}
```

### 3.3 skip 정책 — 쓰는 Service 안의 private 메서드

skip("충돌이면 조용히 넘어감")은 보통 **그것을 쓰는 흐름 하나에만** 의미가 있다. 별도 클래스로
빼지 말고 그 Service 안의 private 메서드로 둔다. 흐름 바로 옆에 있어 "이 skip이 왜 허용되는지"의
맥락이 코드에 남고, 클래스·빈·주입이 안 늘어난다.

```java
// application/usecase/ — 보상 orchestrator, tx 없음 (외부 호출은 tx 밖, DB 쓰기만 짧은 tx — → PR#97)
@Component
class PaymentApprovalCompensationUseCase {

    private static final Set<PaymentErrorCode> SKIPPABLE = EnumSet.of(
        PAYMENT_CONCURRENTLY_MODIFIED,          // 버전 충돌 = 누가 이미 전이시킴
        PAYMENT_STATUS_TRANSITION_NOT_ALLOWED   // 가드 위반 = 이미 다른 상태
    );

    private final PaymentTransitionService txService;   // 별도 빈 (프록시 위해 필수)
    private final PaymentCancellationService cancellation;
    private final PgCanceller pgCanceller;

    public void runPgCancel(Long paymentId, ...) {
        markUnknownBestEffort(paymentId, ...);   // 충돌이면 아래에서 조용히 skip → 환불 안 끊김
        cancellation.getOrCreate(paymentId, ...); // CANCEL row 생성 (자기 tx)
        pgCanceller.cancel(paymentId, ...);       // PG 환불 외부 호출 (tx 밖)
    }

    // 이 보상 흐름에서만 의미 있는 skip 정책 → private. catch는 tx 경계 "밖"(이 메서드엔 @Transactional 없음)
    private void markUnknownBestEffort(Long paymentId, ...) {
        try {
            txService.markUnknown(paymentId, ...);   // 여기서 tx 열리고 닫힘(충돌이면 rollback 후 예외)
        } catch (PaymentException e) {
            if (SKIPPABLE.contains(e.errorCode())) {
                log.warn("이미 전이됨 → 마킹 skip: payment={}, code={}", paymentId, e.errorCode());
                return;                              // 흡수 = 이 흐름의 정책
            }
            throw e;                                  // 예상 밖이면 전파
        }
    }
}
```

> **별도 클래스로 빼는 건 skip을 여러 Service가 공유할 때만.** 한 흐름에서만 쓰면 private이 더 단순하고,
> "각 Service가 자기 책임 코드를 자기 안에 명시한다"는 원칙(architecture.md: 하나의 Service는 하나의
> 유스케이스 행위)에도 맞는다. 두 번째 사용처가 생기는 순간 추출하면 된다.

### 3.4 retry 정책 — skip과 같은 기준 (한 곳이면 private, 여러 곳이면 helper)

retry의 배치 기준은 skip과 **동일**하다 — 쓰는 곳이 하나면 private 메서드, 여러 곳이면 추출. retry라고
무조건 helper로 빼는 게 아니다.

**한 곳에서만 쓰면 → private 메서드** (예: Stock 차감에서만 재시도)

```java
@Component
class StockDecreaseUseCase {   // application/usecase/
    private final StockService txService;   // application/service/ — 별도 빈 (프록시 위해 필수)

    public void decrease(Long id, int qty) { decreaseWithRetry(id, qty, 3); }

    // retry를 private으로 — 한 곳뿐이면 이게 더 명시적
    private void decreaseWithRetry(Long id, int qty, int maxAttempts) {
        for (int attempt = 1; ; attempt++) {
            try {
                txService.decrement(id, qty);   // 매 시도 새 tx (별도 빈 → 프록시 → 새 tx → fresh read)
                return;
            } catch (PaymentConcurrencyConflictException e) {
                if (attempt >= maxAttempts) throw e;
                // 짧은 backoff + jitter
            }
        }
    }
}
```

> 함정 하나: retry 루프가 부르는 대상은 **별도 빈**이어야 한다. 자기 클래스의 다른 메서드를 부르면
> self-invocation으로 tx 프록시가 안 먹어 "매 시도 새 tx"가 깨진다. 대상이 별도 빈인 한 private retry도 안전하다.

**여러 도메인에서 쓰면 → 범용 helper** (Stock·Cart·Payment가 같은 retry를 공유)

같은 루프·maxAttempts·backoff를 복붙하게 되면 그때 추출한다. 특정 Service의 책임이 아니므로
`support/`·`common/` 등에 둔다.

```java
@Component
class OptimisticRetry {   // support/ 또는 common/
    <T> T execute(int maxAttempts, Supplier<T> txUnit) {
        for (int attempt = 1; ; attempt++) {
            try {
                return txUnit.get();                  // 매번 새 tx (txService 호출)
            } catch (PaymentConcurrencyConflictException e) {  // 도메인 충돌 예외를 키로
                if (attempt >= maxAttempts) throw e;
            }
        }
    }
}
// 호출: retry.execute(3, () -> stockService.decrement(id, qty));
```

> skip이든 retry든 같은 규칙이다: **한 곳이면 private(흐름 옆에 명시), 여러 곳이면 추출.**
> retry가 skip보다 추출 빈도가 높은 건 "retry는 마침 여러 도메인이 쓰는 경우가 잦아서"일 뿐,
> retry라서 항상 helper인 것은 아니다.

---

## 4. 충돌 정책 3종 — 어디서 갈리나

> 핵심 원칙: **충돌 정책은 엔티티/리포지토리의 속성이 아니라 use-case의 속성이다.**
> 그래서 tx 단위작업은 정책을 모르고, **orchestrator가 매번 결정**한다.
> 같은 `txService.markUnknown` 단위작업이 호출 맥락에 따라 세 정책으로 갈린다.

| 정책 | 어디서 | 언제 | 어떻게 |
|------|--------|------|--------|
| **전파(기본)** | 코드 없음 | 클라이언트가 요청 전체를 재시도하면 되는 경우 | 도메인 예외가 GlobalExceptionHandler로 → 409 |
| **skip(best-effort)** | 쓰는 Service의 private 메서드 (tx 밖) | 보상의 마킹처럼 "충돌=이미 처리됨=내 작업 무의미" | 도메인 예외 catch → 로그 → 계속 |
| **retry** | 별도 헬퍼 (tx 밖) | Stock/Cart 같은 정당한 고경합 | **단위작업 전체를 새 tx로 재실행**(매 시도 fresh read) |

### 4.1 호출 맥락별 매핑

- **실시간 승인** → `txService.markUnknown` 직접 호출, catch 안 함 → 충돌은 409.
- **보상(runPgCancel)** → private `markUnknownBestEffort` → 충돌/가드 위반이면 skip 후 PG 환불 진행.
- **대사 루프(reconcile)** → `processOne`(별도 빈, 자기 tx)을 루프에서 건별 try-catch로 격리(skip-and-continue).

### 4.2 retry 헬퍼 (코드는 3.4 참조)

retry는 skip과 같은 기준으로 배치한다(한 곳이면 private, 여러 곳이면 helper, 3.4). 결정적인 두 가지:

1. 죽은(rollback-only) tx 안에서는 재시도 못 한다 → **반드시 새 tx로 재진입**(단위작업 통째로 재호출).
2. 엔티티도 **시도마다 새로 find**(stale 엔티티 재사용 금지).

> **주의**: adapter가 `ObjectOptimisticLockingFailureException`을 도메인 예외로 변환하면
> `@Retryable(ObjectOptimisticLockingFailureException.class)`는 안 먹는다(이미 변환됨).
> retry는 **도메인 충돌 예외/코드**를 키로 해야 한다. 그래서 orchestrator가
> "재시도 가능한 충돌"과 "비즈니스 규칙 위반"을 구분하도록 **충돌 전용 코드가 필요**하다.

---

## 5. 도메인 예외 매핑 — 일반 코드 vs 의미 코드

### 5.1 변환 자체는 맞다, 단 분기 경로에서만

adapter에서 `OptimisticLockException`을 도메인 예외로 변환하는 것은 코드베이스 패턴(선례 saveUsed/saveApproved)과
일관되며 "application/domain은 Spring DAO 타입에 의존 안 함"을 지킨다. **단, skip/retry 분기가 필요한
경로에서만 변환**한다. 순수 전파→409 경로는 변환 없이 GlobalExceptionHandler까지 올려보낸다.

### 5.2 @Version은 행 단위지 컬럼 단위가 아니다

버전 충돌은 "이 행이 내가 읽은 뒤 누군가 커밋했다"만 알려줄 뿐, **어느 컬럼/어느 연산이 충돌했는지는
알려주지 않는다.** 동시 succeed/fail/markUnknown이 전부 같은 version을 올리기 때문이다.
"A 컬럼 충돌 vs B 컬럼 충돌" 구분은 @Version으로는 **원천적으로 불가능**하다.
(그게 진짜 필요하면 @Version이 틀린 도구 → 조건부 UPDATE나 관심사별 별도 version 컬럼.)

### 5.3 매핑 granularity 원칙

**정책이 갈라지는 단위로 매핑하라. 더 잘게도, 더 굵게도 하지 마라.**

- 버전 충돌은 보통 정책이 하나(skip 또는 retry)라서 → **단일 `PAYMENT_CONCURRENTLY_MODIFIED`로 충분**.
  (단, 진단용으로 엔티티 타입 + id는 예외에 실어라.)
- **버전 충돌과 unique 위반은 절대 한 코드로 합치지 마라.** 정책이 다르다
  (unique=중복→보상, version=재시도/skip). `PAYMENT_DUPLICATE`(unique)와
  `PAYMENT_CONCURRENTLY_MODIFIED`(version)는 별개로 유지.
- 정책 레이어가 "충돌 났는데 *지금 상태가 뭔지* 알아야" 분기하는 경우 → **예외에 담지 말고 재조회(fresh read)**.
  이긴 트랜잭션이 어떤 상태로 커밋했을지 예외가 신뢰성 있게 못 담는다.
  → "충돌 예외 = 누가 옮겼으니 멈춰", "어디로 옮겼는지 알아야 하면 = 다시 읽어"로 분리.

### 5.4 의미 코드(`ALREADY_USED` 류)는 전제가 좁다 — 기본값으로 삼지 마라

reservation의 `saveUsed`가 충돌을 `PAYMENT_RESERVATION_ALREADY_USED`로 번역할 수 있는 이유는,
그 경로에서 **버전 충돌을 일으킬 수 있는 동시 쓰기가 의미상 "이미 사용됨" 하나뿐**이라
"버전 충돌 = 이미 사용됨"이 **1:1로 성립**하기 때문이다.

**이 전제는 코드가 커지면 조용히 깨진다.** 같은 행에 다른 동시 쓰기 경로(예: 메모 수정, 만료시각 갱신)가
하나만 추가돼도:

```
T1: reservation 읽음 (version=5)
T2: 같은 reservation의 메모 수정 후 commit (version=6)   ← 사용 처리가 아님
T1: saveUsed → version=5로 UPDATE 시도 → 충돌
    → 실제론 "이미 사용됨"이 아닌데 코드는 무조건 ALREADY_USED를 던짐  ← 거짓 양성
```

더 나쁜 건 이게 **컴파일 에러로 안 잡힌다**는 점이다. 보이지 않는 결합이라 유지보수 리스크가 고약하다.

**권장 디폴트**: 충돌은 일반 코드(`CONCURRENTLY_MODIFIED`)로 정직하게 던지고,
"이미 사용됨인지"가 필요하면 **재조회로 판정**한다. 전제에 안 기대고, 동시 쓰기 경로가 늘어도 안 깨진다.

**의미 코드를 써도 견고한 예외 케이스**(전제가 아니라 구조/도메인이 1:1을 보장할 때):

- 충돌 단위가 의미 단위와 구조적으로 일치 — 예: 조건부 UPDATE(`WHERE status='AVAILABLE'`)로
  "사용됨 경합"만 떼어내면 `0행 = 진짜 이미 사용됨`이 **보장**된다.
- 엔티티가 도메인상 단일 목적인 게 자명 — 일회성 토큰 소비, 멱등 키 점유 등.

> 기존 reservation 선례를 지금 당장 바꿀 필요는 없다(현재 1:1이면 정확하므로).
> 대신 **"이건 reservation이 단일 목적인 동안에만 정직하다"는 전제를 주석/테스트로 박아두는 것**이
> 실용적 절충이다. 새로 짜는 Payment 전이는 처음부터 일반 코드 + 재조회로 간다.

---

## 6. 트랜잭션 경계를 어디에 두나

1. **트랜잭션은 service 패키지(tx 단위작업)에서만 관리.** 한 트랜잭션 = **"불변식이 원자적으로 성립해야 하는 최소 집합"**.
   - 대개 한 애그리거트지만 **항상은 아니다.** `succeedApproval`은 `payment.succeed()`와
     `order.completePayment()`가 **반드시 함께 커밋**돼야 하므로(중간 상태가 보상 불가능) 두 애그리거트를
     한 tx로 묶는다. order 행은 `findByIdForUpdate`(비관 락)로 직렬화, payment는 @Version(낙관 락)으로
     전이 가드 — 한 tx 안에 락 전략을 섞어도 된다.
   - 디시플린은 "무조건 1애그리거트"가 아니라 **"원자성이 필요한 최소 집합까지만 묶고, 그 이상(많은
     애그리거트, 외부 호출)으로 번지지 않게 한다"**.

2. **orchestrator는 트랜잭션을 절대 열지 않는다.** (기존 보상 경계 결정 그대로, → PR#97·PR#118·PR#125)
   외부 호출과 단계별 독립 commit을 순서대로 호출만 하고, 한 단계 실패가 이전 단계를 롤백하지 못하게 한다.

3. **충돌 정책은 orchestrator에서만 결정.** tx 단위작업은 정책을 모른다.

### 6.1 두 단위작업을 한 tx로 묶고 싶을 때 — usecase가 아니라 service 패키지에서

각 tx 단위작업이 자기 `@Transactional`을 갖는데, 두 개를 **반드시 함께 커밋**시키고 싶을 때가 있다
(`payment.succeed()` + `order.completePayment()`처럼 중간 상태가 보상 불가능한 경우). 이때 **usecase에
`@Transactional`을 달면 안 된다.**

```java
// ✗ usecase에 @Transactional — 하면 안 됨
@Component
class NaverPayApprovalUseCase {   // application/usecase/
    @Transactional                  // ← usecase가 tx를 열게 됨
    public void approve(...) {
        pgGateway.approve(...);     // ← 외부 호출이 tx 안으로 빨려들어감 ("외부 호출은 tx 밖" 규칙 위반!)
        paymentTxService.succeed(...);
        orderTxService.complete(...);
    }
}
```

문제 둘: (1) "usecase는 tx를 안 연다"는 규칙(ArchUnit 강제)이 깨진다. (2) usecase엔 보통 PG 호출 같은
외부 호출이 섞여 있어, tx를 달면 그 외부 호출이 트랜잭션 안으로 들어가 행 락을 잡은 채 PG를 기다린다("외부 호출은 tx 밖" 규칙 위반).

대신 **함께 커밋할 둘을 감싸는 전용 메서드를 `service` 패키지에 만들고 거기에만 tx를 단다.**

```java
// ✓ 두 애그리거트를 한 tx로 묶는 전용 단위작업 — application/service/
@Service
class PaymentApprovalService {
    @Transactional   // ← tx는 여기. 딱 두 DB 쓰기만 감쌈
    public void succeedApproval(Long orderId, Long paymentId, ...) {
        Order order = orderRepo.findByIdForUpdate(orderId);   // 비관 락
        Payment payment = paymentRepo.findById(paymentId).orElseThrow(...);
        payment.succeed(...);          // 낙관 락(@Version)
        order.completePayment();
        // 둘 다 이 tx 안에서 함께 커밋 — 원자성
    }
}

// usecase는 호출만 (tx 없이)
pgGateway.approve(...);                          // 외부 호출 (tx 밖)
approvalService.succeedApproval(orderId, ...);   // 묶인 tx 호출
```

두 가지를 지킨다:
- **묶음 메서드는 다른 tx 서비스를 부르기보다 리포지토리/도메인 객체를 직접 다뤄 한 메서드 안에서 완결한다.**
  `succeedApproval`이 `paymentTxService.succeed()`+`orderTxService.complete()`를 부르면 REQUIRED라 합류는
  하지만, 그 서비스들이 독립 호출도 되는 거라 책임이 모호해진다. 한 메서드 안에서 `payment.succeed()` /
  `order.completePayment()`를 직접 부르는 게 깨끗하다.
- 한 tx 안에 **락 전략을 섞어도 된다**(order는 비관, payment는 낙관). 경합 지점은 비관으로 직렬화,
  전이 가드는 @Version으로.

> 원칙 한 줄: **tx 경계는 "함께 커밋돼야 하는 것들"을 감싸는 메서드에 달리고, 그 메서드는 `service`
> 패키지에 산다.** 단일이면 `PaymentTransitionService.markUnknown`, 둘을 묶으면
> `PaymentApprovalService.succeedApproval` — 둘 다 service 패키지의 메서드이고, 묶는 단위만 다르다.

### 6.2 동기·비동기 모두에서 동일하다

"tx는 service 패키지에서만 연다"는 규칙은 **동기·비동기와 무관하게 유지된다.** 비동기는 tx 경계의 위치를 옮기는 게 아니라, **여러 tx를 잇는 방식**만 바꾼다.

| | tx는 service에서만? | 흐름을 잇는 주체 | 진입점 |
|---|---|---|---|
| 동기 | 예 | orchestrator(`usecase`)가 같은 스레드에서 순서대로 호출 | Controller |
| 비동기 | 예 | 메시지 브로커(Kafka)가 다른 스레드/시간에 연결 | `@KafkaListener`(consumer) |

비동기로 가면 한 흐름이 여러 tx로 쪼개지지만, 각 조각도 여전히 "진입점(consumer) → tx 단위작업"이다. 추가로 신경 쓸 것은 메시지 중복 전달 대비 **멱등성**뿐이며, 이는 "tx를 어디서 관리하나"가 아니라 "tx 안 작업을 멱등하게 짜나"의 문제다.

> 패키지명 주의: tx 단위작업 묶음을 `application/service/`로 둔다(클래스명도 `…Service`). `service`는 평이해서
> "여기만 tx를 연다"는 의도가 이름에 안 드러나므로, 그 의도는 패키지 주석과 ArchUnit("@Transactional은 service에만")으로
> 보완한다. 대안으로 `transaction`/`tx`처럼 의도를 직접 드러내는 이름도 가능하다.

---

## 7. 두 가지 도구 — 낙관 락(catch) vs 조건부 UPDATE

충돌을 다루는 방법은 둘이다. **어느 하나가 기본이고 다른 하나가 예외가 아니다.** 둘은 트레이드오프 관계이고,
**전이의 성격**에 따라 고른다.

| | 낙관 락 + catch | 조건부 UPDATE |
|---|---|---|
| 충돌 표현 | **예외**(`OptimisticLockException` → 도메인 예외) | **숫자**(`affected rows = 0`) |
| 전이 로직 위치 | **엔티티 메서드**(도메인 안) | **SQL**(repository) |
| read-modify-write | 읽고 → 가드 → 쓰기(버전 검증) | 단일 원자 UPDATE(가드를 WHERE에) |
| 동시성 메커니즘 | @Version 행 버전 비교 | InnoDB 행 락(X-lock) + WHERE 가드 |
| tx 딜레마 | catch 위치를 tx 밖으로 빼야 함(1·2장) | 예외가 없어 딜레마 자체가 없음 |
| 풍부한 불변식 | **강함**(엔티티가 표현) | 약함(SQL로 끌어내려야 함, DDD 냄새) |

핵심은 **상호 배타가 아니라 적합도**다. 같은 코드베이스에서 전이마다 다른 도구를 써도 된다.

### 7.1 낙관 락 + catch — 전이가 도메인 로직을 품을 때

다음 중 하나라도 해당하면 낙관 락이 맞다. 이게 이 코드베이스의 주력이다.

- **전이가 단순 대입이 아니다.** 현재 상태에 따라 다음 상태/필드를 계산하는 불변식이 있다(`payment.succeed()` 등).
  이걸 SQL `SET`으로 옮기면 도메인 로직이 repository로 샌다.
- **여러 필드를 함께 바꾸고 그게 엔티티 불변식으로 묶여 있다**(status + respondedAt + failDetail 등).
- **도메인 이벤트/후처리가 엔티티 전이에 걸려 있다.**
- **여러 애그리거트가 한 tx에 얽힌다**(`succeedApproval`: payment.succeed + order.completePayment → 낙관/비관 락 혼용).

이때 1~3장의 설계(tx 단위작업이 전파, orchestrator가 tx 밖에서 private 메서드로 catch)를 그대로 적용한다.

### 7.2 조건부 UPDATE — 전이가 단순 멱등 플립일 때

전이가 "현재 status가 X일 때만 Y로 바꾸는" 단순 플립이고 도메인 로직을 거의 안 품으면, 조건부 UPDATE가
충돌을 **예외에서 숫자로** 바꿔 1·2장의 딜레마(catch 위치, rollback-only)를 통째로 없앤다.

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("update Payment p set p.status = 'UNKNOWN', p.version = p.version + 1, ... " +
       "where p.id = :id and p.status = 'REQUESTED'")
int markUnknownIfRequested(@Param("id") Long id, ...);
// 반환 0 = 이미 누가 전이함 → skip (예외 없음), 1 = 완료
```

가드("REQUESTED일 때만")를 SQL에 **원자적으로** 넣어 read-modify-write 레이스도 `OptimisticLockException`도
아예 없앤다. orchestrator는 반환값만 보고 진행 → 예외가 없으니 rollback-only도 없고,
같은 tx 안에서 해도 `UnexpectedRollbackException`이 안 난다.

> 주의: 여러 필드를 보더라도 **셀 수 있는 단순 조건**이면 여전히 조건부 UPDATE가 가능하다
> (`WHERE status='REQUESTED' AND amount=:expected`). 갈림길은 "WHERE 조건이 복잡하냐"가 **아니라**
> "전이가 단순 플립이냐, 도메인 로직을 품었냐"다.

#### 동시성 메커니즘 (@Version 아님, InnoDB 행 락 + WHERE 가드)

```
T1: UPDATE ... WHERE id=X AND status='REQUESTED'   -- 행 X에 X-lock 획득
T2: 같은 UPDATE 실행 → 같은 행 X-lock 요청 → 대기(blocked)
T1: commit → 행은 status='UNKNOWN'
T2: unblock → 최신 커밋본을 재평가(UPDATE는 스냅샷이 아닌 latest committed를 읽음)
    → status가 'REQUESTED' 아님 → WHERE 불일치 → 0 rows affected
```

- "하나만 동작, 진 쪽은 커밋 전까지 락 대기"가 맞다.
- 단 **진 쪽은 예외를 안 받고 `affected rows = 0`을 받는다** — 충돌이 "예외"에서 "0이라는 숫자"로 바뀐다.
- 격리 수준(RC/RR)과 무관하게 net 결과는 동일(UPDATE는 항상 최신 커밋본에 대해 동작).

#### 주의 3가지

- **짧게.** 행 X-lock은 holder가 commit할 때까지 잡힌다. 그 사이 PG 같은 외부 호출을 끼우면
  대기자가 `innodb_lock_wait_timeout`에 걸린다 → 다시 "외부 호출은 tx 밖" 규칙(→ PR#97).
- **managed @Version 엔티티와 섞지 마라.** 같은 영속성 컨텍스트에 그 Payment를 managed로 로드해두고
  bulk `@Modifying`을 날리면 이후 stale version 불일치가 날 수 있다 →
  `@Modifying(clearAutomatically=true, flushAutomatically=true)` 또는 managed 인스턴스를 들고 있지 말 것.
- 조건부 UPDATE는 **전이 로직을 SQL로 끌어내린다**(DDD 냄새). 이 비용이 단순 플립에선 작지만,
  로직이 두꺼워지는 순간 커진다.

### 7.3 선택 기준 정리

| 상황 | 선택 | 이유 |
|------|------|------|
| 전이가 도메인 로직을 품음(계산된 다음 상태, 여러 필드 일관 변경, 도메인 이벤트) | **낙관 락 + catch** | 엔티티가 불변식을 표현, 로직이 domain에 남음 |
| 여러 애그리거트 + 불변식이 얽힘 (succeedApproval) | **낙관/비관 락 + 한 tx** | 원자성 최소 집합을 한 tx로 |
| 전이가 단순 멱등 status 플립 (markUnknownIfRequested, failIfPending 등) | **조건부 UPDATE** | 충돌이 예외→0행, 딜레마 소멸 |
| 고경합 카운터성 (Stock/Cart 차감) | **낙관 락 + retry** | 정당한 동시 갱신을 재시도로 수렴 |

> 트레이드오프 한 줄: **조건부 UPDATE는 충돌을 "예외→0행"으로 바꿔 tx 딜레마를 없애주지만,
> 그 대가로 전이 로직을 SQL로 끌어내린다.** 그 대가가 단순 플립에선 이득, 도메인 로직이 두꺼우면 손해다.
> 그래서 "조건부 UPDATE를 기본으로" 가 아니라 **"전이 성격을 보고 둘 중에서 고른다"** 가 맞다.
> 이 코드베이스의 주력은 낙관 락 + catch(1~3장)이고, 조건부 UPDATE는 단순 멱등 플립에서 선택하는 도구다.

---

## 8. #243이 위반한 것 → 본 설계가 바로잡는 것

| # | #243의 문제 | 위반한 패턴 | 본 설계의 교정 |
|---|------------|------------|---------------|
| 1 | application이 DAO 예외를 직접 catch | adapter 변환 / application은 DAO 타입 의존 금지 | adapter가 도메인 예외로 변환, **상위는 도메인 예외만** catch |
| 2 | `@Transactional` 제거 | 단계별 독립 commit | tx 단위작업에 `@Transactional` 유지, **catch는 tx 밖**으로 이동 |
| 3 | 흡수(skip)를 tx 안에 도입 | 충돌=전파, 흡수 안 함(rollback-only) | 흡수는 유지하되 **orchestrator 레이어(tx 밖)로 재배치** |

> 이 설계는 기존 주석("approve가 race window에서 이미 SUCCEEDED여도 PG cancel은 멈추지 않는다")의
> 의도를 **구조적으로 강제**한다: tx 단위작업은 던지고 / orchestrator의 private skip이 흡수하고 / 보상은 계속.
> 예전엔 tx 안 in-line catch에 의존하던 의도가 이제 레이어 책임으로 표현된다.

---

## 9. 배경 지식 — 무엇을 공부하면 되나

이 코드베이스는 **이미 헥사고날(포트앤어댑터)**이다("domain에 repository 인터페이스(port),
infrastructure에 구현(adapter)", "application은 Spring DAO 타입에 의존 안 함"). 새 패러다임이 아니라
**이미 쓰는 것의 이름·원리 정리** 차원이라 짧게 봐도 된다.

다만 헥사고날은 **트랜잭션 경계나 동시성 충돌 정책을 직접 다뤄주지 않는다.** 이번 문제의 알맹이는:

- **Spring 트랜잭션**: 전파(REQUIRED / REQUIRES_NEW), 프록시 self-invocation,
  rollback-only / `UnexpectedRollbackException`.
- **JPA 낙관 락**: `@Version`의 **행 단위** 의미, **flush 시점**.

헥사고날은 "클래스를 어디 두나"의 골격을 주고, 위 두 묶음이 "그 안에서 트랜잭션·충돌을 어떻게 다루나"의
알맹이를 준다.
