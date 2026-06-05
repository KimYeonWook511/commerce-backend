from __future__ import annotations

import json
import re
import subprocess
import tempfile
import uuid
from pathlib import Path

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


def ensure_tmux_session(session: str):
    """tmux 세션이 없으면 생성한다."""
    result = subprocess.run(
        ["tmux", "has-session", "-t", session],
        capture_output=True,
    )
    if result.returncode != 0:
        subprocess.run(
            ["tmux", "new-session", "-d", "-s", session],
            capture_output=True,
        )


def run_claude_in_pane(root: str, session: str, pane_name: str, prompt_path: Path, output_path: Path, exit_code_path: Path | None = None, cwd: str | None = None, model: str = "sonnet") -> int:
    """tmux pane을 생성하고 claude -p를 실행한다. 완료까지 대기한다."""
    done_signal = f"{pane_name}-done-{uuid.uuid4().hex[:8]}"
    cd_prefix = f"cd {cwd} && " if cwd else ""
    exit_capture = f"; echo $? > {exit_code_path}" if exit_code_path else ""
    cmd = (
        f"{cd_prefix}claude -p --dangerously-skip-permissions --model {model}"
        f" < {prompt_path}"
        f" > {output_path}"
        f" 2>&1"
        f"{exit_capture}"
        f"; tmux wait-for -S {done_signal}"
    )

    subprocess.run(
        ["tmux", "new-window", "-t", session, "-n", pane_name],
        capture_output=True,
    )
    subprocess.run(
        ["tmux", "send-keys", "-t", f"{session}:{pane_name}", cmd, "Enter"],
    )
    subprocess.run(["tmux", "wait-for", done_signal])

    # 완료 후 pane 정리
    subprocess.run(
        ["tmux", "kill-window", "-t", f"{session}:{pane_name}"],
        capture_output=True,
    )
    return 0


def run(root: str, phase_dir: Path, write_json, step: dict, context_text: str, guardrails_text: str, model: str = "sonnet") -> dict:
    """developer agent를 tmux pane에서 실행하고 step output 파일을 기록한다."""
    step_num = step["step"]
    step_name = step["name"]
    step_file = phase_dir / f"step{step_num}.md"

    if not step_file.exists():
        print(f"  ERROR: {step_file} not found")
        raise SystemExit(1)

    prompt = build_prompt(context_text, guardrails_text, step_file.read_text(encoding="utf-8"))

    session = "harness"
    pane_name = f"step{step_num}-developer"
    ensure_tmux_session(session)

    with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False, encoding="utf-8") as prompt_file:
        prompt_file.write(prompt)
        prompt_path = Path(prompt_file.name)

    output_path = phase_dir / f"step{step_num}-raw-output.txt"
    exit_code_path = phase_dir / f"step{step_num}-exit-code.txt"

    try:
        run_claude_in_pane(root, session, pane_name, prompt_path, output_path, exit_code_path, cwd=root, model=model)
        last_message = output_path.read_text(encoding="utf-8") if output_path.exists() else ""
        exit_code = int(exit_code_path.read_text(encoding="utf-8").strip()) if exit_code_path.exists() else 0
    finally:
        prompt_path.unlink(missing_ok=True)
        if output_path.exists():
            output_path.unlink(missing_ok=True)
        if exit_code_path.exists():
            exit_code_path.unlink(missing_ok=True)

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
