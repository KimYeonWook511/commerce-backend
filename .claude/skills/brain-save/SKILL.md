---
name: brain-save
description: Use when 현재 작업 세션의 결정·트레이드오프·막힌 점·배운 것·미해결 쟁점을 commerce-brain 에 raw 로 남길 때 — "brain 에 저장", "brain-save", "기록해줘", "정리해서 남겨줘", 세션 종료/PR 머지 시점에 남길 가치가 있을 때. 현재 repo(commerce-backend 등)의 세션 핵심을 commerce-brain 의 `raw/sessions/<platform>/` 에 주제 단위 마크다운으로 쓰고, 다른 agent 검토를 거쳐 `raw:` 커밋·push 한다. raw→wiki 정리(ingest)는 commerce-brain 세션의 책임이며 이 skill 은 거기까지 하지 않는다.
---

# brain-save

현재 repo 세션의 핵심을 commerce-brain 의 `raw/sessions/<platform>/` 에 영구 기록한다.

핵심 원칙 두 가지:
- **다 담는다, 거르는 건 ingest 의 몫.** raw 는 압축 요약이 아니다. 결정·이유·대안·트레이드오프·막힌 점·놓친 것을 충분히 담는다. 단 하나의 필터: **"6개월 뒤 다른 팀원이 비슷한 상황에서 써먹을 게 있나(전이되는 지식인가)?"** — *명백히* 없으면(그 순간으로 끝나는 일회성 처리) 뺀다. 조금이라도 애매하면 담는다. 애매한 건 사람이 버릴 게 아니라 ingest 가 거른다.
  - 기준선: *환경·운영적 우연*(일시적 실패, 재시도하니 됨, 세션 사용량 같은 외부 제약, 규약 위반 고침 같은 단순 처리)은 코드·설계에 대해 아무것도 안 알려주므로 **뺀다**. *코드·설계·인프라 구성에서 비롯된 원인*은 "왜 멈췄나·원인·해결"이 다음 사람에게 전이되므로 **담는다**. (괄호는 감 잡기용 예시일 뿐 — 단어에 매달리지 말고 "전이되는가"로 판단.)
- **commerce-brain 안에서만으로 읽혀야 한다.** raw 는 자기가 태어난 repo(코드·세션 대화)에 의존하면 안 된다(아래 §결합 끊기). commerce-brain 은 여러 repo 가 모이는 공용 공간이라, 특정 repo 를 봐야만 이해되는 raw 는 brain 안에서 고립된 섬이 된다.

wiki 의 어느 타입·플랫폼으로 분해될지는 ingest 의 책임이다 — 이 skill 은 거기까지 의식하지 않는다.

## 언제 호출하나 — context 가 생생할 때 (merge 전 X)

**"merge 전에 한 번"이 아니라, 의미 있는 결정·토론이 매듭지어진 직후 그때그때 저장한다.** 이유는 LLM context 구조 때문이다:

- 대화는 한정된 context window 에 담기고, 길어져 꽉 차기 직전 **auto-compact** 가 과거 turn 들을 *요약으로 압축*하고 원본을 버린다. compact 후에는 결정의 *이유·대안·트레이드오프*(= raw 의 본체)가 결론만 남기고 증발한다.
- 작은 모델(예: sonnet)로 바꾸면 compact 가 더 일찍·공격적으로 일어난다.
- 따라서 merge 까지 긴 토론을 들고 가면, brain-save 가 호출될 때쯤엔 추출할 원본이 이미 뭉개진 뒤다.

**원칙:**
1. **주제가 매듭지어질 때마다 조기·분할 저장** — 한 세션 끝에 몰지 않는다. 결제 토론 끝 → 저장, 로그인 토론 끝 → 저장. 주제 단위 파일 구조가 이걸 돕는다. compact 를 앞지르는 가장 확실한 방법.
2. **긴 토론 뒤 compact 임박이 보이면 미루지 말고 선제 저장.**
3. 이미 compact 돼 추출이 빈약하면 → §추출 빈약 시 폴백(추측 금지, 사용자에게 직접 묻기)이 마지막 그물.

(compact 는 context 의 근본 제약이라 skill 로 100% 막지 못한다. 진짜 방어는 "결정 날 때마다 짧게짧게"라는 호출 습관이다.)

## 전제
- raw 는 **불변 원본**. 거친 메모·날것 OK. 문장 다듬기는 강요하지 않는다(단, 결합은 끊는다).
- 규약(파일명·frontmatter·정본 모드)의 정본은 **commerce-brain 의 `CLAUDE.md`**. 이 skill 은 규약을 복붙하지 않고 실행 시 그 파일을 읽어 따른다.
- 이 skill 은 commerce-brain 의 `raw/sessions/<platform>/` 바깥에는 쓰지 않는다.

## 0. 연결 해결 — 메인 repo 루트의 `.brain` 하나로 (worktree 어디서 실행해도 메인 것 하나만 봄)

`.brain` 은 **메인 repo 루트에 딱 하나** 둔다. worktree 는 별도 디렉토리지만 `.brain` 을 따로 만들지 않고 메인 루트의 것을 공유한다 — worktree 마다 만들면 그때마다 경로 자동탐색이 반복돼 토큰을 낭비하기 때문. 머신 전역 파일·환경변수는 쓰지 않는다.

1. **메인 repo 루트** `{repo_root}` 찾기 (worktree 에서 실행해도 메인을 가리킴):
   ```bash
   git_common=$(git rev-parse --git-common-dir)          # 메인의 .git (worktree 에서도 메인 것)
   repo_root=$(cd "$(dirname "$git_common")" && pwd)      # 그 .git 의 부모 = 메인 repo 루트 (절대경로)
   ```
   `--show-toplevel`(현재 트리 루트)이 아니라 `--git-common-dir`(메인의 .git) 기준이라, worktree 에서 실행해도 항상 메인 루트를 얻는다.
2. `{repo_root}/.brain` 이 있으면 → `platform`, `brain_path` 를 읽고 끝. (worktree 에서 실행해도 메인의 `.brain` 을 그대로 읽으므로 재탐색 없음.)
3. 없으면 만든다(메인 루트에):
   - `platform`: `{repo_root}` 폴더명에서 `commerce-` 접두를 떼어 추출(`commerce-backend` → `backend`). 폴더명이 `commerce-<platform>` 형식이 아니면 1회 확인.
   - `brain_path`: `{repo_root}/../commerce-brain`, `{repo_root}/../../commerce-brain` 자동 탐색 → 못 찾으면 사용자에게 한 번 묻는다.
   - 유효성: `{brain_path}/CLAUDE.md` 존재 확인. 없으면 잘못된 경로 — 다시 묻는다.
   - 확정된 두 값을 `{repo_root}/.brain` 에 쓴다 (형식은 `키=값`):
     ```
     platform=backend
     brain_path=../commerce-brain
     ```
4. `.brain` 은 `.gitignore` 대상 — repo 에 커밋되지 않는다. 메인 루트에 한 번만 생기므로 worktree 마다 흩어지지 않는다.

> 위치 기준이 메인 repo 루트라, 메인이든 worktree 든 어느 하위 디렉토리든 항상 같은 `.brain` 하나를 본다.

## 1. commerce-brain CLAUDE.md 읽기
`{brain_path}/CLAUDE.md` 를 Read 해서 raw 파일명 규칙·frontmatter schema·정본 모드를 확인한다.

## 2. 주제 분해 — 파일 개수는 "결" 단위

세션을 주제(결) 단위로 나눈다. **세션당 1파일이 아니다** — 한 세션이 서로 다른 주제(예: 결제 재설계 + 로그인 토큰)를 담았으면 주제마다 파일을 나눈다. 한 파일이 한 주제에 집중되면 부담 없이 충분히 담을 수 있어 압축 충동이 사라진다.

- 분리 기준: *서로 독립적으로 읽히는 주제*면 나눈다. 한 주제 안에선 다 담는다.
- 과도하게 잘게 쪼개지도, 한 파일에 욱여넣지도 않는다. 분리가 애매하면 자연스러운 결을 따른다.
- 플랫폼·타입 분해는 ingest 가 하므로 여기서 미리 그 기준으로 쪼개지 않는다(주제 기준으로만).

각 파일: `YYYY-MM-DD-<slug>.md`. slug 는 **구체적으로**(kebab-case, `login` ✗ → `login-token-rotation` ✓). 여러 파일이면 slug 가 서로 겹치지 않게.

## 3. 본문 — 지식 중심 섹션

권장 섹션(무관하면 생략, 활동 로그가 아니라 *지식* 중심):
```markdown
## 결정한 것      — 결정·선택, 그 이유, 검토한 대안과 각각의 트레이드오프
## 막힌 점·해결    — 버그·장애·원인 추적·해결 (있었다면)
## 배운 것        — 일반화 가능한 패턴·기법·교훈
## 미해결·열린 질문 — 의도적으로 안 한 결정, 못 정한 쟁점, 미룬 트레이드오프
```
- `한 일`(활동 로그)·`다음 단계`(todo) 같은 섹션은 두지 않는다 — 지식 가치가 약하고 시간이 지나면 의미가 사라진다. 남길 것은 "무엇을 왜 결정했나"와 "무엇이 아직 열려 있나"다.
- "결정한 것"의 *이유·대안·트레이드오프*가 raw 의 본체다. 결론만 적지 말고 왜 그 결론인지를 담는다.

### 결합 끊기 — commerce-brain 안에서만으로 읽히게 (필수)
raw 는 *그 파일 하나만 열어도, 그것이 나온 repo 코드나 세션 대화 없이* 이해되어야 한다. 두 종류 결합을 끊는다:

- **(가) 세션 결합** — 그 대화에서만 통하던 것: 임시 약어(`C1`, `P0`), "아까 그거", "위에서 말한", "첫 번째 케이스". → 의미를 드러내는 표현으로 푼다. 순서·심각도는 "(가장 심각)"처럼 말로.
- **(나) repo 결합** — 그 repo 를 봐야만 이해되는 것:
  - *문서 내부 번호* (`ADR-5`, `PRD 3.2`): 번호 대신 *그때 무엇을 가리키려 했는지 의도*를 풀어쓴다. (정본 위치는 `{repo, path}`, 추적은 이슈/PR 번호 — 이 둘은 허용. 금지는 "문서 내부 항목 번호".)
  - *코드 맥락 가정* ("그 메서드", "이 클래스", "방금 고친 그 함수"): 무엇을 하는 것인지 역할을 한 번 푼다.
  - *repo 상태 전제* ("방금 PR 에서 바꾼 그거"): 무엇을 어떻게 바꿨는지 내용을 적는다.

판별: **다른 플랫폼 팀원(예: ios 개발자)이 commerce-brain 에서 이 파일을 열었을 때, 그 repo 를 안 보고도 무슨 결정을 왜 했는지 이해되는가.** 이게 안 되면 그 raw 는 brain 안의 고립된 섬이다.

### 추출 빈약 시 폴백
세션이 길거나 압축돼 핵심 추출이 모호하면 **임의로 채우지 말고** 사용자에게 직접 묻는다("이번 세션에서 꼭 남길 핵심 결정/막힌 점이 뭐였는지 짚어줘"). 추측으로 메운 raw 는 더 해롭다.

## 4. frontmatter
commerce-brain CLAUDE.md 의 `raw/sessions/` 컨벤션에 맞춰 자동 채운다:
```yaml
platform: <0단계에서 확정>
author: <git config user.name (없으면 user.email)>
created: <오늘 날짜>
origin:                       # 자동 감지되거나 사용자가 언급할 때만. 없으면 통째 생략
  - { type: pr, repo: commerce-<platform>, ref: <번호> }
```
- PR 번호는 가능하면 자동 감지(`gh pr view --json number` 등), 없으면 생략.

## 5. 다른 agent 검토 (필수 — 자가검토 금지)

작성한 초안을 **반드시 다른 agent(Task/subagent)에게** 검토 맡긴다. 작성자 자가 점검에 의존하지 않는다 — 작성자는 자기 repo·세션 맥락 안에 있어서 *결합을 스스로 보지 못하기 때문*이다. 맥락 밖의 다른 agent 가 봐야 결합이 드러난다.

검토 초점 (1순위 = 결합):
1. **결합 탐지 (최우선)** — 세션 결합(가)·repo 결합(나)이 남아있나. "그 repo 안 보고도 이해되나"를 다른 agent 가 *맥락 없이* 읽으며 검증한다. 결합이 보이면 어디인지 지적.
2. 빠진 핵심, 과장, 결론만 있고 이유·트레이드오프가 빠진 곳.
3. 주제 분리가 적절한지(한 파일에 무관한 주제가 섞였는지, 또는 과하게 쪼갰는지).

검토 피드백을 반영한다. 이 단계가 품질·결합 보정을 담당한다. (잘못된 *정보*의 최종 교정은 wiki 단계 — ingest·lint 의 책임이다. raw 는 검토만 거치면 사용자 확인 없이 저장한다.)

## 6. 충돌 안전망
작성 직전 각 파일 경로 존재 확인:
```bash
test -f {brain_path}/raw/sessions/<platform>/YYYY-MM-DD-<slug>.md
```
있으면 2택: (1) slug 에 `-<짧은해시>` (2) 덮어쓰기. 선택 전까지 멈춘다.

## 7. 저장
```bash
mkdir -p {brain_path}/raw/sessions/<platform>
```
경로: `{brain_path}/raw/sessions/<platform>/YYYY-MM-DD-<slug>.md`. 주제가 여러 개면 파일도 여러 개 쓴다.

## 8. 커밋·push (raw: 컨벤션)
`cd` 로 이동하지 않는다 — `git -C {brain_path}` 로 cwd 를 바꾸지 않고 brain repo 에만 커밋한다(현재 작업 디렉토리는 commerce-backend 그대로 유지):
```bash
git -C {brain_path} add raw/sessions/<platform>/<파일들>
git -C {brain_path} commit -m "raw: <platform> <slug>"   # 여러 파일이면 대표 주제로, 본문에 목록
git -C {brain_path} push
```
- **raw 외에는 stage 하지 않는다** (`wiki/`·`index.md` 등 절대 금지 — wiki 변경의 정상 경로는 `ingest:` 커밋뿐).
- push 충돌 시 `git -C {brain_path} pull --rebase` 후 재시도(raw 는 append-only 라 내용 충돌은 사실상 없다).

## 9. 보고 (확인이 아니라 사후 통지)
저장·push 후 무엇을 했는지 알린다 — 사전 확인은 받지 않지만, 비가역(push) 동작이므로 사람이 즉시 알아챌 수 있게 한다.
> 저장 완료: `{brain_path}/raw/sessions/<platform>/<파일들>`
> 커밋: `raw: <platform> <slug>` (push 됨)
> commerce-brain 세션에서 `ingest` 하면 wiki 로 정리된다. (raw 는 append-only — 잘못됐으면 그 파일만 지우면 된다.)

## 주의사항
- **wiki/ · index.md · log.md · _tag-glossary.md · _skipped.md 는 절대 만지지 않는다.** ingest 의 책임.
- **정본 본문(ADR·OpenAPI 등) 통째 복사 금지.** companion 대상은 `{repo, path}` 인용 + "내 이해"만.
- **현재 repo 의 어떤 파일도 변경하지 않는다.** 쓰기는 `{brain_path}/raw/sessions/<platform>/` 와 (첫 실행 시) 메인 repo 루트의 `.brain` 뿐.
- **5단계(다른 agent 검토) 전 파일 작성·커밋 금지.** (사용자 사전 확인은 받지 않되, 검토는 필수.)
- raw 를 ingest 구조에 맞추려 하지 않는다 — raw 의 가치는 "세션 핵심이 결합 없이 그대로 전달되는 것".
