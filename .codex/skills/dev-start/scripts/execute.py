#!/usr/bin/env python3
"""
Harness Step Executor

`docs/features/<feature-name>/phases/<phase-name>` 아래 step 문서를 순차 실행하고, 상태를 기록하고,
필요하면 git 브랜치/커밋/푸시까지 자동으로 처리한다.

Usage:
    python3 .codex/skills/dev-start/scripts/execute.py docs/features/<feature-name>/phases/<phase-name> [--push]
"""

from __future__ import annotations

import argparse
import contextlib
import json
import re
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

import codex_client
import git_ops
import guardrails

# .codex/skills/dev-start/scripts/execute.py -> repository root
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
    FEAT_MSG = "feat: {phase} {num}단계 {name} 작업을 반영한다"
    CHORE_MSG = "chore: {phase} {num}단계 실행 결과를 기록한다"
    TZ = timezone(timedelta(hours=9))

    def __init__(self, phase_path: str, *, auto_push: bool = False):
        self.root = str(ROOT)
        self.root_path = ROOT
        self.auto_push = auto_push
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
        self.check_blockers()
        self.checkout_branch()
        self.ensure_created_at()
        self.execute_all_steps()
        self.finalize()

    # --- step files & edit scope ---

    def step_file_path(self, step_num: int) -> Path:
        """현재 phase의 step 문서 경로를 반환한다."""
        return self.phase_dir / f"step{step_num}.md"

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

    def commit_step(self, step_num: int, step_name: str, editable_paths: list[str]):
        """코드 변경과 메타데이터 변경을 분리해 2단계 커밋한다."""
        git_ops.commit_step(self, step_num, step_name, editable_paths)

    def update_feature_index(self, status: str):
        """기능 내부 phases index와 현재 phase 상태를 동기화한다."""
        if not self.feature_index_file.exists():
            return

        feature = self.read_json(self.feature_index_file)
        timestamp = self.stamp()
        key = {"completed": "completed_at", "error": "failed_at", "blocked": "blocked_at"}.get(status)

        for phase in feature.get("phases", []):
            if phase.get("dir") == self.phase_dir_name:
                phase["status"] = status
                if key:
                    phase[key] = timestamp
                break

        self.write_json(self.feature_index_file, feature)

    # --- guardrails & context ---

    def resolve_doc(self, *candidates: str) -> Optional[Path]:
        """후보 경로 중 실제로 존재하는 첫 문서를 반환한다."""
        return guardrails.resolve_doc(self.root_path, *candidates)

    def list_agents_reference_docs(self) -> list[Path]:
        """AGENTS.md의 `참고 문서` 섹션에 나열된 markdown 경로를 반환한다."""
        return guardrails.list_agents_reference_docs(self.root_path)

    def load_guardrails(self, step_text: str) -> str:
        """현재 step과 직접 관련된 최소 문서만 preamble에 주입한다."""
        return guardrails.load_guardrails(self.root_path, self.feature_dir, step_text)

    @staticmethod
    def build_step_context(index: dict) -> str:
        """이전 완료 step의 summary를 다음 step용 컨텍스트로 구성한다."""
        return guardrails.build_step_context(index)

    def build_preamble(self, guardrails_text: str, step_context: str, prev_error: Optional[str] = None) -> str:
        """실행기 공통 작업 지시문을 만든다."""
        return guardrails.build_preamble(
            project=self.project,
            phase_name=self.phase_name,
            phase_index_relpath=f"{self.phase_relpath}/index.json",
            max_retries=self.MAX_RETRIES,
            feat_msg_template=self.FEAT_MSG,
            guardrails=guardrails_text,
            step_context=step_context,
            prev_error=prev_error,
        )

    # --- Codex 호출 ---

    def invoke_codex(self, step: dict, preamble: str) -> dict:
        """현재 step 문서와 preamble을 합쳐 Codex CLI를 비대화형으로 호출한다."""
        return codex_client.invoke_codex(self.root, self.phase_dir, self.write_json, step, preamble)

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
                print("  Fix and reset status to 'pending' to retry.")
                raise SystemExit(1)
            if step.get("status") == "blocked":
                print(f"\n  ⏸ Step {step['step']} ({step['name']}) blocked.")
                print(f"  Reason: {step.get('blocked_reason', 'unknown')}")
                print("  Resolve and reset status to 'pending' to retry.")
                raise SystemExit(2)
            if step.get("status") != "pending":
                break

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

        for attempt in range(1, self.MAX_RETRIES + 1):
            index = self.read_json(self.index_file)
            step_context = self.build_step_context(index)
            guardrails = self.load_guardrails(step_text)
            preamble = self.build_preamble(guardrails, step_context, prev_error)

            label = f"Step {step_num}/{self.total_steps - 1} ({done} done): {step_name}"
            if attempt > 1:
                label += f" [retry {attempt}/{self.MAX_RETRIES}]"

            with progress_indicator(label) as info:
                self.invoke_codex(step, preamble)
                elapsed = int(info.elapsed)

            index = self.read_json(self.index_file)
            current = next((item for item in index["steps"] if item["step"] == step_num), None)
            if current is None:
                print(f"  ERROR: step {step_num} not found in index after execution")
                raise SystemExit(1)

            status = current.get("status", "pending")
            timestamp = self.stamp()

            if status == "completed":
                current["completed_at"] = timestamp
                self.write_json(self.index_file, index)
                self.commit_step(step_num, step_name, editable_paths)
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
                current["status"] = "pending"
                current.pop("error_message", None)
                self.write_json(self.index_file, index)
                prev_error = error_message
                print(f"  ↻ Step {step_num}: retry {attempt}/{self.MAX_RETRIES} — {error_message}")
            else:
                current["status"] = "error"
                current["error_message"] = f"[{self.MAX_RETRIES}회 시도 후 실패] {error_message}"
                current["failed_at"] = timestamp
                self.write_json(self.index_file, index)
                self.commit_step(step_num, step_name, editable_paths)
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
        git_ops.validate_worktree_scope(
            self,
            editable_paths=[],
            metadata_paths=[
                f"{self.phase_relpath}/index.json",
                f"{self.feature_phases_relpath}/index.json",
            ],
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
            message = f"chore: {self.phase_name} 완료 상태를 기록한다"
            result = self.run_git("commit", "-m", message)
            if result.returncode == 0:
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
