---
name: pr-merge-cleanup
description: PR 머지 후 develop을 최신화하고 머지된 브랜치의 worktree와 로컬 브랜치를 정리하는 skill
---

# PR Merge Cleanup Workflow

이 skill은 PR이 머지된 직후 사용한다.

- `develop` 최신화
- 원격에서 삭제된 브랜치 prune
- 머지된 브랜치의 worktree 제거
- 로컬 브랜치 삭제

## 실행 절차

### 1. 메인 repo 루트로 복귀

worktree 안에서 실행 중일 수 있으므로 먼저 메인 repo 루트로 이동한다.

```bash
cd "$(git rev-parse --git-common-dir)/.."
```

### 2. develop 최신화 및 원격 브랜치 prune

```bash
git checkout develop
git pull origin develop
git fetch -p
```

### 3. 정리 대상 브랜치 자동 감지

`git fetch -p` 이후 원격이 삭제된 브랜치는 upstream이 사라진 상태(`[gone]`)가 된다. 스크립트에서는 porcelain인 `git branch -vv`(로케일에 따라 `gone`이 `없음`으로 번역되고, 앞에 `*`/`+` 마커가 붙는다) 대신, plumbing인 `git for-each-ref`로 감지한다 — 출력이 로케일·마커에 무관하게 고정돼 파싱이 안정적이다.

```bash
git for-each-ref --format='%(refname:short) %(upstream:track)' refs/heads/ | grep '\[gone\]' | awk '{print $1}'
```

출력된 브랜치 목록이 정리 대상이다.

### 4. worktree 제거

worktree 경로는 브랜치명의 `/`를 `-`로 교체해 유도한다.

예: `refactor/outbox-ddd-migration` → `worktrees/refactor-outbox-ddd-migration`

```bash
git worktree remove worktrees/<branch-name-with-slash-replaced>
```

worktree가 없는 경우(worktree 없이 작업한 브랜치)는 이 단계를 건너뛴다.

### 5. 로컬 브랜치 삭제

```bash
git branch -D <branch-name>
```

### 6. 완료 확인

```bash
git worktree list
git branch
```

정리된 브랜치와 worktree가 목록에서 사라졌는지 확인한다.
