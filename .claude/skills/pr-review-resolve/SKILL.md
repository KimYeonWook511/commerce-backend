---
name: pr-review-resolve
description: PR에 달린 review 코멘트를 수집해 사용자와 항목별로 처리 방향을 정한 뒤 답변·resolve까지 처리하는 skill
---

# PR Review Resolve Workflow

이 skill은 PR에 달린 review 코멘트(예: Gemini Code Assist)를 일괄 처리할 때 사용한다.

- 모든 review 코멘트 수집 (root review + inline)
- 사용자와 항목별로 처리 방향 협의 (accept / reject / modify)
- 결정에 따라 코드 반영, 답변 등록, thread resolve

## 호출 형식

```
/pr-review-resolve <PR번호>
```

`<PR번호>`는 다음 형식 모두 허용한다:
- `119`
- `#119`

`#` 접두사가 있으면 제거한 뒤 사용한다.

인자가 없으면 다음 메시지로 종료한다:

> PR 번호를 명시하세요. 예: `/pr-review-resolve 119` 또는 `/pr-review-resolve #119`

## 실행 절차

### 1. 인자 정규화

입력 인자에서 `#` 접두사를 제거해 PR 번호만 추출한다.

```bash
PR_NUMBER=$(echo "$1" | sed 's/^#//')
```

### 2. Repo 정보 확보

```bash
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
```

### 3. PR 존재 확인

```bash
gh pr view "$PR_NUMBER" --json number,title,state >/dev/null 2>&1
```

실패 시 "PR #<번호>를 찾을 수 없습니다" 메시지로 종료한다.

### 4. Review 코멘트 수집

두 종류를 모두 수집한다.

**(a) Root review 코멘트** (PR 본문에 달린 review):

```bash
gh pr view "$PR_NUMBER" --comments
```

**(b) Inline 코멘트** (코드 라인별):

```bash
gh api "repos/$REPO/pulls/$PR_NUMBER/comments"
```

각 inline 코멘트에서 추출할 필드:
- `id` (코멘트 ID, 답변 시 사용)
- `path` (파일 경로)
- `line` (라인 번호)
- `user.login` (작성자)
- `body` (내용)
- `in_reply_to_id` (존재하면 답변 코멘트이므로 root 코멘트가 아님)

`in_reply_to_id`가 없는 코멘트만 root로 분류한다.

### 5. Review thread 노드 ID 수집

resolve에 필요한 thread node ID는 GraphQL로만 얻을 수 있다.

```bash
gh api graphql -f query='
{
  repository(owner: "<OWNER>", name: "<REPO_NAME>") {
    pullRequest(number: <PR_NUMBER>) {
      reviewThreads(first: 50) {
        nodes {
          id
          isResolved
          comments(first: 1) {
            nodes { databaseId body }
          }
        }
      }
    }
  }
}'
```

각 thread의 첫 코멘트 `databaseId`로 inline 코멘트와 매칭한다.

### 6. 사용자에게 정리해서 보고

수집된 코멘트를 다음 형식으로 정리한다:

```
PR #<번호> "<제목>" review 코멘트 N개를 발견했습니다.

[1] <파일:라인> by <작성자>
    <코멘트 본문 요약 (3~5줄)>

[2] <파일:라인> by <작성자>
    <코멘트 본문 요약>

...
```

이미 resolved된 thread는 표시에서 제외한다.

### 7. 항목별 처리 방향 협의

각 항목에 대해 사용자와 대화하며 결정한다:

- **accept**: 제안을 그대로 적용
- **reject**: 적용하지 않음 (이유 명시)
- **modify**: 일부만 또는 변형해서 적용

결정은 사용자가 명시적으로 내린다. 임의로 판단하지 않는다.

### 8. 결정에 따라 실행 (항목별 1커밋 원칙)

각 review 항목마다 **별도 커밋**을 생성한다. 여러 항목을 묶어서 하나의 커밋으로 합치지 마라. 이유: 커밋 메시지가 어떤 review에 대응하는 변경인지 명시되어야 하고, 사후 revert 단위가 review 항목과 일치해야 한다.

답변은 **반드시 해당 thread 내 reply로 등록**한다. 일반 PR 코멘트(`gh pr comment`)로 등록하지 마라. 이유: 답변이 thread 외부로 새면 review 맥락이 끊긴다.

**reject 인 경우**:

1. Thread reply로 답변 등록 — 거부 이유를 본문에 포함

```bash
# replies 엔드포인트가 thread 내 답변을 생성한다
gh api "repos/$REPO/pulls/$PR_NUMBER/comments/<ROOT_COMMENT_ID>/replies" \
  -X POST \
  -f body="<거부 사유>"
```

2. Thread resolve

```bash
gh api graphql -f query='
mutation {
  resolveReviewThread(input: {threadId: "<THREAD_NODE_ID>"}) {
    thread { isResolved }
  }
}'
```

**accept 인 경우**:

1. 코드 변경 적용 (Edit / Write 사용)
2. 테스트 실행으로 회귀 없음 확인 (`./gradlew test` 등)
3. 변경된 파일의 diff 출력
4. 해당 review 항목 단독 커밋 — **반드시 `docs/commit-conventions.md` 형식을 준수한다.** 타입(`feat`, `fix`, `refactor`, `test`, `docs`, `chore`)과 subject 문체(`~한다`)를 확인하고, 커밋 메시지에 어떤 review에 대한 대응인지 드러나도록 작성한다.
5. **사용자 확인 대기** — 커밋 메시지와 hash를 보여주고 "push/답변/resolve로 진행할까요?" 확인. 커밋은 로컬에 머물러 있으므로 사용자가 수정 요청 시 `git reset HEAD~1`로 되돌리거나 `git commit --amend`로 수정한 뒤 다시 확인을 받는다.
   - 사용자가 OK → 6번부터 진행
   - 사용자가 수정 요청 → 1번부터 재시작 (필요 시 commit reset)
6. push
7. Thread reply로 답변 등록 (`replies` 엔드포인트 사용) — 적용 내용 + 커밋 hash 본문에 포함
8. Thread resolve

**modify 인 경우**:

사용자에게 변형 방향을 입력받아 accept와 동일한 흐름(코드 변경 → 테스트 → diff → 커밋 → 사용자 OK → push → thread reply → resolve)으로 진행한다.

### 8-1. 여러 항목 처리 순서

여러 항목이 있을 때는 한 번에 하나씩 처리한다. 한 항목의 commit → push → reply → resolve를 모두 끝낸 뒤 다음 항목으로 넘어간다. 이렇게 하면:

- 항목별 커밋 1개 원칙이 자연스럽게 지켜진다
- 중간에 사용자가 흐름을 변경하면 이미 처리된 항목은 영향 없이 보존된다
- PR 타임라인에서 어떤 commit이 어떤 review에 대응하는지 명확하다

### 9. 완료 보고

처리한 항목을 표로 요약한다:

| # | 항목 | 결과 |
|---|---|---|
| 1 | <파일:라인 요약> | ✅ accept (커밋 <hash>) |
| 2 | <파일:라인 요약> | ❌ reject |
| 3 | <파일:라인 요약> | 🔧 modify (커밋 <hash>) |

resolve 누락 여부도 함께 확인한다.

```bash
gh api graphql -f query='
{
  repository(owner: "<OWNER>", name: "<REPO_NAME>") {
    pullRequest(number: <PR_NUMBER>) {
      reviewThreads(first: 50) {
        nodes { isResolved }
      }
    }
  }
}'
```

미해결 thread가 남아 있으면 사용자에게 알린다.

## 주의사항

- **사용자 결정 없이 임의로 accept/reject하지 마라.** 이유: review 의견은 코드 정책 결정이므로 사용자가 판단해야 한다.
- **commit 메시지는 반드시 `docs/commit-conventions.md` 형식을 준수하라.** 이유: 프로젝트 컨벤션 위반은 PR 단계에서 다시 정리해야 한다.
- **commit까지 자동으로 진행하되, push / 답변 / resolve는 사용자 OK 후에만 실행하라.** 이유: local commit은 reset/amend로 쉽게 정리되지만, push 이후에는 PR 타임라인에 영구 흔적이 남는다. 의도와 다른 변경이 원격으로 새는 것을 막아야 한다.
- **accept 시 테스트 통과 없이 commit하지 마라.** 이유: 회귀가 있는 변경이 commit으로 굳어지면 정리하기 번거롭다.
- **여러 review 항목을 하나의 커밋으로 묶지 마라.** 이유: 커밋과 review 항목이 1:1 매칭되어야 추적과 revert가 단순해진다.
- **답변은 반드시 thread reply로 등록하라. `gh pr comment` 같은 일반 PR 코멘트로 등록하지 마라.** 이유: thread 외부에 답변이 새면 review 맥락이 끊기고 thread resolve와 답변의 매칭이 무너진다.
- **답변 본문에 영어/한국어 일관성을 유지하라.** 이유: 코멘트 가독성.
- **이미 resolved된 thread는 건드리지 마라.** 이유: 이전 처리 결과를 덮어쓰지 않는다.
- **여러 PR을 동시에 처리하지 마라.** 이유: 인자로 받은 단일 PR에만 작용한다.
