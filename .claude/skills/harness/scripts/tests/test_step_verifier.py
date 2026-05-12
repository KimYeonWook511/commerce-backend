import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


def load_module():
    module_path = Path(__file__).resolve().parent.parent / "step_verifier.py"
    spec = importlib.util.spec_from_file_location("step_verifier_module", module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class StepVerifierTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.output_path = self.root / "step2-output.json"
        self.ac_output_path = self.root / "step2-ac-output.json"
        self.step_text = "# Step 2\n\n## Acceptance Criteria\n\n```bash\n./gradlew test\n```\n"

    def tearDown(self):
        self.temp_dir.cleanup()

    def write_output(self, payload: dict):
        self.output_path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

    def write_ac_output(self, payload: dict):
        self.ac_output_path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

    def test_completed_requires_summary(self):
        self.write_output({"exitCode": 0, "stdout": "./gradlew test", "stderr": "", "lastMessage": "ok"})
        result = self.module.verify_step_result({"step": 2, "status": "completed"}, self.step_text, self.output_path)
        self.assertEqual("retryable_error", result.decision)
        self.assertIn("summary", result.message)

    def test_missing_output_is_retryable_error(self):
        result = self.module.verify_step_result({"step": 2, "status": "completed", "summary": "done"}, self.step_text, self.output_path)
        self.assertEqual("retryable_error", result.decision)
        self.assertIn("step2-output.json", result.message)

    def test_completed_requires_acceptance_output_when_requested(self):
        self.write_output({"exitCode": 0, "stdout": "", "stderr": "", "lastMessage": "done"})
        result = self.module.verify_step_result(
            {"step": 2, "status": "completed", "summary": "done"},
            self.step_text,
            self.output_path,
            self.ac_output_path,
        )
        self.assertEqual("retryable_error", result.decision)
        self.assertIn("step2-ac-output.json", result.message)

    def test_completed_requires_acceptance_output_to_pass(self):
        self.write_output({"exitCode": 0, "stdout": "done", "stderr": "", "lastMessage": "done"})
        self.write_ac_output(
            {
                "step": 2,
                "commands": ["./gradlew test"],
                "results": [{"command": "./gradlew test", "exitCode": 0, "stdout": "ok", "stderr": ""}],
                "passed": True,
            }
        )
        result = self.module.verify_step_result(
            {"step": 2, "status": "completed", "summary": "done"},
            self.step_text,
            self.output_path,
            self.ac_output_path,
        )
        self.assertEqual("pass", result.decision)

    def test_completed_fails_when_acceptance_output_failed(self):
        self.write_output({"exitCode": 0, "stdout": "done", "stderr": "", "lastMessage": "done"})
        self.write_ac_output(
            {
                "step": 2,
                "commands": ["./gradlew test"],
                "results": [{"command": "./gradlew test", "exitCode": 1, "stdout": "", "stderr": "fail"}],
                "passed": False,
            }
        )
        result = self.module.verify_step_result(
            {"step": 2, "status": "completed", "summary": "done"},
            self.step_text,
            self.output_path,
            self.ac_output_path,
        )
        self.assertEqual("retryable_error", result.decision)
        self.assertIn("재검증", result.message)


if __name__ == "__main__":
    unittest.main()
