# Architecture — 대사 스캔 KEEP_WAITING backoff

## 변경 개요

대사 루프의 "스캔 → PG 조회 → 판정" 구조는 유지하고, **wait 판정 시 결과를 행에 기록**하는
경로를 추가한다. 기존에 `KEEP_WAITING`은 아무것도 쓰지 않는 no-op이었고, 그래서 같은 행이 매
주기 재스캔·재조회됐다.

## 구성 요소

### 1. 도메인 — `Payment`

- 새 필드 `next_reconcile_at: LocalDateTime`(nullable). `status`와 무관한 직교 필드로,
  `escalated_at`(ADR-049)과 같은 성격이다. NULL이면 "한 번도 미뤄지지 않음 = 즉시 대사 대상".
- 새 도메인 메서드 `delayReconcile(now, backoff)`: `next_reconcile_at = now + backoff`만 세팅하고
  `status`는 바꾸지 않는다. wait 판정 후 "다음 재조회를 미룬다"는 의도를 표현한다.

### 2. 영속 — 스캔 쿼리

- `findStaleApprovePaymentsForReconciliation` / `findStaleCancelPaymentsForReconciliation`에
  backoff 게이트 `(next_reconcile_at IS NULL OR next_reconcile_at <= :now)`를 추가하고 `now`
  파라미터를 받는다. escalation 후보 조회(`find*EscalationCandidates`)는 backoff와 무관하므로
  건드리지 않는다(6시간 초과 종착 경로는 cadence 대상이 아니다).
- 마이그레이션 `V10`으로 `next_reconcile_at DATETIME(6) NULL` 컬럼을 추가한다(백필 불필요).

### 3. 응용 — backoff 기록 service + 루프 배선

- 새 `@Transactional` service가 대상 결제를 로드해 `delayReconcile`을 적용하고 낙관 락
  (`@Version`)을 통해 저장한다. 동시 전이 충돌(`PAYMENT_CONCURRENTLY_MODIFIED`)·행 없음은
  무해하므로 호출부에서 흡수(skip)한다(기존 대사의 `*Skippable` 패턴 동일).
- `ReconcilePaymentUseCase`는 PG 조회 후 wait로 끝나는 분기에서 이 service를 호출한다:
  - APPROVE: `processApproveReconcile`의 `KEEP_WAITING`
  - CANCEL: `processCancelReconcile`의 `KEEP_WAITING`, `executeCancelRetry`의 `PROCESSING`
- 상태를 확정하는 분기(succeed/fail/markUnknown)는 이미 행을 쓰며 자기 cadence로 재진입하므로
  backoff를 추가하지 않는다.

### 4. 정책 상수

- backoff 간격은 단일 고정 값으로, 기존 대사 시간 상수들이 모인
  `PaymentPostProcessTargetPolicy`에 둔다(단일 출처, 운영 config 승격 전제).

## 트랜잭션·경계

- PG 조회는 기존과 동일하게 tx 밖에서 수행한다. backoff 기록만 건별 단건 tx로 처리한다.
- `next_reconcile_at` write는 `respondedAt`을 건드리지 않으므로 escalation·stale 윈도우 계산은
  그대로 보존된다.

## 영향 범위

- `payment` 도메인 내부에 한정된다. API 계약·다른 aggregate 변경 없음.
