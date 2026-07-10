"""pre_tool_use_policy 정책 테스트.

실행:
    python3 -m unittest discover -s .claude/hooks/tests
"""

from __future__ import annotations

import io
import json
import os
import sys
import unittest
from unittest.mock import patch

# 스크립트(.claude/hooks/pre_tool_use_policy.py)를 import 경로에 추가
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

import pre_tool_use_policy as policy  # noqa: E402


class PolicyTestBase(unittest.TestCase):
    """command + 현재 브랜치를 주입해 evaluate_bash 결과를 검증한다."""

    def evaluate(self, command: str, branch: str):
        # detect_current_branch(git 호출)를 주입한 브랜치로 대체
        with patch.object(policy, "detect_current_branch", return_value=branch):
            return policy.evaluate_bash(command)

    def assertBlocked(self, command: str, branch: str):
        result = self.evaluate(command, branch)
        self.assertTrue(result.blocked, f"차단 기대했으나 허용됨: ({branch}) {command}")

    def assertAllowed(self, command: str, branch: str):
        result = self.evaluate(command, branch)
        self.assertFalse(
            result.blocked,
            f"허용 기대했으나 차단됨: ({branch}) {command}\n사유: {result.reason}",
        )


class ProtectedBranchPush(PolicyTestBase):
    def test_direct_push_to_protected_blocked(self):
        self.assertBlocked("git push origin develop", "feature/x")
        self.assertBlocked("git push origin main", "feature/x")

    def test_current_branch_push_on_protected_blocked(self):
        self.assertBlocked("git push", "develop")
        self.assertBlocked("git push", "main")

    def test_head_refspec_blocked(self):
        self.assertBlocked("git push origin HEAD:develop", "feature/x")
        self.assertBlocked("git push -f origin HEAD:main", "feature/x")

    def test_delete_protected_blocked(self):
        self.assertBlocked("git push origin :develop", "feature/x")
        self.assertBlocked("git push --delete origin main", "feature/x")

    def test_all_and_mirror_blocked(self):
        self.assertBlocked("git push --all", "feature/x")
        self.assertBlocked("git push --mirror", "feature/x")

    def test_plus_refspec_blocked(self):
        self.assertBlocked("git push origin +develop", "feature/x")

    # ── 추가: refspec 파서 경계 ──────────────────────────────────────────────
    def test_refs_heads_prefix_blocked(self):
        # refs/heads/ 접두사가 붙어도 대상 브랜치로 인식해 차단
        self.assertBlocked("git push origin refs/heads/main", "feature/x")
        self.assertBlocked("git push origin HEAD:refs/heads/develop", "feature/x")

    def test_value_option_skipped_in_parsing(self):
        # `-o <value>`의 값이 positional(원격/refspec)로 오인되지 않고, 대상 develop이 차단됨
        self.assertBlocked("git push -o ci.skip origin develop", "feature/x")

    def test_force_all_blocked(self):
        self.assertBlocked("git push --all --force origin", "feature/x")


class FeatureBranchFreedom(PolicyTestBase):
    def test_push_current_feature_allowed(self):
        self.assertAllowed("git push", "feature/x")
        self.assertAllowed("git push -u origin feature/x", "feature/x")

    def test_force_push_feature_allowed(self):
        self.assertAllowed("git push --force-with-lease origin feature/x", "feature/x")
        self.assertAllowed("git push -f origin feature/x", "feature/x")
        self.assertAllowed("git push --force origin feature/x", "feature/x")

    def test_destructive_on_feature_allowed(self):
        self.assertAllowed("rm -rf build", "feature/x")
        self.assertAllowed("git reset --hard HEAD~1", "feature/x")
        self.assertAllowed("git checkout -- src/Main.java", "feature/x")
        self.assertAllowed("git restore src/Main.java", "feature/x")

    def test_commit_and_merge_on_feature_allowed(self):
        self.assertAllowed("git commit -m 'feat: x를 추가한다'", "feature/x")
        self.assertAllowed("git merge develop", "feature/x")
        self.assertAllowed("git rebase develop", "feature/x")

    # ── 추가: 피처에서 amend/continue 등 히스토리 조작도 자유 ──────────────────
    def test_amend_and_continue_on_feature_allowed(self):
        self.assertAllowed("git commit --amend -m x", "feature/x")
        self.assertAllowed("git rebase --continue", "feature/x")
        self.assertAllowed("git cherry-pick abc123", "feature/x")


class ProtectedBranchWrites(PolicyTestBase):
    def test_commit_history_writes_blocked(self):
        self.assertBlocked("git commit -m 'x'", "develop")
        self.assertBlocked("git merge feature/x", "main")
        self.assertBlocked("git rebase develop", "develop")
        self.assertBlocked("git cherry-pick abc123", "develop")
        self.assertBlocked("git revert abc123", "develop")

    # ── 추가: am, amend ──────────────────────────────────────────────────────
    def test_am_blocked(self):
        self.assertBlocked("git am patch.mbox", "develop")

    def test_commit_amend_blocked(self):
        self.assertBlocked("git commit --amend -m x", "develop")

    def test_safe_subcommand_actions_allowed(self):
        self.assertAllowed("git merge --abort", "develop")
        self.assertAllowed("git rebase --abort", "develop")
        self.assertAllowed("git cherry-pick --quit", "develop")
        self.assertAllowed("git commit --dry-run", "develop")

    # ── 추가: am --abort 도 안전 동작으로 허용 ────────────────────────────────
    def test_am_abort_allowed(self):
        self.assertAllowed("git am --abort", "develop")
        self.assertAllowed("git am --quit", "develop")

    # ── 추가: --continue / --skip 은 커밋을 만들 수 있으므로 차단 (회귀 방지) ──
    def test_continue_and_skip_blocked(self):
        self.assertBlocked("git rebase --continue", "develop")
        self.assertBlocked("git cherry-pick --continue", "develop")
        self.assertBlocked("git rebase --skip", "main")
        self.assertBlocked("git revert --continue", "develop")

    def test_pull_fetch_allowed_on_protected(self):
        self.assertAllowed("git fetch", "develop")
        self.assertAllowed("git pull", "develop")
        self.assertAllowed("git status", "develop")
        self.assertAllowed("git add .", "develop")


class DestructiveOnProtected(PolicyTestBase):
    def test_reset_hard_blocked(self):
        self.assertBlocked("git reset --hard HEAD~1", "develop")

    def test_checkout_file_blocked(self):
        self.assertBlocked("git checkout -- src/Main.java", "main")

    def test_restore_worktree_blocked(self):
        self.assertBlocked("git restore src/Main.java", "develop")

    def test_restore_staged_only_allowed(self):
        self.assertAllowed("git restore --staged src/Main.java", "develop")

    # ── 추가: --staged --worktree 조합은 워킹트리를 건드리므로 차단 ────────────
    def test_restore_staged_and_worktree_blocked(self):
        self.assertBlocked("git restore --staged --worktree src/Main.java", "develop")

    def test_rm_rf_blocked(self):
        self.assertBlocked("rm -rf build", "develop")
        self.assertBlocked("rm -fr build", "develop")
        self.assertBlocked("rm --recursive --force build", "develop")

    # ── 추가: 단일 파일 삭제(rm file)는 보호 브랜치라도 허용 ──────────────────
    def test_plain_rm_allowed(self):
        self.assertAllowed("rm src/Old.java", "develop")
        self.assertAllowed("rm -f src/Old.java", "develop")  # -r 없으면 통과


class BranchNameBoundary(PolicyTestBase):
    """보호 브랜치 이름을 부분 문자열로 포함하는 브랜치는 보호 대상이 아니다(오탐 방지)."""

    def test_push_to_lookalike_branch_allowed(self):
        self.assertAllowed("git push origin develop-2", "feature/x")
        self.assertAllowed("git push origin mainline", "feature/x")
        self.assertAllowed("git push origin release/main", "feature/x")

    def test_commit_on_lookalike_branch_allowed(self):
        self.assertAllowed("git commit -m x", "develop-hotfix")
        self.assertAllowed("git commit -m x", "feature/main-refactor")
        self.assertAllowed("git reset --hard", "mainline")


class DetachedHead(PolicyTestBase):
    """detached HEAD(브랜치명 'HEAD')는 보호 브랜치가 아니다."""

    def test_current_branch_ops_allowed_on_detached(self):
        self.assertAllowed("git commit -m x", "HEAD")
        self.assertAllowed("git reset --hard", "HEAD")
        self.assertAllowed("git push", "HEAD")

    def test_explicit_protected_push_still_blocked_on_detached(self):
        self.assertBlocked("git push origin develop", "HEAD")


class NonGitCommands(PolicyTestBase):
    """git이 아닌 일반 명령은 보호 브랜치에서도 정책 대상이 아니다."""

    def test_common_commands_allowed_on_protected(self):
        self.assertAllowed("ls -la", "develop")
        self.assertAllowed("./gradlew test", "main")
        self.assertAllowed("rg hooks .claude", "develop")
        self.assertAllowed("cat build.gradle", "main")


class CompoundCommands(PolicyTestBase):
    def test_branch_switch_then_destructive_blocked(self):
        self.assertBlocked("git checkout develop && git reset --hard", "feature/x")
        self.assertBlocked("git switch main && git commit -m x", "feature/x")

    def test_switch_to_feature_then_destructive_allowed(self):
        self.assertAllowed("git switch feature/y && git reset --hard", "develop")

    def test_checkout_file_not_treated_as_switch(self):
        # `git checkout -- file`은 전환이 아니라 파일 복원이므로 이후 브랜치 기준이 안 바뀐다
        self.assertAllowed("git checkout -- file && git push origin feature/x", "feature/x")

    # ── 추가: 복합 명령 어느 한 부분이라도 위반이면 전체 차단 ──────────────────
    def test_any_violating_segment_blocks(self):
        self.assertBlocked("git status && git push origin main", "feature/x")
        self.assertBlocked("echo hi; git push origin develop", "feature/x")

    def test_checkout_b_new_branch_then_commit_allowed(self):
        # `git checkout -b feature/z` 로 새 피처 브랜치 전환 후 커밋은 허용
        self.assertAllowed("git checkout -b feature/z && git commit -m x", "develop")


class TokenNormalization(PolicyTestBase):
    def test_sudo_prefix(self):
        self.assertBlocked("sudo git push origin develop", "feature/x")

    def test_env_prefix(self):
        self.assertBlocked("env FOO=bar git push origin main", "feature/x")

    def test_git_c_prefix(self):
        self.assertBlocked("git -c user.name=x push origin develop", "feature/x")

    def test_command_prefix(self):
        self.assertBlocked("command git push origin develop", "feature/x")

    # ── 추가: 중첩/혼합 prefix 도 정규화되어 차단 ─────────────────────────────
    def test_nested_prefixes(self):
        self.assertBlocked("sudo git -c x=y push origin main", "feature/x")
        self.assertBlocked("sudo env FOO=bar git push origin develop", "feature/x")
        self.assertBlocked("env A=1 B=2 git commit -m x", "develop")


class FailOpen(PolicyTestBase):
    def test_broken_quoting_fail_open(self):
        # 닫히지 않은 따옴표 → 토큰화 실패 → 통과(fail-open)
        self.assertAllowed('git push "origin develop', "develop")

    def test_branch_detect_failure_fail_open_for_current(self):
        # 브랜치 탐지 실패("") → 현재 브랜치 의존 검사는 통과
        self.assertAllowed("git commit -m x", "")
        self.assertAllowed("git reset --hard", "")

    def test_explicit_protected_push_blocked_even_without_branch(self):
        # 단, refspec으로 명시된 보호 브랜치 push는 브랜치 탐지와 무관하게 차단
        self.assertBlocked("git push origin develop", "")


class MainEntrypoint(unittest.TestCase):
    """main()을 stdin/stdout mock으로 end-to-end 검증한다."""

    def _run_main(self, payload, branch):
        stdin = io.StringIO(json.dumps(payload))
        stdout = io.StringIO()
        with patch.object(policy, "detect_current_branch", return_value=branch), \
                patch.object(sys, "stdin", stdin), \
                patch.object(sys, "stdout", stdout):
            code = policy.main()
        return code, stdout.getvalue()

    def test_blocked_emits_deny(self):
        code, out = self._run_main(
            {"tool_name": "Bash", "tool_input": {"command": "git push origin develop"}},
            "feature/x",
        )
        self.assertEqual(code, 0)
        self.assertIn('"deny"', out)
        self.assertIn("permissionDecisionReason", out)

    def test_deny_payload_is_valid_json_with_hook_shape(self):
        # 차단 응답이 Claude Code hook 형식(JSON)을 충족하는지
        _, out = self._run_main(
            {"tool_name": "Bash", "tool_input": {"command": "git commit -m x"}},
            "develop",
        )
        parsed = json.loads(out)
        hso = parsed["hookSpecificOutput"]
        self.assertEqual(hso["hookEventName"], "PreToolUse")
        self.assertEqual(hso["permissionDecision"], "deny")
        self.assertTrue(hso["permissionDecisionReason"])

    def test_allowed_no_output(self):
        code, out = self._run_main(
            {"tool_name": "Bash", "tool_input": {"command": "git status"}},
            "develop",
        )
        self.assertEqual(code, 0)
        self.assertEqual(out, "")

    def test_non_bash_tool_passthrough(self):
        code, out = self._run_main(
            {"tool_name": "Write", "tool_input": {"file_path": "/x"}},
            "develop",
        )
        self.assertEqual(code, 0)
        self.assertEqual(out, "")

    def test_broken_json_fail_open(self):
        stdout = io.StringIO()
        with patch.object(sys, "stdin", io.StringIO("{not json")), \
                patch.object(sys, "stdout", stdout):
            code = policy.main()
        self.assertEqual(code, 0)
        self.assertEqual(stdout.getvalue(), "")

    # ── 추가: 입력 형식 방어 (모두 fail-open, 출력 없음) ──────────────────────
    def test_payload_not_dict_fail_open(self):
        stdout = io.StringIO()
        with patch.object(sys, "stdin", io.StringIO("[1, 2, 3]")), \
                patch.object(sys, "stdout", stdout):
            code = policy.main()
        self.assertEqual(code, 0)
        self.assertEqual(stdout.getvalue(), "")

    def test_tool_input_not_dict_fail_open(self):
        code, out = self._run_main(
            {"tool_name": "Bash", "tool_input": "git push origin develop"},
            "feature/x",
        )
        self.assertEqual(code, 0)
        self.assertEqual(out, "")

    def test_command_not_str_fail_open(self):
        code, out = self._run_main(
            {"tool_name": "Bash", "tool_input": {"command": ["git", "push"]}},
            "develop",
        )
        self.assertEqual(code, 0)
        self.assertEqual(out, "")

    def test_empty_command_passthrough(self):
        code, out = self._run_main(
            {"tool_name": "Bash", "tool_input": {"command": "   "}},
            "develop",
        )
        self.assertEqual(code, 0)
        self.assertEqual(out, "")


if __name__ == "__main__":
    unittest.main()
