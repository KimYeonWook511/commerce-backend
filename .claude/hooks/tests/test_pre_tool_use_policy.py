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


class BlacklistTest(unittest.TestCase):
    """그 외(메인/일반) — 기존 블랙리스트가 유지되는지."""

    @classmethod
    def setUpClass(cls):
        cls.m = load_module()

    def ev(self, cmd):  # agent_type 없음 = 일반 작업
        return self.m.evaluate_bash(cmd, "")

    def test_blocks_git_reset_hard(self):
        self.assertTrue(self.ev("git reset --hard HEAD~1").blocked)

    def test_blocks_git_checkout_dash_dash(self):
        self.assertTrue(self.ev("git checkout -- src/main/java/App.java").blocked)

    def test_blocks_rm_rf_variants(self):
        self.assertTrue(self.ev("rm -rf build").blocked)
        self.assertTrue(self.ev("sudo rm -fr /tmp/work").blocked)
        self.assertTrue(self.ev("sudo -u root rm -rf /tmp/work").blocked)
        self.assertTrue(self.ev("env FOO=bar rm --recursive --force build").blocked)

    def test_blocks_force_push(self):
        self.assertTrue(self.ev("git push --force origin feature/test").blocked)
        self.assertTrue(self.ev("git push -f origin feature/test").blocked)
        self.assertTrue(self.ev("git -c credential.helper=store push --force origin main").blocked)

    def test_allows_read_only(self):
        self.assertFalse(self.ev("rg hooks .claude").blocked)
        self.assertFalse(self.ev("./gradlew test").blocked)
        self.assertFalse(self.ev("git status").blocked)

    def test_blocks_in_compound(self):
        self.assertTrue(self.ev('git commit -m "x" && git push --force').blocked)
        self.assertTrue(self.ev("./gradlew build && rm -rf build").blocked)
        self.assertTrue(self.ev("ls;rm -rf build").blocked)

    def test_does_not_split_inside_quotes(self):
        self.assertFalse(self.ev('git commit -m "feat: add && remove"').blocked)

    def test_git_prefix_option_variants(self):
        self.assertTrue(self.ev("git -c core.editor=true reset --hard HEAD~1").blocked)
        self.assertTrue(self.ev("git --no-pager reset --hard HEAD~1").blocked)


class CommitterWhitelistTest(unittest.TestCase):
    """harness-v3-committer — git 5개만 허용, 그 외 모든 Bash 차단."""

    @classmethod
    def setUpClass(cls):
        cls.m = load_module()

    def ev(self, cmd):
        return self.m.evaluate_bash(cmd, "harness-v3-committer")

    def test_allows_five(self):
        self.assertFalse(self.ev("git status").blocked)
        self.assertFalse(self.ev("git diff --cached").blocked)
        self.assertFalse(self.ev("git log --oneline -5").blocked)
        self.assertFalse(self.ev("git add -p src/").blocked)
        self.assertFalse(self.ev('git commit -m "feat: x"').blocked)

    def test_blocks_other_git(self):
        self.assertTrue(self.ev("git push origin main").blocked)
        self.assertTrue(self.ev("git reset HEAD~1").blocked)
        self.assertTrue(self.ev("git checkout develop").blocked)
        self.assertTrue(self.ev("git rebase main").blocked)
        self.assertTrue(self.ev("git stash").blocked)

    def test_blocks_commit_amend(self):
        # commit 서브커맨드라도 --amend(history 조작)는 차단
        self.assertTrue(self.ev("git commit --amend -m x").blocked)

    def test_blocks_non_git(self):
        self.assertTrue(self.ev("rm file.txt").blocked)
        self.assertTrue(self.ev('echo "x" > src/App.java').blocked)
        self.assertTrue(self.ev("./gradlew test").blocked)

    def test_blocks_other_git_in_compound(self):
        self.assertTrue(self.ev('git add . && git push').blocked)
        self.assertTrue(self.ev('git commit -m "x" ; git reset --hard').blocked)


class ReviewerWriteGuardTest(unittest.TestCase):
    """harness-v3-reviewer — review 핸드오프만 쓰기 허용."""

    @classmethod
    def setUpClass(cls):
        cls.m = load_module()

    def test_allows_review_handoff(self):
        r = self.m.evaluate_reviewer_write("docs/tasks/payment/phases/1-policy/handoff/step2-review.json")
        self.assertFalse(r.blocked)

    def test_blocks_code_write(self):
        self.assertTrue(self.m.evaluate_reviewer_write("src/main/java/App.java").blocked)
        self.assertTrue(self.m.evaluate_reviewer_write("docs/tasks/payment/adr.md").blocked)
        # dev 핸드오프에 쓰려는 시도도 차단 (review 핸드오프가 아님)
        self.assertTrue(self.m.evaluate_reviewer_write("docs/tasks/payment/phases/1-policy/handoff/step2-dev.json").blocked)


class MainDispatchTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.m = load_module()

    def run_main(self, payload):
        stdin = io.StringIO(json.dumps(payload))
        stdout = io.StringIO()
        with patch("sys.stdin", stdin), patch("sys.stdout", stdout):
            code = self.m.main()
        return code, stdout.getvalue()

    def test_blacklist_block_format(self):
        code, out = self.run_main({"tool_name": "Bash", "tool_input": {"command": "git reset --hard HEAD"}})
        self.assertEqual(0, code)
        payload = json.loads(out)
        self.assertEqual("deny", payload["hookSpecificOutput"]["permissionDecision"])
        self.assertEqual("PreToolUse", payload["hookSpecificOutput"]["hookEventName"])

    def test_committer_block(self):
        code, out = self.run_main({
            "agent_type": "harness-v3-committer", "tool_name": "Bash",
            "tool_input": {"command": "git push origin main"},
        })
        self.assertEqual("deny", json.loads(out)["hookSpecificOutput"]["permissionDecision"])

    def test_committer_allows_commit(self):
        code, out = self.run_main({
            "agent_type": "harness-v3-committer", "tool_name": "Bash",
            "tool_input": {"command": 'git commit -m "feat: x"'},
        })
        self.assertEqual("", out)  # 통과 = 출력 없음

    def test_reviewer_write_block(self):
        code, out = self.run_main({
            "agent_type": "harness-v3-reviewer", "tool_name": "Write",
            "tool_input": {"file_path": "src/main/java/App.java"},
        })
        self.assertEqual("deny", json.loads(out)["hookSpecificOutput"]["permissionDecision"])

    def test_reviewer_write_allows_handoff(self):
        code, out = self.run_main({
            "agent_type": "harness-v3-reviewer", "tool_name": "Write",
            "tool_input": {"file_path": "docs/tasks/x/phases/0-main/handoff/step1-review.json"},
        })
        self.assertEqual("", out)

    def test_fail_open_invalid(self):
        code, out = self.run_main_raw("{not-json")
        self.assertEqual(0, code)
        self.assertEqual("", out)

    def run_main_raw(self, raw):
        stdin = io.StringIO(raw)
        stdout = io.StringIO()
        with patch("sys.stdin", stdin), patch("sys.stdout", stdout):
            code = self.m.main()
        return code, stdout.getvalue()


if __name__ == "__main__":
    unittest.main()
