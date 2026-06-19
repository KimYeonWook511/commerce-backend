# 회고 — 대사 스캔 KEEP_WAITING backoff

PR #263. 결제 대사 스캔이 `KEEP_WAITING`(PG가 PENDING/NOT_FOUND 반환)으로 끝난 건을 매 주기
재스캔·재조회하던 문제(starvation·PG 반복 조회, #239 잔여 본체)를, `status`-직교 필드
`next_reconcile_at` + 스캔 게이트 + wait 분기 고정 backoff로 해소했다.

## 무엇을 만들었나

- **읽기 측(step1)**: `Payment.next_reconcile_at`(V10) + `delayReconcile(now, backoff)` 도메인
  메서드(상태 전이 없음), APPROVE·CANCEL stale 스캔 쿼리에 게이트
  `(next_reconcile_at IS NULL OR <= :now)` + `now` 파라미터.
- **쓰기 측(step2)**: `DelayPaymentReconcileService`(`type`별 finder 분기 + `@Version` 저장),
  `RECONCILE_BACKOFF`(5분, 단일 출처) 상수, `ReconcilePaymentUseCase`의 wait 분기 3곳(APPROVE
  `KEEP_WAITING`, CANCEL `KEEP_WAITING`·재시도 `PROCESSING`)에 `delayReconcileSkippable` 배선.

## 핵심 학습

### 1. 검증 인프라(escalated_at)가 이미 있으면 그 패턴을 그대로 빌린다

backoff를 표현할 자리로 `respondedAt` 재사용을 처음 떠올렸으나, 그건 escalation·stale 윈도우 계산이
의존하는 필드라 오염된다. 이미 `escalated_at`(ADR-049)이 "`status` 상태머신을 건드리지 않는 직교
타임스탬프"를 검증해 둔 패턴이 있어, `next_reconcile_at`을 같은 방식으로 추가했다(ADR-066). NULL을
"즉시 대상"으로 두니 백필도 기존 동작 보존도 공짜로 따라왔다. → 새 부가 시점이 필요할 때, 상태머신에
끼워 넣지 말고 직교 필드 + NULL=중립 패턴을 먼저 본다.

### 2. 시그니처 변경의 파급은 호출부·테스트까지 — step 설계에 명시해야 AC가 통과한다

스캔 쿼리에 `now` 파라미터를 더하면 4-인자 → 5-인자가 되어 **세 테스트 파일(통합 1 + usecase mock 2)**
이 전부 컴파일 깨진다. 실행 전 독립 검토 에이전트가 이 누락(C1)을 잡았고, step1 문서에 "시그니처 변경에
깨지는 테스트를 같은 step에서 5-인자로 갱신"을 명시해 AC(`compileTestJava`) 실패를 사전에 차단했다.
→ "메서드 시그니처를 바꾼다"는 지시는 항상 호출부·mock stub 동반 수정을 함께 적는다. 안 적으면
developer가 "관련 파일"만 보고 컴파일 단계에서 막힌다.

### 3. "양쪽에 일관 적용"은 로드 경로가 실제로 양쪽을 가리는지 확인해야 한다

delay service의 로드 키를 처음엔 기존 CANCEL 전이 service들처럼 단일 `findCancelPayment`로 적으려
했다. 그런데 포트에는 `findApprovePayment`/`findCancelPayment`가 **type별로 분리**돼 있어, 단일
CANCEL finder로는 APPROVE 건을 못 찾아 `PAYMENT_RECORD_NOT_FOUND`로 흡수돼 **APPROVE backoff가 silent
no-op**이 된다(R1). 독립 검토가 코드 대조로 이 설계 공백을 짚었고, service가 `type`으로 finder를
분기하도록 step2를 고쳤다. silent no-op은 테스트가 usecase mock만 보면 안 잡혀서, service 단위
테스트로 finder 분기를 못 박았다. → "A·B 양쪽에 적용"이라는 요구는 적용 경로가 정말 A와 B를 둘 다
거치는지 코드로 확인한다. 한쪽만 거치면 조용히 절반만 동작한다.

### 4. backoff는 wait 분기에만 — 상태 확정 경로는 이미 자기 cadence가 있다

succeed/fail/markUnknown 분기는 이미 행을 쓰며(markUnknown은 `responded_at=now` 갱신) 그 자체로 다음
재진입을 늦춘다. 거기에 backoff까지 더하면 두 시점 필드가 경합한다. backoff의 목적은 "쓰지 않아 매 주기
재스캔되는 wait 건"을 늦추는 것이므로 그 분기에 한정했다(ADR-068). `delayReconcile`이 `status`를 읽지도
바꾸지도 않게 둔 덕에, wait 분기에 도달하는 어떤 status(일부 FAILED CANCEL 포함)에도 가드 없이
안전했다. → "어디에 적용하지 않을지"가 "어디에 적용할지"만큼 설계의 일부다.

### 5. 고정 간격으로 시작 — "점증"은 예시였지 요구가 아니었다

#239 코멘트에 "재조회 간격을 점증"이라 적었지만, 지수 backoff는 시도 카운터 컬럼·상태·테스트를 늘린다.
두 목표(starvation 해소, PG 조회 빈도 감소)는 고정 간격만으로 충족되고, 스캔 윈도우 상한(6시간)이
무한 재시도를 이미 막아 한 건의 PG 조회가 `6h/간격`으로 bound된다. 코드베이스 기조(과설계 방지·단일
값 우선)에 맞춰 고정 간격을 택했다(ADR-067). PG 읽기 호출 빈도는 금전 변이가 아니라 상한 안에서
허용된다고 판단했다. → 이슈 본문의 예시("예: 점증")를 요구로 굳히지 말고, acceptance가 무엇을
요구하는지로 최소 설계를 정한다. 점증이 필요해지면 카운터를 가산적으로 도입하면 된다.

### 6. docker 태그 통합 테스트는 `test`가 아니라 `integrationTest`로 돈다

AC에 처음 `./gradlew test --tests "*ReconciliationScanQueryIntegrationTest"`라 적었으나, 그 테스트는
`@Tag("docker")`라 `test` task에서 제외된다. developer가 `integrationTest` task로 보정했다(build.gradle
에 docker daemon 검증과 함께 등록돼 있다). → Testcontainers/`@Tag` 테스트의 AC는 해당 격리 task를
명시한다. 일반 `test`로 적으면 "통과"가 사실은 "실행 안 됨"이다.

## 리뷰 처리

- 실행 전 독립 검토: C1(테스트 시그니처 누락)·R1(APPROVE silent no-op)을 코드 대조로 잡아 step 문서를
  보강한 뒤 실행 → 두 결함이 코드에 들어가기 전에 차단됐다.
- PR review(Gemini) 3건 모두 low-priority로 reject: 통합 테스트의 native UPDATE 유지(임의 timestamp
  직접 검증이 의도에 더 부합), 도메인·service의 `requireNonNull` 미추가(기존 패턴 어디도 안 씀, 내부
  호출·null 불가 — 과설계 회피).

## 관련

- 이슈 #239 (윈도우 상한·REQUESTED 하한은 선행 PR에서 이미 닫힘, 본 PR이 KEEP_WAITING backoff 완료)
- ADR-066~068, `docs/tasks/payment-escalation/`(ADR-049 직교 필드 패턴)
- 후속 가능: 지수 backoff(시도 카운터)·#260(FAILED CANCEL 자동 재시도)에서 본 backoff 재사용
