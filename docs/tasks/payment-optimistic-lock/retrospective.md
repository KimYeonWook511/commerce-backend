# 회고록: payment-optimistic-lock

## 1. 작업 요약

`Payment`에만 빠져 있던 `@Version`(낙관 락)을 추가해 같은 행 동시 종착 전이(succeed vs fail 등)의 lost update를 차단했다(step1). 이어 충돌 처리 구조와 escalation 멱등 메커니즘을 재작성했다.

- **step1**: `Payment.version` + V9 migration (`tbl_payment.version BIGINT NOT NULL DEFAULT 0`).
- **step2**: 충돌 처리를 transition(별도 빈 public `@Transactional`, 충돌 전파) + useCase(트랜잭션 없음, tx 밖 skip) + adapter `saveChecked`(DAO 예외 → `PAYMENT_CONCURRENTLY_MODIFIED` 변환) 구조로 재작성.
- **step3**: escalation 멱등을 조건부 UPDATE(`escalateIfPending`)에서 `Payment.escalate()` 도메인 메서드 + `@Version`으로 환원.

목적: 결제 동시성 방어의 일관성 누락 해소(#243). step2/step3는 이미 push된 초기 구현이 세 정책(application의 DAO 예외 직접 catch / transition `@Transactional` 제거 / 흡수를 트랜잭션 안에서 수행)을 위반해 **재작성**한 것이다.

---

## 2. 결정한 정책 (ADR-050/051/052)

- **ADR-050**: `Payment`에 `@Version` 도입(비관 락 대신 — 짧은 tx·낮은 충돌·부수효과 분리 워크로드 + 다른 엔티티와의 일관성).
- **ADR-051**: 충돌은 transition이 트랜잭션 **안**에서 전파하고, useCase가 트랜잭션 **밖**에서 skip한다. DAO 예외는 adapter `saveChecked`가 도메인 예외로 변환. `succeed`·무조건 `fail`(APPROVE 종착)은 전파.
- **ADR-052**: escalation 멱등을 `@Version` + `escalate()` 도메인 메서드로 환원(ADR-049의 조건부 UPDATE 영향 행 수 메커니즘을 supersede, `escalatedAt` 직교 필드·통지 1회 정신은 유지).

---

## 3. 주요 발견 및 논의

### 문제의 본질은 "흡수했다"가 아니라 "흡수를 트랜잭션 안에서 했다"

초기 구현은 조건부 skip 메서드에서 `ObjectOptimisticLockingFailureException`을 직접 catch하고 `@Transactional`을 제거해 "독립 트랜잭션"을 만들려 했다. 그러나 flush 충돌은 그 트랜잭션을 `rollback-only`로 마킹하므로, 같은 메서드 안에서 catch하면 commit 시점에 `UnexpectedRollbackException`이 전파된다. 해법은 흡수를 **트랜잭션 경계 밖**(useCase)으로 옮기는 것이었다. transition은 catch하지 않고 도메인 예외를 전파해 트랜잭션을 깨끗이 rollback시키고, 트랜잭션을 열지 않는 useCase의 private 래퍼가 그 도메인 예외를 흡수한다.

### `@Transactional(REQUIRES_NEW)`는 해법이 아니다 (Gemini review 4건 reject)

PR #245에 Gemini가 "메서드에 `@Transactional(REQUIRES_NEW)`를 붙이라"는 제안 4건을 달았다. 그러나 **같은 메서드 안에서 catch**하면 그 새 트랜잭션이 flush 충돌로 rollback-only가 되어 REQUIRES_NEW를 붙여도 `UnexpectedRollbackException`이 그대로 발생한다. 이 안은 task ADR-L2(→ADR-051) 작성 시 이미 명시적으로 기각한 것이라, 재작성으로 superseded됐다는 근거로 4건 모두 reject + resolve했다.

### `failIfPending`↔`fail` 병합, transition은 "조건 안 맞으면 예외"

skip 판단을 useCase로 올리자, 사전 status 체크 + 흡수를 하던 `failIfPending`/`markUnknownIfRequested`는 도메인 가드가 던지는 예외를 그대로 전파하는 단순 transition(`find → 전이 → saveChecked`)이 됐다. `failIfPending`은 기존 무조건 `fail`과 동일 형태가 되어 **하나로 병합**됐다. 호출처가 skip을 원하면 useCase의 `failSkippable`/`markUnknownSkippable` 래퍼(SKIPPABLE = `PAYMENT_CONCURRENTLY_MODIFIED`/`PAYMENT_STATUS_TRANSITION_NOT_ALLOWED`/`PAYMENT_RECORD_NOT_FOUND`)를 거치고, 무조건 전이(대사 `fail`, `succeedApproval`)는 래퍼 없이 전파한다.

### escalation: 영향 행 수 → save 성공/예외

`escalateIfPending` 조건부 UPDATE를 제거하고 `escalate(now)` 도메인 메서드(boolean 반환)로 통지 주체 후보를 표시한 뒤, transition `escalate`의 `saveChecked` 성공으로 통지 주체를 최종 확정한다. 진 쪽은 `PAYMENT_CONCURRENTLY_MODIFIED`로 skip해 통지 정확히 1회를 보장한다. `@DynamicUpdate`가 없어 CAS+수동 version bump는 동시 `fail()` save가 `escalatedAt`을 stale로 덮을 위험이 있어, 도메인 메서드 환원이 더 단순·안전했다.

### CANCEL `succeed`/`fail` 충돌 흡수 경계 (독립 리뷰 → ADR 명확화)

독립 리뷰에서, `saveChecked` 도입으로 cancel `succeed`/`fail` 충돌이 이제 `PaymentException`이 되어 보상 `runPgCancel`의 기존 `catch(PaymentException)`에 흡수된다는 행위 변화가 지적됐다(이전엔 raw DAO 예외라 전파). 분석 결과 진 쪽은 이미 다른 주체가 같은 CANCEL 레코드를 종착시킨 **중복 보상**이라 흡수가 멱등적으로 옳고, 미해소분은 REQUESTED로 남아 CANCEL 대사(#208)에서 재확정된다. "`succeed`·무조건 `fail` 전파"는 **APPROVE 종착 기준**임을 ADR-L2(→ADR-051)에 한 줄 명확화했다(코드 변경 없음).

### 결정적 충돌 테스트는 layer를 갈라 검증

transition이 자기 트랜잭션 안에서 **재조회**하므로 단일 스레드로 transition 내부 충돌을 결정적으로 만들 수 없다. 그래서 (1) **adapter `saveChecked`** 충돌 변환은 두 detached 복사본 중 하나로 version을 bump한 뒤 stale 복사본을 저장해 `PAYMENT_CONCURRENTLY_MODIFIED`를 결정적으로 검증하고(통합), (2) **useCase skip 래퍼** 흡수는 transition을 mock해 `PAYMENT_CONCURRENTLY_MODIFIED`를 던지게 하고 흡수/비-SKIPPABLE 재전파를 검증했다(단위). 실제 race의 lost-update 부재는 기존 동시성 테스트가 담당한다.

---

## 4. 변경 범위 정리

| 파일 | 변경 내용 |
|---|---|
| `Payment.java` | `@Version version` 필드(step1), `escalate(now)` 도메인 메서드(step3) |
| `V9__add_payment_version.sql` | `tbl_payment.version BIGINT NOT NULL DEFAULT 0` |
| `PaymentErrorCode.java` | `PAYMENT_CONCURRENTLY_MODIFIED`(409) 추가 |
| `PaymentRepository`/`PaymentRepositoryAdapter` | `saveChecked` 추가(충돌 변환), `escalateIfPending` 제거 |
| `JpaPaymentRepository` | `escalateIfPending` 조건부 UPDATE 제거 |
| `PaymentApprovalRecordService` | `fail`(failIfPending 병합)/`markUnknown`/`escalate` transition으로 통일(saveChecked, @Transactional) |
| `PaymentCancellationService` | `succeed`/`fail`/`markUnknown` saveChecked 기반 |
| `NaverPayApprovalService`, `PaymentApprovalCompensationService` | private skip 래퍼(SKIPPABLE 흡수) |
| `PaymentReconciliationService` | `processEscalations`를 `escalateSkippable` 구조로, DAO catch 제거 |
| 테스트 | `PaymentRepositorySaveCheckedConflictTest`(신규 결정적), `PaymentOptimisticLockConcurrencyTest`(전파 검증으로 갱신), `PaymentEscalationConcurrencyTest`(save 성공 1회), 단위 테스트(전파/skip 계약) 갱신 |
| `docs/adr.md` | ADR-050/051/052 append, ADR-049 멱등 메커니즘 supersede 노트 |
| `docs/db-schema.md` | `tbl_payment.version` 추가, escalation 멱등 메커니즘 갱신 |

---

## 5. 미결 과제

- CANCEL 전용 동시 충돌 재현 테스트는 CANCEL 대사 미구현이라 Epic #208로 위임(메커니즘 검증은 APPROVE 결정적 충돌 테스트가 담당).
- 루트 `docs/exception-strategy.md`의 "낙관 락 충돌 처리" 섹션 정본화는 ADR-051대로 별도 작업으로 분리.

---

## 6. 회고

### 잘된 점

- 초기 구현의 결함을 "흡수 자체"가 아니라 "흡수의 트랜잭션 위치"로 정확히 진단해, REQUIRES_NEW 같은 표면적 처방 대신 흡수를 트랜잭션 경계 밖으로 옮기는 구조적 해법을 택했다. 이 진단이 Gemini의 REQUIRES_NEW 제안 4건을 근거 있게 reject하는 기준이 됐다.
- transition이 재조회하는 구조 때문에 단일 스레드 결정적 충돌이 불가능하다는 한계를 인지하고, adapter 변환(통합)과 useCase 흡수(mock 단위)로 layer를 갈라 결정적으로 검증했다. 검증 가능한 지점을 억지로 한 곳에 몰지 않았다.
- 독립 리뷰로 CANCEL `succeed`/`fail` 흡수의 행위 변화를 잡아내, 금전 정합성에 안전함을 분석하고 ADR에 경계를 한 줄 남겼다(코드를 늘리지 않고 문서로 의도를 고정).

### 개선할 점

- Stage 8 Root Sync에서 루트 `docs/`를 **메인 worktree(develop)** 경로에 잘못 편집했다가 되돌리고 PR 브랜치 worktree에 다시 적용했다. worktree 작업 중에는 루트 문서도 worktree 경로로 편집해야 한다는 점을 먼저 확인했어야 했다.
- 재작성이 step2/step3 양쪽 커밋에 걸쳐 한 파일(`PaymentApprovalRecordService`)을 건드려, 목적별 커밋 분리를 위해 메서드를 임시로 떼었다 붙이는 수작업이 필요했다. step 경계와 파일 경계가 어긋날 때의 커밋 분할 비용을 step 설계 시 고려할 여지가 있었다.
