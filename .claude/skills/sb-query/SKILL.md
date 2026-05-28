---
name: sb-query
description: Use when the user wants to look up past decisions, trade-offs, domain understanding, or learnings stored in ~/second-brain — triggered by "second-brain 에서", "wiki 에서 찾아줘", "sb 에 뭐 있더라", "이전에 어떻게 결정했지", or any question that the second-brain wiki might answer about the commerce project. Reads ~/second-brain/index.md, wiki/projects/commerce/, wiki/knowledge/, and (if needed) raw/project/commerce/, returns an answer with [[wikilink]] citations. Read-only — does not modify second-brain.
---

# sb-query

`~/second-brain` 에 누적된 commerce 프로젝트 관련 결정·도메인 이해·learnings·troubleshooting 을 조회해 commerce-backend 세션의 의사결정 컨텍스트에 활용한다.

이 skill 은 **read-only**. wiki·raw·index.md·log.md·_tag-glossary.md 어느 파일도 수정하지 않는다. 새 통찰이 생기면 [[sb-save]] 또는 second-brain 세션의 ingest 로 처리한다.

## 전제

- `~/second-brain/CLAUDE.md` 는 Read 하지 않아도 된다 (읽기 전용이므로 운영 규칙 fetch 불필요).
- 답은 항상 *인용* 과 함께 — `[[wiki/...]]`, `[[raw/...]]`, `{ repo, path }` 명시.
- commerce-backend 는 LLM Wiki "모드 A (companion)" — 정본은 `commerce-backend/docs/`. wiki 는 "내 이해·트레이드오프·다시 본다면" 레이어.

## 절차

### 1. 질문 분해

핵심 키워드·도메인·type 을 추출한다.

- 예: "outbox 패턴 왜 도입했지?" → 키워드 `outbox`, `event`, `transaction` / type `decision` 가능성 / project `commerce`
- 예: "MDC traceId 비동기 전파 어떻게 처리했더라?" → 키워드 `mdc`, `traceid`, `async` / type `learning` 또는 `decision`

skill args 가 있으면 그대로 사용. 없거나 모호하면 사용자에게 한 줄 짧게 묻는다.

### 2. 카탈로그·어휘 스캔

```text
1. ~/second-brain/index.md          # 카탈로그 — 후보 페이지 list
2. ~/second-brain/_tag-glossary.md  # 어휘 정규화 — 동의어/상위/하위
```

`_tag-glossary.md` 로 키워드를 canonical 로 정규화한다 (예: 사용자가 "TraceID" 라고 물으면 `traceid` 로). 상위/하위 관계로 검색 폭 확장 가능 (예: `event-driven` → `event-outbox`, `cdc`).

### 3. frontmatter + wikilink 그래프로 추리기

후보 페이지의 frontmatter 로 1차 필터:

- `type` — decision / topic / tradeoff / troubleshooting / project
- `project` — `commerce` 일치
- `domain` — `backend` / `frontend` / `infra` / ...
- `tags` — 키워드와 일치
- `status` — `outdated` / `superseded` 는 가중치 낮춤

후보 우선 순위:

1. `~/second-brain/wiki/projects/commerce/decisions/` — 결정 단위
2. `~/second-brain/wiki/projects/commerce/domain/` — 도메인 모델
3. `~/second-brain/wiki/projects/commerce/learnings/` — 일반화 패턴
4. `~/second-brain/wiki/projects/commerce/troubleshooting/` — 사건 단위
5. `~/second-brain/wiki/projects/commerce/*.md` — 4분류 밖 평탄 노트
6. `~/second-brain/wiki/knowledge/` — 프로젝트 무관 일반 지식

선별된 페이지를 Read 한 뒤 본문의 `[[wikilink]]` 그래프를 따라간다. backlink 도 확인 (다른 페이지가 이 페이지를 인용하는지). 필요시 `[[raw/project/commerce/...]]` 까지 거슬러 올라가 원본 확인.

### 4. companion 모드 정본 처리

답이 외부 repo `commerce-backend/docs/` 에 의존하면:

- wiki 페이지 자체는 "내 이해" 만 인용
- 정본은 `{ repo: commerce-backend, path: docs/<...>.md }` 형식으로 명시
- 정본 본문이 필요하면 read-only 로 직접 읽기 가능 — 현재 cwd 기준 `docs/<...>.md` 상대 경로 (worktree 포함)

### 5. 답변 작성

인용 형식:

- 짧은 답 — 본문에 `[[페이지명]]` 인라인 인용
- 긴 답 — 출처 섹션 별도, 각 주장에 `[[페이지명]]` 또는 `{ repo, path }` 인용
- 모순 발견 시 양쪽 모두 인용 + `> [!warning] 모순` 콜아웃 추가하라고 제안 (직접 wiki 수정은 금지)

답이 wiki 와 `commerce-backend/docs/` 양쪽에 걸치면 정본은 docs/ 임을 명시한다.

### 6. 새 통찰·저장 안내 (직접 저장 금지)

답변이 단순 조회가 아니라 *여러 페이지의 종합·새로운 cross-link* 라면, 사용자에게 두 가지 옵션을 안내한다:

- 이 세션 산출물의 일부로 보고 `/sb-save` 로 raw 에 적기
- second-brain 세션에서 "정리해줘" 로 ingest 시 직접 wiki 페이지 생성

**이 skill 은 wiki 에 직접 쓰지 않는다.** LLM Wiki 운영상 wiki 작성은 second-brain 세션 ingest 의 책임.

### 7. 빈 답 처리

후보 페이지가 0개면 솔직히 알린다:

- wiki 에 없음 → raw 에 있는지 마지막으로 확인 (`raw/project/commerce/` `raw/learn/`)
- raw 에도 없으면 "관련 페이지를 찾지 못했다" 라고 보고. 추측으로 채우지 않는다.
- raw 에는 있는데 wiki 에 없으면 "ingest 안 된 듯 — second-brain 세션에서 정리하면 wiki 로 올라온다" 안내.

## Query 4원칙 (second-brain CLAUDE.md 기준)

1. **일관된 어휘** — tag/alias 는 `_tag-glossary.md` 표준
2. **단일 진실 원천** — 분류는 frontmatter 에만, 폴더는 navigation 보조
3. **풍부한 cross-link** — `[[topic]]`, `[[projects/commerce]]`, `[[raw/...]]` 적극 활용
4. **type별 frontmatter 표준화** — query 필터 기준

## 주의사항

- **read-only.** wiki·raw·index.md·log.md·_tag-glossary.md 어느 파일도 수정하지 않는다.
- **answer fabrication 금지.** wiki·raw 에 근거가 없으면 "관련 페이지를 찾지 못했다" 라고 보고. 추측으로 채우지 않는다.
- **모드 A (companion) 인식.** 정본은 `commerce-backend/docs/`. wiki 가 "다시 본다면" 으로 docs/ 와 다른 견해를 보일 수 있는데, 그 경우 둘 다 보여주고 사용자가 판단하게 한다.
- **commerce-backend 의 어떤 파일도 변경하지 않는다.** 이 skill 은 read-only.
- **second-brain 의 wiki 페이지를 직접 만들지 않는다.** 새 통찰은 `/sb-save` 또는 ingest 로 안내만.
