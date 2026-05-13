# 브랜치 컨벤션

이 문서는 현재 Repo에서 사용하는 브랜치 이름 규칙을 정의한다.

## 허용 브랜치 타입

- `feature/<name>`: 새로운 기능 개발
- `fix/<name>`: 버그 수정
- `docs/<name>`: 문서 작업
- `chore/<name>`: 설정, 도구, 운영성 작업
- `refactor/<name>`: 동작 변화 없는 구조 개선
- `test/<name>`: 테스트 추가 또는 수정

## 기본 원칙

- 브랜치 이름은 `<type>/<name>` 형식을 따른다.
- `/` 뒤의 `name`은 kebab-case를 사용한다.
- 하나의 브랜치는 하나의 작업 의도를 표현한다.
- 기능 내부 `phase`는 작업 분해 단위로만 사용하고, 브랜치 이름에는 넣지 않는다.
- 브랜치는 `develop`에서 분기하고, PR도 `develop`을 base로 생성한다.

## 예시

- `feature/skill-test`
- `fix/auth-token-expiry`
- `docs/dev-start-guide`
- `chore/codex-harness-engineering`
- `chore/claude-code-harness-engineering`
- `refactor/payment-service`
- `test/auth-controller`

## worktree로 브랜치 생성하기

브랜치 생성 시 반드시 `git worktree add`를 사용한다. `git switch -c`, `git checkout -b`는 사용하지 않는다.

worktree 방식은 현재 작업 중인 브랜치를 checkout하지 않고 별도 디렉토리에서 새 브랜치를 독립적으로 작업할 수 있다.

worktree 디렉토리는 repo 루트의 `worktrees/` 아래에 생성한다. `worktrees/`는 `.gitignore`에 등록되어 있다.

```bash
# 브랜치 생성 및 이동 (develop 기준)
git worktree add worktrees/<name> -b <type>/<name> develop
cd worktrees/<name>

# 예시
git worktree add worktrees/feature-new-feature -b feature/new-feature develop
cd worktrees/feature-new-feature

# 작업 후 원래 디렉토리로 복귀 및 정리
cd -
git worktree remove worktrees/feature-new-feature
```

`<name>` 부분은 브랜치 이름의 `<type>/<name>` 중 `<name>` 부분과 동일하게 사용한다.
