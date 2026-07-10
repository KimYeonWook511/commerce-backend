---
name: brain-query
description: Use when 현재 repo(commerce-backend 등)에서 작업하다가 과거 결정·트레이드오프·도메인 이해·장애·계약을 commerce-brain 에서 찾아봐야 할 때 — "brain 에서 찾아줘", "brain-query", "이거 전에 어떻게 결정했지", "왜 이렇게 했더라", 또는 의사결정 중 맥락이 부족할 때. commerce-brain 의 wiki(필요시 raw)를 읽어 **인용과 함께** 답한다. read-only — commerce-brain 을 수정하지 않는다.
---

# brain-query

commerce-brain 에 누적된 결정·도메인 이해·장애·계약을 조회해, 현재 repo 세션의 의사결정 컨텍스트에 활용한다.

이 skill 은 **read-only**. wiki·raw·index.md·log.md 등 어느 파일도 수정하지 않는다. 새 통찰이 생기면 wiki 에 직접 쓰지 않고 [[brain-save]] 로 raw 에 떨궈 ingest 가 올린다(commerce-brain 의 단일 진실 원천 규칙).

## 0. 연결 해결 — 메인 repo 루트의 `.brain`
brain-save 와 동일하게 **메인 repo 루트**의 `.brain` 하나를 본다(worktree 에서 실행해도 메인 것 하나; 머신 전역 파일·환경변수 안 씀):
1. 메인 루트: `git_common=$(git rev-parse --git-common-dir)` → `repo_root=$(cd "$(dirname "$git_common")" && pwd)`. (`--show-toplevel` 이 아니라 `--git-common-dir` 기준이라 worktree 에서도 메인 루트를 얻는다.)
2. `{repo_root}/.brain` 있으면 `platform`, `brain_path` 를 읽는다 (형식은 `키=값` — `platform=backend`, `brain_path=../commerce-brain`).
3. 없으면 만든다: `platform` 은 `{repo_root}` 폴더명에서 자동, `brain_path` 는 `../commerce-brain`·`../../commerce-brain` 자동 탐색 → 못 찾으면 한 번 묻기. 유효성은 `{brain_path}/CLAUDE.md` 존재로 확인 후 `{repo_root}/.brain` 에 기록.
4. `.brain` 은 gitignore 대상. 메인 루트에 하나만 — worktree 마다 흩어지지 않는다.

(query 에서 `platform` 은 *답을 거를 때만* 선택적으로 쓴다 — 질문이 특정 플랫폼에 한정되면 필터로, 아니면 전 플랫폼 검색. 현재 repo 의 platform 을 기본 힌트로 삼되 강제하지 않는다.)

## 1. 질문 분해
핵심 키워드·platform·type 을 추출한다.
- 예: "refresh token 회전 왜 그렇게 했지?" → 키워드 `refresh-token`, `auth` / type `decision` 가능성 / platform `backend` 힌트
- skill 인자가 있으면 그대로. 모호하면 한 줄 짧게 묻는다.

## 2. 카탈로그·어휘 스캔
```text
{brain_path}/index.md          # 카탈로그 — 후보 페이지 list
{brain_path}/_tag-glossary.md  # 어휘 정규화 — 동의어/상위·하위
```
glossary 로 키워드를 canonical 로 정규화한다(예: 사용자가 "토큰 회전" 이라 물으면 → `refresh-token`). 상위/하위 관계로 검색 폭을 넓힌다. **이 동의어 확장이 "사용자 표현 ≠ 태그" 간극을 메운다** — 사용자가 정확한 태그를 몰라도 찾히게.

## 3. frontmatter + wikilink 그래프로 추리기
후보 페이지 frontmatter 로 1차 필터:
- `type` — decision / tradeoff / topic / incident / api-contract / moc
- `platform` — 질문이 한정적이면 일치(아니면 무시)
- `tags` — 키워드 일치 (security·db·devops 등 가로지르는 관심사는 전부 여기)
- `status` — `superseded` / `deprecated` / `outdated` 는 **가중치 낮추되 존재는 노출**

후보 우선순위: `decisions/` → `topics/` → `incidents/` → `contracts/` → `knowledge/`. `features/`(MOC)는 한 기능이 여러 플랫폼에 흩어졌을 때 진입점으로 활용.

선별 페이지를 Read 한 뒤 본문 `[[wikilink]]` 그래프를 따라가고 backlink 도 확인한다. 필요하면 `[[raw/sessions/<platform>/...]]` 까지 거슬러 원본 확인.

## 4. companion(코드 정본) 처리
답이 `api-contract` 또는 `{repo, path}` 정본에 의존하면:
- wiki 페이지는 "왜 이 형태인가·우리 이해"만 인용.
- 정본은 `{ repo: commerce-<platform>, path: ... }` 로 명시. 현재 세션이 그 repo 안이면 정본 파일을 read-only 로 직접 확인 가능.
- wiki 의 "다시 본다면" 견해가 정본과 다르면 둘 다 보여주고 사용자가 판단하게.

## 5. 답변 — 소비자에 따라 출력만 분기
검색 로직은 하나, **출력 형태만** 누가 묻느냐로 나뉜다:

- **사람** — 서술형 답 + 인라인 `[[페이지명]]` 인용(긴 답은 출처 섹션 분리). Obsidian 에서 바로 열어볼 수 있게 링크 형태로.
- **AI 에이전트** (의사결정 중 프로그램적 호출) — 구조화된 JSON:
  ```json
  {
    "answer": "...",
    "citations": [
      { "page": "[[wiki/decisions/...]]", "status": "accepted", "platform": "backend", "sources": ["[[raw/sessions/backend/...]]", {"repo":"commerce-backend","path":"..."}] }
    ]
  }
  ```
- 어느 출력이든 **항상 인용과 함께**. `superseded`/`deprecated` 는 하향하되 status 를 드러내, 옛 결정을 현재처럼 답하지 않는다.
- 모순 발견 시 양쪽 모두 인용 + "모순 있음" 표시. (직접 wiki 수정 금지 — 정정은 raw→ingest 또는 lint.)

## 6. 빈 답 처리
- 후보 0개 → 솔직히 알린다. raw(`raw/sessions/`, `raw/meetings/`, `raw/specs/`)에 있는지 마지막 확인.
- raw 에도 없으면 "관련 내용을 못 찾았다." 추측으로 채우지 않는다.
- raw 엔 있는데 wiki 에 없으면 "아직 ingest 안 됨 — commerce-brain 세션에서 ingest 하면 wiki 로 올라온다" 안내.

## 7. 새 통찰 (직접 저장 금지)
답이 단순 조회가 아니라 *여러 페이지를 종합한 새 통찰* 이면, wiki 에 직접 쓰지 않고 안내만: "[[brain-save]] 로 raw 에 떨구면 ingest 가 wiki 로 올린다." (에이전트 JSON 모드에선 반환만.)

## 규모 커지면
index 통독이 비효율이 될 만큼(수백 페이지) 커지면, 2단계 카탈로그 스캔을 BM25/벡터 검색엔진(qmd 등) 폴백으로 교체한다. glossary 동의어로도 못 잡는 *의미 검색*(글자 안 겹쳐도 뜻이 가까운 것)은 이 단계에서 메워진다.

## 주의사항
- **read-only.** wiki·raw·index.md·log.md·_tag-glossary.md 어느 것도 수정하지 않는다.
- **answer fabrication 금지.** 근거 없으면 "못 찾았다"고 보고.
- **wiki 페이지를 직접 만들지 않는다.** 새 통찰은 [[brain-save]] 또는 ingest 로만.
- **현재 repo 의 코드 파일을 변경하지 않는다.** companion 정본은 읽기만.
