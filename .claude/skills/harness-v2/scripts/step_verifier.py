from __future__ import annotations

import json
import sys
from dataclasses import dataclass
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import acceptance_runner


@dataclass(frozen=True)
class VerificationResult:
    decision: str
    message: str = ""


VALID_STATUSES = {"pending", "completed", "error", "blocked"}


def verify_step_result(step: dict, step_text: str, output_path: Path, ac_output_path: Path | None = None) -> VerificationResult:
    status = step.get("status")
    if status not in VALID_STATUSES:
        return VerificationResult("retryable_error", "step status가 유효하지 않습니다.")

    if status == "completed" and not has_text(step.get("summary")):
        return VerificationResult("retryable_error", "completed 상태에는 summary가 필요합니다.")

    if status == "blocked" and not has_text(step.get("blocked_reason")):
        return VerificationResult("retryable_error", "blocked 상태에는 blocked_reason이 필요합니다.")

    if status == "error" and not has_text(step.get("error_message")):
        return VerificationResult("retryable_error", "error 상태에는 error_message가 필요합니다.")

    if status == "pending" and has_text(step.get("error_message")):
        return VerificationResult("retryable_error", step["error_message"].strip())

    output = load_step_output(output_path)
    if output is None:
        return VerificationResult("retryable_error", f"{output_path.name}이 없거나 형식이 올바르지 않습니다.")

    exit_code = output.get("exitCode")
    if not isinstance(exit_code, int):
        return VerificationResult("retryable_error", f"{output_path.name}의 exitCode가 올바르지 않습니다.")

    if status == "pending":
        return VerificationResult("retryable_error", "Step이 status를 completed, error, blocked 중 하나로 갱신하지 않았습니다.")

    if status == "completed" and exit_code != 0:
        return VerificationResult("retryable_error", "Codex 실행 exitCode가 0이 아닌데 step이 completed로 기록되었습니다.")

    ac_commands = acceptance_runner.extract_acceptance_commands(step_text)
    if status == "completed" and ac_commands and ac_output_path is not None:
        ac_output = acceptance_runner.load_output(ac_output_path)
        if ac_output is None:
            return VerificationResult("retryable_error", f"{ac_output_path.name}이 없거나 형식이 올바르지 않습니다.")
        if not isinstance(ac_output.get("passed"), bool):
            return VerificationResult("retryable_error", f"{ac_output_path.name}의 passed 값이 올바르지 않습니다.")
        if not isinstance(ac_output.get("results"), list):
            return VerificationResult("retryable_error", f"{ac_output_path.name}의 results 값이 올바르지 않습니다.")
        for result in ac_output["results"]:
            if not isinstance(result, dict):
                return VerificationResult("retryable_error", f"{ac_output_path.name}의 results 형식이 올바르지 않습니다.")
            if not isinstance(result.get("exitCode"), int):
                return VerificationResult("retryable_error", f"{ac_output_path.name}의 exitCode가 올바르지 않습니다.")
        if not ac_output["passed"]:
            return VerificationResult("retryable_error", "Acceptance Criteria 재검증이 실패했습니다.")

    return VerificationResult("pass")


def has_text(value: object) -> bool:
    return isinstance(value, str) and bool(value.strip())


def load_step_output(path: Path) -> dict | None:
    if not path.exists():
        return None

    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None

    return payload if isinstance(payload, dict) else None
