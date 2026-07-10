---
name: spec-harness-v1
description: 이 저장소에서 "harness로 spec 실행", "하네스로 이 phase 돌려줘", "<spec>의 <phase>를 자동 실행", 또는 구현 전 명세 정의(Specify)·모호함 해소(Clarify)·설계와 step 분해(Plan+Tasks)·정합성 검사(Analyze)·phase/step 기반 자동 구현 요청이 오면 반드시 이 skill을 사용한다. 명세를 먼저 확정하고 그 명세가 구현을 끌고 가는 SDD 워크플로우로, 위험영역(결제·인증·데이터 모델·상태 전이)은 spec-constitution에 따라 가정 없이 확정한다. 탐색·Specify·Clarify·Plan+Tasks·Analyze의 확정 단계부터, 준비된 phase를 dynamic workflow(/spec-harness-v1-execute)로 자동 완주시키는 실행, PR·루트 동기화까지 전체를 담당한다. 사용자가 "skill"·"harness"·"workflow"·"스펙"이라는 말을 정확히 쓰지 않아도 이 흐름에 해당하면 적용한다.
---

# Spec Harness Workflow (v1)

이 harness는 **명세를 먼저 확정하고, 그 명세가 구현을 끌고 가게** 한다(이른바 명세 주도 개발, SDD). 바로 코드를 짜지 않는다 — 먼저 spec을 세우고(Specify), 모호한 곳은 추측으로 메우지 않고 마커로 드러내 사용자와 해소하며(Clarify), 위험영역(결제·인증·데이터 모델·상태 전이)은 `docs/spec-constitution.md`에 따라 절대 가정하지 않는다. 그렇게 *확정된* 명세와 설계만 자동 실행으로 넘긴다. 명세가 상류, 코드가 하류다.

전체 흐름은 8개 Stage다. 크게 두 묶음으로 나뉜다.

- **확정 단계 (1~5)**: Explore(탐색) → Specify(명세 초안) → Clarify(모호함 해소) → Plan + Tasks(설계 + phase/step 분해) → Analyze(교차 정합·헌법 검사). 사람이 검토·확정하는 구간이다.
- **실행 단계 (6~8)**: Execution(자동 구현) → PR Review → Root Sync. 확정된 명세를 코드로 옮기고 루트에 반영하는 구간이다.

이 skill은 아래 상황에서 사용한다.

- 구현 전에 작업의 명세를 세우고 단계별로 나누고 싶을 때
- spec별 `phases/` 구조의 설계·계획 파일이 필요할 때
- 큰 작업을 자기완결적인 step으로 분해해야 할 때
- 준비된 phase를 자동으로 구현·검증·커밋까지 완주시키고 싶을 때

**Stage 6 실행은 dynamic workflow(`/spec-harness-v1-execute`)를 기동해 수행한다.**
preflight가 phase index.json을 읽어 workflow 인자를 만들고, workflow가 step마다
developer→reviewer→committer→recorder 서브에이전트를 돌린 뒤 finalize까지 자동으로 완주한다.

## 용어

작업 단위의 위계를 고정한다.

- **spec**: 최상위 작업 단위. 하나의 `spec.md`(확정·동결된 명세)가 정점이고, `docs/specs/<spec-name>/` 폴더 하나가 한 spec이다. 리팩터링·버그픽스·기능 추가 등 "한 덩어리 작업" 하나가 한 spec.
- **phase**: spec 안에서 "그 단위로 한 번 통합·검증할 가치가 있는 덩어리". 기본은 spec당 1개(`0-main`), 강한 선후 의존·중간 검증 가치가 있을 때만 여러 개.
- **step**: phase 안의 구현 작업 단위. **커밋 1개**에 대응하며 자기완결적 AC를 가진다. Stage 6(Execution)에서 workflow가 실행한다.

또 **Stage**(워크플로 전체의 진행 단계 1~8)와 위 작업 단위는 다른 축이다. 즉 Stage 6(Execution) "안에서" workflow(`/spec-harness-v1-execute`)가 한 spec의 phase·step들을 순차 실행하는 포함 관계다.

> spec-kit과의 대응: 이 harness의 **spec** = spec-kit의 feature(하나의 spec.md), **step** = spec-kit `tasks.md`의 한 task(T###, 최하위 실행 단위). **phase**는 spec-kit에 직접 대응이 없는 이 harness 고유의 중간 통합 묶음이다. (spec-kit "task"가 최하위 실행을 뜻해 이 harness의 최상위 단위와 충돌했기에, 최상위를 spec으로 부른다.)

---

## 필수 준수 규칙

아래 규칙은 반드시 지켜야 한다.

- **위험영역(결제·인증·데이터 모델·상태 전이)은 가정하지 않는다.** `docs/spec-constitution.md`의 불가침 원칙으로, spec에서 미확정이면 기본값으로 메우지 않고 마커로 남겨 Clarify(3)에서 사용자와 확정한다. 위험영역 마커가 남아 있으면 Plan(4)으로 넘어가지 않는다. Clarify는 이 마커들을 5개 질문 한도·deferral에서 면제해 끝까지 묻는다.
- 이 skill을 사용하는 작업에서는 `phases`가 준비된 이후의 기본 구현 경로를 수동 파일 수정이 아니라 `execute.py` 실행으로 본다.
- 사용자가 명시적으로 `execute.py`를 쓰지 말라고 하지 않은 이상, agent가 직접 구현을 시작하면 안 된다.
- `Implement the plan`은 자동으로 직접 구현을 뜻하지 않는다. `phases` 준비 여부와 실행 승인 여부를 먼저 확인해야 한다.
- Workflow는 spec 레벨 `workflow-checklist.json`(spec 폴더 바로 아래) 하나로 8-Stage를 추적하며, 다음 Stage로 넘어가기 전 이전 Stage가 모두 `completed`여야 한다. 이 checklist는 Specify(2)에서 worktree를 만들 때 `_template/workflow-checklist.json`을 복사해 생성한다.
- `harness` 진행 상태를 사용자에게 보고할 때는 1~8번 Workflow 상태 표를 함께 보여준다.
- `Analyze`(5) 통과 후에는 반드시 멈추고 작성된 문서 경로를 사용자에게 보고한 뒤 검토 응답을 기다린다. 바로 `execute.py` 실행 요청으로 넘어가지 않는다.
- `execute.py` 실행 전 반드시 사용자에게 진행 의사를 확인하고, 사용자가 진행을 승인한 뒤에만 실행한다(가벼운 확인 — 별도 Plan Mode·`ExitPlanMode` 절차는 거치지 않는다). 자동 코드 검증은 없으므로 이 룰은 agent가 직접 지킨다.
- Stage 6(Execution)에서 PR을 연 뒤 agent는 멈추고 사용자의 Stage 7(PR Review) 검토 완료 신호를 기다린다. "리뷰 코멘트가 아직 없음"은 Stage 7 완료가 아니다. Stage 7 완료가 확인되기 전에는 Stage 8(Root Sync)에 착수하지 않는다.
- Stage 8(Root Sync)는 두 가지를 한다. (1) 루트 문서 갱신 — ADR=append, 스냅샷(architecture/db-schema/api-spec)=overwrite로 동작이 다르다. (2) `_archive` 승격 — spec 정본(spec·설계·step 문서)을 `docs/specs/_archive/pr-<번호>-<spec명>/`로 복사해 같은 PR에 커밋한다(진행 상태·실행 부산물은 휘발로 남김). 한 지시로 뭉치지 않는다(아래 Stage 8 참고).

---

## Workflow 상태 표

`harness`를 진행하면서 사용자에게 상태를 보고할 때는 아래 표 형식을 사용한다.

| 단계 | Stage | 상태 |
| --- | --- | --- |
| 1 | Explore |  |
| 2 | Specify |  |
| 3 | Clarify |  |
| 4 | Plan + Tasks |  |
| 5 | Analyze |  |
| 6 | Execution |  |
| 7 | PR Review |  |
| 8 | Root Sync |  |

상태 표는 `workflow-checklist.json`이 있으면 그 값을 기준으로 표시한다. checklist 생성 전에는 현재 대화에서 실제 완료한 Stage만 `✅`로 표시한다.

`execute.py`는 checklist의 Stage 상태를 *자기 판단으로* 갱신하지 않는다(특히 phase 단위인 preflight·finalize는 spec 레벨 Stage를 건드리지 않는다). 대신 Stage 6(Execution)은 **메인이 자동 흐름으로** 갱신한다 — Stage 6 진입 시 `set-stage … in_progress`, phase 루프를 다 돈 뒤 `set-stage … completed`를 자동으로 호출한다(사람이 단계마다 지시하지 않는다). Stage 1~5는 진행하며 작성하고, Stage 7·8은 리뷰 결과·승격 완료 등 사람 판단이 필요한 시점에 `set-stage`로 갱신한다.

---

## 먼저 읽을 것

항상 먼저 아래를 읽는다.

- `CLAUDE.md`
- `docs/commit-conventions.md`

그 다음 현재 작업 대상 spec 문서를 먼저 읽는다.

- `docs/specs/<spec-name>/spec.md`
- `docs/specs/<spec-name>/architecture.md`
- `docs/specs/<spec-name>/adr.md`
- `docs/specs/<spec-name>/api-spec.md`
- `docs/specs/<spec-name>/db-schema.md`

spec 문서와 `phases` 문서로 부족한 공통 맥락이 있을 때만 `CLAUDE.md`의 `참고 문서` 섹션을 따라 루트 `docs/` 기준 문서를 추가로 읽는다.
작업 범위에 직접 연결된 코드와 테스트도 함께 읽는다.

> **문서 산출 모델(File Drafting 해체):** 옛 "File Drafting" 스테이지는 해체됐다. 각 단계가 자기 문서를 그 자리에서 쓴다 — Specify가 `spec.md`, Clarify가 `spec.md`의 `## Clarifications`, Plan + Tasks가 `architecture.md`·`api-spec.md`·`db-schema.md`와 phase·step 파일을 만든다. `adr.md`는 한 단계에 묶지 않고 **결정이 생기는 곳(주로 Clarify·Plan + Tasks)에서 그때그때 append**한다.

---

## Workflow

### 1. Explore

이 단계는 Specify(2)에 넘길 **입력 맥락을 모은다**. 아직 worktree도 `spec.md`도 만들지 않는다 — 현재 위치에서 *기존* 자산(외부 명세·루트 문서·관련 코드)을 **읽기만** 한다. 스캐폴딩(worktree 생성·checklist 생성)은 Specify(2)에서 한다.

- `CLAUDE.md`를 읽고 현재 Repo 규칙을 파악한다.
- **외부 기능 명세가 있으면 읽는다** — 이 작업이 더 큰 명세의 한 조각이면 그 부분을 Specify의 출발점으로 넘긴다. (명세가 없으면 생략한다. 리팩터링·버그 수정·탐색적 작업은 사전 명세가 없는 게 정상이다 — 그 경우 대화가 출발점이다.)
- 작업 범위에 직접 연결된 **코드와 테스트를 읽어** 현재 구조와 변경 범위를 파악한다.
- 공통 아키텍처, 다른 도메인 ERD, 전역 ADR 같은 루트 `docs/` 기준 문서는 *더 필요할 때만* 추가로 읽는다.
- 이 작업이 무엇인지 윤곽이 잡히면 **slug 후보**와 type을 머릿속에 떠올려 둔다(허용 타입·형식은 `docs/branch-conventions.md`를 따른다) — 확정은 Specify 초반에 사용자와 한다.
- **재실행이라 이미 worktree·`spec.md`가 있으면** 그 worktree로 이동해 현재 진행 상태를 읽는다(신규 작업엔 해당 없음).
- 이미 답할 수 있는 질문은 하지 않는다.
- 병렬 탐색이 가능한 환경이면 관련 영역을 나눠 추가 탐색할 수 있다.

산출: 없음(탐색만). 모은 맥락은 Specify(2)의 입력이 된다.

---

### 2. Specify

이 단계는 작업의 *무엇을·왜*를 확정하고 `spec.md` **초안**을 쓴다. 그 전에 **작업 공간(worktree)과 진행 추적(checklist)을 먼저 만든다** — 이후 모든 문서·구현이 이 worktree 안에서 산다.

#### 작업 공간 준비 (Specify 맨 앞 — 한 번)

1. **slug·type 확정**: 사용자의 한 줄 작업 설명에서 `<spec-name>` slug와 type을 정한다. type과 브랜치 형식은 `docs/branch-conventions.md`를 단일 출처로 따른다(harness가 값을 하드코딩하지 않는다). spec 내용을 다 채우기 전에 *이름만* 먼저 정하는 것이다(slug는 폴더·브랜치 이름일 뿐, 명세의 완성이 아니다). 모호하면 사용자에게 짧게 확인한다.
2. **worktree 생성·이동**: 브랜치·worktree 생성은 `docs/branch-conventions.md`의 "worktree로 브랜치 생성하기" 규칙을 그대로 따른다. `<name>`은 여기서 `<spec-name>`으로 대응한다. 메인 repo 루트로 이동한 뒤 그 규칙대로 worktree를 만들고 그 안으로 이동한다.

   이동 직후 `pwd`(또는 `git branch --show-current`)로 방금 만든 worktree 경로·브랜치 안에 있는지 반드시 확인한다. 이후 모든 문서 작성·`execute.py` 실행은 이 worktree 루트 기준이다.
3. **스캐폴딩(checklist 생성)**: worktree 안에 `docs/specs/<spec-name>/` 폴더를 만들고, `docs/specs/_template/workflow-checklist.json`을 `docs/specs/<spec-name>/workflow-checklist.json`으로 복사한다. 이 시점에 8-Stage 진행 추적 checklist가 존재한다(실행 게이트가 이를 읽는다). **설계 문서(plan·architecture·data-model 등)는 지금 복사하지 않는다 — Plan(4)에서 필요한 것만 `_template`에서 꺼내 만든다.** `spec.md`도 아래에서 새로 만든다.

> worktree·checklist는 spec당 한 번만 만든다. 재실행이면 이미 있으므로 건너뛴다.

#### spec.md 초안 작성

**핵심 방식: 초안을 먼저 뽑는다.** 길게 대화한 뒤 받아쓰는 게 아니라, 가진 입력으로 `spec.md` 템플릿(`_template/spec.md`)을 복사해 *지금* 채운다. 긴 질의응답은 이 단계가 아니라 Clarify(3)에서 일어난다. Specify의 목표는 "완성된 spec"이 아니라 **"빈칸이 마커·가정으로 드러난 초안"**이다.

#### 입력 (어디서 출발하나)

- **외부에 기능 명세가 있으면** 그 조각을 참고해 이 spec 범위로 좁힌다.
- **없으면** 사용자와의 대화를 출발점으로 삼는다. 명세가 없는 것은 정상이다 — 리팩터링·버그 수정·기술 부채 정리·탐색적 작업은 사전 명세 자체가 없다.

#### 작성 규칙 (spec.md 템플릿의 작성 규칙을 따른다)

`spec.md` 템플릿 상단의 작성 규칙을 그대로 적용한다. 요지:

- **추측 금지.** 대화에서 명시되지 않은 결정을 그럴듯하게 지어내 채우지 않는다. 안 정해진 것은 둘 중 하나로 *드러낸다*:
  - 합리적 기본값이 있으면 → 그 값을 적되 `## 가정(Assumptions)`에 명시한다.
  - 기본값이 없으면 → `[NEEDS CLARIFICATION: 질문]` 마커로 남긴다(Clarify에서 해소).
- **위험영역은 기본값 금지.** 결제·인증·데이터 모델·상태 전이가 미확정이면 가정으로 메우지 않고 **반드시 마커**로 남긴다. (이 영역의 강제 규칙 상세는 작업 E·C에서 확정한다.)
- **누락 금지.** 필수 섹션은 비우지 않는다. 해당 없으면 "해당 없음"이라고 적는다(빈 섹션이 곧 미검토 신호).
- 요구사항·완료 기준에는 ID(`FR-001`·`SC-001`)를 붙인다(Analyze가 이 ID로 커버리지를 추적).

#### 모호함을 마커로 남길 신호

아래에 해당하면 임의로 확정하지 말고 `[NEEDS CLARIFICATION]` 마커로 남긴다(Clarify에서 사용자와 해소).

- 요구사항이 둘 이상으로 해석될 수 있을 때
- 설계 선택이 결과에 큰 영향을 줄 때
- 외부 인증·API 키·수동 설정 등 사용자 개입이 필요할 때
- 기존 구조나 규칙과 충돌 가능성이 있을 때

산출: `docs/specs/<spec-name>/spec.md` (배경·시나리오·범위·요구사항·데이터 모델·완료 기준·제약·가정).

#### 작성 전 루트 문서 정합성 확인 (필수)

스펙 방향을 확정하기 전에, 작업이 기존 결정·원칙과 충돌하는지 **agent가 먼저 확인**한다. 임의로 방향을 정한 뒤 "충돌 지점을 검토해 달라"고 사용자에게 떠넘기지 않는다. 충돌이 발견되면 그 지점을 마커로 남기거나, 아래 제안 형식으로 사용자에게 방향을 묻는다.

1. **항상**: `docs/adr/index.md`(루트 ADR 색인)를 읽고, "주요 결정 키워드"를 훑어 이 작업과 관련된 ADR을 식별한 뒤 해당 항목을 확인한다.
2. **영역별**: 작업이 건드리는 영역에 해당하는 루트 문서를 추가로 확인한다.

| 작업이 건드리는 것 | 확인할 루트 문서 |
| --- | --- |
| 설계 방식·정책 (어떻게 풀까) | 루트 ADR (+ 관련 spec adr) |
| 구조·레이어·책임 배치 | `docs/architecture.md` |
| 데이터 모델·테이블·제약 | `docs/db-schema.md` |
| API 추가·변경 | `docs/api-spec.md` |

3. **제안 형식**: 확인 결과를 아래 형식으로 제시한다. 이 형식은 agent가 문서를 실제로 확인했다는 근거이며, 사용자는 정합 라벨만 보고 판단할 수 있다.

```
## 제안

관련 결정/문서:
- ADR-020 (cross-aggregate ID 참조) — 이 작업이 새 연관을 추가하므로 관련
- architecture.md (payment 도메인 책임) — 결제 상태를 건드리므로 관련

선택지:
A. <방식 A> — ADR-020 일치. 추천.
B. <방식 B> — ADR-020 위반. 채택하려면 ADR 갱신 필요.

→ 어느 방향으로 진행할까요?
```

- 기존 결정과 충돌하는 방향은 반드시 "위반/갱신 필요"로 명시한다.
- ADR이 많으므로 전문을 모두 읽지 말고, 색인으로 관련 항목을 식별한 뒤 그 항목만 깊이 읽는다.

#### 출구

초안이 서면(템플릿이 채워지고 빈칸이 마커·가정으로 드러나면) Clarify(3)로 이어간다. 초안 마커가 하나도 없어도 Clarify를 건너뛰지 않는다 — 마커는 초안 작성자가 잡은 것뿐이라, Clarify에서 taxonomy 전체 스캔을 최소 1회 돌려 표시되지 않은 모호함을 확인한 뒤에야 통과 여부를 정한다.

---

### 3. Clarify

`spec.md`의 모호함을 사용자와의 대화로 해소하는 단계다. spec-kit의 clarify 흐름을 따른다 — **고정 taxonomy로 spec을 훑어 빈칸을 잡고, 최대 5개를 한 번에 하나씩 묻고, 답을 즉시 spec에 반영·기록**한다. 그 위에 `spec-constitution.md`의 **위험영역 불가침** 원칙을 게이트로 얹는다.

> 이 단계는 스크립트가 아니라 LLM 프롬프트로 동작한다. 메인 스레드에서 사용자와 직접 Q&A하며(서브에이전트 아님), 답을 받을 때마다 `spec.md`를 원자적으로 저장한다.

#### 시작 시

1. `spec.md`를 읽는다. 없으면 Specify(2)를 먼저 하라고 안내하고 여기서 spec을 새로 만들지 않는다.
2. **`docs/spec-constitution.md`를 로드한다.** 위험영역 4종(결제·인증·데이터 모델·상태 전이)과 면제 규칙을 이 단계가 강제한다.

#### 모호함 스캔 (taxonomy)

아래 고정 카테고리로 `spec.md`를 훑어, 각 항목을 **Clear / Partial / Missing**으로 내부 분류한다(coverage map). "agent가 모호하다고 느꼈나"가 아니라 "이 칸이 채워졌나"가 판정 기준이다.

- 기능 범위·동작 (목표/비목표, 성공 판정, 명시적 out-of-scope)
- 도메인·데이터 모델 (엔티티, 관계, **식별·유일성 규칙**, **상태 전이/lifecycle**)
- 상호작용·UX 흐름 (액터/역할, 핵심 동선, 빈/에러/로딩 상태)
- 비기능 품질 (성능, 확장성, 신뢰성, 관측성, **보안·프라이버시 authN/Z**, 컴플라이언스)
- 통합·외부 의존 (외부 서비스/API와 실패 모드, 데이터 포맷, 프로토콜/버전 가정)
- 엣지·실패 처리 (부정 시나리오, rate limit, 동시 편집 등 충돌 해소)
- 제약·트레이드오프 (기술 제약, 명시적 트레이드오프/기각 대안)
- 용어 일관성 (표준 용어, 회피할 동의어)
- 완료 신호 (완료 기준의 검증 가능성, 측정 가능한 DoD)
- 기타·placeholder (TODO 마커, "robust"·"직관적" 등 정량화 안 된 모호 형용사)

Partial/Missing 항목은 질문 후보로 올리되, 다음이면 **뺀다**: 명확히 해도 구현·검증 전략이 실질적으로 안 바뀌거나, 계획(Plan) 단계로 미루는 게 나은 경우.

#### 질문 큐 산정 (≤5, 단 위험영역은 면제)

후보를 우선순위 큐로 만든다. 규칙:

- **일반 모호함**: 최대 5개. 5개를 넘으면 (영향 × 불확실성) 상위 5개만. 한 번에 다 보여주지 않는다.
- 각 질문은 객관식(2~5개 상호배타 옵션) 또는 단답(≤5단어)으로 답할 수 있어야 한다.
- 아키텍처·데이터 모델·태스크 분해·테스트 설계·UX·운영·컴플라이언스에 실질 영향을 주는 것만.
- **위험영역 면제 (헌법 강제)**: 위험영역 4종에 걸린 미확정 마커는 위 5개 한도와 deferral 대상에서 **제외된다.** 일반 모호함이 5개를 채워도, 위험영역 질문은 항상 큐에 남아 반드시 묻는다. 우선순위에 밀려 Deferred로 가지 않는다.

#### 질문 루프 (한 번에 하나)

- **정확히 한 질문씩** 제시한다. 다음 큐 질문을 미리 노출하지 않는다.
- 객관식: 최적 옵션을 먼저 분석해 **추천 옵션 + 이유(1~2문장)**를 맨 위에 두고, 그 아래 옵션 표를 낸다. 사용자는 옵션 문자로 답하거나, "추천"으로 추천을 수락하거나, 직접 단답할 수 있다.
- 단답: **제안 답 + 근거**를 먼저 주고, ≤5단어로 답하게 한다.
- 사용자가 "추천/yes"로 답하면 직전 추천을 답으로 쓴다. 모호하면 같은 질문 내에서 한 번 더 확인한다(질문 수에 추가 안 됨).
- **종료 조건**: 치명 모호함이 다 풀렸거나 / 사용자가 "그만·됐다" 신호 / 5개에 도달. **단, 위험영역 마커가 남아 있으면 종료하지 않는다**(면제 항목이라 5개 한도와 무관하게 끝까지 묻는다).

#### 답마다 즉시 반영·기록 (원자적)

수락된 답 하나마다:

1. `## Clarifications`가 없으면 만들고(개요 섹션 바로 뒤), 그 아래 `### Session YYYY-MM-DD`를 둔다.
2. `- Q: <질문> → A: <최종 답>` 한 줄을 추가한다.
3. 곧바로 해당 본문 섹션에 반영한다 — 기능 모호함→요구사항, 데이터/엔티티→데이터 모델, 비기능→완료 기준(모호 형용사를 수치로), 엣지→Edge Cases, 용어→전체 통일. 답이 기존 모호 문장을 무효화하면 그 문장을 **교체**한다(모순 잔존 금지).
4. **반영 직후 `spec.md`를 원자적으로 저장**한다(컨텍스트 유실 대비). 무관한 섹션은 재정렬하지 않는다.

#### 검증 (매 저장 + 최종)

- Clarifications에 수락 답당 정확히 한 줄(중복 없음), 수락 질문 총 ≤5(위험영역 면제분 제외).
- 새 답이 해소하려던 모호 placeholder가 본문에 남아 있지 않다. 모순되는 옛 문장 없음.
- 새로 추가된 heading은 `## Clarifications` / `### Session YYYY-MM-DD`만.
- 용어 일관성 유지.

#### 반복·종료 (사용자 판단으로 닫는다)

Clarify는 한 번의 스캔·질문 루프로 닫지 않는다. 초안(Specify)에 남은 `[NEEDS CLARIFICATION]` 마커를 푸는 것은 **첫 패스일 뿐**이며, 그것으로 이 Stage를 완료로 두지 않는다. 초안 작성자가 미처 마커로 남기지 못한 모호함이 있으므로, 마커 해소 후에도 위 taxonomy 전체로 **재스캔을 최소 1회 더** 돌려 표면화한다. (한 질문 루프의 종료 조건(위 "질문 루프")은 그 *패스*를 닫을 뿐, *Clarify Stage*를 닫는 것이 아니다.)

- **최소 1회의 taxonomy 전체 스캔 전에는 닫기를 제시하지 않는다.** 초안 마커 해소는 taxonomy 전체 스캔이 아니다 — 마커를 다 풀었어도(또는 애초에 마커가 없어도) 먼저 taxonomy 전체를 한 번 스캔한다. 이 최소 스캔이 끝나기 전에는 "닫고 Plan(4)로 갈지" 선택지를 내지 않는다. (초안 작성자가 위험영역 미확정을 마커로 남기지 못했을 때, 이 재스캔이 헌법상 Risk Floor를 보완하는 관문이므로 우회를 허용하지 않는다.)
- **최소 스캔 이후 매 스캔 후 진행 의사를 묻는다.** agent가 스스로 Stage를 닫지 않는다 — 최소 스캔을 마친 뒤부터 각 패스가 끝날 때 "한 번 더 taxonomy를 스캔할지 / 닫고 Plan(4)로 갈지"를 물어 방향을 정한다. 한 번에 닫지도, 무한 반복하지도 않는다.
- **종료 기준은 사용자 판단이다.** "모호함 완전 소거"가 아니라 **사용자가 충분히 해소됐다고 판단하는 시점**에 닫는다. 무모호 상태를 종료 조건으로 강제하지 않는다 — Plan에서 확정할 계약·구현 세부(DTO 필드명·에러 코드 네이밍·JSON 매핑 방식 등)까지 여기서 붙들지 않는다.
- **위험영역은 사용자 판단과 무관하다(헌법 강제).** 위험영역(결제·인증·데이터 모델·상태 전이) 마커가 하나라도 남으면, 사용자가 "됐다"고 해도 이 Stage를 `completed`로 두지 않는다. 위험영역 마커는 5개 한도·deferral에서 면제되어 반드시 해소되며, 미확정 상태로는 Plan(4)으로 넘어가지 않는다(실행 게이트가 이를 전제한다).

#### 게이트

- **모호함이 없으면**: 재스캔에서도 새 빈칸이 나오지 않고 사용자가 닫기로 하면, "물을 것 없음(치명 모호함 없음)"으로 통과하고 Plan(4) 진행을 권한다. 단 초안 마커만 푼 첫 패스 직후에 곧장 통과시키지 않는다 — 위 재스캔을 먼저 돌린다.
- 일반 모호함이 5개 한도에 걸려 못 푼 게 남으면 Deferred로 명시(위험영역은 Deferred 불가).
- 결정이 생기면 `adr.md`에 그때그때 append 한다.

---

### 4. Plan + Tasks

`spec.md`(확정·동결)를 받아 **어떻게 만들 것인가**를 설계하고, 작업을 phase·step으로 분해한다.

이 단계는 두 부분이다 — **Plan**(설계 문서 작성)과 **Tasks**(phase/step 분해). spec-kit의 plan→tasks에 대응하며, 네 harness는 한 Stage에서 처리한다.

#### 설계 문서 (Plan)

`plan.md`가 **총괄 설계서**이고, 나머지는 그 하위 문서다. plan.md가 "무엇을 어떤 접근으로"를 잡고, 각 하위 문서가 관점별로 구체화한다.

- **`plan.md`** (상위, 항상 작성) — Summary + 기술 맥락(이 작업에서 *달라지는 것만*; 스택 고정값은 루트 컨벤션 참조) + **Constitution Check** + 구조 결정 + 어떤 하위 문서를 쓸지 지정.
- 하위 문서 (이 작업에 필요한 것만 작성):
  - **`architecture.md`** — 구조·레이어·책임·데이터 흐름.
  - **`data-model.md`** — 도메인 엔티티·관계·식별·**상태 전이**(도메인 모델을 다루면). ← 위험영역.
  - **`db-schema.md`** — 테이블·인덱스·마이그레이션(물리 스키마). data-model이 개념, db-schema가 물리.
  - **`api-spec.md`** — API 계약(API 변경이 있으면).
  - **`adr.md`** — 채택 결정 staging(기록할 결정이 생기면 그때그때 append).
  - **`research.md`** — 기술 선택 조사(새 의존성·미해결 기술 선택이 있을 때만).

**Constitution Check (GATE)**: plan을 확정하기 전에 `docs/spec-constitution.md`로 점검한다.

- 위험영역(결제·인증·데이터 모델·상태 전이)에 닿는데 spec.md에 미확정 마커가 남아 있으면, **Plan을 진행하지 않고 Clarify(3)로 되돌아간다.**
- 설계가 참조 규약(레이어 의존 방향·Aggregate 경계·예외 전략 등)을 위반하면, plan.md "복잡도·예외 기록"에 정당화하거나 설계를 고친다. 헌법을 희석하지 않는다.

#### Phase 설계 (Tasks)

phase는 "그 단위만으로 한 번 통합·검증할 가치가 있는 덩어리"다. step이 커밋 단위라면 phase는 통합 사이클 단위다.

- 기본값은 spec당 phase 1개다. 통합 지점이 한 번뿐인 보통 크기의 작업은 phase를 나누지 않는다.
- 아래 중 하나가 분명할 때만 phase를 여러 개로 나눈다.
  - 강한 선후 의존: 앞 phase가 끝나야 다음 phase를 안전하게 시작할 수 있다. (예: 공통 도메인/인프라 선행 → 그 위에 기능)
  - 중간 검증 가치: 큰 작업에서 중간에 한 번 끊어 제대로 됐는지 확인하고 가는 게 의미 있다.
- 큰 작업을 phase 없이 step만 길게 늘어놓지 않는다. 중간 통합 지점이 없으면 검증이 끝으로 몰려 되돌림 비용이 커진다.
- phase 이름은 `<순번>-<slug>` 형식을 쓴다. 단일 phase의 기본 이름은 `0-main`으로 한다.

#### Step 설계 원칙

- 한 step은 테스트 가능한 사용자 기능 단위를 기본값으로 삼는다.
- API feature는 domain, repository, service, controller, test가 같은 사용자 기능 완성에 필요하면 한 step에 함께 포함한다.
- 레이어별 step 분리는 공통 도메인 선행 작업, 독립 DB 마이그레이션처럼 분리 검증이 명확히 필요한 경우에만 사용한다.
- command/query는 데이터 흐름과 검증 기준이 다르면 분리하고, 같은 정책과 aggregate를 공유하는 command 동작은 묶을 수 있다.
- 각 step 문서는 독립 실행 가능한 자기완결 문서여야 한다.
- step 설계 시 구현 단위와 커밋 단위가 같은 기능/정책 목적을 가리키도록 나눈다. 파일 단위로 과도하게 쪼개지 않는다.
- 관련 문서 경로와 이전 step 결과를 이해하는 데 필요한 파일 경로를 명시한다.
- 구현 지시는 인터페이스와 핵심 제약 위주로 작성하고, 내부 구현은 과도하게 고정하지 않는다.
- Acceptance Criteria는 실행 가능한 커맨드로만 적는다. (이 AC가 "스펙에서 벗어남"의 정의선이다.)
- 주의사항은 `하지 마라. 이유: ...` 형식으로 구체적으로 작성한다.
- step name은 kebab-case slug를 사용한다.

산출:
- `docs/specs/<spec-name>/plan.md` (+ 필요한 하위 설계 문서).
- `docs/specs/<spec-name>/phases/index.json`, `phases/<phase-name>/index.json`, `phases/<phase-name>/step{N}.md`.

포맷과 상세 규칙은 `references/phase-files.md`를 따른다.

루트 docs 동기화(`sync-root-docs`)는 phase의 step으로 두지 않는다. Stage 8(Root Sync)에서 phase 바깥에서 수행한다.

---

### 5. Analyze

구현 전, 작성된 문서들의 **교차 정합성**과 **constitution 위반**을 읽기 전용으로 점검하는 게이트다. spec-kit의 analyze를 따른다 — spec ↔ plan ↔ 설계 문서 ↔ phase/step을 교차 검사해 불일치·중복·모호·미명세·커버리지 공백을 잡는다. context 오염을 막기 위해 **`spec-harness-v1-analyzer` 에이전트를 `Task` 도구로 띄워** 돌린다(읽기 전용 배치라 상호작용이 없다 — Clarify와 반대). 이 단계는 워크플로(Stage 6 전용) 바깥이므로 **메인 에이전트가 직접** 그 에이전트를 호출한다.

> 메인은 `Task` 도구로 `spec-harness-v1-analyzer`를 띄우며, spec 폴더 경로(`docs/specs/<spec-name>/`)를 프롬프트로 전달한다. 에이전트는 아래 입력을 읽어 검출 패스를 돌리고 **마크다운 리포트**를 반환한다. 메인은 그 리포트를 사용자에게 그대로 보여준다. 에이전트는 **절대 파일을 수정하지 않는다**(읽기 전용). 수정은 사용자 승인 후 사람이 한다. (별도 로그는 남기지 않는다 — 리포트가 곧 산출물이다.)

#### 입력 (최소 로드)

- **spec.md**: 배경, 기능 요구사항(FR-###), 완료 기준(SC-###), 사용자 시나리오, Edge Cases, Assumptions, Clarifications.
- **plan.md**: 구조 결정, 기술 맥락, Constitution Check 결과, 어떤 설계 문서를 썼는지.
- **설계 문서**(작성된 것): architecture·data-model·db-schema·api-spec·adr.
- **phase/step**: phase index, 각 `step<N>.md`의 구현 지시 + Acceptance Criteria.
- **`docs/spec-constitution.md`**: 원칙 검증 기준.

#### 검출 패스 (high-signal 위주)

1. **헌법 정합 (최우선)**: spec·plan·설계·step 중 무엇이든 `spec-constitution.md`의 불가침 원칙과 충돌하는가? 특히 **위험영역(결제·인증·데이터 모델·상태 전이)** 미확정·임의 가정, 참조 규약(레이어 의존 방향·Aggregate 경계·예외 전략) 위반. **헌법 위반은 자동 CRITICAL.**
2. **커버리지 공백**: FR-###/SC-### 중 대응 step이 0개인 요구사항. 반대로 어떤 요구사항에도 안 걸리는 step. (buildable한 SC만 — 출시 후 KPI는 제외.)
3. **미명세**: 동사는 있는데 대상·측정 기준이 없는 요구사항, AC가 spec과 안 맞는 step, spec/plan에 없는 파일·컴포넌트를 참조하는 step.
4. **모호함**: 측정 기준 없는 형용사("빠른"·"안정적"·"확장 가능"), 미해소 placeholder(TODO·???).
5. **중복**: 거의 같은 요구사항. 더 흐린 표현을 통합 대상으로.
6. **불일치**: 용어 드리프트(같은 개념 다른 이름), plan엔 있는데 spec엔 없는 엔티티(또는 반대), step 순서 모순(기반 작업 전에 통합 step), 충돌하는 요구사항.

#### 심각도

- **CRITICAL**: 헌법 MUST 위반 / 핵심 spec 산출물 누락 / 기본 기능을 막는 무커버리지 요구사항.
- **HIGH**: 중복·충돌 요구사항 / 모호한 보안·성능 속성 / 검증 불가능한 AC.
- **MEDIUM**: 용어 드리프트 / 비기능 커버리지 누락 / 미명세 엣지 케이스.
- **LOW**: 표현·문구 개선, 실행 순서에 영향 없는 사소한 중복.

#### 리포트 (파일 안 씀)

- 발견 표(ID·범주·심각도·위치·요약·권고).
- **커버리지 표**: 요구사항 키(FR/SC) ↔ 대응 step ↔ 비고.
- 헌법 위반 목록(있으면), 미매핑 step 목록(있으면).
- 지표: 총 요구사항·총 step·커버리지%·모호 수·중복 수·CRITICAL 수.

#### 게이트·종료

- **CRITICAL이 있으면** 구현 전 해소를 권고하고, 이 Stage를 `completed`로 두지 않는다(실행 게이트가 전제). 특히 헌법 위반은 spec·plan·step을 고쳐 해소하지, 헌법을 희석하지 않는다.
- **LOW/MEDIUM만 있으면** 진행 가능하되 개선안을 제시한다.
- 수정은 자동 적용하지 않는다 — 사용자가 어디로 되돌아갈지(Specify·Clarify·Plan) 정해 사람이 고친다.

#### Analyze 통과 후 필수 중단

- 작성·수정한 spec 문서(`spec.md`·`plan.md`·`architecture.md`·`data-model.md`·`db-schema.md`·`api-spec.md`·`adr.md`), phase index, step 문서, `workflow-checklist.json` 경로를 사용자에게 보고한다.
- 이 시점의 checklist는 `Explore`, `Specify`, `Clarify`, `Plan + Tasks`, `Analyze`만 `completed`여야 하고, `Execution`(6) 이후는 `pending`이어야 한다.
- 사용자의 단순한 "진행해", "계속해", "Implement the plan"은 문서 검토 완료 또는 실행 승인으로 해석하지 않는다.

---

### 6. Execution

Stage 6(Execution) 실행은 **dynamic workflow(`/spec-harness-v1-execute`)를 기동**해 수행한다. `execute.py`를 직접 돌려
phase를 완주시키지 않는다 — 대신 preflight로 workflow 인자를 만들고, workflow가 step 루프를 돈다.
실행 전 아래 순서를 반드시 거친다. 자동 검증 게이트는 없으므로 이 룰은 agent가 직접 지킨다.

1. Analyze까지의 결과(작성한 spec 문서·phase 경로)와 실행 계획을 사용자에게 보고하고, 실행 진행 의사를 가볍게 확인받는다. 별도 Plan Mode·`ExitPlanMode` 절차는 거치지 않는다.
2. `AskUserQuestion`으로 agent별 실행 모델을 수집한다. (아래 "실행 옵션 수집" 절 참고)
3. 수집한 모델을 phase index의 `execution` 필드에 기록한 뒤, preflight → workflow 기동으로 실행한다.

- workflow는 worktree 안에서 기동하며, committer·finalizer 서브에이전트를 통해 커밋·push를 수행한다.
- 이 Stage에 들어가기 전 checklist의 `Explore`, `Specify`, `Clarify`, `Plan + Tasks`, `Analyze`는 모두 `completed`여야 한다.
- 사용자가 승인하지 않으면 구현으로 진행하지 않는다.

> spec 문서(spec·plan·architecture·data-model·db-schema·api-spec·adr)와 phase·step·index·checklist는 `docs/specs/<spec-name>/` 아래에 있고 **작업 중에는 `.gitignore` 대상**이라 커밋하지 않는다(예외: Stage 8에서 `docs/specs/_archive/`로 승격되는 사본). 따라서 workflow 기동 전 "spec 문서 사전 커밋" 단계는 없다. committer는 코드 변경만 커밋하며, 작업 중 spec 폴더는 git에 잡히지 않는다.

#### 실행 옵션 수집

workflow 기동 직전, `AskUserQuestion`으로 agent별 모델을 한 번에 수집한다. 한 호출에 세 질문을 묶어 전달해 한 화면에 동시에 표시한다.

| 질문 (header) | 옵션 (label) | 기본 권장 |
| --- | --- | --- |
| Developer | `sonnet (Recommended)` / `opus` / `haiku` | sonnet |
| Reviewer | `opus (Recommended)` / `sonnet` / `haiku` | opus |
| Commit | `haiku (Recommended)` / `sonnet` / `opus` | haiku |

옵션값 변환 규칙:

- label에서 첫 공백 또는 ` (` 이전 토큰을 추출해 모델 값으로 사용한다. 예: `"sonnet (Recommended)"` → `sonnet`.
- 사용자가 "Other"를 선택하고 자유 입력하면 입력 문자열을 그대로 모델 값으로 전달한다. alias(`opus`/`sonnet`/`haiku`)와 full name 형태를 모두 받는다.
- 옵션을 묻지 않고 기본값으로 진행하라는 명시 지시가 있으면 Developer=sonnet / Reviewer=opus / Commit=haiku를 사용한다.

수집된 값은 phase index의 `execution` 필드에 1회 기록되어 추적 가능해진다. 재실행 시에는 기존 값이 보존된다. 자세한 스키마는 `references/phase-files.md`를 참고한다.

#### 실행 — Stage 6 자동 흐름 (in_progress → phase 루프 → completed)

`phases` 파일이 준비되고 모델이 정해지면, 메인은 **Stage 6 진입과 동시에 아래 ①~③을 하나의 자동 흐름으로 수행한다.** 사람이 단계마다 개입해 in_progress·completed를 일일이 지시하는 게 아니라, 메인이 이 절차를 끝까지 자동으로 진행한다. checklist는 **spec 레벨에 하나**(`workflow-checklist.json`, spec 폴더 바로 아래)이고, 그 안에서 **phase는 여러 개일 수 있다**(각 phase가 자기 step들을 따로 돈다).

**① Execution을 in_progress로 — 자동, 진입 시 1회**

Stage 6에 들어가면 메인이 곧바로 checklist의 Execution을 in_progress로 표시한다(phase 루프를 시작하기 직전, spec 단위 1회).

```bash
python3 .claude/skills/spec-harness-v1/scripts/execute.py set-stage docs/specs/<spec-name> Execution in_progress
```

**② phase 루프 — 모든 phase에 대해 preflight → workflow 반복 (자동)**

메인은 spec의 각 phase(`0-main`, `1-domain`, …)를 순서대로 돌린다. phase가 하나면 1회, 여러 개면 차례로 반복한다. 사람 개입 없이 이어서 진행한다.

```bash
# (2-a) preflight — worktrees/<type>-<spec-name>/ 안에서
python3 .claude/skills/spec-harness-v1/scripts/execute.py preflight docs/specs/<spec-name>/phases/<phase-name>/
```

STDOUT으로 `{"ok": true, "execute": "...", "phase_dir": "...", "steps": [...], "execution": {...}, ...}`
형태의 JSON 한 줄이 나온다. 이것을 그대로 workflow 인자로 넘긴다.

```
# (2-b) workflow 기동
/spec-harness-v1-execute with args <preflight가 출력한 JSON 전체>
```

> 참고: 이 런타임에서 workflow `args`는 JSON **문자열**로 주입된다. workflow 스크립트가 내부에서
> 파싱하므로, preflight 출력 JSON을 그대로 넘기면 된다. 자연어로 막연히 "실행해" 두면 args가 채워지지 않으니
> 저장된 `/spec-harness-v1-execute` 명령으로 호출한다.

workflow는 `pending`인 step부터 순차로 developer→(AC확인)→reviewer→committer→recorder를 돌리고,
모든 step이 끝나면 finalizer로 **그 phase를 닫는다**(이 phase의 completed_at·spec index phase status·push). 진행은 `/workflows` 뷰로 관찰하고, 사람용 로그는 `<phase>/logs/<role>.log`에 쌓인다. 한 phase가 `blocked`/`error`로 멈추면 루프를 멈추고 사람에게 보고한다(자동 재개하지 않는다).

**③ Execution을 completed로 — 자동, 전 phase 완료 시 1회**

phase 루프가 끝나 `phases/index.json`의 모든 phase status가 completed가 되면, 메인이 곧바로 Execution을 completed로 표시하고 Stage 7로 넘어간다.

```bash
python3 .claude/skills/spec-harness-v1/scripts/execute.py set-stage docs/specs/<spec-name> Execution completed
```

> **왜 set-stage가 흐름의 양 끝에만 있나**: in_progress·completed는 *spec 전체*의 Execution 상태라 phase 루프를 감싸는 자리에서 1회씩 자동으로 찍는다. 반면 preflight·finalize는 *phase 단위*라 spec 레벨 Stage를 건드리지 않는다 — phase 하나가 끝났다고 Execution을 completed로 만들면, 남은 phase가 있을 때 어긋나기 때문이다. 그래서 "phase 닫기(finalize)"와 "Execution 닫기(set-stage completed)"를 분리하되, 메인이 ①~③을 한 흐름으로 자동 수행한다.

실행 규칙:
- 구현 요청을 받으면 먼저 `phases` 문서와 `workflow-checklist.json`이 준비됐는지, 사용자 진행 확인을 받았는지 확인한다.
- 준비 또는 승인이 부족하면 구현하지 않고 누락된 Stage로 돌아간다.
- 사용자가 명시적으로 수동 구현을 지시한 경우에만 workflow를 우회할 수 있으며, 이때도 해당 예외를 먼저 사용자 업데이트에 분명히 남긴다.

workflow 운영 규칙:
- workflow는 `pending`인 step만 실행한다. `completed`는 건너뛰고, `blocked`/`error`로 멈춘 step은 **자동 재개하지 않는다.**
- step 완료/중단 상태는 recorder가 phase index에 기록한다. 상세 산출물·파일 포맷은 `references/phase-files.md`를 따른다.
- 결과 `outcome`이 `blocked`/`error`이면 즉시 중단된 것이다. 사용자에게 `stopped_at_step`·`reason`을 보고한다. finalize는 일어나지 않는다.
- **중단된 step의 재개는 사람이 원인을 고친 뒤 `reset-step`으로 명시적으로 신호한다**(아래 "중단·재개").
- agent는 사용자 승인 없이 실패 회피 목적으로 step 요구사항, Acceptance Criteria, spec 문서, root docs를 수정해 재시도하지 않는다.
- 실패 원인이 문서 누락, Acceptance Criteria 오류처럼 명확해 보여도 자동 수정하지 않는다. 먼저 원인과 수정 계획을 사용자에게 제시한다.

#### 중단·재개 (pending-only)

- `blocked`/`error`로 멈추면 사용자 승인 없이 자동 복구하지 않는다. 실패 step·사유를 보고한다.
- workflow는 **`pending`인 step만 실행**한다. 그래서 중단된 step(`blocked`/`error`)을 그냥 재실행하면
  workflow가 자동 재개하지 않고 다시 멈춘다(`needs_reset: true`). 이는 의도된 동작이다 — 원인을 안 고친 채
  재실행해 같은 실패를 반복하며 토큰을 낭비하지 않게 하기 위함이다.
- 사람이 원인을 고친 뒤(예: 누락 문서 보강, 환경 문제 해결), 그 step을 pending으로 되돌린다:

```bash
python3 .claude/skills/spec-harness-v1/scripts/execute.py reset-step docs/specs/<spec-name>/phases/<phase-name>/ --step N
```

  그런 다음 같은 phase로 preflight → `/spec-harness-v1-execute`를 다시 기동하면, 이미 `completed`인 step은
  건너뛰고 reset한 step부터 재개한다.
- 사용자 승인 없이 실패 회피 목적으로 step 요구사항·AC·spec 문서·root docs를 수정해 재시도하지 않는다.

#### 커밋·finalize

step별 committer는 **코드 변경만 커밋**한다. spec 폴더(`docs/specs/<spec-name>/` — spec 문서·phase·step·index·checklist)는 전부 `.gitignore` 대상이라 `git status`·`git add`에 잡히지 않으므로, committer가 신경 쓸 필요가 없다.

phase 종료 시점에 finalizer(`execute.py finalize`)는 git 커밋을 만들지 않는다. 대신 다음을 한다:

1. phase index.json에 `completed_at`을 기록하고, 상위 spec `phases/index.json`의 이 phase status를 `completed`로 동기화한다. (둘 다 gitignore라 워킹트리 상태로만 남는다 — 재개·skip 판단에 쓰인다.)
2. `execution.push`가 true이고 `--no-push`가 아니면 현재 feature 브랜치를 원격으로 push한다. 이 push가 올리는 것은 step별 committer가 만든 **코드 커밋**이다.

`execution.push`가 false면 push를 생략하지만, PR은 원격에 push해야 열 수 있으므로 Stage 7(PR Review)로 진행하려면 push가 필요하다.

#### Stage 6 종료

- Stage 6는 workflow로 phase의 step을 모두 완료하고, phase 끝에서 원격 push한 뒤 PR을 오픈하는 것으로 종료한다.
- PR 오픈(`gh pr create`)은 agent가 Stage 6 직후 수행한다. workflow는 구현·검증·커밋·push까지 책임지고, PR 오픈은 그 바깥이다.
- 루트 docs 동기화는 Stage 6(Execution)에 포함하지 않는다. Stage 8(Root Sync)에서 수행한다.
- 실행 중 코드가 spec 설계와 달라지면 **해당 spec 폴더의 설계 md(`architecture.md`·`api-spec.md`·`db-schema.md`)만 as-built로 갱신**하고 루트 상태 문서는 건드리지 않는다. `spec.md`(요구·완료 기준)는 실행 중 편집하지 않는다 — 요구 변경은 Clarify로 되돌아간다. 루트 승격은 Stage 8이 한다.
- PR은 Stage 6에서 한 번만 오픈한다. Stage 7은 같은 브랜치/같은 PR에 커밋·push를 더 쌓을 뿐 PR을 새로 열지 않는다.
- PR을 오픈한 뒤 agent는 Stage 7로 자동 진행하지 않고 멈춰, 사용자의 Stage 7(PR Review) 검토 완료 신호를 기다린다. 이 시점에 리뷰 코멘트가 아직 없다는 것은 Stage 7 완료가 아니므로, Stage 8을 앞당기지 않는다.

---

### 7. PR Review

Stage 6(Execution)에서 오픈한 PR에 달린 review를 처리한다.

Stage 7은 기본적으로 **사용자가 PR을 검토하는 단계**다. agent는 Stage 6(Execution)에서 PR을 연 뒤 멈추고 사용자의 검토 완료 신호를 기다린다(요청 시 agent가 검토·반영을 위임받을 수 있으나, 대부분 사용자 검토로 본다).

**Stage 8(Root Sync) 진입 게이트** — 아래를 분명히 구분한다.

- **리뷰 코멘트 부재 ≠ Stage 7 완료.** PR을 연 직후 코멘트가 아직 없다는 것만으로 Stage 7을 완료로 보지 않는다. 코멘트가 없어도 사용자의 검토는 아직 끝나지 않았을 수 있고, 리뷰가 뒤늦게 코드를 바꾸면 미리 만든 Root Sync 산출물이 stale해져 재작업이 발생한다.
- **Stage 7(PR Review) 완료**는 다음 둘 중 하나다. (1) 사용자가 검토를 종료했다고 알린 경우(코멘트 처리 완료 포함), (2) 사용자가 명시적으로 agent에 검토를 위임했고 그에 따른 반영이 끝난 경우.
- Stage 7(PR Review) 완료가 확인되기 **전에는** Stage 8(Root Sync)에 착수하지 않는다. 완료가 확인된 뒤에야 Stage 8을 진행하며, 이 단계는 agent가 자동으로 처리해도 된다. Stage 8이 끝나면 harness는 종료하고, merge는 사람이 수동으로 한다.

review 처리 방식:

- 사람이 review 코멘트(예: GitHub에 연결한 코드리뷰 봇, 또는 다른 리뷰어)를 보고 항목별 처리 방향(accept / reject / modify)을 **결정**한다.
- 결정에 따른 코드 수정·답변·thread resolve는 **사람이 이 단계에서 직접 수행**한다(harness 워크플로 바깥). 수정 커밋·push는 Stage 6(Execution)에서 오픈한 같은 브랜치/같은 PR에 쌓는다. `execute.py`의 commit agent는 Stage 6 전용이며 이 Stage에 관여하지 않는다.
- review 수정이 계약/구조/결정을 바꿨다면 Stage 8(Root Sync)에서 그 변경을 루트에 반영한다. 내부 구현만 바뀐 경우 Stage 8 sync가 불필요할 수 있다.

---

### 8. Root Sync

이 Stage는 Stage 7(PR Review) 완료가 확인된 뒤에만 착수한다. PR review까지 코드가 확정된 시점에 루트 문서를 현재 상태로 동기화한다. merge 직전 1회 수행을 기본으로 하며, 코드가 또 바뀌면 다시 실행할 수 있는 멱등 연산으로 본다.

문서 종류별로 동작이 다르다. 한 지시로 뭉치지 않는다.

- **ADR (append)**: 루트 ADR은 수정·삭제하지 않는다. spec ADR(staging)에서 새로 채택된 결정만 루트 전역 번호로 이어붙인다. 기존 결정을 대체하면 새 레코드에 `supersedes`를 적고, 옛 레코드 상태를 `superseded`로 바꾼다(상태 한 줄 갱신은 허용). 이미 기록된 결정인지 확인 후 새 결정만 추가한다.
- **architecture / db-schema / api-spec (overwrite)**: 루트 현재 파일과 spec 문서를 **둘 다 입력으로 읽고**, 기억으로 재작성하지 말고 현재 루트 기준으로 이번 변경분만 반영한 전체 완성본을 출력한다. 이번에 안 건드린 부분은 보존한다.

위 루트 동기화와 **별개로**, 이 spec의 작업 기록을 영구 보존한다.

- **`_archive` 승격**: 작업 중 휘발 상태였던 spec 문서 중 **정본만** `docs/specs/_archive/pr-<PR번호>-<spec명>/`로 복사한다. PR 번호는 Stage 6(Execution)에서 PR을 열 때 이미 정해져 있다. `docs/specs/*`는 `.gitignore` 대상이지만 `_archive`는 예외라(`!docs/specs/_archive`), 이 사본만 git에 잡혀 같은 PR에 커밋된다.
  - **승격 대상**: `spec.md`, 설계 문서(`architecture.md`·`adr.md`·`api-spec.md`·`db-schema.md` 중 작성된 것), step 설계 문서(`phases/<phase>/step<N>.md`).
  - **승격 제외(휘발로 남김)**: 진행 상태·실행 부산물 — `phases/index.json`, `phases/<phase>/index.json`, `workflow-checklist.json`, `step<N>-ac-output.json`, `logs/`.
  - 복사만 한다. 내용을 재작성하지 않는다(동결된 정본 그대로 박제). 루트 ADR append는 위에서 이미 했으므로, `_archive`의 `adr.md`는 "이 spec이 그 결정에 어떻게 도달했나"의 맥락 사본이다.

sync 후 agent는 변경 요약(루트 문서 중 무엇을 갱신·보존했는지, `_archive`로 무엇을 승격했는지)을 보고하고 사용자 검토를 받는다. 커밋·push는 Stage 6(Execution)에서 오픈한 같은 PR에 쌓는다(루트 문서 갱신 + `_archive` 사본이 함께 올라간다).

Stage 8(Root Sync)이 spec-harness-v1의 마지막 단계다. 이후 merge는 **사람이 수동으로** 수행한다 — agent는 어떤 경우에도 merge하지 않는다(CLAUDE.md 보호 브랜치 규칙). 작업 회고·지식 축적이 필요하면 harness 바깥에서 별도로 처리한다(이 워크플로의 책임이 아니다).
