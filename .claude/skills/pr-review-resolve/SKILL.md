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

### 8. 결정에 따라 실행

전체 흐름은 다음 3단계로 진행한다.

```
[적용 단계] 모든 accept/modify를 로컬 커밋까지 진행
    ↓
[보고 단계] 사용자에게 전체 변경/커밋 요약 보고 + 단일 OK 게이트
    ↓
[일괄 처리 단계] push 한 번 + 각 thread reply + resolve
```

각 review 항목마다 **별도 커밋**을 생성한다. 여러 항목을 묶어서 하나의 커밋으로 합치지 마라. 이유: 커밋 메시지가 어떤 review에 대응하는 변경인지 명시되어야 하고, 사후 revert 단위가 review 항목과 일치해야 한다.

답변은 **반드시 해당 thread 내 reply로 등록**한다. 일반 PR 코멘트(`gh pr comment`)로 등록하지 마라. 이유: 답변이 thread 외부로 새면 review 맥락이 끊긴다.

### 8-1. 적용 단계 — 모든 accept/modify를 로컬 커밋까지 진행

순회하며 각 항목을 처리한다. **사용자 중간 확인 없이** 적용 → 커밋까지 일괄 진행한다.

**accept 인 경우**:

1. 코드 변경 적용 (Edit / Write 사용)
2. 테스트 실행으로 회귀 없음 확인 (`./gradlew test` 등)
3. 해당 review 항목 단독 커밋 — **반드시 `docs/commit-conventions.md` 형식을 준수한다.** 타입(`feat`, `fix`, `refactor`, `test`, `docs`, `chore`)과 subject 문체(`~한다`)를 확인하고, 커밋 메시지에 어떤 review에 대한 대응인지 드러나도록 작성한다.

**modify 인 경우**:

사용자가 7단계 협의 시점에 제시한 변형 방향대로 적용한다. accept와 동일한 흐름(변경 → 테스트 → 커밋).

**reject 인 경우**:

이 단계에서는 **아무 작업도 하지 않는다.** 보고 단계에서 일괄 처리한다.

### 8-2. 보고 단계 — 사용자에게 변경 요약 + 단일 OK 게이트

모든 적용이 끝나면 사용자에게 요약 보고한다.

```
모든 review 항목 처리 준비 완료.

[적용된 커밋]
1. <hash> <커밋 메시지>  (review [N] line:M)
2. <hash> <커밋 메시지>  (review [N] line:M)
...

[거부 항목]
- review [N] line:M — <거부 사유>

이대로 push + 각 thread 답변 + resolve로 진행할까요?
```

**사용자 OK 인 경우** → 8-3 일괄 처리 단계로 진행

**사용자 수정 요청인 경우**:

1. 요청에 맞게 해당 항목의 변경을 수정한다.
2. 영향받는 커밋을 정리한다.
   - 마지막 커밋만 수정: `git commit --amend`
   - 중간 커밋 수정 필요: `git reset --soft <대상-1>`로 되돌린 뒤 재적용 + 재커밋
   - 모든 커밋은 로컬에만 있으므로 자유롭게 정리 가능 (push 이후 아님)
3. 다시 보고 단계로 돌아간다.

### 8-3. 일괄 처리 단계 — push + 각 thread reply + resolve

사용자 OK가 확인되면 다음을 순차로 실행한다.

1. **단일 push**

```bash
git push
```

2. **각 accept/modify 항목** — thread reply (커밋 hash 포함) + resolve

```bash
gh api "repos/$REPO/pulls/$PR_NUMBER/comments/<ROOT_COMMENT_ID>/replies" \
  -X POST \
  -f body="반영했습니다. <요약> (커밋 <hash>)"

gh api graphql -f query='
mutation {
  resolveReviewThread(input: {threadId: "<THREAD_NODE_ID>"}) {
    thread { isResolved }
  }
}'
```

3. **각 reject 항목** — thread reply (거부 사유) + resolve

```bash
gh api "repos/$REPO/pulls/$PR_NUMBER/comments/<ROOT_COMMENT_ID>/replies" \
  -X POST \
  -f body="<거부 사유>"

gh api graphql -f query='
mutation {
  resolveReviewThread(input: {threadId: "<THREAD_NODE_ID>"}) {
    thread { isResolved }
  }
}'
```

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

- **사용자 결정 없이 임의로 accept/reject하지 마라.** 이유: review 의견은 코드 정책 결정이므로 사용자가 판단해야 한다. 결정은 7단계 협의에서 일괄로 받는다.
- **commit 메시지는 반드시 `docs/commit-conventions.md` 형식을 준수하라.** 이유: 프로젝트 컨벤션 위반은 PR 단계에서 다시 정리해야 한다.
- **모든 적용 단계(8-1)는 로컬 commit까지만 진행하고, push / 답변 / resolve는 보고 단계(8-2)의 사용자 OK 후 일괄 처리(8-3)에서만 실행하라.** 이유: 적용 도중 push가 섞이면 사용자가 한 묶음으로 검토할 수 없고, 의도와 다른 변경이 원격으로 새는 것을 막을 수 없다. local commit은 reset/amend로 쉽게 정리되지만 push 이후에는 PR 타임라인에 영구 흔적이 남는다.
- **적용 단계(8-1)에서 사용자 중간 확인을 받지 마라.** 이유: 항목마다 확인을 받으면 흐름이 끊기고 보고 단계의 단일 OK 게이트 의의가 사라진다. 확인은 보고 단계에서 한 번에 받는다.
- **accept 시 테스트 통과 없이 commit하지 마라.** 이유: 회귀가 있는 변경이 commit으로 굳어지면 정리하기 번거롭다.
- **여러 review 항목을 하나의 커밋으로 묶지 마라.** 이유: 커밋과 review 항목이 1:1 매칭되어야 추적과 revert가 단순해진다.
- **답변은 반드시 thread reply로 등록하라. `gh pr comment` 같은 일반 PR 코멘트로 등록하지 마라.** 이유: thread 외부에 답변이 새면 review 맥락이 끊기고 thread resolve와 답변의 매칭이 무너진다.
- **답변 본문에 영어/한국어 일관성을 유지하라.** 이유: 코멘트 가독성.
- **이미 resolved된 thread는 건드리지 마라.** 이유: 이전 처리 결과를 덮어쓰지 않는다.
- **여러 PR을 동시에 처리하지 마라.** 이유: 인자로 받은 단일 PR에만 작용한다.
