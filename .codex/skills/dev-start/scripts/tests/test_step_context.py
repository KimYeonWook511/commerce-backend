import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


def load_module():
    module_path = Path(__file__).resolve().parent.parent / "step_context.py"
    spec = importlib.util.spec_from_file_location("step_context_module", module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class StepContextTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.task_dir = self.root / "docs" / "tasks" / "skill-test"
        self.task_dir.mkdir(parents=True)
        (self.root / "AGENTS.md").write_text(
            "# AGENTS\n\n## 참고 문서\n- 설계 결정: `docs/ADR.md`\n- API 스펙: `docs/api-spec.md`\n",
            encoding="utf-8",
        )
        (self.root / "docs" / "ADR.md").parent.mkdir(parents=True, exist_ok=True)
        (self.root / "docs" / "ADR.md").write_text("# ADR", encoding="utf-8")
        (self.root / "docs" / "api-spec.md").write_text("# API", encoding="utf-8")
        for filename in ("prd.md", "architecture.md", "adr.md", "api-spec.md", "db-schema.md"):
            (self.task_dir / filename).write_text(f"# {filename}", encoding="utf-8")

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_load_step_documents_includes_task_and_referenced_docs(self):
        result = self.module.load_step_documents(self.root, self.task_dir, "`docs/ADR.md`를 읽어라.")
        self.assertIn("프로젝트 규칙 (AGENTS.md)", result)
        self.assertIn("태스크 문서 (docs/tasks/skill-test/prd.md)", result)
        self.assertIn("관련 문서 (docs/ADR.md)", result)
        self.assertNotIn("관련 문서 (docs/api-spec.md)", result)

    def test_build_previous_step_context_uses_completed_summary_only(self):
        index = {
            "steps": [
                {"step": 0, "name": "setup", "status": "completed", "summary": "초기화 완료"},
                {"step": 1, "name": "core", "status": "pending"},
            ]
        }
        result = self.module.build_previous_step_context(index)
        self.assertIn("Step 0 (setup): 초기화 완료", result)
        self.assertNotIn("core", result)


if __name__ == "__main__":
    unittest.main()
