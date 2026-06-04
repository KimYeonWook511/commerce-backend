---
name: sb-save
description: Use when the user wants to persist commerce-backend session output to ~/second-brain — triggered by "second-brain 에 저장해줘", "sb-save", "메모 남겨줘", "기록해줘", "정리해서 남겨줘", or at session end / after PR merge when there are decisions, trade-offs, or learnings worth keeping. Writes raw markdown to ~/second-brain/raw/project/commerce/ capturing the session's core takeaways. raw → wiki 정리는 second-brain 세션의 ingest 책임이며, 이 skill 은 거기까지 신경 쓰지 않는다.
---

# sb-save

commerce-backend 세션의 핵심(작업·결정·트레이드오프·막힌 점·배운 것)을 `~/second-brain/raw/project/commerce/` 에 영구 기록한다.

이 skill 의 목적은 **세션의 핵심을 사용자가 나중에 다시 읽었을 때 그대로 전달되도록 적는 것**이다. ingest 시 wiki 의 어느 분류로 갈지, 어떤 페이지로 분해될지는 second-brain 세션의 책임이다 — 이 skill 은 거기까지 의식하지 않는다.

## 전제

- raw 는 *불변 원본*. 거친 메모·날것 OK. 문장 다듬기·구조화는 강요하지 않는다.
- commerce-backend 는 LLM Wiki "모드 A (companion)" — `commerce-backend/docs/` 가 정본. raw 에는 결정·트레이드오프·"내 이해" 만 적고, ADR 본문 통째 복사는 금지.

## 파일 분리 — 기본 2파일 (도메인 / AI-메타)

기본값은 다음 2파일이다. 한 PR(또는 세션)의 산출물이므로 **두 파일 모두 날짜 다음에 `pr-<번호>-` 가 붙는다** (`2026-06-05-pr-205-...`). 본문에서 서로를 "같은 PR 의 다른 메모"로 한 줄 교차 언급한다.

- **파일 1 — 프로젝트 도메인/서비스/비즈니스**: 설계 결정, 트레이드오프, 도메인 이슈, 막힌 점 등. slug: `pr-<번호>-<short>`.
- **파일 2 — AI 사용 교훈**: harness·skill·agent 운영에서 얻은 *일반화 가능한* 교훈. slug 에 `ai` 가 들어가 파일 1 과 구분된다: `pr-<번호>-<short>-ai`.

slug 의 구체 생성 규칙은 아래 실행 절차 2단계의 "slug 자동 생성 규칙" 참조.

규칙:
- 한쪽에 담을 내용이 없으면 그 파일은 **생략**한다 (AI 교훈이 없으면 파일 1만, 도메인 작업이 없으면 파일 2만).
- **추가 분리는 파일 1에 한해서만** 제안한다 — 파일 1 내용이 너무 많아 한 파일이 과하게 길어질 때만, 도메인 안에서 결이 다른 덩어리로 나눈다. **파일 2 는 추가로 쪼개지 않는다.**
- 두 파일 모두 `~/second-brain/raw/project/commerce/` 아래에 두고 frontmatter `project: commerce` 를 유지한다. 파일 2 가 일반화된 AI 교훈이라도 raw 위치는 여기다 — *commerce 맥락에서 일하며 얻은* 교훈이므로. 일반화된 wiki 배치(knowledge 등)는 ingest 가 알아서 라우팅한다 (기존 책임 경계와 일치).

## 실행 절차

### 1. second-brain CLAUDE.md fetch

`~/second-brain/CLAUDE.md` 를 Read 한다. raw 파일 작성 규칙(파일명·frontmatter·companion 모드)을 매번 fetch 한다. 이 단계 없이 진행하면 second-brain 컨벤션을 보장할 수 없다.

### 2. slug + 본문 초안을 한 번에 작성

slug 를 따로 떼서 먼저 확인받지 않는다. 현재 세션 대화에서 핵심을 추출해 **slug 후보와 본문 초안을 한 번에** 만든다 (위 "파일 분리" 기본값에 따라 1~2 파일). slug 는 아래 규칙으로 *자동 생성*한다.

#### slug 자동 생성 규칙

최종 파일명은 `YYYY-MM-DD-<slug>.md` (날짜는 작성 시점 자동). slug 부분을 다음 규칙으로 뽑는다.

- 전부 kebab-case 소문자.
- **세션이 PR 로 연결되면 두 파일 모두 `pr-<번호>-` 로 시작한다 (번호 누락 금지).** 즉 파일명은 날짜 다음 `pr-<번호>-...` 형태가 되고(`2026-06-05-pr-205-...`), 도메인 파일과 AI-메타 파일 둘 다 같은 `pr-<번호>` 를 단다.
  - 파일 1 (도메인): `pr-<번호>-<short>` — 예: `pr-205-payment-redesign-review-fixes`
  - 파일 2 (AI-메타): `pr-<번호>-<short>-ai` (또는 `pr-<번호>-ai-<short>`). **항상 `ai` 가 들어가** 도메인 파일과 구분되게 한다 — 예: `pr-205-harness-multi-ai-review-lessons`.
  - 파일 1 을 추가 분리하는 경우(도메인 내용 과다)에도 분리된 파일 모두 같은 `pr-<번호>-` 를 유지한다.
- `<short>` 는 세션의 핵심 주제를 드러내는 2~4 단어 (작업 동사 + 대상, 또는 핵심 결정 주제). 너무 일반적인 단어(`fix`, `update`, `work`)만으로 끝내지 않는다.
- **PR 이 없는 세션**(아직 PR 미생성, 탐색/논의만)이면 PR prefix 대신 주제 기반 slug 를 쓴다: 결정 위주는 `why-<topic>`, 일반화 패턴은 `<topic>-<technique>`. 이 경우에도 파일 2 는 `-ai` 로 구분한다.
- 두 파일의 slug 가 서로 겹치지 않게 한다 (파일 2 의 `ai` 표식으로 자연히 구분됨).

권장 섹션 (전부 채울 필요 없음, 무관하면 생략):

```markdown
## 한 일
- 이번 세션에서 실제로 작업한 것

## 결정한 것
- 결정·선택·트레이드오프·검토한 대안·근거
- 정본 ADR 이 있으면 ADR *파일 경로*(repo path)만 인용하고, 본문에는 "내가 어떻게 이해했는가·다시 본다면" 만. ADR/PRD 의 *항목 번호*(ADR-5 등)로는 가리키지 않는다 (아래 자기완결성 §b)

## 막힌 점
- 버그·장애·원인 추적·해결 (있었다면)

## 배운 것
- 이번 세션을 통해 일반화 가능한 패턴·기법·교훈

## 다음 단계
- 지식 가치가 있는 미해결 항목만. 의도적으로 뺀 설계, 못 정한 도메인 질문, deferred 트레이드오프
- 제외: 세션 운영 잔무(merge·worktree 정리·PR 후속·rebase). git/PR 상태와 다른 skill 이 추적한다
- 판별: "다음 세션의 나에게 지식으로 도움이 되는가" — 아니면 섹션 생략
```

외부 인용(이슈 `#167`, PR `#N`, `docs/tasks/.../adr.md`, 관련 raw `[[raw/project/commerce/...]]`)은 자연스러운 위치에 끼워넣는다. 별도 섹션으로 강제하지 않는다.

#### 자기완결성 원칙 — 외부·세션 의존만 끊는다

raw 는 *나중에 이 파일 하나만 열어도 그 자체로 이해되어야* 한다. 단, 이건 **전면 정제 요구가 아니다.** 내부 거칠음(문장 다듬기, 구조화 부족, 거친 메모)은 그대로 OK. *반드시 끊어야 할 것은 외부·세션 의존 딱 세 가지뿐*이다.

- **(a) 세션 한정 약어**: 그 세션 안에서만 통하던 임시 레이블(결함 번호 `C1`/`P0`, 심각도 등급, "이슈 3번", "첫 번째 케이스" 등)은 나중에 의미가 복원되지 않는다. 소제목·본문 모두 *내용을 드러내는 표현*으로 푼다. 심각도·순서는 "(가장 심각)"처럼 말로. 약어를 *나쁜 예로 인용*할 때도 그대로 박제하지 말고 의미를 같이 적거나 일반화한다.
- **(b) 문서 내부 번호 참조**: `ADR-5`, `PRD 3.2` 같은 항목 번호는 그 문서가 수정·재배치되면 어긋난다. 번호 대신, *그 당시 어떤 기준의 어떤 부분에 부합해서 그걸 가리키려 했는지* 의도를 본문에 직접 풀어쓴다. (정본 *위치*는 `docs/tasks/.../adr.md` 파일 경로로, *추적*은 이슈/PR 번호 `#174`로 — 이 둘은 허용. 금지 대상은 "문서 내부의 항목 번호"다.)
- **(c) 논점을 좌우하는 식별자/약어의 첫 등장 풀이**: 메서드명·컬럼명·도메인 약어(AC=수용 기준 등)는 raw 에 남겨도 되지만, *논점을 좌우하는 것*은 첫 등장 시 괄호로 역할을 한 번 푼다 (예: `existsUnknownByOrderId`(결과 불명 결제가 있으면 재시도 차단)). 모든 식별자를 풀라는 게 아니다 — 그 줄의 논리가 그 식별자 뜻에 달려 있을 때만.

판별 기준: *6개월 뒤의 내가 이 파일 하나만 열었을 때, 외부 문서나 세션 기억 없이 무슨 일이 있었고 왜 그렇게 했는지 복원되는가.* (a)(b)(c) 외의 거칠음은 raw 의 권리이므로 손대지 않는다.

#### 추출 빈약 시 폴백

세션이 길거나 컨텍스트가 압축돼 핵심 추출이 빈약·모호하면, **임의로 채우지 말고** 사용자에게 직접 요점을 묻는다 ("이번 세션에서 꼭 남길 핵심 결정/막힌 점이 뭐였는지 짚어줘"). 추측으로 메운 raw 는 나중에 더 해롭다.

### 3. agent 검토 → 피드백 반영

작성한 초안을 **다른 agent(Task/subagent)에게 검토 요청**한다. 검토 초점:
- 자기완결성 위반 — 세션 한정 약어(a), 문서 내부 번호 참조(b), 풀어주지 않은 핵심 식별자(c)
- 빠진 핵심, 과장 표현
- 도메인 / AI-메타 분리가 적절한지 (파일 2 에 도메인 디테일이 새지 않았는지, 그 반대도)

검토 피드백을 반영한다. 이 단계가 품질 보정을 담당한다 — 작성자 자가 점검에만 의존하지 않는다.

### 4. 사용자 최종 확인 (1회)

검토 반영본을 사용자에게 **1회만** 최종 확인받는다. slug 를 따로 확인받는 게이트는 없다 — slug·본문·파일 분리를 묶어 한 번에 보여주고, 사용자가 OK 또는 수정. 사용자 확인 전에는 파일을 작성하지 않는다.

### 5. 충돌 안전망

각 파일 작성 직전 대상 경로 존재 여부 확인:

```bash
test -f ~/second-brain/raw/project/commerce/YYYY-MM-DD-<session-slug>.md
```

이미 있으면 사용자에게 2택: (1) 덮어쓰기 (2) 다른 slug 입력. 사용자가 선택할 때까지 멈춘다.

### 6. 디렉토리·파일 작성

```bash
mkdir -p ~/second-brain/raw/project/commerce
```

파일 경로: `~/second-brain/raw/project/commerce/YYYY-MM-DD-<session-slug>.md`

frontmatter (second-brain CLAUDE.md `raw/project/<project-slug>/` 컨벤션 그대로):

```yaml
---
project: commerce
agent: claude-code
created: YYYY-MM-DD
session: <optional, 생략 가능>
---
```

본문은 4단계에서 사용자 확인을 마친 내용을 그대로 쓴다. 2파일이면 둘 다 작성한다.

### 7. 결과 보고

생성된 파일들의 절대 경로를 사용자에게 알린다:

> 작성 완료:
> - `~/second-brain/raw/project/commerce/<file-1>.md`
> - `~/second-brain/raw/project/commerce/<file-2>.md` (있으면)
>
> second-brain 세션에서 "정리해줘" 로 ingest 하면 wiki 로 끌어올려진다.

## 주의사항

- **wiki 디렉토리는 절대 만지지 않는다.** `~/second-brain/wiki/` 하위 어떤 파일도 생성·수정·삭제하지 않는다.
- **index.md, log.md, _tag-glossary.md 도 만지지 않는다.** 이 세 파일은 second-brain 세션의 ingest 가 갱신한다.
- **ADR 원문을 복사하지 않는다.** companion 모드 — 정본은 `commerce-backend/docs/`. raw 본문에는 "내가 어떻게 이해했는가·다시 본다면" 만. 본문 통째 복사 금지.
- **사용자 최종 확인(4단계) 전 파일 작성 금지.**
- **second-brain 의 ingest 구조에 본문을 맞추려 하지 않는다.** raw 의 가치는 "세션의 핵심이 그대로 전달되는 것". ingest 분해는 second-brain 세션이 알아서 한다.
- **commerce-backend 의 어떤 파일도 변경하지 않는다.** 이 skill 은 `~/second-brain/raw/project/commerce/` 바깥에는 쓰기 작업을 하지 않는다.
