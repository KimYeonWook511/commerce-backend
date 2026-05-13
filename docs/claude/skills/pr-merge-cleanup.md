# pr-merge-cleanup 요약

`pr-merge-cleanup`은 PR이 머지된 직후 develop 최신화, 머지된 브랜치 감지, worktree 제거, 로컬 브랜치 삭제를 순서대로 수행하는 skill이다.

## 실행 흐름

```
git pull origin develop
git fetch -p
→ gone 브랜치 자동 감지 (git branch -vv | grep ': gone]')
→ worktree 제거 (브랜치명 / → - 변환으로 경로 유도)
→ 로컬 브랜치 삭제
```

## 브랜치 → worktree 경로 변환 규칙

브랜치명의 `/`를 `-`로 교체한다.

예: `refactor/outbox-ddd-migration` → `worktrees/refactor-outbox-ddd-migration`

## 현재 Repo의 역할별 구성

- Workflow policy: `.claude/skills/pr-merge-cleanup/SKILL.md`
