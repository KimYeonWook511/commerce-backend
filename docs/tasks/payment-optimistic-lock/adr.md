# Task ADR (staging)

> 이 파일은 이번 Task에서 **새로 채택된** 결정만 쌓는 staging 로그다.
> 루트 ADR을 복사해 오지 않는다. 여기 번호(L1, L2…)는 task 내 임시 번호이며,
> Stage 8(Root Sync)에서 루트 전역 번호(ADR-XXXX)로 다시 부여하며 루트에 append한다.
> 탐색만 하고 채택하지 않은 안은 별도 레코드로 만들지 않고, 채택된 결정의 `고려한 대안`에 적는다.

---

## ADR-L1: Payment 동시 전이 방어로 `@Version`(낙관 락)을 도입한다

- 상태: accepted
- supersedes: 없음
- superseded-by: 없음

### 배경

- `Payment`만 `@Version`이 없어, 같은 행을 동시 read-modify-write로 전이하는 경합(`succeed` vs `fail` 등)에서 lost update가 가능하다. 다른 핵심 엔티티(Order/PaymentReservation/CartItem/Stock)는 모두 `@Version`을 갖는다. 방어 수단으로 낙관 락(`@Version`)과 비관 락(행 FOR UPDATE) 중 선택이 필요했다.

### 고려한 대안

- **비관 락(payment 행 FOR UPDATE)**: 같은 행 전이를 대기로 직렬화한다. 그러나 (a) payment+order DB 쓰기 트랜잭션은 매우 짧고(상태 전이 2회뿐) 충돌 빈도가 낮아 비관 락의 대기-직렬화 이점이 작고, (b) 부수효과(메일/알림/포인트)는 결제 성공 commit 이후 별도 실행하는 원칙이 이미 확립돼 있어 핵심 상태 변경 tx이 구조적으로 짧게 유지된다. 비관 락이 유리한 건 tx 길이가 아니라 충돌 빈도가 높을 때(batch 동시 수정 등)인데 현재 그런 워크로드가 없다. 또 다른 엔티티가 모두 `@Version`이라 비관 락은 일관성을 깬다.

### 결정 내용

- `Payment`에 `@Version Long version`을 추가하고, Flyway로 `version BIGINT NOT NULL DEFAULT 0` 컬럼을 추가한다(`PaymentReservation`에 version을 추가한 직전 선례와 동일 형태).
- 같은 행 동시 전이는 `@Version` 불일치(`OptimisticLockException`)로 감지한다.

### 근거

- 짧은 tx·낮은 충돌·부수효과 분리라는 현재 워크로드에서 낙관 락이 적합하다. 자동 재시도 루프는 두지 않고 충돌은 흡수(종착 전이) 또는 전파(succeed)로만 처리한다(ADR-L2). `@Version`은 기존 방어(생성=Reservation `@Version`, 이중 SUCCEEDED=`uk_payment_approved_order_key`, 승인 직렬화=order 비관 락)와 직교하며 그 위에 같은 행 동시 전이 방어를 더한다.

### 결과

- 결제 동시성 방어의 일관성 누락이 해소된다. order `findByIdForUpdate` 비관 락은 payment+order 원자성·승인 반영 직렬화 목적이라 유지한다(낙관 전환은 범위 밖 — 부분취소 등 여러 행 합산 계산 판단이 도입되면 재판단).

---

## ADR-L2: 충돌 처리 — transition(tx 안 변환 전파) + useCase(tx 밖 skip), adapter가 도메인 예외로 변환

- 상태: accepted
- supersedes: 없음 (본 task 내 이전 초안 "메서드 의도별 application 흡수"를 폐기하고 본 결정으로 대체)
- superseded-by: 없음

### 배경

- `@Version` 도입으로 전이 시 `OptimisticLockException`(`ObjectOptimisticLockingFailureException`)이 **flush 시점** 발생한다. 충돌이 나면 그 트랜잭션은 **rollback-only**로 마킹된다.
- 보상·best-effort 경로(`markUnknownIfRequested`/`failIfPending`)는 충돌을 **흡수(skip)** 해야 한다(예외를 던지면 보상 흐름의 PG 환불이 끊김 — `runPgCancel` 단계 독립 commit 정책). 그런데 흡수를 **트랜잭션 안에서** 하면(같은 메서드에서 catch 후 정상 리턴) commit 시점에 `UnexpectedRollbackException`이 난다. `@Transactional(REQUIRES_NEW)`를 붙여도 같은 메서드 안 catch면 동일하다.
- 코드베이스 기존 정책: 인프라 예외(DAO)는 application/domain이 직접 의존하지 않고 **adapter에서 도메인 예외로 변환**(선례 `saveUsed`/`saveApproved`). 보상 흐름은 각 단계가 자기 `@Transactional`로 **독립 commit**(`docs/tasks/payment-compensation-to-domain/adr.md`).

### 고려한 대안

- **application에서 `OptimisticLockException` 직접 catch + `@Transactional` 제거**(본 task 초기 구현): DAO 타입 직접 의존(정책 위반) + 보상 단계별 독립 commit 깨짐 + 트랜잭션 경계가 "호출자가 tx를 안 연다"는 암묵 전제에 의존. 기각.
- **메서드에 `@Transactional(REQUIRES_NEW)` + 내부 catch**(코드 리뷰어 제안): 같은 메서드 안 catch라 그 새 트랜잭션이 rollback-only가 되어 `UnexpectedRollbackException`이 여전하다. 기각.

### 결정 내용

문제의 본질은 "흡수했다"가 아니라 **"흡수를 트랜잭션 안에서 했다"** 이다. 흡수를 트랜잭션 경계 밖으로 옮긴다.

- **transition**(별도 빈, **public `@Transactional`**): `find → 도메인 전이 → saveChecked`. 충돌도 가드 위반도 catch 안 함 → 도메인 예외 전파 → 트랜잭션 깨끗이 rollback.
- **adapter `saveChecked`**(신규): `saveAndFlush`로 flush를 adapter 프레임 안으로 당겨와 `ObjectOptimisticLockingFailureException`을 `PaymentException(PAYMENT_CONCURRENTLY_MODIFIED)`으로 변환 throw. (`saveAndFlush`는 "실패할 save를 성공시키는 도구"가 아니라 "충돌을 잡을 위치로 flush를 당겨오는 도구" — `saveUsed`/`saveApproved` 선례.)
- **useCase**(= orchestrator: 실시간 승인·보상·대사 흐름, **트랜잭션 없음**): transition을 호출하고, skip이 필요하면 **useCase의 private 래퍼 메서드**에서 도메인 예외(`PAYMENT_CONCURRENTLY_MODIFIED`, 가드 위반 `PAYMENT_STATUS_TRANSITION_NOT_ALLOWED` 등 skip 대상)를 catch해 skip. catch가 트랜잭션 경계 밖이라 rollback-only와 무관.
- **함정(반드시 지킬 것)**: transition은 useCase와 **별도 빈의 public 메서드**여야 한다(private이면 `@Transactional` 무효, 같은 빈 self-call이면 프록시 우회로 무효). useCase에는 `@Transactional`을 **달지 않는다**(달면 흡수가 다시 트랜잭션 안으로 들어가 원래 문제로 회귀).
- **`succeed`·무조건 `fail`**: skip 안 함 → 전파 → 409. succeed가 졌다 = 누가 먼저 종착. 상대가 SUCCEEDED면 재호출 시 사전 find가 멱등 흡수, FAILED/UNKNOWN이면 모순이라 드러나야 한다. 단 이 "전파" 원칙은 **APPROVE 종착 전이** 기준이다(과금됐는데 실패 기록되는 모순을 막기 위함). CANCEL `succeed`/`fail`은 보상 useCase(`runPgCancel`)의 best-effort `catch(PaymentException)`가 충돌(`PAYMENT_CONCURRENTLY_MODIFIED`)을 멱등 흡수한다 — 진 쪽은 이미 다른 주체가 같은 CANCEL 레코드를 종착시킨 중복 보상이라 흡수가 옳고, 미해소분은 REQUESTED로 남아 CANCEL 대사(Epic #208)에서 재확정된다.

### 예외 코드 granularity (의미 코드 vs 일반 코드)

- 충돌은 **일반 코드**(`PAYMENT_CONCURRENTLY_MODIFIED` = "다른 처리가 먼저 상태를 바꿈")로 던지고, "이미 무엇이 됐는지"가 필요하면 **재조회**로 판정한다.
- unique 위반(`PAYMENT_DUPLICATE`)과 version 충돌은 정책이 달라(중복→보상 vs 재시도/skip) 절대 한 코드로 합치지 않는다.
- 의미 코드(`PAYMENT_RESERVATION_ALREADY_USED` 류)는 "version 충돌 = 이미 사용됨"이 1:1로 성립하는 전제 위에서만 정직하다. 같은 행에 다른 동시 쓰기 경로가 하나만 늘어도 거짓 양성이 되고 컴파일로 안 잡힌다. 새로 짜는 전이는 일반 코드 + 재조회로 간다.

### 근거

- 흡수 위치를 트랜잭션 경계 밖(useCase)으로 옮기면 기존 모든 정책과 양립한다: adapter 변환 / DAO 타입 격리 / 보상 단계별 독립 commit / 보상 메서드는 예외 안 던짐. 상세 설계·코드 스케치는 외부 설계 논의(`payment-optimistic-lock-design.md`, repo 미커밋 — 별도 보관)에 있다.

### 결과

- transition/useCase 분리로 흡수가 트랜잭션 밖에서 안전하게 일어난다. 낙관 락 충돌 처리가 코드베이스 정책과 일관된다. 루트 `docs/exception-strategy.md`에 "낙관 락 충돌 처리" 섹션으로 정본화 예정(별도 작업). CANCEL 전용 동시 충돌 재현 테스트는 CANCEL 대사 미구현이라 Epic #208로 위임(메커니즘 검증은 APPROVE 경로 결정적 충돌 테스트가 담당).

---

## ADR-L3: escalation을 조건부 UPDATE(CAS)에서 `escalate()` 도메인 메서드 + `@Version`으로 환원한다

- 상태: accepted
- supersedes: `payment-escalation` task adr의 escalation 멱등 메커니즘(조건부 UPDATE 영향 행 수) 부분
- superseded-by: 없음

### 배경

- 기존 escalation(6시간 초과 미확정 APPROVE를 운영자에게 위임 표시)은 repository 조건부 UPDATE(`UPDATE Payment SET escalatedAt=:now WHERE id=:id AND escalatedAt IS NULL AND status IN (UNKNOWN,REQUESTED)`)로 하고, 영향 행 수=1을 통지 주체로 본다. 이 WHERE의 `status IN (...)`·`escalatedAt IS NULL`은 사실 도메인 규칙(어떤 상태에서 escalation 가능한가, 한 번만)이 SQL로 표현된 것이다. `succeed`/`fail`은 같은 종류 규칙을 엔티티 메서드 가드로 갖는데 escalation만 SQL에 나가 있는 비대칭이 있었다.
- `payment-escalation`이 그 비대칭을 의도적으로 택한 이유는 명시돼 있다: "`Payment`에 `@Version`이 없어 메모리 가드로는 동시 race에서 중복 통지를 막지 못하므로 DB 레벨 원자성(영향 행 수)으로 멱등을 보장한다." 이번 Task가 `@Version`을 도입하면 그 전제가 사라진다.

### 고려한 대안

- **CAS 유지 + version 수동 bump**: 조건부 UPDATE에 `version = version + 1`을 추가한다. `@DynamicUpdate`가 코드베이스에 없어 엔티티 save가 전체 컬럼 UPDATE를 하므로, version을 bump하지 않으면 동시 `fail()` save가 `escalatedAt`을 stale 값(null)으로 덮어써 `@Version` 도입이 오히려 escalation에 새 lost update를 유발한다. 그래서 version bump가 필수가 되는데, "JPA가 자동 관리하는 version을 JPQL에서 수동 bump"라는 비표준성과 규칙이 SQL에 남는 비대칭이 그대로다. 기각.

### 결정 내용

- `escalateIfPending`(repository 조건부 UPDATE)을 제거하고 `Payment.escalate(now)` 도메인 메서드로 환원한다. escalation 가능 상태(`status IN (UNKNOWN,REQUESTED)`)·멱등(`escalatedAt IS NULL`) 가드를 엔티티 메서드 안에 둔다. 네 전이(`succeed`/`fail`/`markUnknown`/`escalate`)가 모두 엔티티 가드에 모인다.
- 통지 주체 판정: application 건별 트랜잭션에서 `find → escalate() → save`. save 성공 = 이 트랜잭션이 통지 주체 → 커밋 후 통지. 동시 시도 중 진 쪽은 `OptimisticLockException` → skip(통지 안 함). 사전 find에서 이미 `escalatedAt != null`이거나 status가 종착이면 escalation 대상이 아니므로 skip.
- `escalatedAt`을 status와 무관한 직교 필드로 두고 status를 늘리지 않는 결정은 **유지**한다. 바뀌는 것은 멱등 메커니즘(영향 행 수 → `@Version`)과 규칙 위치(SQL WHERE → 도메인 메서드)뿐이다.

### 근거

- `@Version` 도입으로 "메모리 가드로 race를 못 막는다"는 전제가 해소됐으므로, 규칙을 도메인 메서드로 올리는 게 다른 전이와 일관되고 표현력이 좋다(`approval-concurrency-guard`가 "낙관 락과 CAS는 정확성이 동등하면 도메인 표현력을 살리는 `@Version`을 택한다"고 한 것과 같은 논리). 통지 정확히 1회는 `@Version`이 보장한다.

### 결과

- escalation 규칙이 엔티티에 모여 전이 모델이 일관된다. 통지 주체 판정이 영향 행 수에서 save 성공/예외 흡수로 바뀌므로, 기존 `PaymentEscalationConcurrencyTest`(영향 행 수=1 검증)를 예외 흡수 방식으로 갱신한다. `payment-escalation`의 `escalatedAt` 직교 필드 자체는 그대로 쓴다.
