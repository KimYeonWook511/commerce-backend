#!/usr/bin/env python3
"""
Harness Step Executor

`docs/features/<feature-name>/phases/<phase-name>` 아래 step 문서를 순차 실행하고, 상태를 기록하고,
필요하면 git 브랜치/커밋/푸시까지 자동으로 처리한다.

Usage:
    python3 .claude/skills/harness/scripts/execute.py docs/features/<feature-name>/phases/<phase-name> [--push]
"""

from __future__ import annotations

import argparse
import contextlib
import json
import re
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

import developer_guardrails
import developer_worker
import acceptance_runner
import git_ops
import reviewer_guardrails
import reviewer_worker
import step_context
import step_verifier

# .claude/skills/harness/scripts/execute.py -> repository root
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
        (4, "File Drafting"),
        (5, "Execution Authorization"),
        (6, "Execution"),
    ]
    EXECUTE_PREFIX_RULE = ["python3", ".claude/skills/harness/scripts/execute.py"]
    APPROVAL_PROMPT_MODES = {"per_run", "saved_prefix_rule"}

    def __init__(self, phase_path: str, *, auto_push: bool = False):
        self.root = str(ROOT)
        self.root_path = ROOT
        self.auto_push = auto_push
        self.worktree_path: Path | None = None
        self.phase_dir = self.resolve_phase_dir(phase_path)
        self.phase_relpath = self.phase_dir.relative_to(ROOT).as_posix()
        self.phase_dir_name = self.phase_dir.name
        self.feature_phases_dir = self.phase_dir.parent
        self.feature_phases_relpath = self.feature_phases_dir.relative_to(ROOT).as_posix()
        self.feature_index_file = self.feature_phases_dir / "index.json"
        self.feature_dir = self.feature_phases_dir.parent

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
        self.feature_name = self.extract_feature_name(index)
        self.total_steps = len(index["steps"])
        self.branch_name = f"feature/{self.feature_name}"
        self.validate_feature_phase_registration()

    def resolve_phase_dir(self, phase_path: str) -> Path:
        """저장소 기준 feature phase 경로를 해석한다."""
        direct = ROOT / phase_path
        if direct.is_dir():
            return direct
        return direct

    def extract_feature_name(self, index: dict) -> str:
        """phase 경로 또는 index 정보에서 기능명을 추출한다."""
        parts = self.phase_dir.relative_to(ROOT).parts
        if len(parts) >= 4 and parts[0] == "docs" and parts[1] == "features":
            return parts[2]
        return index.get("feature", self.phase_name)

    def validate_feature_phase_registration(self):
        """feature-level phases index가 현재 phase를 포함하는지 확인한다."""
        if not self.feature_index_file.exists():
            print(f"ERROR: {self.feature_index_file} not found")
            raise SystemExit(1)

        feature_index = self.read_json(self.feature_index_file)
        if not any(phase.get("dir") == self.phase_dir_name for phase in feature_index.get("phases", [])):
            print(f"ERROR: phase '{self.phase_dir_name}' is not registered in {self.feature_index_file}")
            print("Fix the feature phases/index.json entry and retry.")
            raise SystemExit(1)

    def run(self):
        """실행 헤더 출력부터 전체 phase 완료 처리까지 오케스트레이션한다."""
        self.print_header()
        self._preflight_tools()
        self.validate_workflow_checklist()
        self.validate_completed_step_artifacts()
        self.check_blockers()
        git_ops.preflight_git_write(self)
        self._setup_worktree()

    def _preflight_tools(self):
        """tmux와 claude CLI가 설치되어 있는지 확인한다."""
        import shutil
        for tool in ("tmux", "claude"):
            if not shutil.which(tool):
                print(f"\n  ERROR: '{tool}'이 설치되어 있지 않습니다. execute.py 실행 전 설치하세요.")
                raise SystemExit(1)
        try:
            self.checkout_branch()
            self.mark_workflow_execution_in_progress()
            self.ensure_created_at()
            self.execute_all_steps()
            self.finalize()
        finally:
            self._teardown_worktree()

    def _setup_worktree(self):
        """phase 실행용 격리 worktree를 생성하고 self.root / self.phase_dir를 재설정한다."""
        import uuid as _uuid
        worktree_path = Path("/tmp") / f"dev-start-{self.feature_name}-{_uuid.uuid4().hex[:8]}"
        git_ops.create_worktree(self.root_path, worktree_path, self.branch_name)
        self.worktree_path = worktree_path

        phase_relpath = self.phase_dir.relative_to(self.root_path)
        self.root = str(worktree_path)
        self.phase_dir = worktree_path / phase_relpath
        self.phase_relpath = phase_relpath.as_posix()

        feature_phases_relpath = self.feature_phases_dir.relative_to(self.root_path)
        self.feature_phases_dir = worktree_path / feature_phases_relpath
        self.feature_phases_relpath = feature_phases_relpath.as_posix()
        self.feature_index_file = self.feature_phases_dir / "index.json"
        self.feature_dir = self.feature_phases_dir.parent
        self.index_file = self.phase_dir / "index.json"

        print(f"  Worktree: {worktree_path}")

    def _teardown_worktree(self):
        """worktree를 정리한다. 성공/실패 모두 호출된다."""
        if self.worktree_path:
            git_ops.remove_worktree(self.root_path, self.worktree_path)
            self.worktree_path = None

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
        if checklist.get("workflow") != "dev-start":
            print("\n  ERROR: workflow-checklist.json must have workflow='dev-start'.")
            raise SystemExit(1)

        items = checklist.get("items")
        if not isinstance(items, list) or len(items) != len(self.WORKFLOW_ITEMS):
            print("\n  ERROR: workflow-checklist.json must contain the six dev-start workflow items.")
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
            if expected_order == 5 and status == "completed":
                invalid_items.extend(self.validate_execution_authorization_item(item))

        if invalid_items:
            print("\n  ERROR: workflow-checklist.json has invalid workflow items.")
            for item in invalid_items:
                print(f"  - {item}")
            print("  Keep order/title values identical to the dev-start Workflow section.")
            raise SystemExit(1)

        observed_orders = {item.get("order") for item in items if isinstance(item, dict)}
        if set(expected_by_order) != observed_orders:
            print("\n  ERROR: workflow-checklist.json has missing or duplicate workflow orders.")
            raise SystemExit(1)

        if incomplete_items:
            print("\n  ERROR: dev-start workflow is not authorized for execution.")
            for title in incomplete_items:
                print(f"  - {title}: not completed")
            print("  Complete document review and Execution Authorization before running execute.py.")
            raise SystemExit(1)

    def validate_execution_authorization_item(self, item: dict) -> list[str]:
        """Execution Authorization 완료 항목의 상세 승인 기록을 검증한다."""
        errors: list[str] = []
        authorization = item.get("authorization")
        if not isinstance(authorization, dict):
            return ["5. Execution Authorization authorization is required"]

        if authorization.get("escalation_approved") is not True:
            errors.append("5. Execution Authorization escalation_approved must be true")

        mode = authorization.get("approval_prompt_mode")
        if mode not in self.APPROVAL_PROMPT_MODES:
            errors.append("5. Execution Authorization approval_prompt_mode must be per_run or saved_prefix_rule")
        elif mode == "saved_prefix_rule" and authorization.get("prefix_rule") != self.EXECUTE_PREFIX_RULE:
            errors.append("5. Execution Authorization prefix_rule must match execute.py command")
        elif mode == "per_run" and authorization.get("prefix_rule") not in (None, []):
            errors.append("5. Execution Authorization prefix_rule must be null or empty for per_run")

        if authorization.get("approved_by") != "user":
            errors.append("5. Execution Authorization approved_by must be user")
        if not authorization.get("approved_at"):
            errors.append("5. Execution Authorization approved_at is required")

        return errors

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

    def parse_editable_paths(self, step_text: str) -> list[str]:
        """step 문서의 `수정 가능 경로` 섹션에서 허용 경로 목록을 추출한다."""
        match = re.search(
            r"^## 수정 가능 경로\s*$\n(?P<body>.*?)(?=^## |\Z)",
            step_text,
            re.MULTILINE | re.DOTALL,
        )
        if not match:
            print(f"ERROR: {self.phase_relpath}/step*.md is missing the `수정 가능 경로` section.")
            print("Add the allowed edit paths to the step document and retry.")
            raise SystemExit(1)

        editable_paths: list[str] = []
        for raw_line in match.group("body").splitlines():
            line = raw_line.strip()
            if not line.startswith("- "):
                continue
            value = line[2:].strip().strip("`").strip()
            if value:
                editable_paths.append(value)

        if not editable_paths:
            print(f"ERROR: {self.phase_relpath}/step*.md has no editable paths.")
            print("Add at least one allowed edit path under `수정 가능 경로` and retry.")
            raise SystemExit(1)

        feature_docs_path = f"docs/features/{self.feature_name}/**"
        if not any(git_ops.normalize_pathspec(path) == git_ops.normalize_pathspec(feature_docs_path) for path in editable_paths):
            editable_paths.insert(0, feature_docs_path)

        return editable_paths

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

    def checkout_branch(self):
        """`feature/<feature-name>` 브랜치를 준비한다."""
        git_ops.checkout_branch(self)

    def commit_step(self, step_num: int, message: str, editable_paths: list[str]):
        """현재 step의 기능 변경만 커밋한다."""
        git_ops.commit_step(self, step_num, message, editable_paths)

    def update_feature_index(self, status: str):
        """기능 내부 phases index와 현재 phase 상태를 동기화한다."""
        if not self.feature_index_file.exists():
            return

        feature = self.read_json(self.feature_index_file)
        timestamp = self.stamp()
        key = {
            "completed": "completed_at",
            "error": "failed_at",
            "blocked": "blocked_at",
        }.get(status)

        for phase in feature.get("phases", []):
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

        self.write_json(self.feature_index_file, feature)

    # --- context & role guardrails ---

    def resolve_doc(self, *candidates: str) -> Optional[Path]:
        """후보 경로 중 실제로 존재하는 첫 문서를 반환한다."""
        return step_context.resolve_doc(self.root_path, *candidates)

    def list_agents_reference_docs(self) -> list[Path]:
        """CLAUDE.md의 `참고 문서` 섹션에 나열된 markdown 경로를 반환한다."""
        return step_context.list_agents_reference_docs(self.root_path)

    def load_step_context(self, step_text: str) -> str:
        """현재 step에 필요한 최소 문서만 developer 컨텍스트로 주입한다."""
        return step_context.load_step_documents(self.root_path, self.feature_dir, step_text)

    @staticmethod
    def build_previous_step_context(index: dict) -> str:
        """이전 완료 step의 summary를 다음 step용 컨텍스트로 구성한다."""
        return step_context.build_previous_step_context(index)

    def build_developer_guardrails(self, prev_error: Optional[str] = None) -> str:
        """developer worker용 규칙 문자열을 만든다."""
        return developer_guardrails.build(
            project=self.project,
            phase_name=self.phase_name,
            phase_index_relpath=f"{self.phase_relpath}/index.json",
            max_retries=self.MAX_RETRIES,
            prev_error=prev_error,
        )

    def build_commit(self, current: dict, changed_paths: list[str]) -> str:
        """변경 경로와 step summary를 기반으로 커밋 메시지를 만든다."""
        summary = str(current.get("summary", "")).strip()
        if not summary:
            print("ERROR: completed step에는 summary가 필요합니다.")
            raise SystemExit(1)

        commit_type = self.infer_commit_type(changed_paths)
        subject = summary.rstrip(".。").strip()
        return f"{commit_type}: {subject}"

    def infer_commit_type(self, changed_paths: list[str]) -> str:
        """변경 경로로 커밋 type을 보수적으로 추론한다."""
        code_paths = [
            path
            for path in changed_paths
            if not path.startswith(f"{self.phase_relpath}/")
            and not path.startswith(f"{self.feature_phases_relpath}/")
        ]
        if code_paths and all(path.startswith((".claude/", ".github/", "gradle", "docs/hooks/", "docs/agents/", "docs/skills/")) for path in code_paths):
            return "chore"
        if code_paths and all(path.startswith("docs/") for path in code_paths):
            return "docs"
        if code_paths and all(path.startswith("src/test/") for path in code_paths):
            return "test"
        return "feat"

    def build_reviewer_guardrails(self) -> str:
        """reviewer worker용 규칙 문자열을 만든다."""
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

    def review_step_result(self, current: dict, step_text: str, changed_paths: list[str]) -> reviewer_worker.ReviewResult:
        """writer 결과를 read-only review worker로 다시 확인한다."""
        output = self.read_json(self.step_output_path(current["step"]))
        ac_output_path = self.step_acceptance_output_path(current["step"])
        ac_output = self.read_json(ac_output_path) if ac_output_path.exists() else None
        return reviewer_worker.run(
            root=self.root,
            phase_dir=self.phase_dir,
            write_json=self.write_json,
            step=current,
            step_text=step_text,
            changed_paths=changed_paths,
            output=output,
            ac_output=ac_output,
            guardrails_text=self.build_reviewer_guardrails(),
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
        """review worker 판단으로 step을 blocked 처리한다."""
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

    def list_review_changed_paths(self, editable_paths: list[str], metadata_paths: list[str]) -> list[str]:
        """리뷰 대상 변경 파일만 repo-relative 경로로 추린다."""
        changed_paths = git_ops.list_worktree_paths(self)
        review_paths: list[str] = []
        for path in changed_paths:
            if any(git_ops.matches_pathspec(path, allowed_path) for allowed_path in editable_paths):
                review_paths.append(path)
                continue
            if any(git_ops.matches_pathspec(path, metadata_path) for metadata_path in metadata_paths):
                review_paths.append(path)
        return review_paths

    # --- worker 호출 ---

    def run_developer_worker(self, step: dict, context_text: str, guardrails_text: str) -> dict:
        """developer worker를 실행한다."""
        return developer_worker.run(self.root, self.phase_dir, self.write_json, step, context_text, guardrails_text)

    # --- header & validation ---

    def print_header(self):
        """실행기 시작 시 phase와 step 수를 출력한다."""
        print("\n" + "=" * 60)
        print("  Harness Step Executor")
        print(f"  Feature: {self.feature_name} | Phase: {self.phase_name} | Steps: {self.total_steps}")
        if self.auto_push:
            print("  Auto-push: enabled")
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
        """phase index 최초 실행 시점을 한 번만 기록한다."""
        index = self.read_json(self.index_file)
        if "created_at" not in index:
            index["created_at"] = self.stamp()
            self.write_json(self.index_file, index)

    def mark_step_started(self, step_num: int):
        """step 시작 시각을 최초 1회만 기록한다."""
        index = self.read_json(self.index_file)
        for step in index.get("steps", []):
            if step.get("step") == step_num and "started_at" not in step:
                step["started_at"] = self.stamp()
                self.write_json(self.index_file, index)
                self.update_feature_index("in_progress")
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
        editable_paths = self.parse_editable_paths(step_text)
        metadata_paths = [
            f"{self.phase_relpath}/step{step_num}-output.json",
            f"{self.phase_relpath}/step{step_num}-ac-output.json",
            f"{self.phase_relpath}/step{step_num}-review-output.json",
            f"{self.phase_relpath}/index.json",
            f"{self.phase_relpath}/workflow-checklist.json",
            f"{self.feature_phases_relpath}/index.json",
        ]

        for attempt in range(1, self.MAX_RETRIES + 1):
            index = self.read_json(self.index_file)
            context_text = self.load_step_context(step_text)
            previous_step_context = self.build_previous_step_context(index)
            developer_rules = self.build_developer_guardrails(prev_error)
            developer_context = "\n\n---\n\n".join(
                section for section in (context_text, previous_step_context) if section
            )

            label = f"Step {step_num}/{self.total_steps - 1} ({done} done): {step_name}"
            if attempt > 1:
                label += f" [retry {attempt}/{self.MAX_RETRIES}]"

            with progress_indicator(label) as info:
                self.run_developer_worker(step, developer_context, developer_rules)
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
                self.update_feature_index("error")
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
                    self.update_feature_index("error")
                    raise SystemExit(1)

                git_ops.validate_worktree_scope(
                    self,
                    editable_paths=editable_paths,
                    metadata_paths=metadata_paths,
                    context=f"step {step_num} review",
                )
                review_paths = self.list_review_changed_paths(editable_paths, metadata_paths)
                review = self.review_step_result(current, step_text, review_paths)
                if review.decision == "blocked":
                    self.mark_step_blocked_from_review(current, review.message)
                    current["blocked_at"] = timestamp
                    self.write_json(self.index_file, index)
                    print(f"  ⏸ Step {step_num}: {step_name} blocked [{elapsed}s]")
                    print(f"    Reason: {review.message}")
                    self.update_feature_index("blocked")
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
                    self.update_feature_index("error")
                    raise SystemExit(1)

                current["completed_at"] = timestamp
                self.write_json(self.index_file, index)
                commit_paths = self.list_review_changed_paths(editable_paths, metadata_paths)
                message = self.build_commit(current, commit_paths)
                self.commit_step(step_num, message, editable_paths)
                print(f"  ✓ Step {step_num}: {step_name} [{elapsed}s]")
                return True

            if status == "blocked":
                current["blocked_at"] = timestamp
                self.write_json(self.index_file, index)
                reason = current.get("blocked_reason", "")
                print(f"  ⏸ Step {step_num}: {step_name} blocked [{elapsed}s]")
                print(f"    Reason: {reason}")
                self.update_feature_index("blocked")
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
                self.update_feature_index("error")
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
        """phase 완료 시각 기록, feature 내부 상태 동기화, 선택적 push를 수행한다."""
        index = self.read_json(self.index_file)
        index["completed_at"] = self.stamp()
        self.write_json(self.index_file, index)
        self.update_feature_index("completed")
        self.mark_workflow_execution_completed()
        metadata_paths = [
            f"{self.phase_relpath}/index.json",
            f"{self.phase_relpath}/workflow-checklist.json",
            f"{self.feature_phases_relpath}/index.json",
        ]
        for step in index.get("steps", []):
            step_num = step.get("step")
            if isinstance(step_num, int):
                metadata_paths.extend(
                    [
                        f"{self.phase_relpath}/step{step_num}-output.json",
                        f"{self.phase_relpath}/step{step_num}-ac-output.json",
                        f"{self.phase_relpath}/step{step_num}-review-output.json",
                    ]
                )

        git_ops.validate_worktree_scope(
            self,
            editable_paths=[],
            metadata_paths=metadata_paths,
            context="phase finalize",
        )
        git_ops.stage_paths(
            self,
            [
                f"{self.phase_relpath}/index.json",
                f"{self.feature_phases_relpath}/index.json",
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

        print("\n" + "=" * 60)
        print(f"  Phase '{self.phase_name}' completed!")
        print("=" * 60)


def main():
    """CLI 진입점. phase 디렉터리명을 받아 실행기를 시작한다."""
    parser = argparse.ArgumentParser(description="Harness Step Executor")
    parser.add_argument("phase_dir", help="Phase path (e.g. docs/features/<feature-name>/phases/<phase-name>)")
    parser.add_argument("--push", action="store_true", help="Push branch after completion")
    args = parser.parse_args()

    StepExecutor(args.phase_dir, auto_push=args.push).run()


if __name__ == "__main__":
    main()
