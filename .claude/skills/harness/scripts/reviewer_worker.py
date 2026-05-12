from __future__ import annotations

import re
import subprocess
import tempfile
import uuid
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class ReviewResult:
    decision: str
    message: str = ""
    raw: str = ""


VALID_DECISIONS = {"pass", "retryable_error", "blocked"}


def build_prompt(
    guardrails_text: str,
    step: dict,
    step_text: str,
    changed_paths: list[str],
    output: dict,
    ac_output: dict | None = None,
) -> str:
    changed = "\n".join(f"- {path}" for path in changed_paths) if changed_paths else "- 변경 파일 없음"
    summary = step.get("summary", "")
    output_summary = (
        f"step={output.get('step')}\n"
        f"name={output.get('name')}\n"
        f"exitCode={output.get('exitCode')}\n"
        f"stdout={truncate(output.get('stdout', ''))}\n"
        f"stderr={truncate(output.get('stderr', ''))}\n"
        f"lastMessage={truncate(output.get('lastMessage', ''))}\n"
    )
    ac_summary = build_ac_summary(ac_output)
    return (
        f"{guardrails_text}\n\n---\n\n"
        f"## Step 상태\nstep={step.get('step')}\nname={step.get('name')}\nstatus={step.get('status')}\nsummary={summary}\n\n"
        f"## 변경 경로\n{changed}\n\n"
        f"## Step 문서\n{step_text}\n\n"
        f"## 실행 출력 요약\n{output_summary}\n"
        f"## Acceptance Criteria 재검증 요약\n{ac_summary}\n"
    )


def parse_review_result(raw: str) -> ReviewResult:
    decision_match = re.search(r"^DECISION:\s*(pass|retryable_error|blocked)\s*$", raw, re.MULTILINE)
    message_match = re.search(r"^MESSAGE:\s*(.+)$", raw, re.MULTILINE)
    if not decision_match or not message_match:
        return ReviewResult("retryable_error", "reviewer worker 출력 형식이 올바르지 않습니다.", raw)

    decision = decision_match.group(1)
    if decision not in VALID_DECISIONS:
        return ReviewResult("retryable_error", "reviewer worker decision이 유효하지 않습니다.", raw)

    return ReviewResult(decision, message_match.group(1).strip(), raw)


def output_path(phase_dir: Path, step_num: int) -> Path:
    return phase_dir / f"step{step_num}-review-output.json"


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


def run_claude_in_pane(root: str, session: str, pane_name: str, prompt_path: Path, output_file: Path, cwd: str | None = None) -> int:
    """tmux pane을 생성하고 claude -p (read-only 모드)를 실행한다."""
    done_signal = f"{pane_name}-done-{uuid.uuid4().hex[:8]}"
    # reviewer는 파일 수정 없이 read-only 검토만 수행한다
    # --allowedTools Read,Grep,Glob 으로 write 도구를 제한한다
    cd_prefix = f"cd {cwd} && " if cwd else ""
    cmd = (
        f"{cd_prefix}claude -p --dangerously-skip-permissions"
        f" --allowedTools 'Read,Grep,Glob,Bash'"
        f" < {prompt_path}"
        f" > {output_file}"
        f" 2>&1"
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

    subprocess.run(
        ["tmux", "kill-window", "-t", f"{session}:{pane_name}"],
        capture_output=True,
    )
    return 0


def run(
    root: str,
    phase_dir: Path,
    write_json,
    step: dict,
    step_text: str,
    changed_paths: list[str],
    output: dict,
    ac_output: dict | None,
    guardrails_text: str,
) -> ReviewResult:
    """reviewer worker를 tmux pane에서 실행하고 review output 파일을 기록한다."""
    prompt = build_prompt(guardrails_text, step, step_text, changed_paths, output, ac_output)

    session = "harness"
    pane_name = f"step{step['step']}-reviewer"
    ensure_tmux_session(session)

    with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False, encoding="utf-8") as prompt_file:
        prompt_file.write(prompt)
        prompt_path = Path(prompt_file.name)

    raw_output_path = phase_dir / f"step{step['step']}-review-raw.txt"

    try:
        run_claude_in_pane(root, session, pane_name, prompt_path, raw_output_path, cwd=root)
        last_message = raw_output_path.read_text(encoding="utf-8") if raw_output_path.exists() else ""
    finally:
        prompt_path.unlink(missing_ok=True)
        if raw_output_path.exists():
            raw_output_path.unlink(missing_ok=True)

    raw_output = {
        "step": step["step"],
        "name": step["name"],
        "exitCode": 0,
        "stdout": last_message,
        "stderr": "",
        "lastMessage": last_message,
        "changedPaths": changed_paths,
        "reviewMode": "repo-read-only",
        "acceptanceOutput": ac_output,
    }
    write_json(output_path(phase_dir, step["step"]), raw_output)

    return parse_review_result(last_message)


def truncate(value: object, limit: int = 800) -> str:
    text = str(value or "")
    if len(text) <= limit:
        return text
    return text[:limit] + "\n...[truncated]"


def build_ac_summary(ac_output: dict | None) -> str:
    if not ac_output:
        return "Acceptance Criteria 출력 없음\n"

    lines = [
        f"step={ac_output.get('step')}",
        f"passed={ac_output.get('passed')}",
    ]
    commands = ac_output.get("commands")
    if isinstance(commands, list):
        lines.append("commands=" + ", ".join(str(command) for command in commands))

    results = ac_output.get("results")
    if isinstance(results, list):
        for index, result in enumerate(results):
            if not isinstance(result, dict):
                continue
            lines.append(f"result[{index}]: command={result.get('command')} exitCode={result.get('exitCode')}")
            stderr = result.get("stderr")
            if stderr:
                lines.append(f"result[{index}].stderr={truncate(stderr)}")

    return "\n".join(lines) + "\n"
