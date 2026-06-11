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

## ADR-L2: 충돌 처리는 메서드 의도 기반 — 조건부 skip 메서드는 흡수, 무조건 전이는 전파(기존 핸들러·루프에 위임)

- 상태: accepted
- supersedes: 없음
- superseded-by: 없음

### 배경

- `@Version` 도입으로 전이 시 `OptimisticLockException`(`ObjectOptimisticLockingFailureException`)이 발생할 수 있다. 충돌을 어디서 어떻게 처리할지 결정이 필요했다. 기존 예외 처리 정책(`docs/exception-strategy.md`)에는 두 메커니즘이 이미 있다: (a) `GlobalExceptionHandler`에 `OptimisticLockingFailureException → 409`(COMMON-409-1) 핸들러(낙관 락 충돌을 "정상 시나리오"로 매핑), (b) `PaymentReconciliationService.reconcile()` 대사 본 루프의 건별 `catch (Exception)` 격리. 그리고 "DAO 예외(`OptimisticLockingFailureException` 포함)는 application/adapter에서 catch 금지" 원칙이 있다.

### 고려한 대안

- **모든 종착 전이를 application에서 try-catch로 흡수**: `@Version` 도입 시 영향받는 save 경로마다 try-catch를 새로 심는 방식. DAO 예외 catch 금지 원칙과 어긋나고, 기존 409 핸들러·대사 루프 격리와 중복이다. 기각.
- **모든 충돌을 흡수(succeed 포함)**: `succeed`가 졌을 때(상대가 FAILED/UNKNOWN 종착) "PG는 승인(과금)했는데 우리는 실패로 기록"한 모순을 조용히 삼켜 돈 문제가 묻힌다. 기각.

### 결정 내용

- `@Version`의 핵심 역할은 **lost update 차단**이고, "진 쪽(충돌 예외) 처리"는 메서드 의도로 가른다. 이는 exception-strategy의 "catch 안 메서드는 예외 안 던지게 설계, 의도를 메서드명에 캡슐화" 철학과 일치한다.
  - **조건부 skip 메서드**(`markUnknownIfRequested`, `failIfPending` — 이름에 "조건 안 맞으면 skip"을 박은 메서드, 보상·best-effort 경로에서 호출): `OptimisticLockException`을 **내부 skip**으로 흡수한다(이미 다른 주체가 전이 → 단조 종착이라 재시도 아닌 skip). 이는 그 메서드의 기존 "조건부 skip" 의미의 자연스러운 확장이다.
  - **무조건 전이 메서드**(`fail`, `succeed`, cancel `succeed`): **전파**한다. HTTP 경로는 기존 `OptimisticLockingFailureException → 409` 핸들러가, 대사 경로는 본 루프의 건별 `catch (Exception)`가 받는다. application에 새 try-catch를 심지 않는다.
- `succeed`는 흡수하지 않는다(전파 → 409). succeed가 졌다 = 누가 먼저 종착. 상대가 SUCCEEDED면 재호출 시 사전 find가 멱등 흡수하고, FAILED/UNKNOWN이면 모순이라 드러나야 한다.
- 흡수가 필요한 조건부 skip 메서드 처리는 결제 타입(APPROVE/CANCEL) 무관하게 일관 적용한다. 단 CANCEL 전용 동시 충돌 **재현 테스트**는 CANCEL 대사가 미구현이라(충돌 시나리오 부재) Epic #208로 위임한다(메커니즘 검증은 APPROVE succeed-vs-fail 테스트가 담당).

### 근거

- 낙관 락 충돌은 무결성 위반(unique/FK)과 달리 "정상 시나리오"라 기존 정책이 409 매핑·루프 격리를 이미 마련해 뒀다. 그 위에 새 catch를 심는 건 중복·정책 위반이다. 흡수가 정말 필요한 곳은 "예외를 던지면 보상 흐름이 깨지는" 조건부 skip 메서드뿐이고, 그 흡수는 `approval-concurrency-guard`의 `ObjectOptimisticLockingFailureException` 처리 선례를 따른다(인프라 예외 타입 직접 의존 최소화).

### 결과

- 새로 심는 try-catch가 최소화되고(조건부 skip 메서드에 한정), 무조건 전이의 충돌은 기존 409 핸들러·대사 루프 격리가 받는다. step 작업은 "모든 전이 경로를 전수 점검해 메서드 의도대로 흡수(조건부 skip)/전파(무조건)가 일관되는지 확인"이 된다(누락 시 예상 못 한 `OptimisticLockException` 누수가 `@Version` 도입의 진짜 회귀 위험). 기존 succeed-vs-succeed 동시성 테스트는 order 비관 락 직렬화로 `@Version` 충돌이 안 나 통과 유지된다.

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
