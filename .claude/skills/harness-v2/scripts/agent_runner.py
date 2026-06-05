"""claude -p agent를 subprocess로 직접 실행하고 stream-json 출력을 로그로 분기한다.

tmux로 agent를 띄우던 v1과 달리, execute.py가 agent의 부모가 되어 proc.wait()로 완료를
정확히 감지한다(wait-for edge-trigger race 제거). stream-json stdout은 두 갈래로 흘린다.

- `{role}.raw.jsonl`: stream-json 원본. result 추출·디버깅의 단일 출처.
- `{role}.log`: format_events로 변환한 사람용. tmux pane이 tail한다.

timeout은 두지 않는다. proc.wait()는 자식이 어떻게 끝나든 OS가 부모를 깨워주므로 hang 원인
(신호 유실)이 사라진다. 다만 execute.py가 예외/중단으로 죽을 때 자식 claude가 고아로 남아
토큰을 계속 태우지 않도록, 자식을 새 프로세스 그룹으로 띄우고 finally에서 그룹째 정리한다.
"""

from __future__ import annotations

import json
import os
import signal
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path

import format_events

# 중단 핸들러(execute.py)가 실행 중인 agent 프로세스를 회수할 수 있도록 추적한다.
_CURRENT_PROC: "subprocess.Popen | None" = None


@dataclass
class AgentResult:
    exit_code: int
    result_text: str  # stream-json result 이벤트의 최종 메시지
    is_error: bool = False


def run_agent(
    *,
    prompt: str,
    model: str,
    cwd: str,
    role: str,  # "developer_agent" | "reviewer_agent" | "commit_agent" — 로그 파일명 prefix
    logs_dir: Path,
    step_num: int,
    attempt: int = 1,
    allowed_tools: "str | None" = None,
    echo: "bool | None" = None,
) -> AgentResult:
    """claude -p를 stream-json으로 실행하고 완료까지 대기한다.

    prompt는 임시 파일을 통해 stdin으로 전달한다(큰 prompt에서 파이프 deadlock 방지).
    echo가 None이면 tmux 세션 밖일 때만 포맷 로그를 콘솔로도 흘린다(degraded fallback).
    """
    if echo is None:
        echo = not os.environ.get("TMUX")
    logs_dir = Path(logs_dir)
    logs_dir.mkdir(parents=True, exist_ok=True)
    raw_path = logs_dir / f"{role}.raw.jsonl"
    log_path = logs_dir / f"{role}.log"

    header = (
        f"===== step {step_num} 시작 ====="
        if attempt <= 1
        else f"----- step {step_num} (retry {attempt}) -----"
    )
    _append(log_path, f"\n{header}\n")

    cmd = [
        "claude", "-p", "--dangerously-skip-permissions",
        "--model", model, "--output-format", "stream-json", "--verbose",
    ]
    if allowed_tools:
        cmd += ["--allowedTools", allowed_tools]

    result_text = ""
    is_error = False
    exit_code = 1

    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as prompt_file:
        prompt_file.write(prompt)
        prompt_path = Path(prompt_file.name)

    global _CURRENT_PROC
    proc = None
    try:
        with open(prompt_path, encoding="utf-8") as stdin_f:
            # start_new_session=True: 자식을 별도 프로세스 그룹 리더로 만들어
            # 중단 시 손자(도구·MCP)까지 그룹째 정리할 수 있게 한다.
            proc = subprocess.Popen(
                cmd, cwd=cwd, stdin=stdin_f,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, start_new_session=True,
            )
        _CURRENT_PROC = proc
        with open(raw_path, "a", encoding="utf-8") as raw_f, open(log_path, "a", encoding="utf-8") as log_f:
            for line in proc.stdout:
                raw_f.write(line)
                raw_f.flush()
                event = _safe_json(line)
                if event is not None and event.get("type") == "result":
                    # 이번 실행의 마지막 result 이벤트를 최종 결과로 채택한다(재실행 누적 대비).
                    result_text = event.get("result", "") or result_text
                    is_error = bool(event.get("is_error")) or event.get("subtype") not in (None, "success")
                formatted = format_events.format_line(line)
                if formatted is not None:
                    log_f.write(formatted + "\n")
                    log_f.flush()
                    if echo:
                        print(formatted, flush=True)
        exit_code = proc.wait()
    finally:
        _terminate(proc)
        _CURRENT_PROC = None
        prompt_path.unlink(missing_ok=True)
        _append(log_path, f"===== step {step_num} 완료 (exit={exit_code}) =====\n")

    return AgentResult(exit_code=exit_code, result_text=result_text, is_error=is_error)


def terminate_current() -> None:
    """실행 중인 agent 프로세스를 정리한다. execute.py의 SIGINT/SIGTERM 핸들러에서 호출한다."""
    _terminate(_CURRENT_PROC)


def _terminate(proc: "subprocess.Popen | None") -> None:
    """살아있는 자식을 프로세스 그룹째 종료한다. 정상 완료된 proc에는 아무 일도 하지 않는다."""
    if proc is None or proc.poll() is not None:
        return
    _killpg(proc, signal.SIGTERM)
    try:
        proc.wait(timeout=5)
        return
    except subprocess.TimeoutExpired:
        pass
    _killpg(proc, signal.SIGKILL)
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass


def _killpg(proc: "subprocess.Popen", sig: int) -> None:
    try:
        os.killpg(os.getpgid(proc.pid), sig)
    except (ProcessLookupError, PermissionError, OSError):
        pass


def _safe_json(line: str) -> "dict | None":
    line = line.strip()
    if not line:
        return None
    try:
        obj = json.loads(line)
    except (json.JSONDecodeError, ValueError):
        return None
    return obj if isinstance(obj, dict) else None


def _append(path: Path, text: str) -> None:
    with open(path, "a", encoding="utf-8") as f:
        f.write(text)
