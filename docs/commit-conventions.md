# 커밋 컨벤션

## 형식

```text
<type>: <subject>
```

## 허용 타입

- `feat`: 새로운 기능 추가
- `fix`: 버그 수정
- `refactor`: 동작 변화 없는 리팩터링
- `test`: 테스트 추가 또는 수정
- `docs`: 설명 문서 변경
- `chore`: 설정, 도구, 자동화, 운영성 파일 변경

## 타입 선택 기준

- `feat`: 사용자 또는 운영자가 사용할 수 있는 기능 동작을 추가하거나 기존 기능 범위를 확장할 때 사용합니다.
- `fix`: 의도와 다르게 동작하던 버그를 고칠 때 사용합니다.
- `refactor`: 외부 동작 변화 없이 구조, 이름, 책임 분리, 중복 제거를 정리할 때 사용합니다.
- `test`: 테스트 코드, 테스트 fixture, 테스트 설정만 추가하거나 수정할 때 사용합니다.
- `docs`: 문서만 변경할 때 사용합니다.
- `chore`: 제품 기능이 아니라 개발/운영/자동화 기반을 변경할 때 사용합니다.
  - 하네스, AI 도구 설정(skill, agent, hook, slash command 포함), CI, PR 템플릿, Docker/샌드박스 실행 환경, Gradle 설정, 코드 리뷰 설정은 `chore`를 사용합니다.

## 혼합 변경 기준

- 기능 코드와 문서 동기화가 함께 들어가도 커밋의 주된 목적이 기능 추가라면 `feat`를 사용합니다.
- 문서만 고치는 커밋은 `docs`를 사용합니다.
- 하네스나 자동화 코드와 그 테스트를 함께 고치는 커밋은 `chore`를 사용합니다.
- 테스트만 추가해서 동작을 재현하거나 검증하는 커밋은 `test`를 사용합니다.

## 커밋 단위 기준

- 커밋 단위는 역할과 목적을 기준으로 나눈다.
- 같은 기능 안에서도 의존 순서에 따라 나눌 수 있다면 나누는 것을 권장한다.
- 역할이 다른 변경을 이유 없이 하나로 묶지 않는다.
- subject에 연결어(및, 하고 등)가 필요해진다면 커밋을 분리해야 한다는 신호다.

## subject 규칙

- `subject`는 `~한다` 형태의 한국어 동사형으로 작성합니다.
- 명사형으로 끝내지 않습니다. (예: `추가` → `추가한다`, `수정` → `수정한다`)
- 마침표(`.`)로 끝내지 않습니다.
- 모호한 표현은 사용하지 않습니다.

## 본문(body) 규칙

- body는 선택적으로 작성한다. 단순 변경은 subject만으로 충분하다.
- 정책 결정, 비표준 트레이드오프, 회귀 위험이 큰 변경 등 "왜"의 설명이 필요한 경우 body를 작성한다.
- body 작성 시 형식:
  - `subject`와 body 사이에 빈 줄 한 줄을 둔다.
  - body는 `-`로 시작하는 bullet 형식으로만 작성한다. 자유 문단은 사용하지 않는다.
  - 한 줄에 한 항목씩 적고 들여쓰기는 일관되게 둔다.
- body는 핵심 요점만 간략하게 정리한다. 자세한 배경·세부 동작은 PR 본문에서 관리하고, body에는 commit 단위로 짚어야 할 결정·트레이드오프·주의사항만 짧게 적는다.
- 각 bullet은 한 줄 이내로 작성한다. 한 줄로 표현하기 어려우면 항목을 더 잘게 나누거나, 자세한 설명은 PR 본문으로 옮긴다.
- body 어미는 자유(`~다`/`~이다`/서술체 등). subject의 `~한다` 규칙은 body에 강제하지 않는다.
- footer(`Closes #N`, `Refs #N` 등)는 commit message에 적지 않는다. 이슈 연결은 PR 본문의 `## 관련 이슈` 섹션에서만 관리한다.
- agent가 자동으로 작성하는 commit(harness `commit_agent`, `pr-review-resolve` 등)은 body를 작성하지 않는다(subject만). 자동화는 깊은 의도나 컨텍스트를 정확히 추출하기 어려워 부정확하거나 hallucinated body를 만들 위험이 있다. 변경 의도는 PR 본문에서 단일 관리한다.

## 예시

### subject만 (단순 변경, 타입·문체 예시)

- `feat: 상품 조회 API를 추가한다`
- `fix: 인증 토큰 만료 처리를 수정한다`
- `refactor: 결제 승인 서비스 책임을 분리한다`
- `test: 인증 컨트롤러 예외 응답 테스트를 추가한다`
- `docs: 상품 조회 API 문서를 보강한다`
- `chore: Claude Code 컨텍스트 엔지니어링 하네스를 구축한다`

### body 포함 (정책 결정·트레이드오프·회귀 위험 설명이 필요한 경우)

```text
refactor: 주문 생성 멱등성을 RDB unique 제약 기반으로 전환한다

- Redis SETNX 방식은 TTL 만료 race window에서 정당한 멱등 재요청을 차단하는 문제가 있어 DB unique 제약으로 전환
- (member_id, idempotency_key)에 unique 제약 추가
- application 계층은 DuplicateKeyException을 catch하지 않고 안전망(500)으로 위임
- Redis는 latency 최적화 레이어로 유지하되 멱등성의 진실 원천은 RDB로 일원화
```

```text
chore: harness Execution Authorization step을 워크플로에서 제거한다

- WORKFLOW_ITEMS를 7개에서 6개로 축소
- validate_execution_authorization_item 메서드와 관련 상수 삭제
- SKILL.md / references / 템플릿을 6-step 흐름으로 동기화
- 사용자 진행 확인 룰은 SKILL.md / CLAUDE.md에 명문화 유지
```
