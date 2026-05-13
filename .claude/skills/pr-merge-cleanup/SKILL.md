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

### 1. develop 최신화 및 원격 브랜치 prune

```bash
git checkout develop
git pull origin develop
git fetch -p
```

### 2. 정리 대상 브랜치 자동 감지

`git fetch -p` 이후 원격이 삭제된 브랜치는 `git branch -vv`에서 `: gone]`으로 표시된다.

```bash
git branch -vv | grep ': gone]' | awk '{print ($1 == "*" || $1 == "+" ? $2 : $1)}'
```

출력된 브랜치 목록이 정리 대상이다.

### 3. worktree 제거

worktree 경로는 브랜치명의 `/`를 `-`로 교체해 유도한다.

예: `refactor/outbox-ddd-migration` → `worktrees/refactor-outbox-ddd-migration`

```bash
git worktree remove worktrees/<branch-name-with-slash-replaced>
```

worktree가 없는 경우(worktree 없이 작업한 브랜치)는 이 단계를 건너뛴다.

### 4. 로컬 브랜치 삭제

```bash
git branch -D <branch-name>
```

### 5. 완료 확인

```bash
git worktree list
git branch
```

정리된 브랜치와 worktree가 목록에서 사라졌는지 확인한다.
