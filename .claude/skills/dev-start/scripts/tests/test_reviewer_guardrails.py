import importlib.util
import sys
import unittest
from pathlib import Path


def load_module():
    module_path = Path(__file__).resolve().parent.parent / "reviewer_guardrails.py"
    spec = importlib.util.spec_from_file_location("reviewer_guardrails_module", module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class ReviewerGuardrailsTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()

    def test_build_includes_review_rules(self):
        result = self.module.build("TestProject")
        self.assertIn("reviewer worker", result)
        self.assertIn("read-only", result)
        self.assertIn("DECISION: pass|retryable_error|blocked", result)


if __name__ == "__main__":
    unittest.main()
