from __future__ import annotations

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


def has_text(value: object) -> bool:
    return isinstance(value, str) and bool(value.strip())


def verify_step_result_v3(step: dict, step_text: str, dev_handoff: dict, ac_output_path: Path | None) -> VerificationResult:
    """harness-v3: dev 핸드오프(구조) + acceptance 재실행 결과(객관 게이트)로 step을 검증한다.

    - 구조 검사(자기보고, 약한 신호): ok(bool) / summary(text) / ok==True
    - 진짜 게이트(객관): Acceptance 명령이 있으면 재실행 결과가 passed 여야 함
    """
    if not isinstance(dev_handoff.get("ok"), bool):
        return VerificationResult("retryable_error", "dev 핸드오프에 ok(bool)가 없습니다.")
    if not has_text(dev_handoff.get("summary")):
        return VerificationResult("retryable_error", "dev 핸드오프에 summary가 없습니다.")
    if dev_handoff["ok"] is not True:
        return VerificationResult("retryable_error", dev_handoff.get("struggles") or "developer가 실패를 보고했습니다.")

    ac_commands = acceptance_runner.extract_acceptance_commands(step_text)
    if ac_commands and ac_output_path is not None:
        ac = acceptance_runner.load_output(ac_output_path)
        if ac is None:
            return VerificationResult("retryable_error", f"{ac_output_path.name}이 없거나 형식이 올바르지 않습니다.")
        if not isinstance(ac.get("passed"), bool) or not isinstance(ac.get("results"), list):
            return VerificationResult("retryable_error", f"{ac_output_path.name}의 형식이 올바르지 않습니다.")
        for result in ac["results"]:
            if not isinstance(result, dict) or not isinstance(result.get("exitCode"), int):
                return VerificationResult("retryable_error", f"{ac_output_path.name}의 results 형식이 올바르지 않습니다.")
        if not ac["passed"]:
            return VerificationResult("retryable_error", "Acceptance Criteria 재검증이 실패했습니다.")

    return VerificationResult("pass")
