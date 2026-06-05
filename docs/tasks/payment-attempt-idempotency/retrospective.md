# 회고록: payment-attempt-idempotency

## 1. 작업 요약

### 무엇을 변경했는가

`PaymentAttemptService`의 `getOrCreateApproveAttempt`와 `getOrCreateCancelAttempt` catch 블록을 보강했다.
기존에는 unique 제약 충돌 후 기존 attempt를 무조건 반환했다. amount가 다른 경우에도 동일하게 처리되어 침묵 처리되는 문제가 있었다.

변경 범위:
- `PaymentErrorCode`에 `PAYMENT_ATTEMPT_AMOUNT_MISMATCH` (409 Conflict) 추가
- catch 블록에 amount 비교 및 불일치 시 예외 처리 추가
- catch 블록에서 재조회 실패 시 `DataIntegrityViolationException` 누수를 `PAYMENT_ATTEMPT_NOT_FOUND`로 변환
- `succeedApproveAttempt` / `failApproveAttempt` 파라미터 명명을 `paymentId`로 통일 (엔티티 필드 기준)
- 단위 테스트 2건 추가, 기존 테스트 1건 수정
- 동시성 테스트 2건 추가
- `docs/adr.md`에 ADR-010 추가

### 왜 변경했는가

멱등성 계약은 "같은 요청 → 같은 결과"다. amount가 다른 재요청은 사실상 다른 요청인데 기존 attempt를 침묵 반환하면 이 위반이 가시화되지 않는다. 호출자 측 amount 산출 오류나 PG 응답 검증 흐름에서 어떤 amount를 기준으로 삼아야 할지 모호해진다. 명시적 예외로 즉시 4xx를 반환해 디버깅이 빠르고 모니터링 경보가 가능하도록 했다.

---

## 2. 주요 설계 결정과 근거

### ADR-A: 신규 에러 코드 추가

기존 `PAYMENT_AMOUNT_MISMATCH`(400)는 PG 응답 금액 불일치(외부 원인)에 사용 중이다. 멱등 재요청 amount 불일치(내부 원인)는 발생 맥락과 모니터링 기준이 다르므로 `PAYMENT_ATTEMPT_AMOUNT_MISMATCH`(409 Conflict)를 분리해 추가했다. 코드를 공유했다면 핸들러에서 두 원인을 분기하거나 알람 필터링이 불가능했다.

### ADR-B: 검증 위치를 catch 블록으로 한정

`save()` 전 pre-check(SELECT)를 추가하면 정상 경로(충돌 없음)에서도 매번 불필요한 쿼리가 발생한다. `Propagation.NOT_SUPPORTED`에서 `save()` commit 직후 unique 위반이 catch에서 잡히므로 catch 한 곳으로 충분하다. 정상 경로 성능을 유지하면서 예외 경로만 보강할 수 있었다.

### ADR-C: 기존 attempt status와 무관하게 mismatch 거부

FAILED 상태의 attempt에 amount를 바꾼 재시도를 허용하는 경우를 검토했다. "FAILED면 amount 수정 가능"이라는 암묵적 규칙이 생기면 멱등성 계약이 흐려진다. amount를 바꾸려면 새 `merchantPayKey`로 새 요청을 발급하는 것이 정상 흐름이므로 status 무관 일관 거부 정책을 채택했다.

### ADR-D: 파라미터 명명을 엔티티 필드 기준으로 통일

`Payment.pgPaymentId`(내부 도메인에서 외부 결제 ID를 부르는 이름)와 `PaymentAttempt.paymentId`(PG API 스펙 그대로의 외부 명명)는 의도된 분리다. `PaymentAttemptService` 내부에서는 엔티티 필드명인 `paymentId`로 통일해 코드 추적을 자연스럽게 했고, 호출자(`NaverPayApprovalService`)는 변경 없이 `attempt.getPaymentId()`를 그대로 전달한다.

---

## 3. 분리된 follow-up

### Issue #99: `PaymentAttempt` 상태 전이 검증

현재 구현은 기존 attempt의 상태(REQUESTED/FAILED/SUCCEEDED)를 확인하지 않고 amount만 검사한다. 예를 들어 SUCCEEDED 상태인 attempt에 동일 amount로 재요청이 들어와도 그대로 반환한다. 상태 전이 유효성(SUCCEEDED → 재요청 허용 여부 등)을 검증하는 로직은 별도 설계가 필요해 이번 범위에서 제외했다.

### Issue #100: `DataIntegrityViolationException` catch 범위 좁히기

현재 catch 블록은 `DataIntegrityViolationException` 전체를 잡는다. unique 제약 위반 외의 다른 DB 제약(NOT NULL, FK 등) 위반도 같은 블록에서 처리되는 문제가 있다. exception message 파싱이나 원인 클래스 체크를 통해 unique 위반만 선별 처리하는 방식은 이번 범위에서 제외했다. 이 개선이 없어도 현재 정책은 올바르게 동작하며, 범위를 좁히려면 별도 설계가 필요하다.

**분리 이유**: 두 follow-up 모두 이번 핵심 변경(amount mismatch 명시적 거부)과 독립적이다. 하나의 PR에 묶으면 리뷰 범위가 넓어지고, 각 정책이 독립적으로 논의되어야 한다.

---

## 4. 회고

### 잘 된 점

- 기능 문서(PRD, architecture, ADR)를 step 시작 전에 모두 작성하고 구현에 들어갔다. 덕분에 구현 도중 설계 질문이 생기지 않았고, 코드와 문서가 일치하는 상태로 마무리됐다.
- catch 블록 한 곳만 수정해 변경 범위를 최소화했다. 정상 경로 코드는 전혀 건드리지 않았다.
- 동시성 테스트에서 선행 attempt 생성 후 mismatch 요청 20개를 동시에 쏘는 시나리오로 실제 충돌 상황을 재현했다. 단위 테스트만으로는 확인하기 어려운 동시 충돌 경로를 검증했다.
- infrastructure 예외 누수(`DataIntegrityViolationException`) 수정과 파라미터 명명 통일을 같은 PR에 묶었는데, 두 변경 모두 catch 블록 관련 정리로 목적이 같아 묶어도 무방했다.

### 더 빠르게 할 수 있었던 점

- 에러 코드 HTTP 상태 결정(400 vs 409)에서 약간의 논의가 있었다. 처음부터 "멱등 키 충돌 = 409 Conflict" 원칙을 설계 문서에 명시해 두었다면 결정 속도가 더 빠랐을 것이다.
- 파라미터 명명 통일 범위를 사전에 명확히 정해 두지 않아 `Payment.pgPaymentId`와 `NaverPayApproveResponse.pgPaymentId` 미변경 이유를 따로 ADR-D에 기록해야 했다. 제약을 PRD 단계에서 명시했더라면 ADR이 더 간결했을 것이다.

### 다음에 개선할 점

- Issue #99(상태 전이 검증)가 구현되면 amount 검사와 상태 검사가 catch 블록에 함께 위치하게 된다. 블록 내 로직이 늘어나는 경우 전용 메서드로 분리해 가독성을 유지하는 것이 좋다.
- Issue #100(catch 범위 좁히기)은 unique 위반 판별 로직을 공통 유틸로 추출할 수 있다. `OrderCreateService`도 유사한 패턴을 사용하므로 함께 정리하면 일관성이 높아진다.
- 멱등 키 관련 정책(amount 검증, 상태 전이, catch 범위)이 각기 다른 issue로 분산됐다. 이후에는 멱등성 계층 전체 설계를 한 번에 논의해 관련 issue를 하나의 epic으로 묶어 진행하면 파편화를 줄일 수 있다.

### 하네스 운영 개선

**Step 4: worktree 생성 후 이동 누락**

이번 작업에서 `git worktree add` 후 `cd`를 즉시 하지 않았다. SKILL.md는 두 명령 완료를 Step 4 완료 조건으로 명시하는데, cd 없이 Step 5(파일 작성)로 넘어갔다. 절대 경로로 작성해서 파일 자체는 문제없었지만, execute.py 실행 전에야 뒤늦게 이동했다.

`execute.py`는 내부에서 `_validate_worktree_context`로 worktree 아닌 곳에서 실행하면 즉시 실패(`SystemExit(1)`)한다. 따라서 Step 7은 보호된다. 그러나 Step 4~6 사이에는 보호 장치가 없다. 두 가지 개선 방향을 고려할 수 있다:

- **SKILL.md 강화**: Step 4 완료 확인 기준에 `pwd` 출력 또는 `git branch --show-current` 확인을 명시해 이동 여부를 강제한다.
- **execute.py 활용**: Step 7 진입 전 execute.py가 잡아주므로, 현재 구조로도 실행 단계에서는 안전하다. 파일 작성 단계(Step 5) 자체는 절대 경로로 처리되어 실질 영향이 없으므로, 운영 규칙(agent 자율 준수)으로 관리하는 수준으로 충분할 수 있다.

**`docs/features/` → `docs/tasks/`로 디렉토리 명칭 변경**

현재 기능 문서 경로가 `docs/features/<name>/`인데, 이 디렉토리 아래에 신규 기능뿐 아니라 정책 fix, 리팩터링 같은 태스크 단위 작업도 함께 관리된다. `features`라는 이름이 범위를 좁게 표현한다. `tasks`로 변경하면 작업 단위를 더 정확히 표현할 수 있다. 다만 SKILL.md, CLAUDE.md, 기존 feature 폴더 참조, harness 스크립트 등 많은 파일을 일괄 수정해야 하므로 별도 작업으로 진행이 필요하다.
