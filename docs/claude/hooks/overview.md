# Claude Code Hooks

## 목적

이 문서는 현재 Repo에서 사용하는 Claude Code hook 구조를 빠르게 파악하기 위한 상위 인덱스다.

이 문서 자체가 개별 hook의 운영 규칙을 모두 설명하지는 않는다. 실제 정책·차단 규칙·검증 방법은 `docs/claude/hooks/` 아래 개별 문서에서 관리한다.

## 현재 구조

- hook 설정 파일: `.claude/settings.json` (hooks 섹션)
- PreToolUse 정책 스크립트: `.claude/hooks/pre_tool_use_policy.py` (repo 공용, 브랜치 인식)
- 정책 테스트: `.claude/hooks/tests/test_*.py`
- 로깅 스크립트: `.claude/skills/spec-harness-v1/scripts/hooks/{log_progress,log_stop}.sh` (spec-harness-v1 스킬 소속)
- 개별 hook 정책 문서: `docs/claude/hooks/*.md`

현재 설정 개요:

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          { "type": "command", "command": "python3 \"$CLAUDE_PROJECT_DIR/.claude/hooks/pre_tool_use_policy.py\"", "timeout": 10 }
        ]
      }
    ],
    "SubagentStop": [
      {
        "matcher": "spec-harness-v1-developer|spec-harness-v1-reviewer|spec-harness-v1-committer|spec-harness-v1-recorder|spec-harness-v1-finalizer",
        "hooks": [
          { "type": "command", "command": "$CLAUDE_PROJECT_DIR/.claude/skills/spec-harness-v1/scripts/hooks/log_stop.sh" }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "Bash|Edit|Write|Read|Grep|Glob",
        "hooks": [
          { "type": "command", "command": "$CLAUDE_PROJECT_DIR/.claude/skills/spec-harness-v1/scripts/hooks/log_progress.sh" }
        ]
      }
    ]
  }
}
```

## 현재 사용 중인 hook

- **PreToolUse / Bash**
  - 목적: 보호 브랜치(main/develop)에 대한 직접 push·commit·머지·파괴 명령을 실행 전 차단(브랜치 인식 정책, agent_type 분기 없음). 피처 브랜치는 제약하지 않는다.
  - 정책 문서: `docs/claude/hooks/pre-tool-use-policy.md`
  - 비고: 로컬 1차 가드레일이며 강제 장치가 아니다. "무조건 PR로만"의 진짜 보장은 서버 측 branch protection이다.

- **PostToolUse / Bash·Edit·Write·Read·Grep·Glob** (실시간 로깅)
  - 목적: spec-harness-v1 sub-agent가 도구를 쓸 때마다 transcript의 새로 확정된 부분만 `<phase>/logs/<role>.log`에 증분 append
  - 스크립트: `.claude/skills/spec-harness-v1/scripts/hooks/log_progress.sh`
  - 비고: 백그라운드 프로세스 없음(좀비 0). 상태파일 + requestId dedup + footer 가드로 중복 0(재기상해도 재덤프 안 함). agent_type으로 harness sub-agent만 자체 필터. 로그 위치는 `cwd`→git 최상위(worktree)로 찾고 `$CLAUDE_PROJECT_DIR`는 fallback. 항상 exit 0.

- **SubagentStop / spec-harness-v1-*** (로깅 마무리)
  - 목적: 보류된 마지막 메시지 flush + 완료 박스(footer)
  - 스크립트: `.claude/skills/spec-harness-v1/scripts/hooks/log_stop.sh`
  - 비고: 차단이 아니라 기록용. `.harness/active-phase` 마커로 로그 경로를 정한다. 상태파일(logstate)은 **여기서 지우지 않는다**(재기상 대비 보존; 정리는 `execute.py` init/finalize/중단). 항상 exit 0.
