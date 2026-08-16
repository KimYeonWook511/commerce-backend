"""pre_tool_use_policy 정책 테스트.

실행:
    python3 -m unittest discover -s .claude/hooks/tests
"""

from __future__ import annotations

import io
import json
import os
import sys
import tempfile
import unittest
from unittest.mock import patch

# 스크립트(.claude/hooks/pre_tool_use_policy.py)를 import 경로에 추가
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

import pre_tool_use_policy as policy  # noqa: E402

# 저장소 판별용 common dir. 아래 기본 테스트들은 모든 디렉터리가 이 저장소라고 본다.
SAME_REPO = "/repo/server/.git"


class PolicyTestBase(unittest.TestCase):
    """command + 현재 브랜치를 주입해 evaluate_bash 결과를 검증한다."""

    def evaluate(self, command: str, branch: str):
        # resolve_context(git 호출)를 주입한 브랜치로 대체 — 어느 디렉터리든 이 저장소로 본다
        with patch.object(policy, "resolve_context", return_value=(branch, SAME_REPO)), \
                patch.object(policy, "project_common_dir", return_value=SAME_REPO):
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


class NewlineSeparatedCommands(PolicyTestBase):
    """줄바꿈은 `;` 와 같은 순차 실행 구분자이므로 각 줄을 개별 검사한다."""

    def test_leading_line_does_not_hide_violation(self):
        self.assertBlocked("echo hi\ngit commit -m x", "develop")
        self.assertBlocked("echo hi\ngit push origin develop", "feature/x")
        self.assertBlocked("ls\ngit reset --hard", "develop")
        self.assertBlocked("echo hi\nrm -rf /some/dir", "develop")

    def test_branch_switch_tracked_across_lines(self):
        self.assertBlocked("git checkout main\ngit commit -m x", "feature/x")
        self.assertAllowed("git switch feature/y\ngit reset --hard", "develop")

    def test_blank_lines_ignored(self):
        self.assertBlocked("echo hi\n\n\ngit push origin main", "feature/x")

    def test_newline_inside_quotes_preserved(self):
        # 여러 줄 커밋 메시지는 값의 일부라 명령이 쪼개지지 않는다
        self.assertBlocked('git commit -m "feat: x\n\n- body"', "develop")
        self.assertAllowed('git commit -m "feat: x\n\n- body"', "feature/x")
        self.assertAllowed("git commit -m 'feat: x\n\n- body'", "feature/x")

    def test_trailing_newline_ignored(self):
        self.assertBlocked("git push origin develop\n", "feature/x")
        self.assertAllowed("git status\n", "develop")

    def test_newline_after_separator_keeps_split(self):
        # 구분자 뒤 줄바꿈은 명령을 잇는 개행이라, 구분자가 묻히면 안 된다
        self.assertBlocked("echo ok &&\ngit commit -m x", "develop")
        self.assertBlocked("echo ok ||\ngit push origin main", "feature/x")
        self.assertBlocked("echo ok |\ngit commit -m x", "develop")
        self.assertBlocked("echo ok ;\ngit reset --hard", "develop")


class HeredocBody(PolicyTestBase):
    """here-doc 본문은 앞 명령의 표준 입력이라 명령으로 쪼개지 않는다."""

    def test_body_is_not_treated_as_command(self):
        self.assertAllowed("cat <<'EOF' > notes\ngit push origin main\nEOF", "develop")
        self.assertAllowed("cat <<EOF > notes\ngit reset --hard\nEOF", "develop")
        self.assertAllowed("cat <<-EOF > notes\nrm -rf /some/dir\nEOF", "develop")

    def test_commands_after_body_still_checked(self):
        self.assertBlocked(
            "cat <<'EOF' > notes\nsome text\nEOF\ngit push origin develop", "feature/x"
        )

    def test_quoted_heredoc_marker_is_not_a_body(self):
        # 따옴표 안의 `<<EOF` 는 값일 뿐이므로 뒤 줄이 데이터가 되면 안 된다
        self.assertBlocked('echo "<<EOF"\ngit push origin develop', "feature/x")

    def test_command_opening_heredoc_is_still_checked(self):
        self.assertBlocked("git commit -F - <<'EOF'\nmessage\nEOF", "develop")


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

    # ── 경로에 변수가 있어도 토큰이 갈라지지 않는다 ────────────────────────────
    # `$` 를 단어에서 떼어내면 `-C` 가 그것을 값으로 삼아 나머지 경로가 git 인자로
    # 남고, 그러면 push·commit 이 차단 대상에서 빠진다.
    def test_variable_in_git_c_path(self):
        self.assertBlocked("git -C $HOME/repo push origin develop", "feature/x")
        self.assertBlocked('git -C "$HOME/repo" push origin main', "feature/x")
        self.assertBlocked("git -C $REPO commit -m x", "develop")

    def test_braced_variable_in_git_c_path(self):
        # 중괄호를 떼어내면 `{` 가 git 하위 명령 자리에 들어가 차단 패턴이 어긋난다.
        self.assertBlocked("git -C ${PWD} push origin develop", "feature/x")
        self.assertBlocked("git -C ${HOME}/repo commit -m x", "develop")
        self.assertBlocked("cd ${PWD} && git push origin main", "feature/x")

    def test_variable_elsewhere_still_blocked(self):
        self.assertBlocked("BODY=$(echo x) && git push origin develop", "feature/x")
        self.assertBlocked("echo $USER && git push origin main", "feature/x")


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


class RepoScope(unittest.TestCase):
    """실효 작업 디렉터리가 어느 저장소인지에 따라 정책 적용 여부가 갈린다."""

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        # hook 이 경로를 realpath 로 정규화하므로 기대값도 실제 경로로 맞춘다
        base = os.path.realpath(tmp.name)
        self.project = os.path.join(base, "server")
        self.worktree = os.path.join(self.project, "worktrees", "fix-x")
        self.other = os.path.join(base, "other")
        for path in (self.project, self.worktree, self.other):
            os.makedirs(path, exist_ok=True)
        self.worktree_alias = os.path.join(base, "wt-alias")
        os.symlink(self.worktree, self.worktree_alias)

        # worktree 는 저장소 루트가 달라도 common dir 이 같아 이 저장소로 인식된다.
        self.contexts = {
            self.project: ("develop", SAME_REPO),
            self.worktree: ("fix/x", SAME_REPO),
            self.other: ("main", "/repo/other/.git"),
        }

    def evaluate(self, command: str):
        def fake_context(directory):
            return self.contexts.get(os.path.normpath(directory), ("", ""))

        with patch.object(policy, "resolve_context", side_effect=fake_context), \
                patch.object(policy, "project_common_dir", return_value=SAME_REPO):
            return policy.evaluate_bash(command, self.project)

    def assertBlocked(self, command: str):
        result = self.evaluate(command)
        self.assertTrue(result.blocked, f"차단 기대했으나 허용됨: {command}")

    def assertAllowed(self, command: str):
        result = self.evaluate(command)
        self.assertFalse(result.blocked, f"허용 기대했으나 차단됨: {command}\n사유: {result.reason}")

    def test_other_repo_write_allowed(self):
        # 다른 저장소의 보호 브랜치 이름은 이 저장소 정책의 대상이 아니다
        self.assertAllowed(f"cd {self.other} && git commit -m x")
        self.assertAllowed(f"git -C {self.other} push origin main")

    def test_same_repo_via_git_c_blocked(self):
        # `-C` 로 이 저장소를 가리키면 그대로 정책이 적용된다
        self.assertBlocked("git -C . commit -m x")
        self.assertBlocked(f"git -C {self.project} push origin develop")

    def test_worktree_treated_as_feature_branch(self):
        self.assertAllowed(f"cd {self.worktree} && git commit -m x")
        self.assertAllowed(f"git -C {self.worktree} reset --hard")

    def test_unresolvable_path_keeps_policy(self):
        # 경로를 확정할 수 없으면 이 저장소로 간주한다 — 우회로가 되지 않는다
        self.assertBlocked("cd $TARGET && git push origin main")
        self.assertBlocked("cd - && git commit -m x")
        self.assertBlocked(f"cd {self.project}/nope && git commit -m x")

    def test_cd_applies_to_following_commands(self):
        self.assertAllowed(f"cd {self.other} && git status && git commit -m x")

    def test_git_c_applies_to_one_command_only(self):
        self.assertBlocked(f"git -C {self.other} commit -m x && git commit -m y")

    def test_cd_does_not_propagate_across_subshell(self):
        # 파이프·백그라운드는 서브셸에서 실행되어 부모 셸의 위치가 바뀌지 않는다
        self.assertBlocked(f"cd {self.other} | git commit -m x")
        self.assertBlocked(f"cd {self.other} & git commit -m x")

    def test_cd_does_not_propagate_across_or(self):
        # `||` 뒤 명령이 실행되는 것은 cd 가 실패했을 때뿐이라 위치가 그대로다
        self.assertBlocked(f"cd {self.other} || git push origin main")

    def test_cd_tracked_back_into_project(self):
        # 다른 저장소를 경유해 돌아오면 다시 이 저장소 기준으로 판정한다
        self.assertBlocked(f"cd {self.other} && cd {self.project} && git commit -m x")

    def test_cd_with_extra_argument_is_unresolved(self):
        # 셸의 cd 는 위치 인자를 하나만 받으므로 그 자리로 이동하지 않는다
        self.assertBlocked(f"cd {self.other} extra && git commit -m x")

    def test_branch_switch_scoped_to_target_dir(self):
        # 다른 worktree 의 전환이 현재 디렉터리 판정에 새면 안 된다
        self.assertBlocked(f"git -C {self.worktree} switch feature/z && git commit -m x")
        # 전환한 그 worktree 는 전환된 브랜치로 판정한다
        self.assertBlocked(f"git -C {self.worktree} switch develop && git -C {self.worktree} commit -m x")

    def test_branch_switch_tracked_within_same_dir(self):
        self.assertAllowed(f"git -C {self.worktree} switch feature/z && git -C {self.worktree} commit -m x")

    def test_branch_switch_tracked_across_symlinked_path(self):
        # 같은 디렉터리를 실제 경로와 심볼릭 링크로 가리켜도 전환 상태가 갈리지 않는다
        self.assertBlocked(
            f"git -C {self.worktree} switch develop && git -C {self.worktree_alias} commit -m x"
        )


class MainEntrypoint(unittest.TestCase):
    """main()을 stdin/stdout mock으로 end-to-end 검증한다."""

    def _run_main(self, payload, branch):
        stdin = io.StringIO(json.dumps(payload))
        stdout = io.StringIO()
        with patch.object(policy, "resolve_context", return_value=(branch, SAME_REPO)), \
                patch.object(policy, "project_common_dir", return_value=SAME_REPO), \
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
