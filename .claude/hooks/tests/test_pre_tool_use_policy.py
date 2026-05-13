import importlib.util
import io
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch


def load_module():
    module_path = Path(__file__).resolve().parents[1] / "pre_tool_use_policy.py"
    spec = importlib.util.spec_from_file_location("pre_tool_use_policy", module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class PreToolUsePolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.module = load_module()

    def test_blocks_git_reset_hard(self):
        result = self.module.evaluate_command("git reset --hard HEAD~1")
        self.assertTrue(result.blocked)

    def test_blocks_git_checkout_dash_dash(self):
        result = self.module.evaluate_command("git checkout -- src/main/java/App.java")
        self.assertTrue(result.blocked)

    def test_blocks_rm_rf_variants(self):
        self.assertTrue(self.module.evaluate_command("rm -rf build").blocked)
        self.assertTrue(self.module.evaluate_command("sudo rm -fr /tmp/work").blocked)
        self.assertTrue(self.module.evaluate_command("sudo -u root rm -rf /tmp/work").blocked)
        self.assertTrue(self.module.evaluate_command("sudo -- rm -rf /tmp/work").blocked)
        self.assertTrue(self.module.evaluate_command("env FOO=bar rm --recursive --force build").blocked)
        self.assertTrue(self.module.evaluate_command("env -- rm -rf build").blocked)
        self.assertTrue(self.module.evaluate_command("command -- rm -rf build").blocked)

    def test_blocks_force_push(self):
        self.assertTrue(self.module.evaluate_command("git push --force origin feature/test").blocked)
        self.assertTrue(self.module.evaluate_command("git push -f origin feature/test").blocked)
        self.assertTrue(self.module.evaluate_command("git -c credential.helper=store push --force origin main").blocked)

    def test_allows_read_only_commands(self):
        self.assertFalse(self.module.evaluate_command("rg hooks .claude").blocked)
        self.assertFalse(self.module.evaluate_command("./gradlew test").blocked)
        self.assertFalse(self.module.evaluate_command("ls -la").blocked)

    def test_blocks_force_push_in_compound_command(self):
        self.assertTrue(self.module.evaluate_command('git commit -m "chore: fix" && git push --force').blocked)
        self.assertTrue(self.module.evaluate_command('git commit -m "chore: fix" && git push --force-with-lease').blocked)
        self.assertTrue(self.module.evaluate_command('git add . ; git commit -m "fix" ; git push -f').blocked)
        self.assertTrue(self.module.evaluate_command('git status || git push --force origin main').blocked)

    def test_does_not_split_on_separator_inside_quotes(self):
        self.assertFalse(self.module.evaluate_command('git commit -m "feat: add && remove"').blocked)
        self.assertFalse(self.module.evaluate_command("git commit -m 'chore: a; b'").blocked)

    def test_blocks_rm_rf_in_compound_command(self):
        self.assertTrue(self.module.evaluate_command("./gradlew build && rm -rf build").blocked)
        self.assertTrue(self.module.evaluate_command("rm -rf build ; echo done").blocked)

    def test_blocks_git_prefix_option_variants(self):
        self.assertTrue(self.module.evaluate_command("git -c core.editor=true reset --hard HEAD~1").blocked)
        self.assertTrue(self.module.evaluate_command("git --no-pager reset --hard HEAD~1").blocked)

    def test_main_emits_block_decision(self):
        stdin = io.StringIO(json.dumps({"tool_input": {"command": "git reset --hard HEAD"}}))
        stdout = io.StringIO()

        with patch("sys.stdin", stdin), patch("sys.stdout", stdout):
            exit_code = self.module.main()

        self.assertEqual(0, exit_code)
        output = json.loads(stdout.getvalue())
        # Claude Code hook 응답 형식: {"decision": "block", "reason": "..."}
        self.assertEqual("block", output["decision"])
        self.assertIn("reason", output)

    def test_main_ignores_invalid_stdin(self):
        stdin = io.StringIO("{not-json")
        stdout = io.StringIO()

        with patch("sys.stdin", stdin), patch("sys.stdout", stdout):
            exit_code = self.module.main()

        self.assertEqual(0, exit_code)
        self.assertEqual("", stdout.getvalue())

    def test_main_fail_open_for_non_object_payload(self):
        stdin = io.StringIO("[]")
        stdout = io.StringIO()

        with patch("sys.stdin", stdin), patch("sys.stdout", stdout):
            exit_code = self.module.main()

        self.assertEqual(0, exit_code)
        self.assertEqual("", stdout.getvalue())

    def test_main_fail_open_for_invalid_tool_input_type(self):
        stdin = io.StringIO(json.dumps({"tool_input": []}))
        stdout = io.StringIO()

        with patch("sys.stdin", stdin), patch("sys.stdout", stdout):
            exit_code = self.module.main()

        self.assertEqual(0, exit_code)
        self.assertEqual("", stdout.getvalue())

    def test_main_fail_open_for_non_string_command(self):
        stdin = io.StringIO(json.dumps({"tool_input": {"command": 123}}))
        stdout = io.StringIO()

        with patch("sys.stdin", stdin), patch("sys.stdout", stdout):
            exit_code = self.module.main()

        self.assertEqual(0, exit_code)
        self.assertEqual("", stdout.getvalue())


if __name__ == "__main__":
    unittest.main()
