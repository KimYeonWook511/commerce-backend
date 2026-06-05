#!/usr/bin/env python3
"""
Harness Step Executor

`docs/tasks/<task-name>/phases/<phase-name>` 아래 step 문서를 순차 실행하고, 상태를 기록하고,
필요하면 git 브랜치/커밋/푸시까지 자동으로 처리한다.

Usage:
    python3 .claude/skills/harness-v2/scripts/execute.py docs/tasks/<task-name>/phases/<phase-name> [--no-push]
"""

from __future__ import annotations

import argparse
import atexit
import contextlib
import json
import os
import signal
import sys
import threading
import time
import types
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Optional

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import agent_runner
import commit_agent
import developer_guardrails
import developer_agent
import acceptance_runner
import git_ops
import reviewer_guardrails
import reviewer_agent
import step_context
import step_verifier

# .claude/skills/harness-v2/scripts/execute.py -> repository root
ROOT = Path(__file__).resolve().parents[4]


@contextlib.contextmanager
def progress_indicator(label: str):
    """터미널 진행 표시기. with 블록 종료 후 `elapsed`를 읽을 수 있다."""
    frames = "◐◓◑◒"
    stop = threading.Event()
    started = time.monotonic()

    def animate():
        index = 0
        while not stop.wait(0.12):
            elapsed = int(time.monotonic() - started)
            sys.stderr.write(f"\r{frames[index % len(frames)]} {label} [{elapsed}s]")
            sys.stderr.flush()
            index += 1
        sys.stderr.write("\r" + " " * (len(label) + 24) + "\r")
        sys.stderr.flush()

    thread = threading.Thread(target=animate, daemon=True)
    thread.start()
    info = types.SimpleNamespace(elapsed=0.0)
    try:
        yield info
    finally:
        stop.set()
        thread.join()
        info.elapsed = time.monotonic() - started


class StepExecutor:
    """Phase 디렉터리 안의 step들을 순차 실행하는 하네스."""

    MAX_RETRIES = 3
    TZ = timezone(timedelta(hours=9))
    WORKFLOW_ITEMS = [
        (1, "Explore"),
        (2, "Discuss"),
        (3, "Step Design"),
        (4, "Worktree 생성 및 이동"),
        (5, "File Drafting"),
        (6, "Execution"),
        (7, "PR Review"),
        (8, "Root Sync"),
        (9, "Retrospective"),
    ]

    def __init__(
        self,
        phase_path: str,
        *,
        auto_push: bool = True,
        developer_model: str = "sonnet",
        reviewer_model: str = "opus",
        commit_model: str = "haiku",
    ):
        self.root = str(ROOT)
        self.root_path = ROOT
        self.auto_push = auto_push
        self.developer_model = developer_model
        self.reviewer_model = reviewer_model
        self.commit_model = commit_model
        self.phase_dir = self.resolve_phase_dir(phase_path)
        self.phase_relpath = self.phase_dir.relative_to(ROOT).as_posix()
        self.phase_dir_name = self.phase_dir.name
        self.task_phases_dir = self.phase_dir.parent
        self.task_phases_relpath = self.task_phases_dir.relative_to(ROOT).as_posix()
        self.task_index_file = self.task_phases_dir / "index.json"
        self.task_dir = self.task_phases_dir.parent

        if not self.phase_dir.is_dir():
            print(f"ERROR: {self.phase_dir} not found")
            raise SystemExit(1)

        self.index_file = self.phase_dir / "index.json"
        if not self.index_file.exists():
            print(f"ERROR: {self.index_file} not found")
            raise SystemExit(1)

        index = self.read_json(self.index_file)
        self.project = index.get("project", "project")
        self.phase_name = index.get("phase", self.phase_dir_name)
        self.task_name = self.extract_task_name(index)
        self.total_steps = len(index["steps"])
        self.branch_name: str = ""  # _validate_worktree_context에서 실제 브랜치로 설정
        self.validate_task_phase_registration()

    def resolve_phase_dir(self, phase_path: str) -> Path:
        """저장소 기준 task phase 경로를 해석한다."""
        direct = ROOT / phase_path
        if direct.is_dir():
            return direct
        print(f"ERROR: phase 디렉토리를 찾을 수 없습니다: {direct}")
        raise SystemExit(1)

    def extract_task_name(self, index: dict) -> str:
        """phase 경로 또는 index 정보에서 task명을 추출한다."""
        parts = self.phase_dir.relative_to(ROOT).parts
        if len(parts) >= 4 and parts[0] == "docs" and parts[1] == "tasks":
            return parts[2]
        return index.get("task", self.phase_name)

    def validate_task_phase_registration(self):
        """task-level phases index가 현재 phase를 포함하는지 확인한다."""
        if not self.task_index_file.exists():
            print(f"ERROR: {self.task_index_file} not found")
            raise SystemExit(1)

        task_index = self.read_json(self.task_index_file)
        if not any(phase.get("dir") == self.phase_dir_name for phase in task_index.get("phases", [])):
            print(f"ERROR: phase '{self.phase_dir_name}' is not registered in {self.task_index_file}")
            print("Fix the task phases/index.json entry and retry.")
            raise SystemExit(1)

    def run(self):
        """실행 헤더 출력부터 전체 phase 완료 처리까지 오케스트레이션한다."""
        self._install_signal_handlers()
        self.print_header()
        self._preflight_tools()
        self.validate_workflow_checklist()
        self.validate_completed_step_artifacts()
        self.check_blockers()
        self._validate_worktree_context()
        self.mark_workflow_execution_in_progress()
        self.ensure_created_at()
        self.execute_all_steps()
        self.finalize()

    def _install_signal_handlers(self):
        """SIGINT/SIGTERM에서 실행 중인 agent 자식 프로세스를 회수한다.

        agent를 별도 프로세스 그룹으로 띄우므로(start_new_session) Ctrl+C가 claude에 직접
        전달되지 않는다. execute.py가 죽을 때 고아 claude가 토큰을 계속 태우지 않도록 명시적으로 정리한다.
        """
        def handler(signum, frame):
            agent_runner.terminate_current()
            raise SystemExit(130)
        for sig in (signal.SIGINT, signal.SIGTERM):
            signal.signal(sig, handler)
        atexit.register(agent_runner.terminate_current)

    def _preflight_tools(self):
        """claude CLI가 설치되어 있는지 확인한다. (tmux는 3-pane 로그 관찰용 선택 사항)"""
        import shutil
        if not shutil.which("claude"):
            print("\n  ERROR: 'claude'가 설치되어 있지 않습니다. execute.py 실행 전 설치하세요.")
            raise SystemExit(1)

    def _read_current_branch(self) -> str:
        """현재 브랜치명을 git에서 읽어 반환한다."""
        result = self.run_git("rev-parse", "--abbrev-ref", "HEAD")
        if result.returncode != 0:
            print("  ERROR: git을 사용할 수 없거나 git repo가 아닙니다.")
            raise SystemExit(1)
        return result.stdout.strip()

    def _validate_worktree_context(self):
        """worktree 안에서 실행 중인지, 보호 브랜치가 아닌지 확인한다."""
        git_dir_result = self.run_git("rev-parse", "--git-dir")
        if git_dir_result.returncode != 0:
            print("  ERROR: git을 사용할 수 없거나 git repo가 아닙니다.")
            raise SystemExit(1)

        git_dir = git_dir_result.stdout.strip()
        if not Path(git_dir).is_absolute():
            print("\n  ERROR: execute.py는 worktree 안에서 실행해야 합니다.")
            print("  hint: git worktree add로 생성한 worktrees/ 디렉토리 안에서 실행하세요.")
            raise SystemExit(1)

        self.branch_name = self._read_current_branch()
        protected = {"main", "master", "develop"}
        if self.branch_name in protected:
            print(f"\n  ERROR: 보호 브랜치({self.branch_name})에서 execute.py를 실행할 수 없습니다.")
            print("  hint: 작업 브랜치 worktree 안에서 실행하세요.")
            raise SystemExit(1)

    # --- workflow checklist ---

    def workflow_checklist_path(self) -> Path:
        """현재 phase의 harness workflow checklist 경로를 반환한다."""
        return self.phase_dir / "workflow-checklist.json"

    def validate_workflow_checklist(self):
        """문서 검토와 실행 승인 완료 여부를 실행 전 강제한다."""
        checklist_path = self.workflow_checklist_path()
        if not checklist_path.exists():
            print(f"\n  ERROR: {checklist_path.relative_to(ROOT).as_posix()} not found")
            print("  Create workflow-checklist.json and complete Execution Authorization before running execute.py.")
            raise SystemExit(1)

        checklist = self.read_json(checklist_path)
        if checklist.get("workflow") != "harness":
            print("\n  ERROR: workflow-checklist.json must have workflow='harness'.")
            raise SystemExit(1)
        if checklist.get("version") != "v2":
            print("\n  ERROR: workflow-checklist.json must have version='v2'.")
            raise SystemExit(1)

        items = checklist.get("items")
        if not isinstance(items, list) or len(items) != len(self.WORKFLOW_ITEMS):
            print("\n  ERROR: workflow-checklist.json must contain the nine harness workflow items.")
            raise SystemExit(1)

        invalid_items: list[str] = []
        incomplete_items: list[str] = []
        expected_by_order = dict(self.WORKFLOW_ITEMS)

        for index, (expected_order, expected_title) in enumerate(self.WORKFLOW_ITEMS):
            item = items[index] if index < len(items) else {}
            if not isinstance(item, dict) or item.get("order") != expected_order or item.get("title") != expected_title:
                invalid_items.append(f"{expected_order}. {expected_title}")
                continue

            status = item.get("status")
            if expected_order <= 5 and status != "completed":
                incomplete_items.append(expected_title)
            if expected_order == 6 and status not in {"pending", "in_progress"}:
                invalid_items.append(f"{expected_order}. {expected_title} status must be pending or in_progress")

        if invalid_items:
            print("\n  ERROR: workflow-checklist.json has invalid workflow items.")
            for item in invalid_items:
                print(f"  - {item}")
            print("  Keep order/title values identical to the harness Workflow section.")
            raise SystemExit(1)

        observed_orders = {item.get("order") for item in items if isinstance(item, dict)}
        if set(expected_by_order) != observed_orders:
            print("\n  ERROR: workflow-checklist.json has missing or duplicate workflow orders.")
            raise SystemExit(1)

        if incomplete_items:
            print("\n  ERROR: harness workflow is not authorized for execution.")
            for title in incomplete_items:
                print(f"  - {title}: not completed")
            print("  Complete document review and Execution Authorization before running execute.py.")
            raise SystemExit(1)

    def update_workflow_item(self, title: str, status: str):
        """workflow checklist의 단일 항목 상태를 갱신한다."""
        checklist_path = self.workflow_checklist_path()
        checklist = self.read_json(checklist_path)
        timestamp = self.stamp()

        for item in checklist.get("items", []):
            if item.get("title") == title:
                item["status"] = status
                if status == "completed":
                    item["completed_at"] = timestamp
                elif status == "in_progress":
                    item["started_at"] = item.get("started_at", timestamp)
                    item.pop("completed_at", None)
                break
        else:
            print(f"\n  ERROR: workflow-checklist.json is missing '{title}'.")
            raise SystemExit(1)

        checklist["status"] = {
            "pending": "authorized",
            "in_progress": "in_progress",
            "completed": "completed",
        }.get(status, checklist.get("status", "drafting"))
        checklist["updated_at"] = timestamp
        self.write_json(checklist_path, checklist)

    def mark_workflow_execution_in_progress(self):
        """실행 시작 상태를 workflow checklist에 기록한다."""
        self.update_workflow_item("Execution", "in_progress")

    def mark_workflow_execution_completed(self):
        """phase 정상 완료 상태를 workflow checklist에 기록한다."""
        self.update_workflow_item("Execution", "completed")

    # --- step files & edit scope ---

    def step_file_path(self, step_num: int) -> Path:
        """현재 phase의 step 문서 경로를 반환한다."""
        return self.phase_dir / f"step{step_num}.md"

    def step_output_path(self, step_num: int) -> Path:
        """현재 phase의 step output JSON 경로를 반환한다."""
        return self.phase_dir / f"step{step_num}-output.json"

    def step_acceptance_output_path(self, step_num: int) -> Path:
        """현재 phase의 step Acceptance Criteria output JSON 경로를 반환한다."""
        return self.phase_dir / f"step{step_num}-ac-output.json"

    def ensure_step_file_exists(self, step_num: int) -> Path:
        """현재 step 문서가 없으면 실행기 레벨에서 즉시 중단한다."""
        step_file = self.step_file_path(step_num)
        if not step_file.exists():
            print(f"ERROR: {step_file} not found")
            print("Create the missing step file and retry.")
            raise SystemExit(1)
        return step_file

    # --- timestamps ---

    def stamp(self) -> str:
        """KST 기준 timestamp 문자열을 생성한다."""
        return datetime.now(self.TZ).strftime("%Y-%m-%dT%H:%M:%S%z")

    # --- JSON I/O ---

    @staticmethod
    def read_json(path: Path) -> dict:
        """UTF-8 JSON 파일을 읽어 dict로 반환한다."""
        return json.loads(path.read_text(encoding="utf-8"))

    @staticmethod
    def write_json(path: Path, data: dict):
        """UTF-8 pretty JSON으로 저장한다."""
        path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")

    # --- git ---

    def run_git(self, *args):
        """git 명령을 실행하고 stdout/stderr를 캡처한다."""
        return git_ops.run_git(self, *args)

    def update_task_index(self, status: str):
        """Task 내부 phases index와 현재 phase 상태를 동기화한다."""
        if not self.task_index_file.exists():
            return

        task = self.read_json(self.task_index_file)
        timestamp = self.stamp()
        key = {
            "completed": "completed_at",
            "error": "failed_at",
            "blocked": "blocked_at",
        }.get(status)

        for phase in task.get("phases", []):
            if phase.get("dir") == self.phase_dir_name:
                phase["status"] = status
                for stale_key in ("completed_at", "failed_at", "blocked_at"):
                    if stale_key != key:
                        phase.pop(stale_key, None)
                if key:
                    phase[key] = timestamp
                elif status == "in_progress":
                    phase["started_at"] = phase.get("started_at", timestamp)
                break

        self.write_json(self.task_index_file, task)

    # --- context & role guardrails ---

    def resolve_doc(self, *candidates: str) -> Optional[Path]:
        """후보 경로 중 실제로 존재하는 첫 문서를 반환한다."""
        return step_context.resolve_doc(self.root_path, *candidates)

    def list_agents_reference_docs(self) -> list[Path]:
        """CLAUDE.md의 `참고 문서` 섹션에 나열된 markdown 경로를 반환한다."""
        return step_context.list_agents_reference_docs(self.root_path)

    def load_step_context(self, step_text: str) -> str:
        """현재 step에 필요한 최소 문서만 developer 컨텍스트로 주입한다."""
        return step_context.load_step_documents(self.root_path, self.task_dir, step_text)

    @staticmethod
    def build_previous_step_context(index: dict) -> str:
        """이전 완료 step의 summary를 다음 step용 컨텍스트로 구성한다."""
        return step_context.build_previous_step_context(index)

    def build_developer_guardrails(self, prev_error: Optional[str] = None) -> str:
        """developer agent용 규칙 문자열을 만든다."""
        return developer_guardrails.build(
            project=self.project,
            phase_name=self.phase_name,
            phase_index_relpath=f"{self.phase_relpath}/index.json",
            max_retries=self.MAX_RETRIES,
            prev_error=prev_error,
        )

    def build_reviewer_guardrails(self) -> str:
        """reviewer agent용 규칙 문자열을 만든다."""
        return reviewer_guardrails.build(self.project)

    def verify_step_result(self, current: dict, step_text: str, *, require_acceptance: bool = False) -> step_verifier.VerificationResult:
        """Codex 실행 직후 step 상태와 output 파일을 후검증한다."""
        ac_output_path = self.step_acceptance_output_path(current["step"]) if require_acceptance else None
        return step_verifier.verify_step_result(
            current,
            step_text,
            self.step_output_path(current["step"]),
            ac_output_path,
        )

    def review_step_result(self, current: dict, step_text: str, changed_paths: list[str], attempt: int = 1) -> reviewer_agent.ReviewResult:
        """developer agent 결과를 read-only review agent로 다시 확인한다."""
        output = self.read_json(self.step_output_path(current["step"]))
        ac_output_path = self.step_acceptance_output_path(current["step"])
        ac_output = self.read_json(ac_output_path) if ac_output_path.exists() else None
        return reviewer_agent.run(
            root=self.root,
            phase_dir=self.phase_dir,
            write_json=self.write_json,
            step=current,
            step_text=step_text,
            changed_paths=changed_paths,
            output=output,
            ac_output=ac_output,
            guardrails_text=self.build_reviewer_guardrails(),
            model=self.reviewer_model,
            attempt=attempt,
        )

    def run_acceptance_checks(self, current: dict, step_text: str) -> dict | None:
        """Acceptance Criteria를 실행기가 직접 재실행하고 결과를 기록한다."""
        return acceptance_runner.run(self.root, self.phase_dir, self.write_json, current, step_text)

    @staticmethod
    def reset_step_for_retry(current: dict, verification_error: str):
        """재시도 전 상태를 pending으로 되돌리고 검증 실패 사유를 남긴다."""
        current["status"] = "pending"
        current["verification_error"] = verification_error
        for key in ("summary", "error_message", "blocked_reason", "completed_at", "failed_at", "blocked_at"):
            current.pop(key, None)

    @staticmethod
    def mark_step_blocked_from_review(current: dict, reason: str):
        """review agent 판단으로 step을 blocked 처리한다."""
        current["status"] = "blocked"
        current["blocked_reason"] = reason
        for key in ("summary", "error_message", "verification_error", "completed_at", "failed_at"):
            current.pop(key, None)

    def mark_step_error(self, current: dict, message: str, timestamp: str):
        """최종 실패 시 step을 error로 고정한다."""
        current["status"] = "error"
        current["error_message"] = f"[{self.MAX_RETRIES}회 시도 후 실패] {message}"
        current["failed_at"] = timestamp
        for key in ("summary", "blocked_reason", "verification_error", "completed_at", "blocked_at"):
            current.pop(key, None)

    # --- agent 호출 ---

    def run_developer_agent(self, step: dict, context_text: str, guardrails_text: str, attempt: int = 1) -> dict:
        """developer agent를 실행한다."""
        return developer_agent.run(self.root, self.phase_dir, self.write_json, step, context_text, guardrails_text, model=self.developer_model, attempt=attempt)

    # --- header & validation ---

    def print_header(self):
        """실행기 시작 시 phase와 step 수를 출력한다."""
        print("\n" + "=" * 60)
        print("  Harness Step Executor")
        print(f"  Task: {self.task_name} | Phase: {self.phase_name} | Steps: {self.total_steps}")
        print(f"  Push: {'enabled' if self.auto_push else 'disabled (--no-push)'}")
        print("=" * 60)

    def check_blockers(self):
        """이전에 실패하거나 차단된 step이 있으면 즉시 실행을 멈춘다."""
        index = self.read_json(self.index_file)
        for step in reversed(index.get("steps", [])):
            if step.get("status") == "error":
                print(f"\n  ✗ Step {step['step']} ({step['name']}) failed.")
                print(f"  Error: {step.get('error_message', 'unknown')}")
                print("  Fix: 사용자 승인 후 status를 'pending'으로 복구하고 execute.py를 재실행하세요.")
                raise SystemExit(1)
            if step.get("status") == "blocked":
                print(f"\n  ⏸ Step {step['step']} ({step['name']}) blocked.")
                print(f"  Reason: {step.get('blocked_reason', 'unknown')}")
                print("  Fix: 사용자 승인 후 차단 사유를 해결하고 status를 'pending'으로 복구한 뒤 execute.py를 재실행하세요.")
                raise SystemExit(2)
            if step.get("status") != "pending":
                break

    def validate_completed_step_artifacts(self):
        """completed step의 실행 상태 기록이 다음 step 진행에 충분한지 확인한다."""
        index = self.read_json(self.index_file)
        invalid: list[str] = []
        for step in index.get("steps", []):
            if step.get("status") != "completed":
                continue

            step_num = step.get("step")
            step_name = step.get("name", "unknown")
            if not isinstance(step_num, int):
                invalid.append(f"step 값이 정수가 아님: {step}")
                continue

            if not self.step_file_path(step_num).exists():
                invalid.append(f"step{step_num} ({step_name}): step 문서가 없음")
            if not step.get("summary"):
                invalid.append(f"step{step_num} ({step_name}): summary가 없음")
            if not step.get("completed_at"):
                invalid.append(f"step{step_num} ({step_name}): completed_at이 없음")

        if invalid:
            print("\n  ERROR: completed step의 실행 상태 기록이 불완전합니다.")
            for item in invalid:
                print(f"  - {item}")
            print("  Fix: 사용자 승인 후 상태 기록을 보정하거나 해당 step을 pending으로 복구한 뒤 execute.py를 재실행하세요.")
            raise SystemExit(1)

    def ensure_created_at(self):
        """phase index 최초 실행 시점과 실행 옵션을 한 번만 기록한다."""
        index = self.read_json(self.index_file)
        dirty = False
        if "created_at" not in index:
            index["created_at"] = self.stamp()
            dirty = True
        # execution 필드는 최초 1회만 기록한다. 재실행 시 기존 값을 보존해 첫 실행의 의도를 유지한다.
        if "execution" not in index:
            index["execution"] = {
                "developer_model": self.developer_model,
                "reviewer_model": self.reviewer_model,
                "commit_model": self.commit_model,
            }
            dirty = True
        if dirty:
            self.write_json(self.index_file, index)

    def mark_step_started(self, step_num: int):
        """step 시작 시각을 최초 1회만 기록한다."""
        index = self.read_json(self.index_file)
        for step in index.get("steps", []):
            if step.get("step") == step_num and "started_at" not in step:
                step["started_at"] = self.stamp()
                self.write_json(self.index_file, index)
                self.update_task_index("in_progress")
                break

    # --- execution loop ---

    def execute_single_step(self, step: dict) -> bool:
        """단일 step 실행 (재시도 포함). 완료되면 True, 실패/차단이면 종료한다."""
        step_num = step["step"]
        step_name = step["name"]
        done = sum(1 for item in self.read_json(self.index_file)["steps"] if item["status"] == "completed")
        prev_error = None
        step_file = self.ensure_step_file_exists(step_num)
        step_text = step_file.read_text(encoding="utf-8")

        for attempt in range(1, self.MAX_RETRIES + 1):
            index = self.read_json(self.index_file)
            context_text = self.load_step_context(step_text)
            previous_step_context = self.build_previous_step_context(index)
            developer_rules = self.build_developer_guardrails(prev_error)
            developer_context = "\n\n---\n\n".join(
                section for section in (context_text, previous_step_context) if section
            )

            label = f"Step {step_num}/{self.total_steps} ({done} done): {step_name}"
            if attempt > 1:
                label += f" [retry {attempt}/{self.MAX_RETRIES}]"

            with progress_indicator(label) as info:
                self.run_developer_agent(step, developer_context, developer_rules, attempt=attempt)
                elapsed = int(info.elapsed)

            index = self.read_json(self.index_file)
            current = next((item for item in index["steps"] if item["step"] == step_num), None)
            if current is None:
                print(f"  ERROR: step {step_num} not found in index after execution")
                raise SystemExit(1)

            status = current.get("status", "pending")
            timestamp = self.stamp()
            verification = self.verify_step_result(current, step_text)

            if verification.decision == "retryable_error":
                error_message = verification.message
                if attempt < self.MAX_RETRIES:
                    self.reset_step_for_retry(current, error_message)
                    self.write_json(self.index_file, index)
                    prev_error = error_message
                    print(f"  ↻ Step {step_num}: retry {attempt}/{self.MAX_RETRIES} — {error_message}")
                    continue

                self.mark_step_error(current, error_message, timestamp)
                self.write_json(self.index_file, index)
                print(f"  ✗ Step {step_num}: {step_name} failed after {self.MAX_RETRIES} attempts [{elapsed}s]")
                print(f"    Error: {error_message}")
                self.update_task_index("error")
                raise SystemExit(1)

            current.pop("verification_error", None)

            if status == "completed":
                self.run_acceptance_checks(current, step_text)
                verification = self.verify_step_result(current, step_text, require_acceptance=True)
                if verification.decision == "retryable_error":
                    error_message = verification.message
                    if attempt < self.MAX_RETRIES:
                        self.reset_step_for_retry(current, error_message)
                        self.write_json(self.index_file, index)
                        prev_error = error_message
                        print(f"  ↻ Step {step_num}: retry {attempt}/{self.MAX_RETRIES} — {error_message}")
                        continue

                    self.mark_step_error(current, error_message, timestamp)
                    self.write_json(self.index_file, index)
                    print(f"  ✗ Step {step_num}: {step_name} failed after {self.MAX_RETRIES} attempts [{elapsed}s]")
                    print(f"    Error: {error_message}")
                    self.update_task_index("error")
                    raise SystemExit(1)

                review_paths = git_ops.list_worktree_paths(self)
                review = self.review_step_result(current, step_text, review_paths, attempt=attempt)
                if review.decision == "blocked":
                    self.mark_step_blocked_from_review(current, review.message)
                    current["blocked_at"] = timestamp
                    self.write_json(self.index_file, index)
                    print(f"  ⏸ Step {step_num}: {step_name} blocked [{elapsed}s]")
                    print(f"    Reason: {review.message}")
                    self.update_task_index("blocked")
                    raise SystemExit(2)
                if review.decision == "retryable_error":
                    error_message = review.message
                    if attempt < self.MAX_RETRIES:
                        self.reset_step_for_retry(current, error_message)
                        self.write_json(self.index_file, index)
                        prev_error = error_message
                        print(f"  ↻ Step {step_num}: retry {attempt}/{self.MAX_RETRIES} — {error_message}")
                        continue

                    self.mark_step_error(current, error_message, timestamp)
                    self.write_json(self.index_file, index)
                    print(f"  ✗ Step {step_num}: {step_name} failed after {self.MAX_RETRIES} attempts [{elapsed}s]")
                    print(f"    Error: {error_message}")
                    self.update_task_index("error")
                    raise SystemExit(1)

                current["completed_at"] = timestamp
                try:
                    commit_agent.run(self.root, self.phase_dir, current, model=self.commit_model, attempt=attempt)
                except Exception as e:
                    self.mark_step_error(current, str(e), timestamp)
                    self.write_json(self.index_file, index)
                    print(f"  ✗ Step {step_num}: {step_name} — commit 실패 [{elapsed}s]")
                    print(f"    Error: {e}")
                    self.update_task_index("error")
                    raise SystemExit(1)
                self.write_json(self.index_file, index)
                print(f"  ✓ Step {step_num}: {step_name} [{elapsed}s]")
                return True

            if status == "blocked":
                current["blocked_at"] = timestamp
                self.write_json(self.index_file, index)
                reason = current.get("blocked_reason", "")
                print(f"  ⏸ Step {step_num}: {step_name} blocked [{elapsed}s]")
                print(f"    Reason: {reason}")
                self.update_task_index("blocked")
                raise SystemExit(2)

            error_message = current.get("error_message", "Step did not update status")
            if attempt < self.MAX_RETRIES:
                self.reset_step_for_retry(current, error_message)
                self.write_json(self.index_file, index)
                prev_error = error_message
                print(f"  ↻ Step {step_num}: retry {attempt}/{self.MAX_RETRIES} — {error_message}")
            else:
                self.mark_step_error(current, error_message, timestamp)
                self.write_json(self.index_file, index)
                print(f"  ✗ Step {step_num}: {step_name} failed after {self.MAX_RETRIES} attempts [{elapsed}s]")
                print(f"    Error: {error_message}")
                self.update_task_index("error")
                raise SystemExit(1)

        return False

    def execute_all_steps(self):
        """pending step이 없어질 때까지 순차 실행한다."""
        while True:
            index = self.read_json(self.index_file)
            pending = next((step for step in index["steps"] if step["status"] == "pending"), None)
            if pending is None:
                print("\n  All steps completed!")
                return

            self.ensure_step_file_exists(pending["step"])
            self.mark_step_started(pending["step"])
            self.execute_single_step(pending)

    def finalize(self):
        """phase 완료 시각 기록, task 내부 상태 동기화, 잔여 task 문서 커밋, 선택적 push를 수행한다."""
        index = self.read_json(self.index_file)
        index["completed_at"] = self.stamp()
        self.write_json(self.index_file, index)
        self.update_task_index("completed")
        self.mark_workflow_execution_completed()

        # task 문서 잔여 변경분 안전망 커밋 (step commit agent가 흡수하지 못한 수정분)
        self._commit_remaining_task_docs()

        # phase index 갱신 chore 커밋
        git_ops.stage_paths(
            self,
            [
                f"{self.phase_relpath}/index.json",
                f"{self.task_phases_relpath}/index.json",
            ],
        )
        if self.run_git("diff", "--cached", "--quiet").returncode != 0:
            message = f"chore: {self.phase_name} 실행 상태를 기록한다"
            result = self.run_git("commit", "-m", message)
            if result.returncode != 0:
                print(f"\n  ERROR: 실행 상태 커밋 실패: {result.stderr.strip()}")
                raise SystemExit(1)
            print(f"  ✓ {message}")

        if self.auto_push:
            result = self.run_git("push", "-u", "origin", self.branch_name)
            if result.returncode != 0:
                print(f"\n  ERROR: git push 실패: {result.stderr.strip()}")
                raise SystemExit(1)
            print(f"  ✓ Pushed to origin/{self.branch_name}")
            print("  → 다음: PR이 아직 없으면 'gh pr create'로 PR을 오픈한 뒤 Stage 7(PR Review)로 진행하세요.")
        else:
            print("  ⚠ push 생략됨(--no-push). PR을 열려면 먼저 원격에 push해야 합니다.")

        print("\n" + "=" * 60)
        print(f"  Phase '{self.phase_name}' completed!")
        print("=" * 60)

    def _commit_remaining_task_docs(self):
        """step commit agent가 흡수하지 못한 task 문서 변경분을 docs: 커밋으로 묶는다.

        task-level과 모든 phase의 index.json은 다음 chore 커밋용이므로 와일드카드로 제외한다.
        """
        task_relpath = Path(self.task_phases_relpath).parent.as_posix()
        self.run_git("add", "--", task_relpath)
        self.run_git("reset", "HEAD", "--", f"{self.task_phases_relpath}/index.json")
        self.run_git("reset", "HEAD", "--", f"{self.task_phases_relpath}/*/index.json")
        if self.run_git("diff", "--cached", "--quiet").returncode != 0:
            message = f"docs: {self.task_name} Task 문서 변경분을 반영한다"
            result = self.run_git("commit", "-m", message)
            if result.returncode != 0:
                print(f"\n  ERROR: Task 문서 커밋 실패: {result.stderr.strip()}")
                raise SystemExit(1)
            print(f"  ✓ {message}")


def main():
    """CLI 진입점. phase 디렉터리명을 받아 실행기를 시작한다."""
    parser = argparse.ArgumentParser(description="Harness Step Executor")
    parser.add_argument("phase_dir", help="Phase path (e.g. docs/tasks/<task-name>/phases/<phase-name>)")
    parser.add_argument(
        "--no-push",
        dest="push",
        action="store_false",
        default=True,
        help="phase 완료 후 원격 push를 생략한다 (기본: push 수행). PR 오픈은 push 이후 agent가 gh pr create로 수행한다.",
    )
    parser.add_argument("--developer-model", default="sonnet", help="Developer agent 모델 alias 또는 full name (기본: sonnet)")
    parser.add_argument("--reviewer-model", default="opus", help="Reviewer agent 모델 alias 또는 full name (기본: opus)")
    parser.add_argument("--commit-model", default="haiku", help="Commit agent 모델 alias 또는 full name (기본: haiku)")
    args = parser.parse_args()

    StepExecutor(
        args.phase_dir,
        auto_push=args.push,
        developer_model=args.developer_model,
        reviewer_model=args.reviewer_model,
        commit_model=args.commit_model,
    ).run()


if __name__ == "__main__":
    main()
