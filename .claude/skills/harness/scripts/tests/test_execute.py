"""
execute.py 리팩터링 안전망 테스트.
참고 실행기와 동일한 책임을 현재 harness 실행기에서 안정적으로 유지하는지 검증한다.
"""

import importlib.util
import io
import json
import tempfile
import types
import unittest
from contextlib import contextmanager
from contextlib import redirect_stdout
from datetime import datetime, timedelta
from pathlib import Path
from unittest.mock import ANY
from unittest.mock import MagicMock
from unittest.mock import patch


def load_execute_module():
    module_path = Path(__file__).resolve().parent.parent / "execute.py"
    spec = importlib.util.spec_from_file_location("execute_module", module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class StepExecutorTest(unittest.TestCase):
    def setUp(self):
        self.execute = load_execute_module()
        self.original_root = self.execute.ROOT
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.execute.ROOT = self.root

        (self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp").mkdir(parents=True)
        (self.root / "CLAUDE.md").write_text(
            "# AGENTS\n\n## 참고 문서\n- 설계 결정: `docs/ADR.md`\n- API 스펙: `docs/api-spec.md`\n- 태스크별 문서 운영 가이드: `docs/tasks/README.md`\n",
            encoding="utf-8",
        )
        (self.root / "docs" / "architecture.md").write_text("# Architecture\nSome content", encoding="utf-8")
        (self.root / "docs" / "ADR.md").write_text("# ADR\nDecision", encoding="utf-8")
        (self.root / "docs" / "api-spec.md").write_text("# API\nSpec", encoding="utf-8")
        (self.root / "docs" / "tasks" / "README.md").write_text("# Tasks\nGuide", encoding="utf-8")
        (self.root / "docs" / "tasks" / "skill-test" / "prd.md").write_text("# Skill Test PRD\nFeature requirements", encoding="utf-8")
        (self.root / "docs" / "tasks" / "skill-test" / "architecture.md").write_text("# Skill Test Architecture\nFeature structure", encoding="utf-8")
        (self.root / "docs" / "tasks" / "skill-test" / "adr.md").write_text("# Skill Test ADR\nFeature decisions", encoding="utf-8")
        (self.root / "docs" / "tasks" / "skill-test" / "api-spec.md").write_text("# Skill Test API\nFeature contract", encoding="utf-8")
        (self.root / "docs" / "tasks" / "skill-test" / "db-schema.md").write_text("# Skill Test DB\nFeature schema", encoding="utf-8")

        self.write_json(
            self.root / "docs" / "tasks" / "skill-test" / "phases" / "index.json",
            {"phases": [{"dir": "0-mvp", "status": "pending"}, {"dir": "1-polish", "status": "pending"}]},
        )
        self.write_json(
            self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json",
            {
                "project": "TestProject",
                "phase": "mvp",
                "task": "skill-test",
                "steps": [
                    {
                        "step": 0,
                        "name": "setup",
                        "status": "completed",
                        "summary": "프로젝트 초기화 완료",
                        "completed_at": "2026-04-30T15:00:00+0900",
                    },
                    {
                        "step": 1,
                        "name": "core",
                        "status": "completed",
                        "summary": "핵심 로직 구현",
                        "completed_at": "2026-04-30T15:10:00+0900",
                    },
                    {"step": 2, "name": "api", "status": "pending"},
                ],
            },
        )
        self.write_workflow_checklist()
        (self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "step2.md").write_text(
            "# Step 2: api\n\n"
            "`docs/ADR.md`와 `docs/api-spec.md`를 참고해 API를 구현하세요.\n\n"
            "## Acceptance Criteria\n\n```bash\n./gradlew test\n```\n",
            encoding="utf-8",
        )
        (self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "step0.md").write_text(
            "# Step 0: setup\n",
            encoding="utf-8",
        )
        (self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "step1.md").write_text(
            "# Step 1: core\n",
            encoding="utf-8",
        )

    def tearDown(self):
        self.execute.ROOT = self.original_root
        self.temp_dir.cleanup()

    @staticmethod
    def write_json(path: Path, data: dict):
        path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")

    @staticmethod
    def read_json(path: Path) -> dict:
        return json.loads(path.read_text(encoding="utf-8"))

    def write_workflow_checklist(self, *, overrides: dict[int, dict] | None = None):
        items = [
            {"order": 1, "title": "Explore", "status": "completed"},
            {"order": 2, "title": "Discuss", "status": "completed"},
            {"order": 3, "title": "Step Design", "status": "completed"},
            {"order": 4, "title": "Worktree 생성 및 이동", "status": "completed"},
            {"order": 5, "title": "File Drafting", "status": "completed"},
            {
                "order": 6,
                "title": "Execution Authorization",
                "status": "completed",
                "authorization": {
                    "escalation_approved": True,
                    "approval_prompt_mode": "per_run",
                    "prefix_rule": None,
                    "approved_by": "user",
                    "approved_at": "2026-04-30T15:30:00+0900",
                },
            },
            {"order": 7, "title": "Execution", "status": "pending"},
        ]
        for order, override in (overrides or {}).items():
            items[order - 1].update(override)

        self.write_json(
            self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "workflow-checklist.json",
            {"workflow": "dev-start", "status": "authorized", "items": items},
        )

    def write_step_output(self, *, exit_code: int = 0, stdout: str = "./gradlew test", stderr: str = "", last_message: str = "테스트로 ./gradlew test를 실행했다."):
        self.write_json(
            self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "step2-output.json",
            {
                "step": 2,
                "name": "api",
                "exitCode": exit_code,
                "stdout": stdout,
                "stderr": stderr,
                "lastMessage": last_message,
            },
        )

    def write_ac_output(self, *, passed: bool = True, exit_code: int = 0):
        self.write_json(
            self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "step2-ac-output.json",
            {
                "step": 2,
                "commands": ["./gradlew test"],
                "results": [{"command": "./gradlew test", "exitCode": exit_code, "stdout": "ok", "stderr": ""}],
                "passed": passed,
            },
        )

    def write_review_output(self, step_num: int = 2):
        self.write_json(
            self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / f"step{step_num}-review-output.json",
            {
                "step": step_num,
                "name": "api",
                "exitCode": 0,
                "stdout": "DECISION: pass\nMESSAGE: OK\n",
                "stderr": "",
                "lastMessage": "DECISION: pass\nMESSAGE: OK\n",
                "changedPaths": [],
                "reviewMode": "repo-read-only",
            },
        )

    def make_executor(self, *, auto_push: bool = False):
        return self.execute.StepExecutor("docs/tasks/skill-test/phases/0-mvp", auto_push=auto_push)

    def mock_git(self, executor, responses):
        state = {"index": 0}

        def fake_git(*_args):
            current = state["index"]
            state["index"] += 1
            if current < len(responses):
                return responses[current]
            return MagicMock(returncode=0, stdout="", stderr="")

        executor.run_git = fake_git

    class TestStamp(unittest.TestCase):
        pass

    def test_root_points_to_repository_root_when_loaded_normally(self):
        module = load_execute_module()
        expected = Path(module.__file__).resolve().parents[4]
        self.assertEqual(expected, module.ROOT)

    def test_stamp_returns_kst_timestamp(self):
        result = self.make_executor().stamp()
        self.assertIn("+0900", result)

    def test_stamp_format_is_parseable(self):
        result = self.make_executor().stamp()
        parsed = datetime.strptime(result, "%Y-%m-%dT%H:%M:%S%z")
        self.assertIsNotNone(parsed.tzinfo)

    def test_stamp_is_current_time(self):
        before = datetime.now(self.execute.StepExecutor.TZ).replace(microsecond=0)
        result = self.make_executor().stamp()
        after = datetime.now(self.execute.StepExecutor.TZ).replace(microsecond=0) + timedelta(seconds=1)
        parsed = datetime.strptime(result, "%Y-%m-%dT%H:%M:%S%z")
        self.assertTrue(before <= parsed <= after)

    def test_json_roundtrip(self):
        data = {"key": "값", "nested": [1, 2, 3]}
        path = self.root / "roundtrip.json"
        self.execute.StepExecutor.write_json(path, data)
        self.assertEqual(data, self.execute.StepExecutor.read_json(path))

    def test_write_json_keeps_korean(self):
        path = self.root / "korean.json"
        self.execute.StepExecutor.write_json(path, {"한글": "테스트"})
        raw = path.read_text(encoding="utf-8")
        self.assertIn("한글", raw)
        self.assertNotIn("\\u", raw)

    def test_read_json_missing_file_raises(self):
        with self.assertRaises(FileNotFoundError):
            self.execute.StepExecutor.read_json(self.root / "missing.json")

    def test_load_step_context_loads_agents_and_selected_docs(self):
        executor = self.make_executor()
        step_text = (self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "step2.md").read_text(encoding="utf-8")

        result = executor.load_step_context(step_text)

        self.assertIn("프로젝트 규칙 (CLAUDE.md)", result)
        self.assertIn("태스크 문서 (docs/tasks/skill-test/prd.md)", result)
        self.assertIn("태스크 문서 (docs/tasks/skill-test/architecture.md)", result)
        self.assertIn("태스크 문서 (docs/tasks/skill-test/adr.md)", result)
        self.assertIn("태스크 문서 (docs/tasks/skill-test/api-spec.md)", result)
        self.assertIn("태스크 문서 (docs/tasks/skill-test/db-schema.md)", result)
        self.assertIn("관련 문서 (docs/ADR.md)", result)
        self.assertIn("관련 문서 (docs/api-spec.md)", result)

    def test_load_step_context_uses_divider(self):
        executor = self.make_executor()
        step_text = (self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "step2.md").read_text(encoding="utf-8")
        result = executor.load_step_context(step_text)
        self.assertIn("---", result)

    def test_load_step_context_skips_unreferenced_agents_docs(self):
        executor = self.make_executor()
        result = executor.load_step_context("# Step 2\n\n코드를 정리하세요.")
        self.assertIn("태스크 문서 (docs/tasks/skill-test/architecture.md)", result)
        self.assertNotIn("관련 문서 (docs/ADR.md)", result)
        self.assertNotIn("관련 문서 (docs/api-spec.md)", result)
        self.assertNotIn("구조 규칙", result)

    def test_load_step_context_tolerates_missing_task_docs(self):
        executor = self.make_executor()
        (self.root / "docs" / "tasks" / "skill-test" / "db-schema.md").unlink()
        result = executor.load_step_context("# Step 2\n\n코드를 정리하세요.")
        self.assertIn("태스크 문서 (docs/tasks/skill-test/prd.md)", result)
        self.assertNotIn("태스크 문서 (docs/tasks/skill-test/db-schema.md)", result)

    def test_build_previous_step_context_includes_completed_with_summary(self):
        index = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")
        result = self.execute.StepExecutor.build_previous_step_context(index)
        self.assertIn("Step 0 (setup): 프로젝트 초기화 완료", result)
        self.assertIn("Step 1 (core): 핵심 로직 구현", result)

    def test_build_previous_step_context_excludes_pending(self):
        index = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")
        result = self.execute.StepExecutor.build_previous_step_context(index)
        self.assertNotIn("api", result)

    def test_build_previous_step_context_empty_when_no_completed(self):
        result = self.execute.StepExecutor.build_previous_step_context(
            {"steps": [{"step": 0, "name": "a", "status": "pending"}]}
        )
        self.assertEqual("", result)

    def test_build_developer_guardrails_includes_commit_example_and_retry(self):
        executor = self.make_executor()
        result = executor.build_developer_guardrails(prev_error="타입 에러")
        self.assertIn("이전 시도 실패", result)
        self.assertIn("/docs/tasks/skill-test/phases/0-mvp/index.json", result)
        self.assertIn("git add/commit/push/checkout은 실행하지 마라", result)
        self.assertIn("커밋하지 않는다", result)

    def test_update_task_index_completed(self):
        executor = self.make_executor()
        executor.update_task_index("completed")
        task = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "index.json")
        self.assertEqual("completed", task["phases"][0]["status"])
        self.assertIn("completed_at", task["phases"][0])

    def test_missing_task_index_exits(self):
        (self.root / "docs" / "tasks" / "skill-test" / "phases" / "index.json").unlink()
        with self.assertRaises(SystemExit) as exc:
            self.make_executor()
        self.assertEqual(1, exc.exception.code)

    def test_phase_missing_from_task_index_exits(self):
        self.write_json(
            self.root / "docs" / "tasks" / "skill-test" / "phases" / "index.json",
            {"phases": [{"dir": "1-polish", "status": "pending"}]},
        )
        with self.assertRaises(SystemExit) as exc:
            self.make_executor()
        self.assertEqual(1, exc.exception.code)

    def test_validate_workflow_checklist_exits_when_missing(self):
        checklist = self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "workflow-checklist.json"
        checklist.unlink()

        with self.assertRaises(SystemExit) as exc:
            self.make_executor().validate_workflow_checklist()

        self.assertEqual(1, exc.exception.code)

    def test_validate_workflow_checklist_exits_when_required_step_pending(self):
        self.write_workflow_checklist(overrides={6: {"status": "pending"}})

        with self.assertRaises(SystemExit) as exc:
            self.make_executor().validate_workflow_checklist()

        self.assertEqual(1, exc.exception.code)

    def test_validate_workflow_checklist_exits_when_title_order_is_wrong(self):
        self.write_workflow_checklist(overrides={3: {"title": "Wrong Title"}})

        with self.assertRaises(SystemExit) as exc:
            self.make_executor().validate_workflow_checklist()

        self.assertEqual(1, exc.exception.code)

    def test_validate_workflow_checklist_exits_when_execution_already_completed(self):
        self.write_workflow_checklist(overrides={7: {"status": "completed"}})

        with self.assertRaises(SystemExit) as exc:
            self.make_executor().validate_workflow_checklist()

        self.assertEqual(1, exc.exception.code)

    def test_validate_workflow_checklist_exits_when_authorization_missing(self):
        self.write_workflow_checklist(overrides={6: {"authorization": None}})

        with self.assertRaises(SystemExit) as exc:
            self.make_executor().validate_workflow_checklist()

        self.assertEqual(1, exc.exception.code)

    def test_validate_workflow_checklist_exits_when_escalation_not_approved(self):
        self.write_workflow_checklist(
            overrides={6: {"authorization": {"escalation_approved": False, "approval_prompt_mode": "per_run"}}}
        )

        with self.assertRaises(SystemExit) as exc:
            self.make_executor().validate_workflow_checklist()

        self.assertEqual(1, exc.exception.code)

    def test_validate_workflow_checklist_exits_when_approval_prompt_mode_invalid(self):
        self.write_workflow_checklist(
            overrides={
                6: {
                    "authorization": {
                        "escalation_approved": True,
                        "approval_prompt_mode": "always",
                        "approved_by": "user",
                        "approved_at": "2026-04-30T15:30:00+0900",
                    }
                }
            }
        )

        with self.assertRaises(SystemExit) as exc:
            self.make_executor().validate_workflow_checklist()

        self.assertEqual(1, exc.exception.code)

    def test_validate_workflow_checklist_exits_when_saved_prefix_rule_missing(self):
        self.write_workflow_checklist(
            overrides={
                6: {
                    "authorization": {
                        "escalation_approved": True,
                        "approval_prompt_mode": "saved_prefix_rule",
                        "prefix_rule": None,
                        "approved_by": "user",
                        "approved_at": "2026-04-30T15:30:00+0900",
                    }
                }
            }
        )

        with self.assertRaises(SystemExit) as exc:
            self.make_executor().validate_workflow_checklist()

        self.assertEqual(1, exc.exception.code)

    def test_validate_workflow_checklist_passes_when_saved_prefix_rule_matches(self):
        self.write_workflow_checklist(
            overrides={
                6: {
                    "authorization": {
                        "escalation_approved": True,
                        "approval_prompt_mode": "saved_prefix_rule",
                        "prefix_rule": ["python3", ".claude/skills/harness/scripts/execute.py"],
                        "approved_by": "user",
                        "approved_at": "2026-04-30T15:30:00+0900",
                    }
                }
            }
        )

        self.make_executor().validate_workflow_checklist()

    def test_validate_workflow_checklist_passes_when_authorized(self):
        self.make_executor().validate_workflow_checklist()

    def test_mark_workflow_execution_in_progress(self):
        executor = self.make_executor()

        executor.mark_workflow_execution_in_progress()

        checklist = self.read_json(
            self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "workflow-checklist.json"
        )
        execution = checklist["items"][6]
        self.assertEqual("in_progress", checklist["status"])
        self.assertEqual("in_progress", execution["status"])
        self.assertIn("started_at", execution)

    def test_mark_workflow_execution_completed(self):
        executor = self.make_executor()

        executor.mark_workflow_execution_completed()

        checklist = self.read_json(
            self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "workflow-checklist.json"
        )
        execution = checklist["items"][6]
        self.assertEqual("completed", checklist["status"])
        self.assertEqual("completed", execution["status"])
        self.assertIn("completed_at", execution)

    def test_check_blockers_error_exits(self):
        index = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")
        index["steps"][2] = {"step": 2, "name": "api", "status": "error", "error_message": "fail"}
        self.write_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json", index)
        output = io.StringIO()
        with self.assertRaises(SystemExit) as exc:
            with redirect_stdout(output):
                self.make_executor().check_blockers()
        self.assertEqual(1, exc.exception.code)
        self.assertIn("사용자 승인 후 status를 'pending'으로 복구", output.getvalue())

    def test_check_blockers_blocked_exits(self):
        index = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")
        index["steps"][2] = {"step": 2, "name": "api", "status": "blocked", "blocked_reason": "API key"}
        self.write_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json", index)
        output = io.StringIO()
        with self.assertRaises(SystemExit) as exc:
            with redirect_stdout(output):
                self.make_executor().check_blockers()
        self.assertEqual(2, exc.exception.code)
        self.assertIn("사용자 승인 후 차단 사유를 해결", output.getvalue())

    def write_completed_step_outputs(self):
        for step_num in (0, 1):
            phase_dir = self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp"
            self.write_json(
                phase_dir / f"step{step_num}-output.json",
                {"step": step_num, "name": "done", "exitCode": 0, "stdout": "", "stderr": "", "lastMessage": "done"},
            )
            self.write_review_output(step_num)

    def test_validate_completed_step_artifacts_passes_from_index_state_without_local_outputs(self):
        self.make_executor().validate_completed_step_artifacts()

    def test_validate_completed_step_artifacts_exits_when_summary_missing(self):
        index = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")
        index["steps"][0].pop("summary")
        self.write_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json", index)

        with self.assertRaises(SystemExit) as exc:
            self.make_executor().validate_completed_step_artifacts()

        self.assertEqual(1, exc.exception.code)

    def test_validate_completed_step_artifacts_exits_when_completed_at_missing(self):
        index = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")
        index["steps"][0].pop("completed_at")
        self.write_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json", index)

        with self.assertRaises(SystemExit) as exc:
            self.make_executor().validate_completed_step_artifacts()

        self.assertEqual(1, exc.exception.code)

    def test_validate_completed_step_artifacts_exits_when_step_file_missing(self):
        (self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "step0.md").unlink()

        with self.assertRaises(SystemExit) as exc:
            self.make_executor().validate_completed_step_artifacts()

        self.assertEqual(1, exc.exception.code)

    def test_mark_started_writes_once(self):
        executor = self.make_executor()
        index_before = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")
        self.assertNotIn("started_at", index_before["steps"][2])
        executor.mark_step_started(2)
        first = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")["steps"][2]["started_at"]
        executor.mark_step_started(2)
        second = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")["steps"][2]["started_at"]
        self.assertEqual(first, second)

    def test_ensure_step_file_exists_exits_when_missing(self):
        executor = self.make_executor()
        (self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "step2.md").unlink()
        with self.assertRaises(SystemExit) as exc:
            executor.ensure_step_file_exists(2)
        self.assertEqual(1, exc.exception.code)

    def test_validate_worktree_context_passes_when_in_worktree_on_work_branch(self):
        executor = self.make_executor()
        self.mock_git(executor, [
            MagicMock(returncode=0, stdout="/absolute/path/.git/worktrees/chore-harness-improvement\n", stderr=""),
            MagicMock(returncode=0, stdout="chore/harness-improvement\n", stderr=""),
        ])
        executor._validate_worktree_context()

    def test_validate_worktree_context_exits_when_not_in_worktree(self):
        executor = self.make_executor()
        self.mock_git(executor, [
            MagicMock(returncode=0, stdout=".git\n", stderr=""),
        ])
        with self.assertRaises(SystemExit) as exc:
            executor._validate_worktree_context()
        self.assertEqual(1, exc.exception.code)

    def test_validate_worktree_context_exits_when_on_protected_branch(self):
        executor = self.make_executor()
        self.mock_git(executor, [
            MagicMock(returncode=0, stdout="/absolute/path/.git/worktrees/develop\n", stderr=""),
            MagicMock(returncode=0, stdout="develop\n", stderr=""),
        ])
        with self.assertRaises(SystemExit) as exc:
            executor._validate_worktree_context()
        self.assertEqual(1, exc.exception.code)

    def test_run_developer_worker_writes_output_json(self):
        executor = self.make_executor()
        mock_result = MagicMock(returncode=0, stdout="{}", stderr="")
        with patch.object(self.execute.developer_worker.subprocess, "run", return_value=mock_result):
            executor.run_developer_worker({"step": 2, "name": "api"}, "CONTEXT", "GUARD")

        output = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "step2-output.json")
        self.assertEqual(2, output["step"])
        self.assertEqual("api", output["name"])

    def test_progress_indicator_sets_elapsed(self):
        with self.execute.progress_indicator("test") as info:
            time_result = types.SimpleNamespace()
            self.assertTrue(hasattr(time_result, "__class__"))
        self.assertGreaterEqual(info.elapsed, 0.0)

    def test_execute_single_step_completed_calls_commit_agent_and_returns_true(self):
        executor = self.make_executor()

        @contextmanager
        def fake_progress(_label: str):
            yield types.SimpleNamespace(elapsed=0.0)

        def fake_invoke(step: dict, _context: str, _guardrails: str):
            self.write_step_output()
            index = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")
            current = next(item for item in index["steps"] if item["step"] == step["step"])
            current["status"] = "completed"
            current["summary"] = "스킬 테스트 API를 추가한다"
            self.write_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json", index)
            return {}

        self.execute.progress_indicator = fake_progress
        executor.run_developer_worker = fake_invoke
        executor.review_step_result = MagicMock(return_value=self.execute.reviewer_worker.ReviewResult("pass", "OK"))
        executor.run_acceptance_checks = MagicMock(return_value={"passed": True})
        self.write_ac_output()

        with patch.object(self.execute.git_ops, "list_worktree_paths", return_value=["src/main/java/com/commerce/skilltest/ApiService.java"]):
            with patch.object(self.execute.commit_agent, "run") as mock_commit_agent:
                result = executor.execute_single_step({"step": 2, "name": "api"})

        self.assertTrue(result)
        mock_commit_agent.assert_called_once_with(executor.root, executor.phase_dir, ANY)
        executor.review_step_result.assert_called_once()

    def test_execute_single_step_blocked_updates_top_index(self):
        executor = self.make_executor()

        @contextmanager
        def fake_progress(_label: str):
            yield types.SimpleNamespace(elapsed=0.0)

        def fake_invoke(step: dict, _context: str, _guardrails: str):
            self.write_step_output()
            index = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")
            current = next(item for item in index["steps"] if item["step"] == step["step"])
            current["status"] = "blocked"
            current["blocked_reason"] = "manual setup needed"
            self.write_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json", index)
            return {}

        self.execute.progress_indicator = fake_progress
        executor.run_developer_worker = fake_invoke
        executor.run_acceptance_checks = MagicMock(return_value={"passed": True})
        self.write_ac_output()

        with self.assertRaises(SystemExit) as exc:
            executor.execute_single_step({"step": 2, "name": "api"})

        self.assertEqual(2, exc.exception.code)
        task = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "index.json")
        self.assertEqual("blocked", task["phases"][0]["status"])

    def test_execute_single_step_marks_error_after_retries(self):
        executor = self.make_executor()
        executor.MAX_RETRIES = 2

        @contextmanager
        def fake_progress(_label: str):
            yield types.SimpleNamespace(elapsed=0.0)

        def fake_invoke(step: dict, _context: str, _guardrails: str):
            self.write_step_output(stderr="테스트 실패", last_message="테스트 실패")
            index = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")
            current = next(item for item in index["steps"] if item["step"] == step["step"])
            current["status"] = "pending"
            current["error_message"] = "AC failed"
            self.write_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json", index)
            return {}

        self.execute.progress_indicator = fake_progress
        executor.run_developer_worker = fake_invoke
        executor.run_acceptance_checks = MagicMock(return_value={"passed": False})

        with self.assertRaises(SystemExit) as exc:
            executor.execute_single_step({"step": 2, "name": "api"})

        self.assertEqual(1, exc.exception.code)
        index = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")
        self.assertEqual("error", index["steps"][2]["status"])
        task = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "index.json")
        self.assertEqual("error", task["phases"][0]["status"])

    def test_finalize_commits_index_state_and_pushes_when_enabled(self):
        """task 문서 잔여 변경분이 없는 경우 chore: 커밋만 만들어진다 (idempotent)."""
        executor = self.make_executor(auto_push=True)
        executor.branch_name = "feature/skill-test"
        calls = []
        diff_call_count = {"count": 0}

        def fake_git(*args):
            calls.append(args)
            if args[:2] == ("diff", "--cached"):
                diff_call_count["count"] += 1
                # 첫 번째 diff: task 문서 잔여 변경분 없음 → docs commit 생략
                # 두 번째 diff: phase index 변경 있음 → chore commit
                if diff_call_count["count"] == 1:
                    return MagicMock(returncode=0, stdout="", stderr="")
                return MagicMock(returncode=1, stdout="", stderr="")
            return MagicMock(returncode=0, stdout="", stderr="")

        executor.run_git = fake_git
        executor.finalize()

        index = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")
        self.assertIn("completed_at", index)
        task = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "index.json")
        self.assertEqual("completed", task["phases"][0]["status"])
        self.assertIn(("push", "-u", "origin", "feature/skill-test"), calls)
        self.assertIn(
            (
                "add",
                "--all",
                "--",
                "docs/tasks/skill-test/phases/0-mvp/index.json",
                "docs/tasks/skill-test/phases/index.json",
            ),
            calls,
        )
        commit_calls = [call for call in calls if call[0] == "commit"]
        self.assertEqual(1, len(commit_calls))
        self.assertIn("chore: mvp 실행 상태를 기록한다", commit_calls[0])

    def test_finalize_commits_remaining_task_docs_when_changes_present(self):
        """task 문서 잔여 변경분이 있으면 docs: 커밋과 chore: 커밋 두 개가 만들어진다."""
        executor = self.make_executor(auto_push=False)
        executor.branch_name = "chore/skill-test"
        calls = []

        def fake_git(*args):
            calls.append(args)
            # 모든 diff --cached가 returncode=1 (변경분 있음)
            if args[:2] == ("diff", "--cached"):
                return MagicMock(returncode=1, stdout="", stderr="")
            return MagicMock(returncode=0, stdout="", stderr="")

        executor.run_git = fake_git
        executor.finalize()

        commit_calls = [call for call in calls if call[0] == "commit"]
        self.assertEqual(2, len(commit_calls))
        self.assertIn("docs: skill-test 태스크 문서 변경분을 반영한다", commit_calls[0])
        self.assertIn("chore: mvp 실행 상태를 기록한다", commit_calls[1])

        # phase index reset 호출 검증 (chore 커밋용으로 분리되었는지)
        # task-level index와 모든 phase index를 와일드카드로 제외
        reset_calls = [call for call in calls if call[0] == "reset"]
        self.assertTrue(
            any("docs/tasks/skill-test/phases/index.json" in call for call in reset_calls)
        )
        self.assertTrue(
            any("docs/tasks/skill-test/phases/*/index.json" in call for call in reset_calls)
        )

    def test_execute_single_step_retries_when_completed_has_no_summary(self):
        executor = self.make_executor()
        executor.MAX_RETRIES = 1

        @contextmanager
        def fake_progress(_label: str):
            yield types.SimpleNamespace(elapsed=0.0)

        def fake_invoke(step: dict, _context: str, _guardrails: str):
            self.write_step_output()
            index = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")
            current = next(item for item in index["steps"] if item["step"] == step["step"])
            current["status"] = "completed"
            self.write_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json", index)

        self.execute.progress_indicator = fake_progress
        executor.run_developer_worker = fake_invoke
        executor.run_acceptance_checks = MagicMock(return_value={"passed": True})

        with self.assertRaises(SystemExit) as exc:
            executor.execute_single_step({"step": 2, "name": "api"})

        self.assertEqual(1, exc.exception.code)
        index = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")
        self.assertEqual("error", index["steps"][2]["status"])
        self.assertIn("summary", index["steps"][2]["error_message"])

    def test_execute_single_step_retries_when_output_missing(self):
        executor = self.make_executor()
        executor.MAX_RETRIES = 1

        @contextmanager
        def fake_progress(_label: str):
            yield types.SimpleNamespace(elapsed=0.0)

        def fake_invoke(step: dict, _context: str, _guardrails: str):
            output_path = self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "step2-output.json"
            output_path.unlink(missing_ok=True)
            index = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")
            current = next(item for item in index["steps"] if item["step"] == step["step"])
            current["status"] = "completed"
            current["summary"] = "API 구현 완료"
            self.write_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json", index)

        self.execute.progress_indicator = fake_progress
        executor.run_developer_worker = fake_invoke

        with self.assertRaises(SystemExit) as exc:
            executor.execute_single_step({"step": 2, "name": "api"})

        self.assertEqual(1, exc.exception.code)
        index = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")
        self.assertEqual("error", index["steps"][2]["status"])
        self.assertIn("step2-output.json", index["steps"][2]["error_message"])

    def test_execute_single_step_retries_when_review_worker_rejects(self):
        executor = self.make_executor()
        executor.MAX_RETRIES = 1

        @contextmanager
        def fake_progress(_label: str):
            yield types.SimpleNamespace(elapsed=0.0)

        def fake_invoke(step: dict, _context: str, _guardrails: str):
            self.write_step_output()
            index = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")
            current = next(item for item in index["steps"] if item["step"] == step["step"])
            current["status"] = "completed"
            current["summary"] = "API 구현 완료"
            self.write_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json", index)

        self.execute.progress_indicator = fake_progress
        executor.run_developer_worker = fake_invoke
        executor.review_step_result = MagicMock(return_value=self.execute.reviewer_worker.ReviewResult("retryable_error", "회귀 위험이 남아 있습니다."))
        executor.run_acceptance_checks = MagicMock(return_value={"passed": True})

        with patch.object(self.execute.git_ops, "list_worktree_paths", return_value=["src/main/java/com/commerce/skilltest/ApiService.java"]):
            self.write_ac_output()
            with self.assertRaises(SystemExit) as exc:
                executor.execute_single_step({"step": 2, "name": "api"})

        self.assertEqual(1, exc.exception.code)
        index = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")
        self.assertEqual("error", index["steps"][2]["status"])
        self.assertIn("회귀 위험", index["steps"][2]["error_message"])

    def test_execute_single_step_retries_when_acceptance_rerun_fails(self):
        executor = self.make_executor()
        executor.MAX_RETRIES = 1

        @contextmanager
        def fake_progress(_label: str):
            yield types.SimpleNamespace(elapsed=0.0)

        def fake_invoke(step: dict, _context: str, _guardrails: str):
            self.write_step_output(stdout="done", last_message="done")
            index = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")
            current = next(item for item in index["steps"] if item["step"] == step["step"])
            current["status"] = "completed"
            current["summary"] = "API 구현 완료"
            self.write_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json", index)

        def fake_run_acceptance(current: dict, _step_text: str):
            self.write_ac_output(passed=False, exit_code=1)
            return {"passed": False}

        self.execute.progress_indicator = fake_progress
        executor.run_developer_worker = fake_invoke
        executor.run_acceptance_checks = fake_run_acceptance

        with self.assertRaises(SystemExit) as exc:
            executor.execute_single_step({"step": 2, "name": "api"})

        self.assertEqual(1, exc.exception.code)
        index = self.read_json(self.root / "docs" / "tasks" / "skill-test" / "phases" / "0-mvp" / "index.json")
        self.assertEqual("error", index["steps"][2]["status"])
        self.assertIn("Acceptance Criteria", index["steps"][2]["error_message"])

    def test_resolve_phase_dir_exits_when_directory_missing(self):
        executor = self.make_executor()
        with self.assertRaises(SystemExit) as exc:
            executor.resolve_phase_dir("docs/tasks/nonexistent/phases/0-mvp")
        self.assertEqual(1, exc.exception.code)

    def test_main_invalid_phase_exits(self):
        argv = self.execute.sys.argv
        self.execute.sys.argv = ["execute.py", "missing"]
        try:
            with self.assertRaises(SystemExit) as exc:
                self.execute.main()
            self.assertEqual(1, exc.exception.code)
        finally:
            self.execute.sys.argv = argv


if __name__ == "__main__":
    unittest.main()
