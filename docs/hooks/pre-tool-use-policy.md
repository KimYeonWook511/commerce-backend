# PreToolUse Bash 정책

## 목적

이 문서는 현재 Repo 전용 `PreToolUse` Bash hook의 운영 규칙을 설명한다.

현재 정책은 Codex가 Bash 명령을 실행하기 전에 대표적인 위험 명령 패턴을 한 번 더 차단하는 최소 방어선 역할을 한다.

## 적용 범위

- 이 정책은 현재 Repo에서만 적용된다.
- hook 설정 파일은 `.codex/hooks.json`이다.
- 정책 스크립트는 `.codex/hooks/pre_tool_use_policy.py`이다.

## 현재 차단 규칙

현재 정책은 아래 대표 패턴을 차단한다.

- `git reset --hard`
- `git checkout -- ...`
- `rm -rf ...`
- `rm -fr ...`
- `rm --recursive --force ...`
- `git push --force ...`
- `git push --force-with-lease ...`
- `git push -f ...`

차단 기준은 명령 문자열 자체가 아니라 shell token 기준으로 검사한다. `sudo`, `command`, `env FOO=bar ...` 같은 prefix가 있어도 실제 명령이 위 규칙에 해당하면 차단한다.

현재 정책은 최소 방어선이다. 아래처럼 위험할 수 있는 다른 명령까지 모두 차단하는 것은 아니다.

- `git restore ...`
- `find ... -delete`
- SQL 실행 도구를 통한 `DROP TABLE`

## 허용 예시

아래 같은 일반적인 조회 및 검증 명령은 허용한다.

- `ls -la`
- `rg hooks .codex`
- `sed -n '1,80p' docs/hooks/pre-tool-use-policy.md`
- `./gradlew test`

## 동작 방식

Codex가 Bash 실행을 시도하면 `PreToolUse` hook이 `tool_input.command`를 읽는다.

- 차단 대상이 아니면 아무 출력 없이 성공 종료한다.
- 차단 대상이면 `deny` 응답 JSON을 반환해서 Codex가 해당 명령을 실행하지 않게 한다.
- 입력 JSON이 깨졌거나 payload 타입, `tool_input`, `command` 형식이 예상과 다르면 fail-open으로 처리한다.

## 로컬 검증

정책 스크립트 테스트는 아래 명령으로 실행할 수 있다.

```bash
python3 .codex/hooks/tests/test_pre_tool_use_policy.py
```

실제 Codex 동작 검증은 현재 Repo 루트에서 Codex를 실행한 뒤 허용 명령과 차단 명령을 각각 한 번씩 실행해 확인한다.
