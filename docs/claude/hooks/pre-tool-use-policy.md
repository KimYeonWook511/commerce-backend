# PreToolUse 정책 (Bash)

## 목적

이 문서는 현재 Repo 전용 Claude Code `PreToolUse` hook의 운영 규칙을 설명한다.

이 정책의 목표는 하나다: **보호 브랜치(main/develop)에 대한 변경은 PR 머지로만 반영되게 하고, 그 외 위험한 Bash 명령을 실행 전에 차단**한다. 에이전트(`agent_type`)별 분기는 없으며, 모든 Bash 명령에 동일한 브랜치 인식 규칙을 적용한다.

이 hook은 **로컬 1차 가드레일**이지 강제 장치가 아니다. "무조건 PR로만"의 진짜 보장은 GitHub/GitLab 서버 측 branch protection / ruleset이며, 이 hook은 그 위에 까는 보조 방어선이다(아래 "한계" 참고).

## 적용 범위

- 이 정책은 현재 Repo에서만 적용된다.
- hook 설정 파일은 `.claude/settings.json`이다.
- 정책 스크립트는 `.claude/hooks/pre_tool_use_policy.py`이다.
- 매처는 `Bash`. Bash 명령만 검사한다(Write/Edit 등 파일 도구는 검사하지 않는다).
- 보호 브랜치 집합은 스크립트 상단 `PROTECTED_BRANCHES = {"main", "develop"}`에서 정의하며, `"master"`·`"release"` 등을 추가해 확장할 수 있다.

## 규칙

규칙은 "현재 체크아웃된 브랜치"와 "명령의 대상 브랜치"를 기준으로 동작한다. 현재 브랜치는 hook 실행 시 `git rev-parse --abbrev-ref HEAD`로 1회 조회한다.

### 1. 보호 브랜치로의 직접 push — 전면 차단

보호 브랜치를 대상으로 하는 push는 **방식과 무관하게 모두 차단**한다.

- 차단: 일반(fast-forward) push, force push(`--force` / `-f` / `--force-with-lease` / `+refspec`), 브랜치 삭제(`:branch` / `--delete`)
- 차단: `git push --all`(모든 로컬 브랜치 — 보호 브랜치 포함), `git push --mirror`
- 판단 기준은 **현재 브랜치가 아니라 push 대상 브랜치(refspec)**다. 따라서 피처 브랜치에 있어도 `git push origin develop`, `git push -f origin HEAD:main`은 차단된다.
- refspec이 없는 `git push`는 "현재 브랜치를 push"로 보고, 현재 브랜치가 보호 브랜치일 때만 차단한다.

> PR 머지는 로컬 push가 아니라 원격 서버에서 일어나므로 이 차단의 영향을 받지 않는다.

### 2. 보호 브랜치에서의 커밋·히스토리 작성 — 차단

보호 브랜치에 체크아웃된 상태에서 커밋을 만들거나 히스토리를 옮기는 명령을 차단한다. 커밋만 막으면 `git merge`로 우회되므로 함께 막는다.

- 차단(보호 브랜치일 때): `git commit` / `git merge` / `git rebase` / `git cherry-pick` / `git revert` / `git am`
- 허용: 커밋을 만들지 않는 안전 동작 — `--abort`, `--quit`(복구), `--dry-run`(조회)
- 차단: `--continue`·`--skip`은 중단된 작업을 이어 커밋을 만들 수 있어 막는다
- `git checkout main && git commit ...`처럼 같은 줄에서 보호 브랜치로 전환한 뒤 작업하는 패턴도 추적해 차단한다(복합 명령의 브랜치 전환 추적).

### 3. 파괴적 명령 — 보호 브랜치에서만 차단

보호 브랜치에서 작업물을 되돌리거나 삭제하는 명령을 차단한다.

- `git reset --hard ...`
- `git checkout -- ...`(워킹트리 복원)
- `git restore ...`(워킹트리 복원; `--staged` 단독은 인덱스만 건드리므로 허용)
- `rm -rf ...` / `rm -fr ...` / `rm --recursive --force ...`

### 그 외 브랜치(피처 브랜치)

위 1~3에 해당하는 명령도 **모두 허용**한다. 피처 브랜치에서는 force push, reset --hard, rm -rf 등 자유롭게 실험할 수 있다.

### 토큰 기준 검사

차단 기준은 명령 문자열이 아니라 shell token 기준이다. `sudo`, `command`, `env FOO=bar ...`, `git -c key=val ...` 같은 prefix가 있어도 실제 명령이 규칙에 해당하면 차단한다. `&&`, `||`, `;`, `&`, `|`로 연결된 복합 명령은 각 명령을 개별 검사하고, 따옴표 안의 구분자는 분리 대상에서 제외한다.

## 허용 예시

- `ls -la`, `rg hooks .claude`, `./gradlew test`, `git status`, `git diff`, `git log`, `git add .`
- 피처 브랜치에서: `git commit -m "..."`, `git push origin feature/x`, `git push -f origin feature/x`
- 보호 브랜치에서: `git fetch`, `git pull`, `git merge --abort`, `git commit --dry-run`

## 동작 방식

Claude Code가 도구 실행을 시도하면 `PreToolUse` hook이 stdin으로 JSON payload를 받는다.

- `tool_name`이 `Bash`인 명령만 검사한다(그 외 도구는 출력 없이 통과).
- 현재 브랜치를 1회 조회한 뒤, 복합 명령을 분리해 각 명령을 순서대로 검사한다. 도중에 브랜치 전환 명령이 있으면 이후 검사 기준 브랜치를 갱신한다.
- 차단 시 아래 응답 형식을 stdout에 출력하고 exit 0. 통과 시 출력 없이 exit 0.
- 입력 JSON이 깨졌거나 형식이 예상과 다르면 fail-open(통과)으로 처리한다.

## Claude Code hook 응답 형식

```json
{
  "hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "permissionDecision": "deny",
    "permissionDecisionReason": "Repo 정책: 보호 브랜치(`develop`)로의 직접 push 는 금지됩니다. 변경은 피처 브랜치 → PR 머지로만 반영하세요. (보호 브랜치: develop, main)"
  }
}
```

## 한계 (중요)

이 hook은 **결정론적이고 형태가 명확한 소수의 사고**를 막는 로컬 가드레일이다. 다음 경로는 막지 못한다.

- **범용 도구 우회**: `gh api -X PATCH .../git/refs/heads/main ...`, `curl -X PATCH https://api.github.com/...` 처럼 인증 토큰으로 GitHub API에 직접 쓰기 호출하면 `git push` 차단을 우회한다. 이런 임의 호출은 hook으로 정밀 차단해도 빈틈이 계속 생기므로 정책 문서(CLAUDE.md)와 서버 보호에 맡긴다.
- **스크립트 내부 명령**: `bash deploy.sh` 안에서 `git push origin main`이 일어나면 hook은 바깥 명령만 보므로 잡지 못한다.
- **브랜치 탐지 실패(fail-open)**: 레포가 아니거나 git 미설치·타임아웃 등으로 현재 브랜치 조회가 실패하면 "현재 브랜치 의존 검사"(commit·bare push·reset 등)는 통과시킨다. 단, refspec으로 **명시된** 보호 브랜치 대상 push(`git push origin develop` 등)는 탐지와 무관하게 항상 차단된다.
- **hook 자체 비활성화**: `.claude/settings.json`/스크립트를 끄거나 다른 환경에서 실행하면 무력화된다.

따라서 권장 구조는 다음 3중 방어다.

1. **이 hook** — 흔하고 형태가 명확한 사고(보호 브랜치 직접 push/commit/merge, 파괴 명령)를 실행 전 차단.
2. **CLAUDE.md 규칙** — "보호 브랜치 직접 변경 금지, PR로만, `gh api`/`curl`로 GitHub 쓰기 호출 금지, 보호 규칙 변경 금지" 등 우회 가능한 의도 영역을 강하게 안내(강제는 아님).
3. **서버 측 branch protection / ruleset** — 직접 push 금지, PR 필수, 리뷰·상태체크 필수, force push·삭제 금지. 무엇으로도 못 뚫는 최종 강제선.

## 로컬 검증

stdin에 JSON payload를 흘려 동작을 빠르게 확인할 수 있다.

```bash
# 보호 브랜치로의 직접 push → 차단(출력 있음)
echo '{"tool_name":"Bash","tool_input":{"command":"git push origin develop"}}' \
  | python3 .claude/hooks/pre_tool_use_policy.py

# 일반 조회 → 통과(출력 없음)
echo '{"tool_name":"Bash","tool_input":{"command":"git status"}}' \
  | python3 .claude/hooks/pre_tool_use_policy.py
```

실제 Claude Code 동작 검증은 repo 루트에서 Claude Code를 실행한 뒤, 보호 브랜치/피처 브랜치 각각에서 허용·차단 명령을 실행해 확인한다.
