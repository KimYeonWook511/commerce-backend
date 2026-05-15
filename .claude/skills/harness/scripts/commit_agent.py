from __future__ import annotations

import subprocess
import tempfile
import uuid
from pathlib import Path


def build_prompt(step_name: str, summary: str) -> str:
    return (
        "당신은 커밋 담당 agent입니다.\n\n"
        "현재 step 작업이 완료됐습니다.\n"
        f"- step 이름: {step_name}\n"
        f"- developer summary: {summary}\n\n"
        "아래를 수행하세요:\n"
        "1. docs/commit-conventions.md를 읽어 커밋 컨벤션을 파악한다.\n"
        "2. git status / git diff로 실제 변경 내용을 직접 확인한다.\n"
        "3. 변경 내용과 컨벤션을 바탕으로 적절한 커밋 단위와 메시지를 스스로 판단한다.\n"
        "4. git add + git commit을 수행한다.\n\n"
        "금지사항:\n"
        "- git push, git pull, 브랜치 생성/변경/삭제는 절대 하지 마라.\n"
        "- commit 외 다른 git 작업은 수행하지 마라.\n"
    )


def ensure_tmux_session(session: str):
    result = subprocess.run(["tmux", "has-session", "-t", session], capture_output=True)
    if result.returncode != 0:
        subprocess.run(["tmux", "new-session", "-d", "-s", session], capture_output=True)


def run(root: str, phase_dir: Path, step: dict) -> None:
    """commit agent를 tmux pane에서 실행한다."""
    step_num = step["step"]
    step_name = step.get("name", f"step{step_num}")
    summary = str(step.get("summary", "")).strip()

    prompt = build_prompt(step_name, summary)

    session = "harness"
    pane_name = f"step{step_num}-commit"
    ensure_tmux_session(session)

    with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False, encoding="utf-8") as f:
        f.write(prompt)
        prompt_path = Path(f.name)

    output_path = phase_dir / f"step{step_num}-commit-output.txt"
    done_signal = f"{pane_name}-done-{uuid.uuid4().hex[:8]}"

    cmd = (
        f"cd {root} && claude -p --dangerously-skip-permissions"
        f" --allowedTools 'Bash(git *) Read'"
        f" < {prompt_path}"
        f" > {output_path}"
        f" 2>&1"
        f"; tmux wait-for -S {done_signal}"
    )

    try:
        subprocess.run(["tmux", "new-window", "-t", session, "-n", pane_name], capture_output=True)
        subprocess.run(["tmux", "send-keys", "-t", f"{session}:{pane_name}", cmd, "Enter"])
        subprocess.run(["tmux", "wait-for", done_signal])
        subprocess.run(["tmux", "kill-window", "-t", f"{session}:{pane_name}"], capture_output=True)
    finally:
        prompt_path.unlink(missing_ok=True)
