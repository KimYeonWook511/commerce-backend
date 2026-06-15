# Claude Code Hooks

## 목적

이 문서는 현재 Repo에서 사용하는 Claude Code hook 구조를 빠르게 파악하기 위한 상위 인덱스다.

이 문서 자체가 개별 hook의 운영 규칙을 모두 설명하지는 않는다. 실제 정책·차단 규칙·검증 방법은 `docs/claude/hooks/` 아래 개별 문서에서 관리한다.

## 현재 구조

- hook 설정 파일: `.claude/settings.json` (hooks 섹션)
- PreToolUse 정책 스크립트: `.claude/hooks/pre_tool_use_policy.py` (repo 공용)
- 정책 테스트: `.claude/hooks/tests/test_*.py`
- SubagentStop 로깅 스크립트: `.claude/skills/harness-v3/scripts/hooks/log_stop.sh` (harness 스킬 소속)
- 개별 hook 정책 문서: `docs/claude/hooks/*.md`

현재 설정 개요:

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash|Write",
        "hooks": [
          { "type": "command", "command": "python3 \"$CLAUDE_PROJECT_DIR/.claude/hooks/pre_tool_use_policy.py\"", "timeout": 10 }
        ]
      }
    ],
    "SubagentStop": [
      {
        "matcher": "harness-v3-developer|harness-v3-reviewer|harness-v3-committer",
        "hooks": [
          { "type": "command", "command": "$CLAUDE_PROJECT_DIR/.claude/skills/harness-v3/scripts/hooks/log_stop.sh" }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "Bash|Edit|Write|Read|Grep|Glob",
        "hooks": [
          { "type": "command", "command": "$CLAUDE_PROJECT_DIR/.claude/skills/harness-v3/scripts/hooks/log_progress.sh" }
        ]
      }
    ]
  }
}
```

## 현재 사용 중인 hook

- **PreToolUse / Bash·Write**
  - 목적: 위험한 Bash 명령 차단(공용 블랙리스트) + harness-v3 sub-agent 역할별 제약(committer 화이트리스트, reviewer Write 가드)
  - 정책 문서: `docs/claude/hooks/pre-tool-use-policy.md`
  - 비고: sub-agent 차단은 플랫폼에 따라 무시될 수 있어(이슈 #40580) 2차 방어로 운용한다.

- **PostToolUse / Bash·Edit·Write·Read·Grep·Glob** (실시간 로깅)
  - 목적: harness sub-agent가 도구를 쓸 때마다 transcript의 새로 확정된 부분만 `<phase>/logs/<role>.log`에 증분 append
  - 스크립트: `.claude/skills/harness-v3/scripts/hooks/log_progress.sh`
  - 비고: 백그라운드 프로세스 없음(좀비 0). 상태파일 + requestId dedup으로 중복 0. agent_type으로 harness sub-agent만 자체 필터. 항상 exit 0.

- **SubagentStop / harness-v3-*** (로깅 마무리)
  - 목적: 보류된 마지막 메시지 flush + 완료 박스 + 상태파일(`.harness/logstate-<id>.json`) 정리
  - 스크립트: `.claude/skills/harness-v3/scripts/hooks/log_stop.sh`
  - 비고: 차단이 아니라 기록용. `.harness/active-phase` 마커로 로그 경로를 정한다. 항상 exit 0.
