from __future__ import annotations

import re
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class ReviewResult:
    decision: str
    message: str = ""
    raw: str = ""


VALID_DECISIONS = {"pass", "retryable_error", "blocked"}


def build_prompt(guardrails_text: str, step: dict, step_text: str, changed_paths: list[str], diff_text: str, output: dict) -> str:
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
    return (
        f"{guardrails_text}\n\n---\n\n"
        f"## Step 상태\nstep={step.get('step')}\nname={step.get('name')}\nstatus={step.get('status')}\nsummary={summary}\n\n"
        f"## 변경 경로\n{changed}\n\n"
        f"## 변경 diff\n{diff_text or '(diff 없음)'}\n\n"
        f"## Step 문서\n{step_text}\n\n"
        f"## 실행 출력 요약\n{output_summary}\n"
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


def run(
    root: str,
    phase_dir: Path,
    write_json,
    step: dict,
    step_text: str,
    changed_paths: list[str],
    diff_text: str,
    output: dict,
    guardrails_text: str,
) -> ReviewResult:
    """reviewer worker를 실행하고 review output 파일을 기록한다."""
    prompt = build_prompt(guardrails_text, step, step_text, changed_paths, diff_text, output)
    with tempfile.NamedTemporaryFile(delete=False) as message_file:
        message_path = Path(message_file.name)

    try:
        result = subprocess.run(
            [
                "codex",
                "exec",
                "-s",
                "read-only",
                "--skip-git-repo-check",
                "--color",
                "never",
                "-C",
                root,
                "-o",
                str(message_path),
                "-",
            ],
            input=prompt,
            cwd=root,
            capture_output=True,
            text=True,
            timeout=1800,
        )
        last_message = message_path.read_text(encoding="utf-8") if message_path.exists() else ""
    finally:
        message_path.unlink(missing_ok=True)

    raw_output = {
        "step": step["step"],
        "name": step["name"],
        "exitCode": result.returncode,
        "stdout": result.stdout,
        "stderr": result.stderr,
        "lastMessage": last_message,
        "changedPaths": changed_paths,
        "diffText": diff_text,
    }
    write_json(output_path(phase_dir, step["step"]), raw_output)

    if result.returncode != 0:
        return ReviewResult(
            "retryable_error",
            f"reviewer worker가 비정상 종료되었습니다 (code {result.returncode}).",
            last_message,
        )

    return parse_review_result(last_message)


def truncate(value: object, limit: int = 800) -> str:
    text = str(value or "")
    if len(text) <= limit:
        return text
    return text[:limit] + "\n...[truncated]"
