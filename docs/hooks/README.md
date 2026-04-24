# Codex Hooks

## 목적

이 문서는 현재 Repo에서 사용하는 Codex hook 구조를 빠르게 파악하기 위한 상위 문서다.

이 문서 자체가 개별 hook의 운영 규칙을 모두 설명하지는 않는다. 실제 정책, 차단 규칙, 검증 방법은 `docs/hooks/` 아래 개별 문서에서 관리한다.

## 현재 구조

- 기능 활성화 설정: `.codex/config.toml`
- hook 등록 파일: `.codex/hooks.json`
- hook 스크립트 파일: `.codex/hooks/*.py`
- hook 스크립트 테스트 파일: `.codex/hooks/tests/test_*.py`
- 개별 hook 정책 문서: `docs/hooks/*.md`

현재 Repo는 전역 `~/.codex/config.toml`이 아니라, Repo 내부 `.codex/config.toml`에서 hook 기능을 활성화한다.

현재 설정은 아래와 같다.

```toml
[features]
codex_hooks = true
```

Codex를 현재 Repo 루트에서 실행하면 `.codex/config.toml`과 `.codex/hooks.json`을 함께 읽어 hook을 적용한다.

## 현재 사용 중인 hook

- `PreToolUse / Bash`
  - 목적: 위험한 Bash 명령을 실행 전에 한 번 더 차단
  - 정책 문서: `docs/hooks/pre-tool-use-policy.md`
