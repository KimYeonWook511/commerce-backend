#!/usr/bin/env python3
"""
Repo 전용 Claude Code PreToolUse 정책 스크립트 (harness-v3 / harness-v4 공존).

agent_type 으로 정책을 갈라 적용한다:

  1. committer (v3/v4, Bash)  → 화이트리스트: git status/diff/log/add/commit 외 모든 Bash 차단
  2. reviewer  (v3/v4, Write) → 핸드오프(stepN-review.json) 외 경로 쓰기 차단
  3. recorder  (v4, Write)    → phase index.json 외 경로 쓰기 차단
  4. 그 외(developer/finalizer/메인 등, Bash) → 블랙리스트: rm -rf, git reset --hard, git checkout --, force push 차단

설계 원칙:
  - fail-open: 입력 파싱 실패·형식 오류는 차단하지 않는다(정책 오류가 작업을 막으면 안 됨).
  - 차단은 최신 형식으로 응답한다: hookSpecificOutput.permissionDecision = "deny".
  - agent_type 이 입력에 없으면(플랫폼/버전 차이) 4번(블랙리스트)으로 fallback 한다.

주의: 일부 Claude Code 버전/플랫폼에서 sub-agent의 PreToolUse 차단이 무시될 수 있다.
그 경우 이 hook은 '탐지'만 하고 실제 차단은 각 agent .md의 tools 제약·프롬프트가 baseline으로 담당한다.
(workflow agent에서 PreToolUse 발동 여부는 trial로 확인 필요.)
"""

from __future__ import annotations

import json
import shlex
import sys
from dataclasses import dataclass
from typing import Iterable


@dataclass(frozen=True)
class PolicyResult:
    blocked: bool
    reason: str = ""


# ─────────────────────────────────────────────────────────────────────────────
# 토큰 정규화 (sudo/env/command/git 접두사 제거) — 우회 차단
# ─────────────────────────────────────────────────────────────────────────────
def normalize_tokens(command: str) -> list[str]:
    try:
        tokens = shlex.split(command, posix=True)
    except ValueError:
        return []  # 잘못된 quoting은 fail-open

    while tokens:
        head = tokens[0]
        if head == "sudo":
            tokens = tokens[1:]
            while tokens:
                current = tokens[0]
                if current == "--":
                    tokens = tokens[1:]
                    break
                if current in {"-u", "-g", "-h", "-p", "-C", "-T", "-r", "-t"}:
                    tokens = tokens[2:]
                    continue
                if current.startswith("-"):
                    tokens = tokens[1:]
                    continue
                break
            continue
        if head == "command":
            tokens = tokens[1:]
            while tokens:
                current = tokens[0]
                if current == "--":
                    tokens = tokens[1:]
                    break
                if current.startswith("-"):
                    tokens = tokens[1:]
                    continue
                break
            continue
        if head == "env":
            tokens = tokens[1:]
            while tokens:
                current = tokens[0]
                if current == "--":
                    tokens = tokens[1:]
                    break
                if "=" in current and not current.startswith("-"):
                    tokens = tokens[1:]
                    continue
                if current.startswith("-"):
                    tokens = tokens[1:]
                    continue
                break
            continue
        break

    if tokens and tokens[0] == "git":
        tokens = normalize_git_tokens(tokens)
    return tokens


def normalize_git_tokens(tokens: list[str]) -> list[str]:
    normalized = tokens[:1]
    remainder = tokens[1:]
    while remainder:
        current = remainder[0]
        if current == "--":
            remainder = remainder[1:]
            break
        if current == "-c" and len(remainder) >= 2:
            remainder = remainder[2:]
            continue
        if current in {"--exec-path", "--git-dir", "--work-tree", "--namespace", "--super-prefix", "--config-env"}:
            remainder = remainder[2:]
            continue
        if current.startswith("--") or current.startswith("-"):
            remainder = remainder[1:]
            continue
        break
    return normalized + remainder


def has_flag(tokens: Iterable[str], *flags: str) -> bool:
    return any(token == flag for token in tokens for flag in flags)


# ─────────────────────────────────────────────────────────────────────────────
# 복합 명령 분리 (&&, ||, ;, &, | 로 연결된 것을 각각 검사)
# ─────────────────────────────────────────────────────────────────────────────
_COMPOUND_SEPARATORS = frozenset({"&&", "||", ";", "&", "|"})


def split_compound_commands(command: str) -> list[str]:
    try:
        lexer = shlex.shlex(command, posix=True, punctuation_chars=True)
        tokens = list(lexer)
    except ValueError:
        return [command]

    commands: list[str] = []
    current: list[str] = []
    for token in tokens:
        if token in _COMPOUND_SEPARATORS:
            if current:
                commands.append(shlex.join(current))
                current = []
        else:
            current.append(token)
    if current:
        commands.append(shlex.join(current))
    return commands if commands else [command]


# ─────────────────────────────────────────────────────────────────────────────
# 1. 블랙리스트 (메인/일반 작업) — 위험한 명령만 차단
# ─────────────────────────────────────────────────────────────────────────────
def blocks_rm_rf(tokens: list[str]) -> bool:
    if not tokens or tokens[0] != "rm":
        return False
    recursive = force = False
    for token in tokens[1:]:
        if token == "--":
            break
        if token == "--recursive":
            recursive = True
            continue
        if token == "--force":
            force = True
            continue
        if token.startswith("-") and len(token) > 1:
            chars = token[1:]
            recursive = recursive or ("r" in chars or "R" in chars)
            force = force or ("f" in chars)
    return recursive and force


def blocks_git_reset_hard(tokens: list[str]) -> bool:
    return len(tokens) >= 3 and tokens[0] == "git" and tokens[1] == "reset" and "--hard" in tokens[2:]


def blocks_git_checkout_restore(tokens: list[str]) -> bool:
    return len(tokens) >= 3 and tokens[0] == "git" and tokens[1] == "checkout" and "--" in tokens[2:]


def blocks_force_push(tokens: list[str]) -> bool:
    if len(tokens) < 2 or tokens[0] != "git" or tokens[1] != "push":
        return False
    return has_flag(tokens[2:], "--force", "--force-with-lease", "-f")


def evaluate_blacklist_single(command: str) -> PolicyResult:
    tokens = normalize_tokens(command)
    if not tokens:
        return PolicyResult(False)
    if blocks_git_reset_hard(tokens):
        return PolicyResult(True, "Repo 정책: `git reset --hard`는 차단됩니다.")
    if blocks_git_checkout_restore(tokens):
        return PolicyResult(True, "Repo 정책: `git checkout --`는 로컬 변경을 버리므로 차단됩니다.")
    if blocks_rm_rf(tokens):
        return PolicyResult(True, "Repo 정책: `rm -rf` 계열 명령은 차단됩니다.")
    if blocks_force_push(tokens):
        return PolicyResult(True, "Repo 정책: 강제 push는 차단됩니다.")
    return PolicyResult(False)


# ─────────────────────────────────────────────────────────────────────────────
# 2. committer 화이트리스트 — git status/diff/log/add/commit 외 모든 Bash 차단
# ─────────────────────────────────────────────────────────────────────────────
_COMMITTER_ALLOWED_GIT = frozenset({"status", "diff", "log", "add", "commit"})


def evaluate_committer_single(command: str) -> PolicyResult:
    tokens = normalize_tokens(command)
    if not tokens:
        return PolicyResult(False)  # 파싱 불가는 fail-open
    if tokens[0] != "git":
        return PolicyResult(True, f"harness committer는 git 명령만 허용됩니다 (시도: `{tokens[0]}`).")
    if len(tokens) < 2 or tokens[1] not in _COMMITTER_ALLOWED_GIT:
        sub = tokens[1] if len(tokens) >= 2 else "(none)"
        return PolicyResult(True, f"harness committer는 git status/diff/log/add/commit만 허용됩니다 (시도: `git {sub}`).")
    # commit 서브커맨드라도 history 조작(--amend)은 차단
    if tokens[1] == "commit" and "--amend" in tokens[2:]:
        return PolicyResult(True, "harness committer는 `git commit --amend`(history 조작)를 할 수 없습니다.")
    return PolicyResult(False)


# ─────────────────────────────────────────────────────────────────────────────
# 3. reviewer Write 가드 — 핸드오프 외 경로 쓰기 차단
# ─────────────────────────────────────────────────────────────────────────────
def evaluate_reviewer_write(file_path: str) -> PolicyResult:
    # 허용: .../handoff/stepN-review.json (review 핸드오프만)
    normalized = file_path.replace("\\", "/")
    if "/handoff/" in normalized and normalized.endswith("-review.json"):
        return PolicyResult(False)
    return PolicyResult(True, f"reviewer는 review 핸드오프 외 파일을 쓸 수 없습니다 (시도: `{file_path}`).")


# ─────────────────────────────────────────────────────────────────────────────
# 3-b. recorder Write 가드 (harness-v4) — phase index.json 외 쓰기 차단
#   recorder는 record-step을 Bash(python3)로 부르는 게 정상 경로라 Write 도구를 쓸 일이 없다.
#   혹시 Write를 시도하면 phase index.json 외에는 막는다(정본 오염 방지).
# ─────────────────────────────────────────────────────────────────────────────
def evaluate_recorder_write(file_path: str) -> PolicyResult:
    normalized = file_path.replace("\\", "/")
    if normalized.endswith("/index.json") and "/phases/" in normalized:
        return PolicyResult(False)
    return PolicyResult(True, f"recorder는 phase index.json 외 파일을 쓸 수 없습니다 (시도: `{file_path}`).")


# ─────────────────────────────────────────────────────────────────────────────
# 디스패치
# ─────────────────────────────────────────────────────────────────────────────
_COMMITTER_AGENTS = frozenset({"harness-v3-committer", "harness-v4-committer"})
_REVIEWER_AGENTS = frozenset({"harness-v3-reviewer", "harness-v4-reviewer"})


def evaluate_bash(command: str, agent_type: str) -> PolicyResult:
    for sub in split_compound_commands(command):
        if agent_type in _COMMITTER_AGENTS:
            result = evaluate_committer_single(sub)
        else:
            # developer / recorder / finalizer / 메인 등 → 블랙리스트(위험 명령만 차단).
            # finalizer는 finalize가 내부에서 push까지 하므로 블랙리스트로 충분(force push 등만 차단).
            result = evaluate_blacklist_single(sub)
        if result.blocked:
            return result
    return PolicyResult(False)


def emit_block(reason: str) -> int:
    payload = {
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }
    }
    sys.stdout.write(json.dumps(payload, ensure_ascii=False))
    sys.stdout.flush()
    return 0


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except json.JSONDecodeError:
        return 0
    if not isinstance(payload, dict):
        return 0

    agent_type = payload.get("agent_type", "") or ""
    tool_name = payload.get("tool_name", "") or ""
    tool_input = payload.get("tool_input", {})
    if not isinstance(tool_input, dict):
        return 0

    # Write 가드: reviewer(핸드오프만) / recorder(phase index만)
    if tool_name == "Write":
        file_path = tool_input.get("file_path", "")
        if isinstance(file_path, str) and file_path.strip():
            if agent_type in _REVIEWER_AGENTS:
                result = evaluate_reviewer_write(file_path)
                if result.blocked:
                    return emit_block(result.reason)
            elif agent_type == "harness-v4-recorder":
                result = evaluate_recorder_write(file_path)
                if result.blocked:
                    return emit_block(result.reason)
        return 0

    # Bash 정책 (committer 화이트리스트 / 그 외 블랙리스트)
    if tool_name == "Bash":
        command = tool_input.get("command", "")
        if not isinstance(command, str) or not command.strip():
            return 0
        result = evaluate_bash(command, agent_type)
        if result.blocked:
            return emit_block(result.reason)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
