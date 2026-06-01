---
name: sb-save
description: Use when the user wants to persist commerce-backend session output to ~/second-brain — triggered by "second-brain 에 저장해줘", "sb-save", "메모 남겨줘", "기록해줘", "정리해서 남겨줘", or at session end / after PR merge when there are decisions, trade-offs, or learnings worth keeping. Writes one markdown file to ~/second-brain/raw/project/commerce/ capturing the session's core takeaways. raw → wiki 정리는 second-brain 세션의 ingest 책임이며, 이 skill 은 거기까지 신경 쓰지 않는다.
---

# sb-save

commerce-backend 세션의 핵심(작업·결정·트레이드오프·막힌 점·배운 것)을 `~/second-brain/raw/project/commerce/` 에 영구 기록한다.

이 skill 의 목적은 **세션의 핵심을 사용자가 나중에 다시 읽었을 때 그대로 전달되도록 적는 것**이다. ingest 시 wiki 의 어느 분류로 갈지, 어떤 페이지로 분해될지는 second-brain 세션의 책임이다 — 이 skill 은 거기까지 의식하지 않는다.

## 전제

- raw 는 *불변 원본*. 거친 메모·날것 OK. 정제 안 해도 된다.
- commerce-backend 는 LLM Wiki "모드 A (companion)" — `commerce-backend/docs/` 가 정본. raw 에는 결정·트레이드오프·"내 이해" 만 적고, ADR 본문 통째 복사는 금지.

## 실행 절차

### 1. second-brain CLAUDE.md fetch

`~/second-brain/CLAUDE.md` 를 Read 한다. raw 파일 작성 규칙(파일명·frontmatter·companion 모드)을 매번 fetch 한다.

이 단계 없이 진행하면 second-brain 컨벤션을 보장할 수 없다.

### 2. session-slug 제안 + 사용자 확인

현재 세션 대화 컨텍스트(작업 주제·결정·이슈 번호·PR 번호 등)로부터 kebab-case session-slug 후보를 한 줄로 제안한다.

명명 가이드:
- kebab-case 소문자, 3~5 단어
- 세션 결과물이 PR로 연결되면 `pr-<N>-<short>` 권장 (한 PR에 여러 이슈가 묶이는 케이스까지 흡수. raw는 세션 단위 1 파일이라 이슈별로 쪼개지 않는다)
- 결정 위주면 `why-<topic>` (예: `why-event-outbox`)
- 일반화 가능한 패턴이면 `<topic>-<technique>` (예: `kafka-traceid-propagation`)

사용자가 OK 또는 수정. 사용자 응답 전에는 다음 단계로 가지 않는다.

### 3. 본문 초안 작성 + 사용자 검토

본문은 *세션의 핵심이 잘 전달되는 것이 1순위*. 정제·구조화는 부차.

권장 섹션 (전부 채울 필요 없음, 해당 세션과 무관하면 생략):

```markdown
## 한 일

- 이번 세션에서 실제로 작업한 것

## 결정한 것

- 결정·선택·트레이드오프·검토한 대안·근거
- 정본 ADR 이 있으면 ADR 경로만 인용하고, 본문에는 "내가 어떻게 이해했는가·다시 본다면" 만

## 막힌 점

- 버그·장애·원인 추적·해결 (있었다면)

## 배운 것

- 이번 세션을 통해 일반화 가능한 패턴·기법·교훈

## 다음 단계

- 지식 가치가 있는 미해결 항목만 적는다
- 기록 대상: 이번에 의도적으로 뺀 설계(트래픽·조건 바뀌면 재검토), 아직 답을 못 정한 도메인 질문, deferred 트레이드오프
- 제외 대상: 세션 운영 잔무 — merge·worktree 정리·PR 후속·rebase·브랜치 정리 등. 이건 git/PR 상태와 `pr-merge-cleanup` 같은 다른 skill이 추적한다
- 판별 기준: "다음 세션의 나에게 지식으로 도움이 되는가" — 아니면 빼고 섹션 자체를 생략
```

외부 인용(이슈 `#167`, PR `#N`, `commerce-backend/docs/ADR.md#anchor`, 관련 raw `[[raw/project/commerce/...]]` 등)은 자연스러운 위치에 끼워넣는다. 별도 섹션으로 강제하지 않는다.

현재 세션 대화에서 핵심을 추출해 초안 제시. **사용자 검토 전 파일을 작성하지 않는다.** 사용자가 더 적고 싶거나 빼고 싶은 게 있으면 반영.

### 4. 충돌 안전망

파일 작성 직전 대상 경로 존재 여부 확인:

```bash
test -f ~/second-brain/raw/project/commerce/YYYY-MM-DD-<session-slug>.md
```

이미 있으면 사용자에게 3택:

1. **덮어쓰기** — 기존 내용은 사라진다
2. **다른 session-slug 입력** — 2단계로 돌아감
3. **시간 suffix 자동 추가** — `YYYY-MM-DD-HHmm-<session-slug>.md`

사용자가 선택할 때까지 멈춘다.

### 5. 디렉토리·파일 작성

대상 디렉토리가 없으면 자동 생성:

```bash
mkdir -p ~/second-brain/raw/project/commerce
```

파일 경로:
- 일반: `~/second-brain/raw/project/commerce/YYYY-MM-DD-<session-slug>.md`
- 충돌 시간 suffix: `~/second-brain/raw/project/commerce/YYYY-MM-DD-HHmm-<session-slug>.md`

frontmatter (second-brain CLAUDE.md `raw/project/<project-slug>/` 컨벤션 그대로):

```yaml
---
project: commerce
agent: claude-code
created: YYYY-MM-DD
session: <optional, 생략 가능>
---
```

본문은 3단계에서 사용자 검토를 마친 내용을 그대로 쓴다.

### 6. 결과 보고

생성된 파일의 절대 경로를 사용자에게 알린다. 두 줄 안내:

> 작성 완료: `~/second-brain/raw/project/commerce/<filename>.md`
> second-brain 세션에서 "정리해줘" 로 ingest 하면 wiki 로 끌어올려진다.

## 주의사항

- **wiki 디렉토리는 절대 만지지 않는다.** `~/second-brain/wiki/` 하위 어떤 파일도 생성·수정·삭제하지 않는다.
- **index.md, log.md, _tag-glossary.md 도 만지지 않는다.** 이 세 파일은 second-brain 세션의 ingest 가 갱신한다.
- **ADR 원문을 복사하지 않는다.** companion 모드 — 정본은 `commerce-backend/docs/`. raw 본문에는 "내가 어떻게 이해했는가·다시 본다면" 만. 본문 통째 복사 금지.
- **사용자 검토 전 파일 작성 금지.** session-slug(2단계) 와 본문(3단계) 둘 다 사용자 OK 후에만 파일 작성으로 진행한다.
- **second-brain 의 ingest 구조에 본문을 맞추려 하지 않는다.** raw 의 가치는 "세션의 핵심이 그대로 전달되는 것". ingest 분해는 second-brain 세션이 알아서 한다.
- **commerce-backend 의 어떤 파일도 변경하지 않는다.** 이 skill 은 `~/second-brain/raw/project/commerce/` 바깥에는 쓰기 작업을 하지 않는다.
