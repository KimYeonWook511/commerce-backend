import importlib.util
import sys
import unittest
from pathlib import Path


def load_module():
    module_path = Path(__file__).resolve().parent.parent / "developer_guardrails.py"
    spec = importlib.util.spec_from_file_location("developer_guardrails_module", module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class DeveloperGuardrailsTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()

    def test_build_includes_retry_and_status_rules(self):
        result = self.module.build(
            project="TestProject",
            phase_name="mvp",
            phase_index_relpath="docs/tasks/skill-test/phases/0-mvp/index.json",
            max_retries=3,
            prev_error="타입 에러",
        )
        self.assertIn("developer worker", result)
        self.assertIn("이전 시도 실패", result)
        self.assertIn("completed` + `summary", result)
        self.assertIn("git add/commit/push/checkout", result)
        self.assertIn("실행 상태", result)
        self.assertIn("step 요구사항, Acceptance Criteria", result)
        self.assertIn("실패 회피 목적", result)
        self.assertIn("현재 step 외의 step 상태를 수정하지 마라", result)
        self.assertIn("status를 `pending`으로 되돌리지 마라", result)
        self.assertIn("실행 중 재시도 reset은 execute.py가 처리한다", result)
        self.assertIn("3회 시도 후에도 실패", result)
        self.assertIn("커밋하지 않는다", result)


if __name__ == "__main__":
    unittest.main()
