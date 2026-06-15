#!/usr/bin/env python3
"""
Harness Step Executor

`docs/tasks/<task-name>/phases/<phase-name>` 아래 step 문서를 순차 실행하고, 상태를 기록하고,
필요하면 git 브랜치/커밋/푸시까지 자동으로 처리한다.

Usage:
    python3 .claude/skills/harness-v3/scripts/execute.py docs/tasks/<task-name>/phases/<phase-name> [--no-push]
"""

from __future__ import annotations

import argparse
import contextlib
import json
import os
import shlex
import subprocess
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

import acceptance_runner
import git_ops
import step_context
import step_verifier

# .claude/skills/harness-v3/scripts/execute.py -> repository root
ROOT = Path(__file__).resolve().parents[4]

# 로그 tail pane의 title prefix. 역할명을 붙여(harness-v3:developer 등) 화면 라벨과
# stale pane 정리(중단으로 남은 pane 식별)를 겸한다.
PANE_TITLE_PREFIX = "harness-v3:"
# 로그 pane이 있는 윈도우에 임시 적용할 border 포맷. 사용자 전역 포맷은 보통 명령명을 보여주므로,
# 이 윈도우에서만 pane title이 보이도록 바꾸고 종료 시 원복한다.
PANE_BORDER_FORMAT = " #{pane_index} #{pane_title} "

# _stage 값 (한 step의 미니 상태기계)
STAGE_NEED_DEV = "need_developer"
STAGE_RAN_DEV = "ran_developer"
STAGE_RAN_REVIEW = "ran_reviewer"
STAGE_RAN_COMMIT = "ran_commit"
STAGE_DONE = "done"


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
        # 이 phase가 harness-v3 워크플로우로 설계됐는지 확인한다 (task index와 함께 버전 혼선 방지).
        if index.get("harness_version") != "v3":
            print(f"\n  ERROR: {self.index_file} must declare harness_version='v3' (harness-v3로 설계된 phase여야 합니다).")
            raise SystemExit(1)
        self.branch_name: str = ""  # _validate_worktree_context에서 실제 브랜치로 설정
        self._tmux = bool(os.environ.get("TMUX"))
        self.log_panes: list[str] = []
        self._main_pane = ""  # 로그 pane을 띄운 메인 pane (border-format 원복용)
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
        entry = next((phase for phase in task_index.get("phases", []) if phase.get("dir") == self.phase_dir_name), None)
        if entry is None:
            print(f"ERROR: phase '{self.phase_dir_name}' is not registered in {self.task_index_file}")
            print("Fix the task phases/index.json entry and retry.")
            raise SystemExit(1)
        # 이 phase 항목이 harness-v3로 설계됐는지 확인한다 (구버전 task로 v3 실행 방지).
        if entry.get("harness_version") != "v3":
            print(f"\n  ERROR: phase '{self.phase_dir_name}' entry in {self.task_index_file} must declare harness_version='v3'.")
            raise SystemExit(1)

    # --- tmux 로그 pane (3-pane 관찰) ---

    def _setup_log_panes(self):
        """tmux 세션 안이면 오른쪽 절반을 3등분해 agent 로그를 tail한다.

        레이아웃: 왼쪽 = execute.py(메인), 오른쪽 = 위→아래 developer / reviewer / commit.
        tmux 밖이면(degraded) pane을 만들지 않는다(로그는 PostToolUse hook이 파일로 계속 남긴다).
        """
        if not self._tmux:
            print("  ⚠ tmux 세션 밖이라 로그 pane을 생략합니다(로그는 콘솔로 출력).")
            return
        self._kill_stale_log_panes()
        logs_dir = self.phase_dir / "logs"
        logs_dir.mkdir(parents=True, exist_ok=True)
        self._main_pane = self._tmux_current_pane()
        # 이 윈도우에서만 pane title이 보이도록 border-format을 임시 변경한다 (_teardown에서 원복).
        subprocess.run(
            ["tmux", "set-option", "-w", "-t", self._main_pane, "pane-border-format", PANE_BORDER_FORMAT],
            capture_output=True,
        )
        # 현재 pane을 좌우로 나눠(-h) 오른쪽 컬럼을 만들고, 그 컬럼을 위→아래로(-v) 쌓아 三 형태를 만든다.
        # 로그 파일명은 로깅 hook이 쓰는 것과 일치해야 한다($ROLE.log, ROLE=agent_type=harness-v3-<role>).
        # pane title은 표시용 짧은 라벨을 쓴다.
        target = self._main_pane
        plan = [
            ("-h", "harness-v3-developer", "developer"),
            ("-v", "harness-v3-reviewer", "reviewer"),
            ("-v", "harness-v3-committer", "committer"),
        ]
        for direction, log_stem, label in plan:
            pane = self._tmux_split(target, direction, logs_dir / f"{log_stem}.log", f"{PANE_TITLE_PREFIX}{label}")
            if not pane:
                break
            self.log_panes.append(pane)
            target = pane  # 다음 split은 방금 만든 pane을 나눈다
        if self.log_panes:
            print(f"  ✓ 로그 pane {len(self.log_panes)}개 생성 (오른쪽: developer / reviewer / committer)")

    def _teardown_log_panes(self):
        """생성한 로그 pane과 임시 border-format을 정리한다 (정상 종료·중단 모두에서 호출)."""
        for pane in self.log_panes:
            subprocess.run(["tmux", "kill-pane", "-t", pane], capture_output=True)
        self.log_panes = []
        # 임시로 바꾼 pane-border-format을 원복한다 (윈도우 설정 제거 → 전역값 상속).
        if self._main_pane:
            subprocess.run(
                ["tmux", "set-option", "-w", "-u", "-t", self._main_pane, "pane-border-format"],
                capture_output=True,
            )
            self._main_pane = ""

    def _kill_stale_log_panes(self):
        """이전 실행이 중단으로 남긴 harness-v3 로그 pane을 정리한다(다음 run 오염 방지)."""
        # 현재 세션으로 제한한다 (-a는 머신의 모든 세션을 훑어, 다른 세션에서 동시 실행 중인
        # harness-v3의 로그 pane까지 오살할 수 있다). 우리 로그 pane은 현재 세션에 생성된다.
        result = subprocess.run(
            ["tmux", "list-panes", "-s", "-F", "#{pane_id} #{pane_title}"],
            capture_output=True, text=True,
        )
        for line in result.stdout.splitlines():
            parts = line.split(None, 1)
            if len(parts) == 2 and parts[1].startswith(PANE_TITLE_PREFIX):
                subprocess.run(["tmux", "kill-pane", "-t", parts[0]], capture_output=True)

    def _tmux_current_pane(self) -> str:
        """execute.py가 도는 현재 pane id를 반환한다."""
        env_pane = os.environ.get("TMUX_PANE")
        if env_pane:
            return env_pane
        return subprocess.run(
            ["tmux", "display-message", "-p", "#{pane_id}"],
            capture_output=True, text=True,
        ).stdout.strip()

    def _tmux_split(self, target: str, direction: str, log_path: Path, title: str) -> Optional[str]:
        """target pane을 direction(-h/-v)으로 나눠 log_path를 tail하는 pane을 만들고 title을 단다."""
        log_path.touch()  # tail -f가 즉시 붙도록 미리 생성
        result = subprocess.run(
            ["tmux", "split-window", direction, "-d", "-t", target,
             "-P", "-F", "#{pane_id}", f"exec tail -f {shlex.quote(str(log_path))}"],
            capture_output=True, text=True,
        )
        pane = result.stdout.strip()
        if not pane:
            return None
        subprocess.run(["tmux", "select-pane", "-t", pane, "-T", title], capture_output=True)
        return pane

    # --- 진행 표시 ---

    @contextlib.contextmanager
    def _plain_progress(self, label: str):
        """degraded(tmux 밖) 진행 표시. 스피너 없이 라벨만 출력하고 경과 시간을 잰다."""
        print(f"\n▶ {label}")
        started = time.monotonic()
        info = types.SimpleNamespace(elapsed=0.0)
        try:
            yield info
        finally:
            info.elapsed = time.monotonic() - started

    def _step_progress(self, label: str):
        """tmux면 스피너 진행 표시, degraded면 콘솔 로그가 진행을 보여주므로 라벨만 출력한다."""
        return progress_indicator(label) if self._tmux else self._plain_progress(label)

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
                "push": self.auto_push,  # finalize는 별도 프로세스라 디스크로 전달
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
            if not self.branch_name:
                print("\n  ERROR: push할 브랜치명을 확인할 수 없습니다(빈 refspec 방지).")
                raise SystemExit(1)
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
        # phase index 두 개는 다음 chore 커밋용이므로 pathspec 제외로 처음부터 스테이징에서 뺀다.
        self.run_git(
            "add",
            "--",
            task_relpath,
            f":(exclude){self.task_phases_relpath}/index.json",
            f":(exclude){self.task_phases_relpath}/*/index.json",
        )
        if self.run_git("diff", "--cached", "--quiet").returncode != 0:
            message = f"docs: {self.task_name} Task 문서 변경분을 반영한다"
            result = self.run_git("commit", "-m", message)
            if result.returncode != 0:
                print(f"\n  ERROR: Task 문서 커밋 실패: {result.stderr.strip()}")
                raise SystemExit(1)
            print(f"  ✓ {message}")

    def _emit(self, obj: dict):
        """메인 에이전트가 파싱할 지시 JSON을 실제 STDOUT에 한 줄 찍고 즉시 종료한다.
        (main에서 sys.stdout을 stderr로 돌려놨으므로 _real_stdout으로 쓴다.)"""
        out = getattr(self, "_real_stdout", sys.__stdout__)
        out.write(json.dumps(obj, ensure_ascii=False) + "\n")
        out.flush()
        raise SystemExit(0)

    def _log(self, msg: str):
        sys.stderr.write(msg + "\n")
        sys.stderr.flush()

    # ── 마커: hook에게 "지금 실행 중인 phase"를 알려주는 쪽지 ────────────────────
    def marker_path(self) -> Path:
        return self.root_path / ".harness" / "active-phase"

    def write_marker(self):
        """init 시 worktree 루트의 약속된 자리에 phase 상대경로를 적는다(재실행 시 덮어씀)."""
        m = self.marker_path()
        m.parent.mkdir(parents=True, exist_ok=True)
        m.write_text(self.phase_relpath + "\n", encoding="utf-8")

    def clear_marker(self):
        """finalize 시 마커를 제거한다(없어도 무시)."""
        try:
            self.marker_path().unlink()
        except FileNotFoundError:
            pass

    # ── 핸드오프 파일 경로 (에이전트가 결과를 쓰는 곳; index 정본 아님) ──────────
    def handoff_dir(self) -> Path:
        d = self.phase_dir / "handoff"
        d.mkdir(parents=True, exist_ok=True)
        return d

    def dev_handoff_path(self, n: int) -> Path:
        return self.handoff_dir() / f"step{n}-dev.json"

    def review_handoff_path(self, n: int) -> Path:
        return self.handoff_dir() / f"step{n}-review.json"

    def dev_prompt_path(self, n: int) -> Path:
        return self.handoff_dir() / f"step{n}-dev-prompt.md"

    def review_prompt_path(self, n: int) -> Path:
        return self.handoff_dir() / f"step{n}-review-prompt.md"

    def commit_prompt_path(self, n: int) -> Path:
        return self.handoff_dir() / f"step{n}-commit-prompt.md"

    def _read_handoff(self, path: Path) -> dict | None:
        if not path.exists():
            return None
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, ValueError):
            return None

    # ── 재시도/실패 공통 처리 ────────────────────────────────────────────────
    def _retry_or_fail(self, index: dict, step: dict, error_message: str) -> dict | None:
        """attempt<MAX면 step을 need_developer로 리셋하고 다음 호출이 dev를 다시 부르게 한다.
        MAX면 status=error로 종료 지시를 반환한다."""
        n = step["step"]
        if step.get("_attempt", 0) < self.MAX_RETRIES:
            # 스테이지/핸드오프 리셋 (다음 step 호출에서 need_developer 진입)
            step["_stage"] = STAGE_NEED_DEV
            step["_prev_error"] = error_message
            step["status"] = "pending"  # 다시 in_progress로 들어가며 attempt++ 된다
            for p in (self.dev_handoff_path(n), self.review_handoff_path(n)):
                try: p.unlink()
                except FileNotFoundError: pass
            self.write_json(self.index_file, index)
            self._log(f"  ↻ Step {n}: retry {step['_attempt']}/{self.MAX_RETRIES} — {error_message}")
            return None  # 호출부가 루프 계속 → need_developer 처리로 떨어진다
        # 최종 실패
        self.mark_step_error(step, error_message, self.stamp())
        step["_stage"] = STAGE_DONE
        self.write_json(self.index_file, index)
        self.update_task_index("error")
        return {"action": "error", "step": n, "message": error_message}

    # ── init: 프리플라이트 게이트 + 마커 + execution 기록 + tmux ────────────────
    def cmd_init(self):
        """v2 run()의 준비 단계 전부를 1회 실행한다(루프 진입 전)."""
        self.print_header()                       # print()는 main에서 stdout→stderr 리다이렉트되어 사람 로그로 감
        self._preflight_tools_v3()                # claude 설치 체크는 불필요(메인이 Task로 호출). tmux만 선택
        self.validate_workflow_checklist()        # ★ 인가 게이트 (checklist 6번 Execution)
        self.validate_completed_step_artifacts()  # 완료 step 무결성
        self.check_blockers()                     # error/blocked 잔재 차단
        self._validate_worktree_context()         # worktree·보호브랜치 검증
        self.mark_workflow_execution_in_progress()
        self.ensure_created_at()                  # created_at + execution{models} 1회 기록
        self.write_marker()                       # ★ hook용 active-phase 마커
        self._setup_log_panes()                   # ★ tmux 3-pane (v2 그대로, phase_dir/logs tail)
        # init은 상태만 잡고 종료. 실제 진행은 메인 에이전트가 step을 반복 호출.
        self._emit({"action": "initialized", "phase": self.phase_relpath})

    def _preflight_tools_v3(self):
        """v3는 claude -p를 직접 안 띄우므로 claude CLI 필수 체크가 불필요하다. (no-op)"""
        return

    # ── step: _stage 전이표를 코드로 (이 파일의 심장부) ─────────────────────────
    def cmd_step(self):
        """현재 활성 step의 _stage를 한 칸 진행시키고,
        에이전트 호출이 필요한 순간 invoke_agent 지시를 STDOUT에 찍고 종료한다.
        순수 Python 게이트(acceptance/verify)는 왕복 없이 인라인으로 처리한다."""
        # 마커가 사라졌으면(외부 정리 등) 복구
        if not self.marker_path().exists():
            self.write_marker()

        while True:  # 순수 전이는 루프로 흘리고, '에이전트 필요'/'종료'에서만 _emit
            index = self.read_json(self.index_file)
            step = self._active_step(index)
            if step is None:
                self._emit({"action": "done"})          # pending/in_progress 없음 → finalize 하라

            n = step["step"]
            step_file = self.ensure_step_file_exists(n)
            step_text = step_file.read_text(encoding="utf-8")
            stage = step.get("_stage") or STAGE_NEED_DEV

            # ── need_developer: developer 호출 지시 ──────────────────────────
            if stage == STAGE_NEED_DEV:
                if not step.get("started_at"):
                    step["started_at"] = self.stamp()
                step["status"] = "in_progress"
                step["_attempt"] = step.get("_attempt", 0) + 1
                step["_stage"] = STAGE_RAN_DEV
                self.write_json(self.index_file, index)
                # 프롬프트 = 동적 컨텍스트만 (guardrails는 developer.md 시스템 프롬프트에 있음)
                self.dev_prompt_path(n).write_text(
                    self._build_dev_prompt(index, step, step_text), encoding="utf-8")
                self._emit({
                    "action": "invoke_agent", "role": "developer",
                    "model": self.developer_model, "step": n, "attempt": step["_attempt"],
                    "prompt_file": self.dev_prompt_path(n).relative_to(self.root_path).as_posix(),
                    "handoff_out": self.dev_handoff_path(n).relative_to(self.root_path).as_posix(),
                })

            # ── ran_developer: dev 결과 → verify + acceptance → reviewer ─────
            if stage == STAGE_RAN_DEV:
                dev = self._read_handoff(self.dev_handoff_path(n))
                # dev가 실제로 안 돌았거나(핸드오프 없음) 이전 attempt 잔재면 → 재시도 (결정 2번: 디스크 검증)
                if dev is None or dev.get("attempt") not in (None, step["_attempt"]):
                    r = self._retry_or_fail(index, step, "developer 핸드오프 없음/불일치")
                    if r: self._emit(r)
                    continue
                if dev.get("ok") is False:
                    r = self._retry_or_fail(index, step, dev.get("struggles") or "developer가 실패 보고")
                    if r: self._emit(r)
                    continue
                # acceptance 재실행(객관 게이트) 후 검증 — 구조(ok/summary) + AC 통과를 한 번에
                self.run_acceptance_checks(step, step_text)
                v = self._verify_v3(step, step_text, dev)
                if v.decision == "retryable_error":
                    r = self._retry_or_fail(index, step, v.message)
                    if r: self._emit(r)
                    continue
                # 통과 → summary/struggles를 정본에 기록 (에이전트가 아니라 execute.py가 옮겨 적음)
                self._record_dev_result(step, dev)        # index.summary + stepN-output.json attempts[]
                step["_stage"] = STAGE_RAN_REVIEW
                step.pop("_prev_error", None)
                self.write_json(self.index_file, index)
                # reviewer 프롬프트 작성 (변경 경로 + dev struggles → ADR 일탈 판정용)
                changed = git_ops.list_worktree_paths(self)
                self.review_prompt_path(n).write_text(
                    self._build_review_prompt(step, step_text, changed, dev), encoding="utf-8")
                self._emit({
                    "action": "invoke_agent", "role": "reviewer",
                    "model": self.reviewer_model, "step": n, "attempt": step["_attempt"],
                    "prompt_file": self.review_prompt_path(n).relative_to(self.root_path).as_posix(),
                    "handoff_out": self.review_handoff_path(n).relative_to(self.root_path).as_posix(),
                })

            # ── ran_reviewer: 판정 → approved/blocked/retry ──────────────────
            if stage == STAGE_RAN_REVIEW:
                review = self._read_handoff(self.review_handoff_path(n))
                if review is None:
                    # reviewer가 안 돌았으면 다시 호출 (재시도 카운트 소모 없이)
                    self.write_json(self.index_file, index)
                    dev = self._read_handoff(self.dev_handoff_path(n))
                    changed = git_ops.list_worktree_paths(self)
                    self.review_prompt_path(n).write_text(
                        self._build_review_prompt(step, step_text, changed, dev), encoding="utf-8")
                    self._emit({
                        "action": "invoke_agent", "role": "reviewer",
                        "model": self.reviewer_model, "step": n, "attempt": step["_attempt"],
                        "prompt_file": self.review_prompt_path(n).relative_to(self.root_path).as_posix(),
                        "handoff_out": self.review_handoff_path(n).relative_to(self.root_path).as_posix(),
                    })
                decision = review.get("decision")
                if decision == "blocked":
                    self.mark_step_blocked_from_review(step, review.get("message", ""))
                    step["blocked_at"] = self.stamp()
                    step["_stage"] = STAGE_DONE
                    self.write_json(self.index_file, index)
                    self.update_task_index("blocked")
                    self._emit({"action": "blocked", "step": n, "reason": review.get("message", "")})
                if decision == "retryable_error":
                    r = self._retry_or_fail(index, step, review.get("message", "reviewer 재시도 요청"))
                    if r: self._emit(r)
                    continue
                # approved → commit 단계로. 커밋 전 HEAD를 기록(B안 _commit_was_made가 비교)
                step["completed_at"] = self.stamp()
                step["_pre_commit_head"] = self.run_git("rev-parse", "HEAD").stdout.strip()
                step["_stage"] = STAGE_RAN_COMMIT
                self.write_json(self.index_file, index)
                self.commit_prompt_path(n).write_text(self._build_commit_prompt(step, step_text), encoding="utf-8")
                self._emit({
                    "action": "invoke_agent", "role": "commit",
                    "model": self.commit_model, "step": n, "attempt": step["_attempt"],
                    "prompt_file": self.commit_prompt_path(n).relative_to(self.root_path).as_posix(),
                })

            # ── ran_commit: 커밋 생겼는지 확인 → step 완료 → 다음 step ─────────
            if stage == STAGE_RAN_COMMIT:
                if self._commit_was_made(step):       # B안: HEAD 변화 OR (변화없음 AND 트리 깨끗)
                    step["status"] = "completed"
                    step["_stage"] = STAGE_DONE
                    self.write_json(self.index_file, index)
                    self._log(f"  ✓ Step {n}: {step['name']}")
                    continue                       # 루프 계속 → 다음 pending step의 need_developer로
                r = self._retry_or_fail(index, step, "commit이 생성되지 않음")
                if r: self._emit(r)
                continue

            if stage == STAGE_DONE:
                # 이 step은 끝났는데 _active_step이 다시 잡았다면 방어적으로 다음으로
                continue

    def _active_step(self, index: dict) -> dict | None:
        """진행 대상 step: status가 pending 또는 in_progress인 첫 step."""
        return next(
            (s for s in index["steps"] if s.get("status") in ("pending", "in_progress")
             and s.get("_stage") != STAGE_DONE),
            None,
        )

    # ── finalize: 마커 정리 + v2 finalize 그대로 ──────────────────────────────
    def cmd_finalize(self):
        # 별도 프로세스라 push 선호(디스크)와 브랜치(git)를 다시 읽는다.
        index = self.read_json(self.index_file)
        recorded_push = index.get("execution", {}).get("push", True)
        self.auto_push = bool(recorded_push) and self.auto_push  # 하나라도 no-push면 no-push
        self.branch_name = self._read_current_branch()           # 빈 refspec 방지
        try:
            self.finalize()        # v2 finalize: completed_at, task index, checklist, docs/chore 커밋, push
        finally:
            self.clear_marker()
            self._teardown_log_panes()
        self._emit({"action": "finalized", "phase": self.phase_relpath})

    # ── 씨앗 ①: verify — verify_step_result_v3 호출 ──────────────────────────
    def _verify_v3(self, step, step_text, dev_handoff):
        """dev 핸드오프(구조: ok/summary) + acceptance 재실행 결과(객관 게이트)로 검증."""
        ac_path = acceptance_runner.output_path(self.phase_dir, step["step"])
        return step_verifier.verify_step_result_v3(step, step_text, dev_handoff, ac_path)

    def _record_dev_result(self, step, dev):
        """dev 핸드오프의 summary/struggles를 정본으로 옮긴다.
        - index의 step.summary  ← dev['summary']
        - stepN-output.json의 attempts[]에 {attempt, ok, struggles} 누적 (회고 재료)"""
        step["summary"] = dev.get("summary", step.get("summary", ""))
        out_path = self.step_output_path(step["step"])
        out = self._read_handoff(out_path) or {"step": step["step"], "attempts": []}
        out["attempts"].append({
            "attempt": step["_attempt"],
            "ok": dev.get("ok", True),
            "struggles": dev.get("struggles"),
        })
        self.write_json(out_path, out)

    # ── 씨앗 ③-a: developer 프롬프트 (guardrails 없음 — developer.md에 있음) ────
    def _build_dev_prompt(self, index, step, step_text) -> str:
        """동적 컨텍스트 + 이번 step + 핸드오프 지시. 정적 규칙은 developer.md 시스템 프롬프트에 있다."""
        n = step["step"]
        context = self.load_step_context(step_text)           # CLAUDE.md + task docs + 필수 컨벤션 요약(4개) + 참조 전문
        prev = self.build_previous_step_context(index)         # 이전 완료 step summary
        parts = [context, prev]
        if step.get("_prev_error"):
            parts.append(
                "## 이전 시도 실패\n\n"
                f"{step['_prev_error']}\n\n"
                "같은 실패를 반복하지 않도록 원인을 해결한 뒤 다시 진행하라."
            )
        handoff_rel = self.dev_handoff_path(n).relative_to(self.root_path).as_posix()
        parts.append(
            "## 이번 작업\n\n"
            f"- step 번호: {n}\n"
            f"- attempt: {step['_attempt']}\n"
            f"- 모든 구현·테스트를 마친 뒤, 마지막 행동으로 핸드오프를 이 경로에 써라: `{handoff_rel}`\n\n"
            "## Step 문서\n\n"
            f"{step_text}"
        )
        return "\n\n---\n\n".join(p for p in parts if p)

    # ── 씨앗 ③-b: reviewer 프롬프트 (dev struggles 포함 — ADR 일탈 판정용) ──────
    def _build_review_prompt(self, step, step_text, changed_paths, dev_handoff) -> str:
        n = step["step"]
        changed = "## 변경된 경로\n\n" + ("\n".join(f"- {p}" for p in changed_paths) if changed_paths else "- (변경 없음)")
        struggles = (dev_handoff or {}).get("struggles")
        struggles_block = f"## developer 시행착오·ADR 일탈 보고\n\n{struggles}" if struggles else ""
        handoff_rel = self.review_handoff_path(n).relative_to(self.root_path).as_posix()
        head = (
            "## 이번 검토\n\n"
            f"- step 번호: {n}\n"
            f"- 판정을 이 경로에 핸드오프로 써라: `{handoff_rel}`"
        )
        return "\n\n---\n\n".join(p for p in (head, f"## Step 문서\n\n{step_text}", changed, struggles_block) if p)

    # ── 씨앗 ③-c: committer 프롬프트 (핸드오프 없음) ──────────────────────────
    def _build_commit_prompt(self, step, step_text) -> str:
        n = step["step"]
        return (
            "## 이번 커밋\n\n"
            f"- step 번호: {n}\n"
            f"- step 이름: {step.get('name', '')}\n"
            f"- developer summary: {step.get('summary', '')}\n\n"
            "이 step에서 발생한 변경을 목적별로 커밋하라. (별도 핸드오프는 쓰지 않는다.)\n\n"
            "## Step 문서\n\n"
            f"{step_text}"
        )

    # ── 씨앗 ②: 커밋 생성 여부 (B안 — 느슨) ───────────────────────────────────
    def _commit_was_made(self, step) -> bool:
        """B안: 커밋이 한 번이라도 생겼으면 통과(개수·내용 안 따짐).
        커밋 0이어도 남은 변경이 없으면 통과('커밋할 것 없음'도 정상).
        커밋 0 + 남은 변경 있음 → False(진짜 깜빡만 잡음).
        전제: logs/·handoff/ 는 .gitignore 라 git status 에 안 잡힌다."""
        pre = step.get("_pre_commit_head", "")
        cur = self.run_git("rev-parse", "HEAD").stdout.strip()
        if pre and cur and pre != cur:
            return True  # 커밋이 생김 → 통과
        status = self.run_git("status", "--porcelain").stdout.strip()
        return status == ""  # 깨끗하면 통과(B안), 남았으면 재시도


def main():
    """v3 CLI 진입점: init / step / finalize 서브커맨드.

    사람용 출력(print_header, 검증 경고 등)은 전부 STDERR로 보낸다. STDOUT에는 _emit이 찍는
    지시 JSON 한 줄만 나간다(메인 에이전트가 파싱). 이를 위해 sys.stdout을 stderr로 돌리고,
    _emit은 보관해 둔 실제 stdout(_real_stdout)으로 쓴다.
    """
    parser = argparse.ArgumentParser(description="Harness v3 Orchestrator (stateless step machine)")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_init = sub.add_parser("init", help="프리플라이트 게이트 + 마커 + tmux + execution 기록(1회)")
    p_init.add_argument("phase_dir")
    p_init.add_argument("--developer-model", default="sonnet")
    p_init.add_argument("--reviewer-model", default="opus")
    p_init.add_argument("--commit-model", default="haiku")
    p_init.add_argument("--no-push", dest="push", action="store_false", default=True)

    p_step = sub.add_parser("step", help="활성 step의 _stage를 한 칸 진행하고 다음 행동을 JSON으로 반환")
    p_step.add_argument("phase_dir")

    p_fin = sub.add_parser("finalize", help="마커 정리 + 완료 기록 + docs/chore 커밋 + push")
    p_fin.add_argument("phase_dir")
    p_fin.add_argument("--no-push", dest="push", action="store_false", default=True)

    args = parser.parse_args()

    # 사람 출력은 stderr로, JSON 지시만 실제 stdout으로.
    _real_stdout = sys.stdout
    sys.stdout = sys.stderr

    ex = StepExecutor(
        args.phase_dir,
        auto_push=getattr(args, "push", True),
        developer_model=getattr(args, "developer_model", "sonnet"),
        reviewer_model=getattr(args, "reviewer_model", "opus"),
        commit_model=getattr(args, "commit_model", "haiku"),
    )
    ex._real_stdout = _real_stdout

    if args.cmd == "init":
        ex.cmd_init()
    elif args.cmd == "step":
        ex.cmd_step()
    elif args.cmd == "finalize":
        ex.cmd_finalize()


if __name__ == "__main__":
    main()
