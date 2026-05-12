#!/usr/bin/env python3
"""
현재 Repo 전용 Claude Code PreToolUse 정책 스크립트.

Claude Code가 Bash 명령을 실행하기 전에 일부 위험한 명령을 차단한다.
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


def normalize_tokens(command: str) -> list[str]:
    try:
        tokens = shlex.split(command, posix=True)
    except ValueError:
        # 잘못된 quoting은 차단 기준으로 삼지 않고 fail-open 한다.
        return []

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


def blocks_rm_rf(tokens: list[str]) -> bool:
    if not tokens or tokens[0] != "rm":
        return False

    recursive = False
    force = False

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


def evaluate_command(command: str) -> PolicyResult:
    tokens = normalize_tokens(command)
    if not tokens:
        return PolicyResult(blocked=False)

    if blocks_git_reset_hard(tokens):
        return PolicyResult(True, "Repo Bash 명령어 정책에 따라 `git reset --hard`는 차단됩니다.")

    if blocks_git_checkout_restore(tokens):
        return PolicyResult(True, "Repo Bash 명령어 정책에 따라 `git checkout --`는 로컬 변경사항을 버리므로 차단됩니다.")

    if blocks_rm_rf(tokens):
        return PolicyResult(True, "Repo Bash 명령어 정책에 따라 위험한 명령인 `rm -rf` 계열 명령은 차단됩니다.")

    if blocks_force_push(tokens):
        return PolicyResult(True, "Repo Bash 명령어 정책에 따라 강제 push는 차단됩니다.")

    return PolicyResult(blocked=False)


def emit_block(reason: str) -> int:
    # Claude Code PreToolUse hook 응답 형식
    payload = {
        "decision": "block",
        "reason": reason,
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

    tool_input = payload.get("tool_input", {})
    if not isinstance(tool_input, dict):
        return 0

    command = tool_input.get("command", "")
    if not isinstance(command, str) or not command.strip():
        return 0

    result = evaluate_command(command)
    if result.blocked:
        return emit_block(result.reason)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
