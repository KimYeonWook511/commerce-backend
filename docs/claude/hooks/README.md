# Claude Code Hooks

## 목적

이 문서는 현재 Repo에서 사용하는 Claude Code hook 구조를 빠르게 파악하기 위한 상위 문서다.

이 문서 자체가 개별 hook의 운영 규칙을 모두 설명하지는 않는다. 실제 정책, 차단 규칙, 검증 방법은 `docs/claude/hooks/` 아래 개별 문서에서 관리한다.

## 현재 구조

- hook 설정 파일: `.claude/settings.json` (hooks 섹션)
- hook 스크립트 파일: `.claude/hooks/*.py`
- hook 스크립트 테스트 파일: `.claude/hooks/tests/test_*.py`
- 개별 hook 정책 문서: `docs/claude/hooks/*.md`

현재 Repo는 `.claude/settings.json`에서 hook을 활성화한다.

현재 설정은 아래와 같다.

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          {
            "type": "command",
            "command": "python3 .claude/hooks/pre_tool_use_policy.py",
            "timeout": 10
          }
        ]
      }
    ]
  }
}
```

Claude Code를 현재 Repo 루트에서 실행하면 `.claude/settings.json`을 읽어 hook을 적용한다.

## 현재 사용 중인 hook

- `PreToolUse / Bash`
  - 목적: 위험한 Bash 명령을 실행 전에 한 번 더 차단
  - 정책 문서: `docs/claude/hooks/pre-tool-use-policy.md`
