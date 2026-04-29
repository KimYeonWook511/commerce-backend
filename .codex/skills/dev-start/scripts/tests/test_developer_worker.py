import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock
from unittest.mock import patch


def load_module():
    module_path = Path(__file__).resolve().parent.parent / "developer_worker.py"
    spec = importlib.util.spec_from_file_location("developer_worker_module", module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class DeveloperWorkerTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.phase_dir = self.root / "phase"
        self.phase_dir.mkdir(parents=True)
        (self.phase_dir / "step2.md").write_text("# Step 2", encoding="utf-8")

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_build_prompt_joins_sections(self):
        prompt = self.module.build_prompt("CTX", "GUARD", "STEP")
        self.assertIn("CTX", prompt)
        self.assertIn("GUARD", prompt)
        self.assertIn("STEP", prompt)

    def test_build_codex_command_uses_ephemeral(self):
        command = self.module.build_codex_command(str(self.root), self.root / "message.txt")
        self.assertEqual(["codex", "exec"], command[:2])
        self.assertIn("--ephemeral", command)
        self.assertIn("--full-auto", command)
        self.assertIn("--skip-git-repo-check", command)

    def test_run_writes_output_file(self):
        mock_result = MagicMock(returncode=0, stdout="ok", stderr="")

        def write_json(path: Path, data: dict):
            path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")

        with patch.object(self.module.subprocess, "run", return_value=mock_result) as run_mock:
            self.module.run(str(self.root), self.phase_dir, write_json, {"step": 2, "name": "api"}, "CTX", "GUARD")

        called_command = run_mock.call_args.args[0]
        self.assertIn("--ephemeral", called_command)

        output = json.loads((self.phase_dir / "step2-output.json").read_text(encoding="utf-8"))
        self.assertEqual(2, output["step"])
        self.assertEqual("api", output["name"])


if __name__ == "__main__":
    unittest.main()
