from __future__ import annotations

import json
import re
from pathlib import Path

import agent_runner

# developer agent가 응답 끝에 남기는 시행착오 블록 마커
STRUGGLES_PATTERN = re.compile(
    r"<<<STRUGGLES>>>(?P<body>.*?)<<<END STRUGGLES>>>",
    re.DOTALL,
)


def extract_struggles(last_message: str) -> str | None:
    """agent 응답에서 시행착오 블록을 추출한다. 없거나 '없음'이면 None."""
    match = STRUGGLES_PATTERN.search(last_message or "")
    if not match:
        return None
    body = match.group("body").strip()
    if not body or body.replace("-", "").strip() in {"", "없음"}:
        return None
    return body


def load_existing_attempts(output_path: Path) -> list[dict]:
    """기존 output.json이 있으면 누적된 attempts 배열을 읽어 반환한다."""
    if not output_path.exists():
        return []
    try:
        payload = json.loads(output_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return []
    attempts = payload.get("attempts") if isinstance(payload, dict) else None
    return attempts if isinstance(attempts, list) else []


def build_prompt(context_text: str, guardrails_text: str, step_text: str) -> str:
    sections = [section for section in (context_text, guardrails_text, step_text) if section]
    return "\n\n---\n\n".join(sections)


def run(
    root: str,
    phase_dir: Path,
    write_json,
    step: dict,
    context_text: str,
    guardrails_text: str,
    model: str = "sonnet",
    attempt: int = 1,
    max_retries: "int | None" = None,
) -> dict:
    """developer agent를 subprocess로 실행하고 step output 파일을 기록한다."""
    step_num = step["step"]
    step_name = step["name"]
    step_file = phase_dir / f"step{step_num}.md"

    if not step_file.exists():
        print(f"  ERROR: {step_file} not found")
        raise SystemExit(1)

    prompt = build_prompt(context_text, guardrails_text, step_file.read_text(encoding="utf-8"))

    result = agent_runner.run_agent(
        prompt=prompt,
        model=model,
        cwd=root,
        role="developer_agent",
        logs_dir=phase_dir / "logs",
        step_num=step_num,
        step_name=step_name,
        attempt=attempt,
        max_retries=max_retries,
    )
    last_message = result.result_text
    exit_code = result.exit_code

    output_file = phase_dir / f"step{step_num}-output.json"

    # 재시도/재실행 시 과거 시도를 보존하기 위해 기존 attempts에 이어붙인다.
    attempts = load_existing_attempts(output_file)
    attempt_record = {
        "attempt": len(attempts) + 1,
        "exitCode": exit_code,
        "struggles": extract_struggles(last_message),
        "lastMessage": last_message,
    }
    attempts.append(attempt_record)

    # 최상위 키는 "가장 최근 시도"로 유지한다.
    # step_verifier / reviewer_agent가 최상위 키(exitCode, stdout, stderr, lastMessage)를
    # 그대로 읽으므로 호환을 위해 형태를 바꾸지 않는다.
    output = {
        "step": step_num,
        "name": step_name,
        "exitCode": exit_code,
        "stdout": last_message,
        "stderr": "",
        "lastMessage": last_message,
        "attempts": attempts,
    }
    write_json(output_file, output)
    return output
