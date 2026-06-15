# PreToolUse 정책 (Bash / Write)

## 목적

이 문서는 현재 Repo 전용 Claude Code `PreToolUse` hook의 운영 규칙을 설명한다.

이 정책은 두 가지를 한다: (1) repo 전체의 위험한 Bash 명령을 실행 전에 차단하는 **공용 최소 방어선**, (2) harness-v3 sub-agent(committer / reviewer)에 대해 역할별로 **더 좁은 규칙**을 적용. 입력의 `agent_type`으로 어느 규칙을 쓸지 가른다.

## 적용 범위

- 이 정책은 현재 Repo에서만 적용된다.
- hook 설정 파일은 `.claude/settings.json`이다.
- 정책 스크립트는 `.claude/hooks/pre_tool_use_policy.py`이다.
- 매처는 `Bash|Write`. Bash는 명령을, Write는 파일 경로를 검사한다.

## 규칙 (agent_type별 분기)

### 1. harness-v3-committer (Bash) — 화이트리스트

`git status` / `git diff` / `git log` / `git add` / `git commit` **다섯 개만 허용**하고 나머지는 모두 차단한다. committer의 본업이 커밋뿐이라 그 외 명령은 정상 흐름이 아니다.

- 차단: 위 5개 외 모든 git 서브커맨드(push / pull / fetch / reset / checkout / switch / rebase / merge / branch / clean / restore / stash / cherry-pick / revert / tag 등)
- 차단: `git commit --amend` (commit 서브커맨드라도 history 조작이므로)
- 차단: git이 아닌 모든 Bash 명령(rm / mv / echo > 파일 등)

### 2. harness-v3-reviewer (Write) — 핸드오프 가드

reviewer는 판정 핸드오프만 쓸 수 있다. `tool_input.file_path`가 `.../handoff/stepN-review.json` 패턴이 아니면 차단한다. (코드·문서·dev 핸드오프 등 다른 경로 쓰기 차단.)

### 3. 그 외(메인 세션 / 일반 작업) (Bash) — 블랙리스트

대표 위험 패턴만 차단하는 최소 방어선이다.

- `git reset --hard`
- `git checkout -- ...`
- `rm -rf ...` / `rm -fr ...` / `rm --recursive --force ...`
- `git push --force ...` / `--force-with-lease` / `-f`

차단 기준은 명령 문자열이 아니라 shell token 기준이다. `sudo`, `command`, `env FOO=bar ...`, `git -c ... ` 같은 prefix가 있어도 실제 명령이 규칙에 해당하면 차단한다. `&&`, `||`, `;`, `&`, `|`로 연결된 복합 명령은 각 명령을 개별 검사하고, 따옴표 안의 구분자는 분리 대상에서 제외한다.

블랙리스트는 최소 방어선이라 `git restore`, `find ... -delete`, SQL `DROP TABLE` 등 다른 위험 명령까지 모두 막지는 않는다.

## 허용 예시 (일반 작업)

- `ls -la`, `rg hooks .claude`, `./gradlew test`, `git status`

## 동작 방식

Claude Code가 도구 실행을 시도하면 `PreToolUse` hook이 stdin으로 JSON payload를 받는다.

- `agent_type`과 `tool_name`을 읽어 위 1~3 규칙 중 하나로 분기한다.
- `agent_type`이 비어 있으면(플랫폼/버전 차이로 미제공) 3번(블랙리스트)으로 fail-safe 동작한다.
- 차단 시 아래 응답 형식을 stdout에 출력하고 exit 0. 통과 시 출력 없이 exit 0.
- 입력 JSON이 깨졌거나 형식이 예상과 다르면 fail-open(통과)으로 처리한다.

## Claude Code hook 응답 형식 (최신)

```json
{
  "hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "permissionDecision": "deny",
    "permissionDecisionReason": "harness-v3-committer는 git status/diff/log/add/commit만 허용됩니다 (시도: `git push`)."
  }
}
```

## sub-agent 차단의 한계 (중요)

일부 Claude Code 버전/플랫폼에서 **sub-agent의 PreToolUse 차단이 무시될 수 있다**(이슈 #40580, WSL 라벨, 작성 시점 open). hook은 호출되고 올바른 입력을 받지만 차단이 강제되지 않을 수 있다.

따라서 committer 화이트리스트·reviewer 가드는 **2차 방어(defense-in-depth)**로 보고, 1차 방어는 각 sub-agent 정의(`.claude/agents/harness-v3-*.md`)의 프롬프트 제약으로 둔다. 본인 환경에서 sub-agent 차단이 실제로 동작하는지 1회 확인을 권장한다(예: committer가 비-git 명령을 시도했을 때 막히는지).

## 로컬 검증

```bash
python3 -m unittest discover -s .claude/hooks/tests
```

실제 Claude Code 동작 검증은 repo 루트에서 Claude Code를 실행한 뒤 허용/차단 명령을 각각 실행해 확인한다.
