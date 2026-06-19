# Task ADR — 대사 스캔 KEEP_WAITING backoff

이 문서는 이번 작업에서 새로 채택한 결정의 staging이다(임시 번호 L1·L2…).
harness Stage 8(Root Sync)에서 루트 전역 번호(ADR-XXXX)로 `docs/adr.md`에 append된다.

---

## ADR-L1: 대사 재조회 backoff를 status-직교 `next_reconcile_at` 필드 + 스캔 게이트로 구현한다

- **결정**: `KEEP_WAITING`으로 판정된 대사 후보의 재조회를 미루기 위해, 결제 행에 `status`와
  무관한 직교 필드 `next_reconcile_at`을 추가한다. 스캔 쿼리는
  `(next_reconcile_at IS NULL OR next_reconcile_at <= :now)` 게이트로 미래 시각 행을 제외한다.
- **배경**: 기존 `KEEP_WAITING` 분기는 행을 쓰지 않아 같은 행이 매 주기 `id ASC` 첫 페이지를
  재점유하고 매 1분 PG에 재조회됐다(starvation·PG 낭비, #239).
- **이유**: `escalated_at`(ADR-049)이 이미 검증한 패턴이다 — `status` 상태머신을 건드리지 않고
  직교 타임스탬프로 부가 시점을 표현한다. `respondedAt`을 재사용하면 escalation·stale 윈도우
  계산이 오염되므로 별도 필드가 안전하다. NULL을 "즉시 대상"으로 두면 기존 행·신규 행 동작이
  보존된다(백필 불필요).
- **트레이드오프**: 컬럼 1개와 wait 시 write 1회가 추가된다(기존 no-op 대비). 그러나 그 write가
  starvation·PG 반복 조회를 동시에 해소한다.
- **고려한 대안**: (1) 스캔 정렬을 `next_reconcile_at`/`respondedAt` 기준으로 교체 — 정렬만으로는
  PG 반복 조회를 못 줄이고, 게이트가 더 단순하다. (2) `respondedAt` 재사용 — escalation·stale
  계산 오염으로 기각.

## ADR-L2: backoff 간격은 단일 고정 값으로 둔다(지수 backoff 미도입)

- **결정**: 재조회 backoff는 단일 고정 간격(초기값 5분)으로 한다. 시도 횟수에 따라 간격을 늘리는
  지수 backoff는 도입하지 않는다.
- **배경**: 이슈 #239는 "재조회 간격을 점증"을 예시로 들었으나, 점증을 구현하려면 시도 횟수
  카운터 컬럼과 상태가 추가된다.
- **이유**: 고정 간격만으로 두 acceptance(starvation 해소, PG 조회 빈도 감소)가 모두 충족된다.
  스캔 윈도우 상한(6시간)이 무한 재시도를 이미 막으므로, 한 건의 PG 조회는 `6h / 간격`으로
  bound된다. 코드베이스 기조(과설계 방지, 단일 값 우선, 운영 config 승격 전제)에 맞춰 가장 작은
  설계로 시작하고, 필요해지면 카운터를 가산적으로 도입한다.
- **트레이드오프**: 영구 정체 transient는 고정 간격으로 6시간 동안 `6h/간격`회까지 PG를
  두드린다(지수보다 많음). 그러나 이는 PG 읽기 호출일 뿐 금전 변이가 아니고, escalation 상한으로
  bound되어 허용 범위다.
- **고려한 대안**: 지수 backoff(`next_reconcile_at` + `reconcile_attempts`) — #239의 "점증"에
  충실하나 컬럼·상태·테스트가 늘어 현재 스코프에 과하다. 후속에서 가산적으로 도입 가능.

## ADR-L3: backoff write는 wait로 끝나는 분기에만 적용하고, 상태 확정 경로는 자기 cadence를 따른다

- **결정**: `next_reconcile_at` backoff는 PG 조회가 "아직 대기"로 끝나는 분기
  (APPROVE `KEEP_WAITING`, CANCEL `KEEP_WAITING`·재시도 `PROCESSING`)에서만 기록한다.
  succeed/fail/markUnknown처럼 `status`를 쓰는 분기에는 추가하지 않는다.
- **이유**: 상태를 확정하는 분기는 이미 행을 쓰며(예: markUnknown이 `respondedAt=now` 갱신) 자기
  cadence로 재진입을 늦춘다. 거기에 backoff까지 더하면 의미가 중복되고 두 시점 필드가 경합한다.
  backoff의 목적은 "쓰지 않아 재스캔되는 wait 건"을 늦추는 것이므로 그 분기에 한정한다.
- **status 비의존**: wait 분기에는 UNKNOWN/REQUESTED뿐 아니라 일부 FAILED CANCEL도 도달한다
  (`PaymentPostProcessTargetPolicy`가 `CANCEL_PROCESS_FAILED`·`PG_INVALID_RESPONSE` FAILED CANCEL을
  `CANCEL_RECONCILE`로 돌려 PG가 PENDING이면 `KEEP_WAITING`이 된다). `delayReconcile`은 `status`를
  읽지도 바꾸지도 않으므로 어떤 status가 와도 안전하다(가드 불필요).
- **동시성**: backoff write는 `@Version` 낙관 락을 거치며, 동시 전이가 먼저 행을 바꿔
  `PAYMENT_CONCURRENTLY_MODIFIED`가 나면 tx 밖에서 흡수(skip)한다. backoff는 best-effort
  cadence 힌트라 충돌 시 건너뛰어도 다음 주기에 자연히 재시도된다(기존 `*Skippable` 패턴 동일).
- **트레이드오프**: wait 분기와 확정 분기의 재진입 cadence 표현이 이원화된다(별 필드). 그러나 각
  분기의 의도가 다르므로(대기 연기 vs 상태 확정) 분리가 명확하다.
