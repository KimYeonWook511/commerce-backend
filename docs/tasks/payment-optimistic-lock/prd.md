# Task PRD

> 이 문서는 이 기능의 **의도를 담은 정본**이며, 작업 종료 후 동결한다.
> 이후 다른 Task에서 이 결정이 바뀌면 이 문서를 거슬러 수정하지 않고,
> 아래 상태/포인터 한 줄만 추가한다. 본문은 당시 기록으로 보존한다.

- 상태: active
- 변경 포인터: 없음

---

## Task명

- `payment-optimistic-lock`

## 배경

- 이 프로젝트는 lost update를 `@Version`(낙관 락)으로 핵심 엔티티에 일관되게 적용해 왔다: **Order / PaymentReservation / CartItem / Stock**. 그런데 **`Payment`에만 `@Version`이 없다.**
- `Payment`는 append-only + 단조 종착 전이(`REQUESTED → SUCCEEDED/FAILED/UNKNOWN`, 종착하면 불변)라 `@Version`이 생략됐으나, 종착 전이의 동시 경합에서 lost update 구멍이 남는다. 종착 전이(`fail`/`markUnknownIfRequested`)는 메모리 상태 가드(`status != REQUESTED/UNKNOWN`)뿐이고, 같은 `Payment` 행을 두 트랜잭션이 read-modify-write로 동시 전이하면(특히 `succeed` vs `fail`) `@Version`·행 락이 없어 나중 커밋이 앞을 덮는 lost update가 가능하다(예: 돈은 받았는데 결제는 FAILED로 기록).
- `succeedApproval`의 order `findByIdForUpdate` 비관 락은 **order 행만** 잠그고 payment 행은 잠그지 않는다(같은 succeed끼리만 직렬화). `fail`(대사·보상)은 order 락을 잡지 않으므로, order 락으로는 succeed vs fail 충돌을 막지 못한다.
- PR #242(`payment-escalation`) 리뷰에서 발견됐고, 본 이슈(#243)로 위임됐다.

## 목표

- 다른 핵심 엔티티와 동일하게 `Payment`에 낙관 락(`@Version`)을 도입해 종착 전이의 lost update를 차단하고, **Payment에만 빠져 있던 동시성 방어의 일관성 누락**을 해소한다.
- 위험도는 낮으나(succeed/fail 동시 갈림은 단일 인스턴스 전제에서 시간 분리되어 비현실적) 금전 정합성 안전장치를 선제 도입한다.

## 범위

- 포함 범위
  - `Payment`에 `@Version` 추가 + Flyway `version` 컬럼(다른 엔티티와 동일 형태).
  - 종착 전이(`fail`/`markUnknownIfRequested`/`failIfPending`) 충돌 시 `OptimisticLockException` **멱등 흡수**(단조 종착이라 재시도 아닌 skip).
  - `succeed` 경로 충돌 시 **전파**(흡수하지 않음).
  - `escalateIfPending`(조건부 UPDATE/CAS) 제거 → `Payment.escalate()` 도메인 메서드 + `@Version`으로 환원. 통지 주체 판정을 영향 행 수 → save 성공 기반으로 전환.
- 제외 범위
  - 다중 인스턴스 분산 락(ShedLock). 단일 행 동시 전이 방어에 한정하며, 다중 인스턴스는 후속(`unknown-reconciliation`의 단일 인스턴스 전제 결정 참조).
  - CANCEL 전용 동시 충돌 **재현 테스트**. 흡수/전파 처리 코드는 CANCEL 경로에도 일관 적용하되, CANCEL 대사가 아직 미구현이라 동시 충돌 시나리오가 없어 전용 테스트는 CANCEL 후처리가 실제 도입되는 Epic #208로 위임한다.
  - order `findByIdForUpdate` 비관 락을 낙관 락으로 전환. 별도 작업(승인 반영의 대기-흡수 동작·정본 결정 변경 동반). 부분취소(여러 결제 행 합산 검증) 도입 시 재판단.

## 주요 시나리오

- **succeed vs fail 동시 전이**: 같은 Payment 행을 한 트랜잭션은 `succeed`, 다른 트랜잭션은 `fail`로 동시 전이 → `@Version`으로 한쪽만 커밋 성공, 다른쪽은 `OptimisticLockException`. `fail`이 지면 멱등 흡수(skip), `succeed`가 지면 전파. lost update 없음.
- **escalation 통지**: 6시간 초과 미확정 APPROVE 결제 → `find → escalate() → save`. save 성공 = 이 트랜잭션이 통지 주체 → 커밋 후 운영자 통지. 동시 시도 중 진 쪽은 `OptimisticLockException` → skip(통지 안 함). 정확히 1회 통지.

## 요구사항

- `Payment`에 `@Version Long version` 추가. Flyway `V9__add_payment_version.sql`: `ALTER TABLE tbl_payment ADD COLUMN version BIGINT NOT NULL DEFAULT 0`. 기존 행은 0으로 백필되고 이후 JPA가 쓰기 시 자동 증가한다.
- 충돌 처리는 **메서드 의도** 기준으로 가른다(상세 ADR-L2):
  - **조건부 skip 메서드**(`markUnknownIfRequested`/`failIfPending` — "조건 안 맞으면 skip"을 이름에 박은 보상·best-effort 경로): `OptimisticLockException`을 내부 skip으로 흡수.
  - **무조건 전이 메서드**(`fail`/`succeed`/cancel `succeed`): 전파. HTTP 경로는 기존 `GlobalExceptionHandler`의 `OptimisticLockingFailureException → 409` 핸들러가, 대사 경로는 `PaymentReconciliationService.reconcile()` 본 루프의 건별 `catch (Exception)`가 받는다. application에 새 try-catch를 심지 않는다.
- `@Version`의 핵심 역할은 lost update 차단이며, 진 쪽 처리는 기존 메커니즘(409 핸들러·대사 루프 격리)에 위임하고 명시적 흡수는 조건부 skip 메서드에 한정한다. 이 처리는 결제 타입(APPROVE/CANCEL) 무관하게 일관 적용한다.
- `escalateIfPending`(repository 조건부 UPDATE) 제거 → `Payment.escalate(now)` 도메인 메서드(escalation 가능 상태·멱등 가드를 엔티티 안에 둔다) + `@Version`. application 건별 트랜잭션에서 `find → escalate() → save`, save 성공 건만 커밋 후 통지.

## 제약사항

- `succeedApproval`의 order `findByIdForUpdate` 비관 락은 payment+order 원자성·승인 반영 직렬화 목적이므로 **유지**한다(낙관 전환은 범위 밖).
- 이미 방어된 경로(생성: Reservation `@Version`, 이중 SUCCEEDED: `uk_payment_approved_order_key`)의 동작은 유지한다. `@Version`은 그 위에 같은 행 동시 전이 방어를 추가하는 직교 메커니즘이다.
- escalation 통지는 commit 이후 best-effort이며, 전송 실패가 트랜잭션을 막지 않는다.
- `escalatedAt`을 status와 무관한 직교 필드로 두고 status를 늘리지 않는 결정(`payment-escalation`)은 유지한다. 이번에 바뀌는 것은 escalation의 **멱등 메커니즘**(조건부 UPDATE 영향 행 수 → `@Version`)과 규칙 위치(SQL WHERE → 도메인 메서드)뿐이다.
- 기존 동시성 테스트(생성/승인/이중 SUCCEEDED/escalation)는 회귀 없이 통과해야 한다. `@Version` 도입으로 기존 save 경로가 새로 `OptimisticLockException`을 던질 수 있으므로 전 경로를 전수 점검한다.
