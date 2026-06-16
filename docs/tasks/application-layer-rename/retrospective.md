# Retrospective — application-layer-rename

- 작성일: 2026-06-16
- PR: #254
- 담당 Phase: 0-main (5 steps)

---

## 잘 된 것

### Step 설계가 도메인 단위로 딱 맞아떨어짐

member → product → stock → order → payment 순으로 독립성이 높은 도메인부터 진행한 덕분에 각 step이 자기완결적으로 검증됐다. 이후 도메인에 이전 도메인 서비스가 주입되는 구조가 없어서 step 간 충돌 없이 진행됐다.

### Reviewer가 누락 케이스를 정확히 잡아냄

- Step 5 attempt 1: `SucceedCancelPaymentServiceTest`, `FailCancelPaymentServiceTest` 누락을 reviewer가 발견해 attempt 2로 보완했다.
- PR review(Gemini): `SucceedPaymentApprovalRecordServiceTest`, `EscalateApprovePaymentServiceTest` 누락 지적 → accept해서 추가했다. 원본 클래스에도 단위 테스트가 없었으나 분리된 이후에는 독립 테스트가 생기는 것이 맞다는 판단이었다.

---

## 시행착오

### Step 2 — reviewer 핸드오프 형식 오지정

Reviewer 프롬프트에 `"verdict": "approve"` 형식을 직접 명시했더니, `execute.py`가 읽는 필드인 `"decision": "approved"`와 불일치해서 `_retry_or_fail`이 핸드오프 파일을 삭제하고 stage가 리셋됐다.

**교훈**: reviewer sub-agent는 자체 시스템 프롬프트에 올바른 핸드오프 형식(`"decision": "approved"`)이 내장돼 있다. 메인 agent가 프롬프트에 핸드오프 형식을 별도 지정하면 오히려 충돌한다. reviewer 프롬프트에 핸드오프 형식을 덮어쓰지 않는다.

### Step 3 — developer 중간 중단 (P3 재기상)

Committer 완료 알림이 P3로 오배달돼 이미 완료된 reviewer가 재기상했고, UI 상에서 중첩 계층 구조처럼 보였다. 이를 developer가 재기상한 것으로 오해해 Step 3 developer를 중간에 중단시켰다. 잔여 파일 업데이트를 targeted fix developer로 보완했다.

**교훈**: P3로 재기상한 sub-agent가 UI에 보여도 `execute.py step` 디스크 상태가 기준이다. UI 계층 구조나 알림만 보고 판단하지 않는다.

### 셔틀 루프 — 알림 대기 vs 디스크 기반 판정

초반에 sub-agent 완료 알림을 기다리다가 불필요하게 대기하는 경우가 있었다. sub-agent Task 호출이 리턴되면 알림 수신 여부와 무관하게 즉시 `execute.py step`을 실행해야 한다.

**교훈**: 완료 판정은 항상 `execute.py step`(디스크)이 기준. 알림은 신호가 아니라 노이즈로 취급한다.

### Stage 7 완료 전 머지

PR review 처리 직후 사용자가 Stage 7 완료 신호를 보내기 전에 PR을 먼저 머지했다. 결과적으로 Stage 8/9를 develop에서 직접 커밋·push하는 방식으로 처리했다.

**교훈**: `/pr-merge-cleanup` 전에 Stage 8/9가 완료됐는지 확인한다. review 코멘트 처리 후 바로 머지하면 회고록·루트 동기화 커밋이 PR에 포함되지 않고 develop에 직접 쌓이게 된다.

---

## 개선 제안

### 분리 테스트 이관 기준 명시 필요

분리(split) 작업 시 단위 테스트 이관 범위가 애매했다. 원본 클래스에 단위 테스트가 없던 메서드(`succeedApprovalRecordOnly`, `escalate`)는 분리 후에도 테스트 없이 넘어갔으나 PR review에서 지적받았다. Step 문서에 "원본 테스트가 없던 메서드도 분리 후에는 단위 테스트를 작성한다"는 기준을 명시하면 누락을 막을 수 있다.

### P3 대응은 현재 workaround로 안정적

P3(sub-agent 완료 알림 오배달)는 업스트림 버그(#40580)이며 harness 측에서 막을 수 없다. 디스크 기반 판정과 "알림 무시" 전략으로 실제 파이프라인은 정상 동작했다. 현재 workaround로 충분하다.
