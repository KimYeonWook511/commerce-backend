# PreToolUse Bash 정책

## 목적

이 문서는 현재 Repo 전용 Claude Code `PreToolUse` Bash hook의 운영 규칙을 설명한다.

현재 정책은 Claude Code가 Bash 명령을 실행하기 전에 대표적인 위험 명령 패턴을 한 번 더 차단하는 최소 방어선 역할을 한다.

## 적용 범위

- 이 정책은 현재 Repo에서만 적용된다.
- hook 설정 파일은 `.claude/settings.json`이다.
- 정책 스크립트는 `.claude/hooks/pre_tool_use_policy.py`이다.

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

`&&`, `||`, `;`로 연결된 복합 명령도 각 명령을 개별적으로 검사한다. 예를 들어 `git commit -m "fix" && git push --force`는 두 번째 명령인 `git push --force`가 위 규칙에 해당하므로 차단한다. 따옴표 안의 `&&`, `;`는 분리 대상에서 제외된다.

현재 정책은 최소 방어선이다. 아래처럼 위험할 수 있는 다른 명령까지 모두 차단하는 것은 아니다.

- `git restore ...`
- `find ... -delete`
- SQL 실행 도구를 통한 `DROP TABLE`

## 허용 예시

아래 같은 일반적인 조회 및 검증 명령은 허용한다.

- `ls -la`
- `rg hooks .claude`
- `sed -n '1,80p' docs/claude/hooks/pre-tool-use-policy.md`
- `./gradlew test`

## 동작 방식

Claude Code가 Bash 실행을 시도하면 `PreToolUse` hook이 stdin으로 JSON payload를 받는다.

- `tool_name`이 `Bash`이면 `tool_input.command`를 읽는다.
- `&&`, `||`, `;`로 연결된 복합 명령은 개별 명령으로 분리한 뒤 각각 검사한다.
- 하나라도 차단 대상이면 `decision: block`과 차단 사유를 포함한 JSON을 stdout에 출력한다.
- 모두 통과하면 exit 0으로 성공 종료한다.
- 입력 JSON이 깨졌거나 payload 타입, `tool_input`, `command` 형식이 예상과 다르면 fail-open으로 처리한다.

## Claude Code hook 응답 형식

```json
{
  "decision": "block",
  "reason": "Repo Bash 명령어 정책에 따라 `git reset --hard`는 차단됩니다."
}
```

## 로컬 검증

정책 스크립트 테스트는 아래 명령으로 실행할 수 있다.

```bash
python3 .claude/hooks/tests/test_pre_tool_use_policy.py
```

실제 Claude Code 동작 검증은 현재 Repo 루트에서 Claude Code를 실행한 뒤 허용 명령과 차단 명령을 각각 한 번씩 실행해 확인한다.
