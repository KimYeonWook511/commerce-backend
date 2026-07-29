# Claude Code Hooks

## 목적

이 문서는 현재 Repo에서 사용하는 Claude Code hook 구조를 빠르게 파악하기 위한 상위 인덱스다.

이 문서 자체가 개별 hook의 운영 규칙을 모두 설명하지는 않는다. 실제 정책·차단 규칙·검증 방법은 `docs/claude/hooks/` 아래 개별 문서에서 관리한다.

## 현재 구조

- hook 설정 파일: `.claude/settings.json` (hooks 섹션)
- PreToolUse 정책 스크립트: `.claude/hooks/pre_tool_use_policy.py` (repo 공용, 브랜치 인식)
- 정책 테스트: `.claude/hooks/tests/test_*.py`
- 개별 hook 정책 문서: `docs/claude/hooks/*.md`

여기 적는 것은 **이 저장소가 등록한 hook**뿐이다. spec-harness 플러그인은 진행 로깅과 이른 머지 차단 hook을 자기 쪽에 등록하며, 이 저장소의 설정 파일에는 나타나지 않는다.

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
    ]
  }
}
```

## 현재 사용 중인 hook

- **PreToolUse / Bash**
  - 목적: 보호 브랜치(main/develop)에 대한 직접 push·commit·머지·파괴 명령을 실행 전 차단(브랜치 인식 정책, agent_type 분기 없음). 피처 브랜치는 제약하지 않는다.
  - 정책 문서: `docs/claude/hooks/pre-tool-use-policy.md`
  - 비고: 로컬 1차 가드레일이며 강제 장치가 아니다. "무조건 PR로만"의 진짜 보장은 서버 측 branch protection이다.
