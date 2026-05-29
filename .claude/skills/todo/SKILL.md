---
name: todo
description: Use when the user wants a prioritized list of pending work pulled from multiple sources (TODO.md, GitHub issues, ad-hoc thoughts) — triggered by "해야할 일 알려줘", "할 일 정리해줘", "todo", "/todo", "지금 뭐부터 하면 좋아?", or similar. Reads TODO.md and `gh issue list`, combines with any ad-hoc items the user includes in the message body, then lists items with weight + priority, proposes PR-level groupings, and recommends an execution order with reasons. The user decides what to actually work on — this skill just structures the input for that decision.
---

# todo

해야 할 작업을 여러 소스에서 모아 정리하고, 묶을 만한 것끼리 묶고, 추천 순서를 제시한다. 우선순위·무게감 산정 로직은 고정 공식이 아니라 매번 맥락 기반으로 판단한다.

이 skill 은 **의사결정 보조용**. 어떤 작업을 실제로 시작할지는 사용자가 정한다. 산출물은 응답에만 나오고, 어떤 파일도 수정하지 않는다.

## 데이터 소스

호출 시 다음 셋을 모아서 다룬다.

1. `TODO.md` — Phase 기반 로드맵. 로컬에만 있는 파일 (`.git/info/exclude` 로 추적 제외)
2. `gh issue list --state open --limit 50` — GitHub open issues
3. **사용자가 호출 메시지 본문에 자유롭게 적은 ad-hoc 항목** — "이런 것도 추가하고 싶어" / "방금 생각났는데 X도 해야 함" 같은 텍스트. 형식 자유

세 소스 모두 비어 있을 수도 있다. 그 경우 가용한 것만으로 진행한다.

## 절차

### 1. 데이터 수집

```text
1. Read TODO.md
2. Bash: gh issue list --state open --limit 50
3. 사용자 메시지 본문에서 ad-hoc 항목 식별
```

각 항목의 **출처를 추적**한다 (TODO.md Phase X / issue #N / ad-hoc). 출력에서 표기.

### 2. 항목 정리

각 작업에 대해:

- **간략 설명** — 1~2줄. 무엇을 하는지 명확하게
- **작업량** — `작음` / `보통` / `큼` 중 하나. 변경 범위·테스트 부담·예상 소요 시간 기반 직관 판단
- **우선순위** — `높음` / `중간` / `낮음` 중 하나. 이 작업이 시스템 전체·중장기 방향에서 얼마나 주요한가의 판단. **시급도(deadline) 가 아니라 중요도**다. 미래 확장성·다른 기능과의 연관·적정 선의 선제 도입 가치를 함께 본다.

판단 기준은 고정하지 않는다. 매번 현재 맥락(작업 중인 PR, 최근 incident, 로드맵 흐름 등)에서 합리적으로 판단한다.

### 3. 묶음 제안 (PR 단위 grouping)

여러 작업을 같은 PR 에서 처리해야 자연스러운 것끼리만 묶는다. **묶음 1 개 = PR 1 개**. Epic 같은 큰 관심사 묶음은 만들지 않는다 — Epic 안의 sub-task 들이 함께 다뤄야 한다고 느껴져도, 한 PR 로 떨어지지 않으면 묶지 않는다.

묶음의 기본 입장은 **보수적** — 의심스러우면 분리.

판단 전에 `docs/commit-conventions.md` 의 "커밋 단위 기준" 섹션을 읽어 분리 원칙을 확인한다. PR 단위도 동일한 원칙을 적용한다.

**묶을 만한 조건** (하나 이상 명확히 해당):

- 동일 파일/함수/클래스를 같이 건드려야 함 — 분리하면 conflict 나 중복 변경이 발생
- 한쪽이 다른 쪽의 직접적 선행 작업 — 의존 순서가 명확
- 동일한 좁은 변경 단위 — 예: 같은 endpoint 의 입력 검증 + 응답 형식 수정

**묶지 말아야 할 경우**:

- 단순히 같은 도메인이라는 이유 — 예: payment 도메인 전체를 한 PR 로 묶지 않음
- 역할·목적이 다른 변경 — 묶음 이름에 "및"·"하고" 같은 연결어가 필요해지면 분리 신호
- 변경 영역이 겹쳐도 한쪽이 크게 risky 한 경우 — 롤백 단위가 달라짐

묶음마다 **묶는 이유**를 한 줄로 명시한다. 묶을 만한 조합이 없으면 묶음 섹션을 비운다.

### 4. 추천 순서 + 이유

묶음 단위와 단독 항목을 섞어 실행 순서를 제시한다. 각 항목/묶음마다 **왜 이 순서인지** 한 줄 이유:

- "선행 작업이라 다른 항목들의 baseline 이 됨"
- "deadline 임박"
- "가벼우니 워밍업으로 먼저"
- "현재 진행 중인 PR 영역과 겹쳐서 같이"
- 등등

## 출력 형식

응답은 4 섹션으로 구성한다.

작업 리스트는 **출처별로 그룹핑**한다 (`TODO.md` / `GitHub issue` / `ad-hoc`). 출처가 없는 그룹은 섹션 자체를 생략.

```markdown
## 1. 작업 리스트

### TODO.md
- **항목명**
  - 설명: ...
  - 작업량: 작음 / 보통 / 큼
  - 우선순위: 높음 / 중간 / 낮음

(반복)

### GitHub issue
- **#N 항목명**
  - 설명: ...
  - 작업량: ...
  - 우선순위: ...

(반복)

### ad-hoc
- **항목명**
  - 설명: ...
  - 작업량: ...
  - 우선순위: ...

(반복)

## 2. 묶음 제안

### 묶음 A — <묶음 이름> (PR 1 개 단위)
- 포함: 항목 1, 항목 2
- 묶는 이유: ...

(반복. 묶을 만한 조합이 없으면 "묶을 만한 조합 없음" 으로 표시)

## 3. 추천 순서

1. **묶음 A** — 이유
2. **항목 X (단독)** — 이유
3. **묶음 B** — 이유
...

## 4. 메모

(선택) 판단하면서 마주친 모호함, 추가 확인이 필요한 부분
```

## 주의사항

- **read-only.** TODO.md, issue, 어떤 파일도 수정하지 않는다. 산출물은 응답뿐.
- **gh CLI 인증 전제.** `gh` 명령어가 없거나 `gh auth status` 실패 시 issue 소스를 건너뛰고 그 사실을 출력에 명시.
- **TODO.md 가 없으면** 해당 소스 건너뛰고 명시.
- **묶음 = 1 PR.** Epic 같은 큰 관심사 묶음을 만들지 않는다. 묶을 만한 조합이 없으면 "묶을 만한 조합 없음" 으로 솔직히 표시.
- **우선순위는 시급도가 아니라 중요도.** 단순 deadline 이 아닌 시스템 전체·중장기 방향에서의 주요도를 판단한다.
- **판단 logic 을 고정하지 않는다.** 작업량·우선순위 모두 매번 현재 맥락에서 판단. 사용자가 보기에 어색하면 redirect 받아 다시 판단.
- **추천이 결정은 아니다.** 어떤 작업을 시작할지는 사용자 몫. skill 은 정보 정리까지만.
