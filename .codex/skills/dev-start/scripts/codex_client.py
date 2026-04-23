from __future__ import annotations

import subprocess
import tempfile
from pathlib import Path


def invoke_codex(root: str, phase_dir: Path, write_json, step: dict, preamble: str) -> dict:
    """현재 step 문서와 preamble을 합쳐 Codex CLI를 비대화형으로 호출한다."""
    step_num = step["step"]
    step_name = step["name"]
    step_file = phase_dir / f"step{step_num}.md"

    if not step_file.exists():
        print(f"  ERROR: {step_file} not found")
        raise SystemExit(1)

    prompt = preamble + step_file.read_text(encoding="utf-8")
    with tempfile.NamedTemporaryFile(delete=False) as message_file:
        message_path = Path(message_file.name)

    try:
        result = subprocess.run(
            [
                "codex",
                "exec",
                "--full-auto",
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

    if result.returncode != 0:
        print(f"\n  WARN: Codex가 비정상 종료됨 (code {result.returncode})")
        if result.stderr:
            print(f"  stderr: {result.stderr[:500]}")

    output = {
        "step": step_num,
        "name": step_name,
        "exitCode": result.returncode,
        "stdout": result.stdout,
        "stderr": result.stderr,
        "lastMessage": last_message,
    }
    write_json(phase_dir / f"step{step_num}-output.json", output)
    return output
