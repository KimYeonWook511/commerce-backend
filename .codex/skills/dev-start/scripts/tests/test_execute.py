"""
execute.py 리팩터링 안전망 테스트.
참고 실행기와 동일한 책임을 현재 dev-start 실행기에서 안정적으로 유지하는지 검증한다.
"""

import importlib.util
import json
import tempfile
import types
import unittest
from contextlib import contextmanager
from datetime import datetime, timedelta
from pathlib import Path
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

        (self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp").mkdir(parents=True)
        (self.root / "AGENTS.md").write_text(
            "# AGENTS\n\n## 참고 문서\n- 설계 결정: `docs/ADR.md`\n- API 스펙: `docs/api-spec.md`\n- 기능별 문서 운영 가이드: `docs/features/README.md`\n",
            encoding="utf-8",
        )
        (self.root / "docs" / "architecture.md").write_text("# Architecture\nSome content", encoding="utf-8")
        (self.root / "docs" / "ADR.md").write_text("# ADR\nDecision", encoding="utf-8")
        (self.root / "docs" / "api-spec.md").write_text("# API\nSpec", encoding="utf-8")
        (self.root / "docs" / "features" / "README.md").write_text("# Features\nGuide", encoding="utf-8")
        (self.root / "docs" / "features" / "skill-test" / "prd.md").write_text("# Skill Test PRD\nFeature requirements", encoding="utf-8")
        (self.root / "docs" / "features" / "skill-test" / "architecture.md").write_text("# Skill Test Architecture\nFeature structure", encoding="utf-8")
        (self.root / "docs" / "features" / "skill-test" / "adr.md").write_text("# Skill Test ADR\nFeature decisions", encoding="utf-8")
        (self.root / "docs" / "features" / "skill-test" / "api-spec.md").write_text("# Skill Test API\nFeature contract", encoding="utf-8")
        (self.root / "docs" / "features" / "skill-test" / "db-schema.md").write_text("# Skill Test DB\nFeature schema", encoding="utf-8")

        self.write_json(
            self.root / "docs" / "features" / "skill-test" / "phases" / "index.json",
            {"phases": [{"dir": "0-mvp", "status": "pending"}, {"dir": "1-polish", "status": "pending"}]},
        )
        self.write_json(
            self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json",
            {
                "project": "TestProject",
                "phase": "mvp",
                "feature": "skill-test",
                "steps": [
                    {"step": 0, "name": "setup", "status": "completed", "summary": "프로젝트 초기화 완료"},
                    {"step": 1, "name": "core", "status": "completed", "summary": "핵심 로직 구현"},
                    {"step": 2, "name": "api", "status": "pending"},
                ],
            },
        )
        (self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "step2.md").write_text(
            "# Step 2: api\n\n"
            "`docs/ADR.md`와 `docs/api-spec.md`를 참고해 API를 구현하세요.\n\n"
            "## 수정 가능 경로\n\n"
            "- `src/main/java/com/commerce/skilltest/**`\n"
            "- `src/test/java/com/commerce/skilltest/**`\n"
            "- `docs/features/skill-test/**`\n",
            encoding="utf-8",
        )
        (self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "step2.md").write_text(
            (self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "step2.md").read_text(encoding="utf-8")
            + "\n## Acceptance Criteria\n\n```bash\n./gradlew test\n```\n",
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

    def write_step_output(self, *, exit_code: int = 0, stdout: str = "./gradlew test", stderr: str = "", last_message: str = "테스트로 ./gradlew test를 실행했다."):
        self.write_json(
            self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "step2-output.json",
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
            self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "step2-ac-output.json",
            {
                "step": 2,
                "commands": ["./gradlew test"],
                "results": [{"command": "./gradlew test", "exitCode": exit_code, "stdout": "ok", "stderr": ""}],
                "passed": passed,
            },
        )

    def make_executor(self, *, auto_push: bool = False):
        return self.execute.StepExecutor("docs/features/skill-test/phases/0-mvp", auto_push=auto_push)

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
        self.assertEqual("commerce-backend", load_execute_module().ROOT.name)

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
        step_text = (self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "step2.md").read_text(encoding="utf-8")

        result = executor.load_step_context(step_text)

        self.assertIn("프로젝트 규칙 (AGENTS.md)", result)
        self.assertIn("기능 문서 (docs/features/skill-test/prd.md)", result)
        self.assertIn("기능 문서 (docs/features/skill-test/architecture.md)", result)
        self.assertIn("기능 문서 (docs/features/skill-test/adr.md)", result)
        self.assertIn("기능 문서 (docs/features/skill-test/api-spec.md)", result)
        self.assertIn("기능 문서 (docs/features/skill-test/db-schema.md)", result)
        self.assertIn("관련 문서 (docs/ADR.md)", result)
        self.assertIn("관련 문서 (docs/api-spec.md)", result)

    def test_load_step_context_uses_divider(self):
        executor = self.make_executor()
        step_text = (self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "step2.md").read_text(encoding="utf-8")
        result = executor.load_step_context(step_text)
        self.assertIn("---", result)

    def test_load_step_context_skips_unreferenced_agents_docs(self):
        executor = self.make_executor()
        result = executor.load_step_context("# Step 2\n\n코드를 정리하세요.")
        self.assertIn("기능 문서 (docs/features/skill-test/architecture.md)", result)
        self.assertNotIn("관련 문서 (docs/ADR.md)", result)
        self.assertNotIn("관련 문서 (docs/api-spec.md)", result)
        self.assertNotIn("구조 규칙", result)

    def test_load_step_context_tolerates_missing_feature_docs(self):
        executor = self.make_executor()
        (self.root / "docs" / "features" / "skill-test" / "db-schema.md").unlink()
        result = executor.load_step_context("# Step 2\n\n코드를 정리하세요.")
        self.assertIn("기능 문서 (docs/features/skill-test/prd.md)", result)
        self.assertNotIn("기능 문서 (docs/features/skill-test/db-schema.md)", result)

    def test_build_previous_step_context_includes_completed_with_summary(self):
        index = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json")
        result = self.execute.StepExecutor.build_previous_step_context(index)
        self.assertIn("Step 0 (setup): 프로젝트 초기화 완료", result)
        self.assertIn("Step 1 (core): 핵심 로직 구현", result)

    def test_build_previous_step_context_excludes_pending(self):
        index = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json")
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
        self.assertIn("feat: mvp N단계 <step-name> 작업을 반영한다", result)
        self.assertIn("/docs/features/skill-test/phases/0-mvp/index.json", result)
        self.assertIn("git add/commit/push/checkout은 실행하지 마라", result)

    def test_parse_editable_paths_returns_declared_paths(self):
        executor = self.make_executor()
        step_text = (self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "step2.md").read_text(encoding="utf-8")
        result = executor.parse_editable_paths(step_text)
        self.assertEqual(
            [
                "src/main/java/com/commerce/skilltest/**",
                "src/test/java/com/commerce/skilltest/**",
                "docs/features/skill-test/**",
            ],
            result,
        )

    def test_parse_editable_paths_exits_when_section_missing(self):
        executor = self.make_executor()
        with self.assertRaises(SystemExit) as exc:
            executor.parse_editable_paths("# Step 2: api\n\n## 작업\n- API를 구현한다.\n")
        self.assertEqual(1, exc.exception.code)

    def test_parse_editable_paths_exits_when_section_empty(self):
        executor = self.make_executor()
        with self.assertRaises(SystemExit) as exc:
            executor.parse_editable_paths("# Step 2: api\n\n## 수정 가능 경로\n\n## 작업\n- API를 구현한다.\n")
        self.assertEqual(1, exc.exception.code)

    def test_update_feature_index_completed(self):
        executor = self.make_executor()
        executor.update_feature_index("completed")
        feature = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "index.json")
        self.assertEqual("completed", feature["phases"][0]["status"])
        self.assertIn("completed_at", feature["phases"][0])

    def test_missing_feature_index_exits(self):
        (self.root / "docs" / "features" / "skill-test" / "phases" / "index.json").unlink()
        with self.assertRaises(SystemExit) as exc:
            self.make_executor()
        self.assertEqual(1, exc.exception.code)

    def test_phase_missing_from_feature_index_exits(self):
        self.write_json(
            self.root / "docs" / "features" / "skill-test" / "phases" / "index.json",
            {"phases": [{"dir": "1-polish", "status": "pending"}]},
        )
        with self.assertRaises(SystemExit) as exc:
            self.make_executor()
        self.assertEqual(1, exc.exception.code)

    def test_check_blockers_error_exits(self):
        index = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json")
        index["steps"][2] = {"step": 2, "name": "api", "status": "error", "error_message": "fail"}
        self.write_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json", index)
        with self.assertRaises(SystemExit) as exc:
            self.make_executor().check_blockers()
        self.assertEqual(1, exc.exception.code)

    def test_check_blockers_blocked_exits(self):
        index = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json")
        index["steps"][2] = {"step": 2, "name": "api", "status": "blocked", "blocked_reason": "API key"}
        self.write_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json", index)
        with self.assertRaises(SystemExit) as exc:
            self.make_executor().check_blockers()
        self.assertEqual(2, exc.exception.code)

    def test_mark_started_writes_once(self):
        executor = self.make_executor()
        index_before = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json")
        self.assertNotIn("started_at", index_before["steps"][2])
        executor.mark_step_started(2)
        first = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json")["steps"][2]["started_at"]
        executor.mark_step_started(2)
        second = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json")["steps"][2]["started_at"]
        self.assertEqual(first, second)

    def test_ensure_step_file_exists_exits_when_missing(self):
        executor = self.make_executor()
        (self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "step2.md").unlink()
        with self.assertRaises(SystemExit) as exc:
            executor.ensure_step_file_exists(2)
        self.assertEqual(1, exc.exception.code)

    def test_checkout_branch_already_on_branch(self):
        executor = self.make_executor()
        self.mock_git(executor, [MagicMock(returncode=0, stdout="feature/skill-test\n", stderr="")])
        executor.checkout_branch()

    def test_checkout_branch_create_new_branch(self):
        executor = self.make_executor()
        self.mock_git(
            executor,
            [
                MagicMock(returncode=0, stdout="main\n", stderr=""),
                MagicMock(returncode=1, stdout="", stderr="not found"),
                MagicMock(returncode=0, stdout="", stderr=""),
            ],
        )
        executor.checkout_branch()

    def test_checkout_branch_fails_when_git_unavailable(self):
        executor = self.make_executor()
        self.mock_git(executor, [MagicMock(returncode=1, stdout="", stderr="not a git repo")])
        with self.assertRaises(SystemExit) as exc:
            executor.checkout_branch()
        self.assertEqual(1, exc.exception.code)

    def test_commit_step_uses_two_phase_commit(self):
        executor = self.make_executor()
        calls = []

        def fake_git(*args):
            calls.append(args)
            if args[:2] == ("diff", "--cached"):
                return MagicMock(returncode=1, stdout="", stderr="")
            return MagicMock(returncode=0, stdout="", stderr="")

        executor.run_git = fake_git
        with patch.object(self.execute.git_ops, "list_worktree_paths", return_value=["src/main/java/com/commerce/skilltest/ApiService.java"]):
            executor.commit_step(2, "api", ["src/main/java/com/commerce/skilltest/**"])
        commit_calls = [call for call in calls if call[0] == "commit"]
        self.assertEqual(2, len(commit_calls))
        self.assertIn("feat: mvp 2단계 api 작업을 반영한다", commit_calls[0][2])
        self.assertIn("chore: mvp 2단계 실행 결과를 기록한다", commit_calls[1][2])
        self.assertNotIn(("add", "-A"), calls)
        self.assertIn(("add", "--all", "--", "src/main/java/com/commerce/skilltest"), calls)
        self.assertIn(
            (
                "add",
                "--all",
                "--",
                "docs/features/skill-test/phases/0-mvp/step2-output.json",
                "docs/features/skill-test/phases/0-mvp/step2-ac-output.json",
                "docs/features/skill-test/phases/0-mvp/step2-review-output.json",
                "docs/features/skill-test/phases/0-mvp/index.json",
                "docs/features/skill-test/phases/index.json",
            ),
            calls,
        )

    def test_commit_step_exits_when_disallowed_change_exists(self):
        executor = self.make_executor()
        executor.run_git = MagicMock(return_value=MagicMock(returncode=0, stdout="", stderr=""))
        with patch.object(
            self.execute.git_ops,
            "list_worktree_paths",
            return_value=["src/main/java/com/commerce/auth/AuthService.java"],
        ):
            with self.assertRaises(SystemExit) as exc:
                executor.commit_step(2, "api", ["src/main/java/com/commerce/skilltest/**"])
        self.assertEqual(1, exc.exception.code)

    def test_run_developer_worker_writes_output_json(self):
        executor = self.make_executor()
        mock_result = MagicMock(returncode=0, stdout="{}", stderr="")
        with patch.object(self.execute.developer_worker.subprocess, "run", return_value=mock_result):
            executor.run_developer_worker({"step": 2, "name": "api"}, "CONTEXT", "GUARD")

        output = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "step2-output.json")
        self.assertEqual(2, output["step"])
        self.assertEqual("api", output["name"])

    def test_progress_indicator_sets_elapsed(self):
        with self.execute.progress_indicator("test") as info:
            time_result = types.SimpleNamespace()
            self.assertTrue(hasattr(time_result, "__class__"))
        self.assertGreaterEqual(info.elapsed, 0.0)

    def test_execute_single_step_completed_commits_and_returns_true(self):
        executor = self.make_executor()

        @contextmanager
        def fake_progress(_label: str):
            yield types.SimpleNamespace(elapsed=0.0)

        def fake_invoke(step: dict, _context: str, _guardrails: str):
            self.write_step_output()
            index = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json")
            current = next(item for item in index["steps"] if item["step"] == step["step"])
            current["status"] = "completed"
            current["summary"] = "API 구현 완료"
            self.write_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json", index)
            return {}

        self.execute.progress_indicator = fake_progress
        executor.run_developer_worker = fake_invoke
        executor.commit_step = MagicMock()
        executor.review_step_result = MagicMock(return_value=self.execute.reviewer_worker.ReviewResult("pass", "OK"))
        executor.list_review_changed_paths = MagicMock(return_value=["src/main/java/com/commerce/skilltest/ApiService.java"])
        executor.run_acceptance_checks = MagicMock(return_value={"passed": True})
        executor.build_review_diff = MagicMock(return_value="diff --git a/a b/a")
        with patch.object(self.execute.git_ops, "validate_worktree_scope"):
            self.write_ac_output()
            result = executor.execute_single_step({"step": 2, "name": "api"})

        self.assertTrue(result)
        executor.commit_step.assert_called_once_with(
            2,
            "api",
            [
                "src/main/java/com/commerce/skilltest/**",
                "src/test/java/com/commerce/skilltest/**",
                "docs/features/skill-test/**",
            ],
        )
        executor.review_step_result.assert_called_once()
        executor.build_review_diff.assert_called_once()

    def test_execute_single_step_blocked_updates_top_index(self):
        executor = self.make_executor()

        @contextmanager
        def fake_progress(_label: str):
            yield types.SimpleNamespace(elapsed=0.0)

        def fake_invoke(step: dict, _context: str, _guardrails: str):
            self.write_step_output()
            index = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json")
            current = next(item for item in index["steps"] if item["step"] == step["step"])
            current["status"] = "blocked"
            current["blocked_reason"] = "manual setup needed"
            self.write_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json", index)
            return {}

        self.execute.progress_indicator = fake_progress
        executor.run_developer_worker = fake_invoke
        executor.run_acceptance_checks = MagicMock(return_value={"passed": True})
        self.write_ac_output()

        with self.assertRaises(SystemExit) as exc:
            executor.execute_single_step({"step": 2, "name": "api"})

        self.assertEqual(2, exc.exception.code)
        feature = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "index.json")
        self.assertEqual("blocked", feature["phases"][0]["status"])

    def test_execute_single_step_marks_error_after_retries(self):
        executor = self.make_executor()
        executor.MAX_RETRIES = 2

        @contextmanager
        def fake_progress(_label: str):
            yield types.SimpleNamespace(elapsed=0.0)

        def fake_invoke(step: dict, _context: str, _guardrails: str):
            self.write_step_output(stderr="테스트 실패", last_message="테스트 실패")
            index = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json")
            current = next(item for item in index["steps"] if item["step"] == step["step"])
            current["status"] = "pending"
            current["error_message"] = "AC failed"
            self.write_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json", index)
            return {}

        self.execute.progress_indicator = fake_progress
        executor.run_developer_worker = fake_invoke
        executor.commit_step = MagicMock()
        executor.run_acceptance_checks = MagicMock(return_value={"passed": False})

        with self.assertRaises(SystemExit) as exc:
            executor.execute_single_step({"step": 2, "name": "api"})

        self.assertEqual(1, exc.exception.code)
        index = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json")
        self.assertEqual("error", index["steps"][2]["status"])
        feature = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "index.json")
        self.assertEqual("error", feature["phases"][0]["status"])
        executor.commit_step.assert_called_once_with(
            2,
            "api",
            [
                "src/main/java/com/commerce/skilltest/**",
                "src/test/java/com/commerce/skilltest/**",
                "docs/features/skill-test/**",
            ],
        )

    def test_finalize_marks_completed_and_pushes_when_enabled(self):
        executor = self.make_executor(auto_push=True)
        calls = []

        def fake_git(*args):
            calls.append(args)
            if args[:2] == ("diff", "--cached"):
                return MagicMock(returncode=1, stdout="", stderr="")
            return MagicMock(returncode=0, stdout="", stderr="")

        executor.run_git = fake_git
        with patch.object(self.execute.git_ops, "list_worktree_paths", return_value=["docs/features/skill-test/phases/0-mvp/index.json"]):
            executor.finalize()

        index = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json")
        self.assertIn("completed_at", index)
        feature = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "index.json")
        self.assertEqual("completed", feature["phases"][0]["status"])
        self.assertIn(("push", "-u", "origin", "feature/skill-test"), calls)
        self.assertIn(
            (
                "add",
                "--all",
                "--",
                "docs/features/skill-test/phases/0-mvp/index.json",
                "docs/features/skill-test/phases/index.json",
            ),
            calls,
        )

    def test_execute_single_step_retries_when_completed_has_no_summary(self):
        executor = self.make_executor()
        executor.MAX_RETRIES = 1

        @contextmanager
        def fake_progress(_label: str):
            yield types.SimpleNamespace(elapsed=0.0)

        def fake_invoke(step: dict, _context: str, _guardrails: str):
            self.write_step_output()
            index = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json")
            current = next(item for item in index["steps"] if item["step"] == step["step"])
            current["status"] = "completed"
            self.write_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json", index)

        self.execute.progress_indicator = fake_progress
        executor.run_developer_worker = fake_invoke
        executor.commit_step = MagicMock()
        executor.run_acceptance_checks = MagicMock(return_value={"passed": True})

        with self.assertRaises(SystemExit) as exc:
            executor.execute_single_step({"step": 2, "name": "api"})

        self.assertEqual(1, exc.exception.code)
        index = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json")
        self.assertEqual("error", index["steps"][2]["status"])
        self.assertIn("summary", index["steps"][2]["error_message"])

    def test_execute_single_step_retries_when_output_missing(self):
        executor = self.make_executor()
        executor.MAX_RETRIES = 1

        @contextmanager
        def fake_progress(_label: str):
            yield types.SimpleNamespace(elapsed=0.0)

        def fake_invoke(step: dict, _context: str, _guardrails: str):
            output_path = self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "step2-output.json"
            output_path.unlink(missing_ok=True)
            index = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json")
            current = next(item for item in index["steps"] if item["step"] == step["step"])
            current["status"] = "completed"
            current["summary"] = "API 구현 완료"
            self.write_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json", index)

        self.execute.progress_indicator = fake_progress
        executor.run_developer_worker = fake_invoke
        executor.commit_step = MagicMock()

        with self.assertRaises(SystemExit) as exc:
            executor.execute_single_step({"step": 2, "name": "api"})

        self.assertEqual(1, exc.exception.code)
        index = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json")
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
            index = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json")
            current = next(item for item in index["steps"] if item["step"] == step["step"])
            current["status"] = "completed"
            current["summary"] = "API 구현 완료"
            self.write_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json", index)

        self.execute.progress_indicator = fake_progress
        executor.run_developer_worker = fake_invoke
        executor.commit_step = MagicMock()
        executor.review_step_result = MagicMock(return_value=self.execute.reviewer_worker.ReviewResult("retryable_error", "회귀 위험이 남아 있습니다."))
        executor.list_review_changed_paths = MagicMock(return_value=["src/main/java/com/commerce/skilltest/ApiService.java"])
        executor.run_acceptance_checks = MagicMock(return_value={"passed": True})
        executor.build_review_diff = MagicMock(return_value="diff --git a/a b/a")

        with patch.object(self.execute.git_ops, "validate_worktree_scope"):
            self.write_ac_output()
            with self.assertRaises(SystemExit) as exc:
                executor.execute_single_step({"step": 2, "name": "api"})

        self.assertEqual(1, exc.exception.code)
        index = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json")
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
            index = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json")
            current = next(item for item in index["steps"] if item["step"] == step["step"])
            current["status"] = "completed"
            current["summary"] = "API 구현 완료"
            self.write_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json", index)

        def fake_run_acceptance(current: dict, _step_text: str):
            self.write_ac_output(passed=False, exit_code=1)
            return {"passed": False}

        self.execute.progress_indicator = fake_progress
        executor.run_developer_worker = fake_invoke
        executor.run_acceptance_checks = fake_run_acceptance
        executor.commit_step = MagicMock()

        with self.assertRaises(SystemExit) as exc:
            executor.execute_single_step({"step": 2, "name": "api"})

        self.assertEqual(1, exc.exception.code)
        index = self.read_json(self.root / "docs" / "features" / "skill-test" / "phases" / "0-mvp" / "index.json")
        self.assertEqual("error", index["steps"][2]["status"])
        self.assertIn("Acceptance Criteria", index["steps"][2]["error_message"])

    def test_finalize_exits_when_unrelated_change_exists(self):
        executor = self.make_executor()
        executor.run_git = MagicMock(return_value=MagicMock(returncode=0, stdout="", stderr=""))
        with patch.object(
            self.execute.git_ops,
            "list_worktree_paths",
            return_value=["src/main/java/com/commerce/auth/AuthService.java"],
        ):
            with self.assertRaises(SystemExit) as exc:
                executor.finalize()
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
