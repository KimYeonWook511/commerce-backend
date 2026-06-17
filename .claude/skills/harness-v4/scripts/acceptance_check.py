from __future__ import annotations

"""harness-v4 Acceptance Criteria 검사·판정 모듈.

이 모듈의 본질은 "실행"이 아니라 "기대값과 실제값을 비교해 통과를 판정"하는 것이다. 핵심 동작:
  1. expectExit 비교: step 문서가 명령별 기대 exit code를 명시(`# expect: N`)할 수 있고,
     명시가 없으면 0이 기본값이다. → "이 파일이 없어야 한다"(exit 1 기대) 같은 역조건 AC를 표현 가능.
  2. 전 명령 실행: 첫 실패에서 멈추지 않고 모든 명령을 끝까지 돌려, 어떤 명령이 왜 깨졌는지
     전부 보고한다(developer가 한 번에 다 고치도록).
  3. attempts 누적: ac-output.json을 덮어쓰지 않고 시도별로 append 한다(감사·회고 재료).

이 모듈은 LLM·claude 세션을 일절 생성하지 않는다. 순수 subprocess로 shell 명령을
돌리고 결과를 비교할 뿐이다. (claude 세션을 띄우는 것은 별개의 agent 메커니즘이다.)
"""

import json
import re
import subprocess
from pathlib import Path

# 명령줄 바로 앞 줄(또는 같은 줄 끝)에서 기대 exit code를 읽는 주석 형식:
#   # expect: 1
#   ./gradlew :pay:test            (명시 없음 → 기본 0)
_EXPECT_PATTERN = re.compile(r"#\s*expect\s*:\s*(\d+)\s*$", re.IGNORECASE)

DEFAULT_EXPECT_EXIT = 0


def extract_acceptance_commands(step_text: str) -> list[dict]:
    """step 문서의 `## Acceptance Criteria` 섹션에서 (command, expect_exit) 목록을 뽑는다.

    반환: [{"command": str, "expect_exit": int}, ...]

    파싱 규칙:
      - `## Acceptance Criteria` 헤더부터 다음 `## ` 헤더(또는 문서 끝)까지가 본문.
      - 그 안의 ```bash / ```sh 코드블록의 각 줄이 명령 후보.
      - 빈 줄은 무시. `# expect: N` 형태의 주석은 "바로 다음 명령"의 기대 exit를 지정한다.
      - 그 외 `#` 주석은 무시한다.
      - 기대 exit 미지정 명령은 DEFAULT_EXPECT_EXIT(0).
    """
    match = re.search(
        r"^## Acceptance Criteria\s*$\n(?P<body>.*?)(?=^## |\Z)",
        step_text,
        re.MULTILINE | re.DOTALL,
    )
    if not match:
        return []

    commands: list[dict] = []
    for block in re.findall(r"```(?:bash|sh)\r?\n(.*?)```", match.group("body"), re.DOTALL):
        pending_expect = DEFAULT_EXPECT_EXIT
        has_pending = False
        for raw_line in block.splitlines():
            line = raw_line.strip()
            if not line:
                continue
            if line.startswith("#"):
                expect_match = _EXPECT_PATTERN.search(line)
                if expect_match:
                    pending_expect = int(expect_match.group(1))
                    has_pending = True
                # expect 주석이 아니면 일반 주석 → 무시
                continue
            # 같은 줄 끝에 붙은 `# expect: N`도 허용
            inline = _EXPECT_PATTERN.search(line)
            if inline:
                expect_exit = int(inline.group(1))
                command = line[: inline.start()].strip()
            else:
                expect_exit = pending_expect if has_pending else DEFAULT_EXPECT_EXIT
                command = line
            if command:
                commands.append({"command": command, "expect_exit": expect_exit})
            pending_expect = DEFAULT_EXPECT_EXIT
            has_pending = False
    return commands


def output_path(phase_dir: Path, step_num: int) -> Path:
    return phase_dir / f"step{step_num}-ac-output.json"


def check(root: str, phase_dir: Path, write_json, step: dict, step_text: str, attempt: int) -> dict | None:
    """AC 명령을 전부 실행하고 expectExit와 비교해 판정한다. attempts에 누적 기록.

    반환: 이번 attempt의 결과 dict (AC가 없으면 None).
      {
        "step": N, "attempt": M, "passed": bool,
        "results": [{"command", "expectExit", "actualExit", "ok", "stdout", "stderr"}, ...]
      }
    """
    commands = extract_acceptance_commands(step_text)
    if not commands:
        return None

    results: list[dict] = []
    passed = True
    for entry in commands:
        command = entry["command"]
        expect_exit = entry["expect_exit"]
        completed = subprocess.run(
            command,
            cwd=root,
            shell=True,
            capture_output=True,
            text=True,
        )
        ok = completed.returncode == expect_exit
        results.append({
            "command": command,
            "expectExit": expect_exit,
            "actualExit": completed.returncode,
            "ok": ok,
            "stdout": completed.stdout,
            "stderr": completed.stderr,
        })
        if not ok:
            passed = False
            # 첫 실패에서 멈추지 않는다 — 나머지 명령도 돌려 전체 실패 상황을 한 번에 보고.

    attempt_record = {
        "step": step["step"],
        "attempt": attempt,
        "passed": passed,
        "results": results,
    }

    # attempts 누적 (덮어쓰기 아님)
    path = output_path(phase_dir, step["step"])
    existing = load_output(path) or {"step": step["step"], "attempts": []}
    if not isinstance(existing.get("attempts"), list):
        existing = {"step": step["step"], "attempts": []}
    existing["attempts"].append(attempt_record)
    write_json(path, existing)

    return attempt_record


def load_output(path: Path) -> dict | None:
    if not path.exists():
        return None
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    return payload if isinstance(payload, dict) else None
