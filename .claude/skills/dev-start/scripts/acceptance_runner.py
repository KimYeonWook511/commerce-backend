from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path


def extract_acceptance_commands(step_text: str) -> list[str]:
    match = re.search(
        r"^## Acceptance Criteria\s*$\n(?P<body>.*?)(?=^## |\Z)",
        step_text,
        re.MULTILINE | re.DOTALL,
    )
    if not match:
        return []

    commands: list[str] = []
    for block in re.findall(r"```(?:bash|sh)\n(.*?)```", match.group("body"), re.DOTALL):
        for raw_line in block.splitlines():
            line = raw_line.strip()
            if line and not line.startswith("#"):
                commands.append(line)
    return commands


def output_path(phase_dir: Path, step_num: int) -> Path:
    return phase_dir / f"step{step_num}-ac-output.json"


def run(root: str, phase_dir: Path, write_json, step: dict, step_text: str) -> dict | None:
    commands = extract_acceptance_commands(step_text)
    if not commands:
        return None

    results: list[dict] = []
    passed = True
    for command in commands:
        completed = subprocess.run(
            command,
            cwd=root,
            shell=True,
            capture_output=True,
            text=True,
        )
        result = {
            "command": command,
            "exitCode": completed.returncode,
            "stdout": completed.stdout,
            "stderr": completed.stderr,
        }
        results.append(result)
        if completed.returncode != 0:
            passed = False
            break

    payload = {
        "step": step["step"],
        "commands": commands,
        "results": results,
        "passed": passed and len(results) == len(commands),
    }
    write_json(output_path(phase_dir, step["step"]), payload)
    return payload


def load_output(path: Path) -> dict | None:
    if not path.exists():
        return None

    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None

    return payload if isinstance(payload, dict) else None
