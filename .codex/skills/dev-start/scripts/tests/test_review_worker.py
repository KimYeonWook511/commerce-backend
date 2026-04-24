import importlib.util
import sys
import unittest
from pathlib import Path


def load_module():
    module_path = Path(__file__).resolve().parent.parent / "reviewer_worker.py"
    spec = importlib.util.spec_from_file_location("reviewer_worker_module", module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class ReviewerWorkerTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()

    def test_parse_review_result_pass(self):
        result = self.module.parse_review_result("DECISION: pass\nMESSAGE: 문제 없음\n")
        self.assertEqual("pass", result.decision)
        self.assertEqual("문제 없음", result.message)

    def test_parse_review_result_invalid_format(self):
        result = self.module.parse_review_result("ok")
        self.assertEqual("retryable_error", result.decision)
        self.assertIn("출력 형식", result.message)

    def test_build_review_prompt_includes_changed_paths(self):
        prompt = self.module.build_prompt(
            "GUARD",
            {"step": 2, "name": "api", "status": "completed", "summary": "완료"},
            "# Step 2",
            ["src/main/java/com/commerce/skilltest/ApiService.java"],
            "diff --git a/a b/a",
            {"step": 2, "name": "api", "exitCode": 0, "stdout": "ok", "stderr": "", "lastMessage": "done"},
        )
        self.assertIn("ApiService.java", prompt)
        self.assertIn("## 변경 경로", prompt)
        self.assertIn("## 변경 diff", prompt)


if __name__ == "__main__":
    unittest.main()
