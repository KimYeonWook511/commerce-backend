#!/usr/bin/env python3
"""
Repo 전용 Claude Code PreToolUse 정책 스크립트.

Bash 명령을 브랜치 인식 정책으로 검사한다 (agent_type 분기 없음).

보호 브랜치(main/develop)에 대한 정책 — "변경은 PR 머지로만 반영":
  1. 직접 push 전면 금지 (force 여부 무관). 일반 push·delete·--all·--mirror 모두 차단.
     (PR 머지는 원격 서버에서 일어나므로 로컬 push 차단의 영향을 받지 않는다.)
  2. 보호 브랜치에 체크아웃된 상태에서 커밋/히스토리 작성 금지:
     commit / merge / rebase / cherry-pick / revert / am 차단.
     (복구용 --abort/--quit, 읽기용 --dry-run 은 허용.)
  3. 기존 파괴적 명령도 계속 차단: reset --hard / checkout -- <file> / restore(워킹트리) / rm -rf.

그 외 브랜치에서는 위 명령들을 모두 허용한다(force push 포함).
force push/삭제·push 대상 판단은 "현재 브랜치"가 아니라 push 대상 브랜치(refspec)를 기준으로 한다.
(피처 브랜치에서 `git push origin develop` 도 차단됨)

적용 범위 — 이 저장소를 향하는 명령만:
  명령의 실효 작업 디렉터리(`cd`·`git -C` 를 반영한 위치)를 따라가, 그곳이 다른 저장소면 판정하지
  않는다. worktree 는 common dir 이 같아 이 저장소로 인식하며, 브랜치도 그 디렉터리에서 조회한다.
  경로를 확정할 수 없으면(`cd $VAR`·`cd -`) 이 저장소로 간주해 판정을 유지한다.

설계 원칙:
  - fail-open: 입력 파싱 실패·형식 오류는 차단하지 않는다(정책 오류가 작업을 막으면 안 됨).
  - 차단은 최신 형식으로 응답한다: hookSpecificOutput.permissionDecision = "deny".
  - 현재 브랜치 탐지 실패(레포 아님 / git 미설치 등) 시 현재-브랜치 의존 검사는 fail-open.
    단, refspec 으로 명시된 보호 브랜치 대상 push 는 브랜치 탐지와 무관하게 항상 차단된다.

주의 (중요):
  - 이 hook 은 로컬 1차 방어선일 뿐, 강제 장치가 아니다. hook 을 끄거나 다른 환경에서
    push 하면 무력화된다. "무조건 PR 로만"을 진짜 강제하려면 GitHub/GitLab 의
    브랜치 보호 규칙(branch protection / ruleset)을 서버 쪽에 설정해야 한다.
  - push 가 스크립트 내부에서 일어나면 hook 은 바깥 명령만 보므로 잡지 못한다.
"""

from __future__ import annotations

import json
import os
import re
import shlex
import subprocess
import sys
from dataclasses import dataclass
from typing import Optional


@dataclass(frozen=True)
class PolicyResult:
    blocked: bool
    reason: str = ""


# ─────────────────────────────────────────────────────────────────────────────
# 보호 브랜치 정의 (필요 시 "master", "release" 등을 추가)
# ─────────────────────────────────────────────────────────────────────────────
PROTECTED_BRANCHES = frozenset({"main", "develop"})

# 보호 브랜치에서 차단할 "커밋/히스토리 작성" git 서브커맨드
_PROTECTED_WRITE_SUBCMDS = frozenset({"commit", "merge", "rebase", "cherry-pick", "revert", "am"})
# 위 서브커맨드라도 커밋을 만들지 않는 안전 동작은 허용 (복구/조회)
_NONMUTATING_SUBCMD_FLAGS = frozenset({"--abort", "--quit", "--dry-run"})


# ─────────────────────────────────────────────────────────────────────────────
# 실효 작업 디렉터리와 저장소 판별
#
# 명령이 어느 디렉터리에서 실행되는지에 따라 브랜치도 저장소도 달라진다. 그래서 판정 기준을
# hook 프로세스의 cwd 가 아니라 "그 명령의 실효 작업 디렉터리"로 잡는다.
# ─────────────────────────────────────────────────────────────────────────────
_context_cache: dict[str, tuple[str, str]] = {}


def _run_git(directory: str, *args) -> str:
    """지정한 디렉터리에서 git 을 실행해 stdout 을 돌려준다. 실패하면 "" (fail-open)."""
    try:
        proc = subprocess.run(
            ["git", "-C", directory, *args],
            capture_output=True,
            text=True,
            timeout=5,
        )
    except Exception:
        return ""
    if proc.returncode != 0:
        return ""
    return proc.stdout.strip()


def resolve_context(directory: str) -> tuple[str, str]:
    """그 디렉터리의 (현재 브랜치, git common dir 절대경로).

    common dir 로 저장소를 식별하는 이유는 worktree 때문이다. worktree 는 저장소 루트가
    메인 체크아웃과 다르지만 common dir 은 같아서, 같은 저장소로 인식된다.
    """
    cached = _context_cache.get(directory)
    if cached is not None:
        return cached

    branch = _run_git(directory, "rev-parse", "--abbrev-ref", "HEAD")
    common = _run_git(directory, "rev-parse", "--git-common-dir")
    if common:
        if not os.path.isabs(common):
            common = os.path.join(directory, common)
        common = os.path.realpath(common)

    context = (branch, common)
    _context_cache[directory] = context
    return context


def project_common_dir() -> str:
    """이 정책이 지키는 저장소의 common dir. 조회 실패면 "" (판별 생략)."""
    root = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
    return resolve_context(root)[1]


def resolve_dir(path: str, base: Optional[str]) -> Optional[str]:
    """경로를 실제 디렉터리로 해석한다. 확정할 수 없으면 None.

    변수 확장(`$VAR`)은 hook 이 평가할 수 없고, 존재하지 않는 경로는 명령 자체가 실패한다.
    심볼릭 링크를 푸는 이유는 같은 디렉터리를 가리키는 여러 경로가 한 값으로 모여야
    디렉터리별 상태(브랜치 전환 추적)가 갈리지 않기 때문이다.
    """
    if "$" in path:
        return None
    expanded = os.path.expanduser(path)
    if not os.path.isabs(expanded):
        if base is None:
            return None
        expanded = os.path.join(base, expanded)
    expanded = os.path.realpath(expanded)
    return expanded if os.path.isdir(expanded) else None


def cd_target(tokens: list[str], base: Optional[str]) -> Optional[str]:
    """`cd` 가 옮겨갈 디렉터리. cd 가 아니거나 확정할 수 없으면 None."""
    if not tokens or tokens[0] != "cd":
        return None
    args = [t for t in tokens[1:] if t == "-" or not t.startswith("-")]
    if not args:
        return os.path.expanduser("~")
    if len(args) > 1:
        # 셸의 cd 는 위치 인자를 하나만 받는다. 인자가 더 있으면 그 자리로 이동하지 않는다
        # (bash 는 인자 초과로 실패하고, zsh 는 경로 치환으로 전혀 다르게 동작한다).
        return None
    if args[0] == "-":  # 이전 디렉터리 — hook 은 알 수 없다
        return None
    return resolve_dir(args[0], base)


def in_project_repo(directory: Optional[str], project: str) -> bool:
    """이 저장소를 향하는 명령인가. 확정할 수 없으면 True — 모를 때는 판정을 유지한다."""
    if directory is None or not project:
        return True
    common = resolve_context(directory)[1]
    if not common:
        return True
    return common == project


def _protected_label() -> str:
    return ", ".join(sorted(PROTECTED_BRANCHES))


# ─────────────────────────────────────────────────────────────────────────────
# 토큰 정규화 (sudo/env/command/git 접두사 제거) — 우회 차단
# ─────────────────────────────────────────────────────────────────────────────
def normalize_tokens(command: str) -> list[str]:
    return normalize_tokens_with_dirs(command)[0]


def normalize_tokens_with_dirs(command: str) -> tuple[list[str], list[str]]:
    """정규화한 토큰과, `git -C` 가 지정한 디렉터리 목록을 함께 돌려준다."""
    try:
        tokens = shlex.split(command, posix=True)
    except ValueError:
        return [], []  # 잘못된 quoting은 fail-open

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
        return normalize_git_tokens_with_dirs(tokens)
    return tokens, []


def normalize_git_tokens(tokens: list[str]) -> list[str]:
    return normalize_git_tokens_with_dirs(tokens)[0]


def normalize_git_tokens_with_dirs(tokens: list[str]) -> tuple[list[str], list[str]]:
    normalized = tokens[:1]
    remainder = tokens[1:]
    dirs: list[str] = []
    while remainder:
        current = remainder[0]
        if current == "--":
            remainder = remainder[1:]
            break
        # `-C` 는 값을 소비하며, 그 값이 이 명령의 실효 작업 디렉터리다(여러 번 오면 순차 적용).
        if current == "-C" and len(remainder) >= 2:
            dirs.append(remainder[1])
            remainder = remainder[2:]
            continue
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
    return normalized + remainder, dirs


# ─────────────────────────────────────────────────────────────────────────────
# 복합 명령 분리 (줄바꿈과 &&, ||, ;, &, | 로 연결된 것을 각각 검사)
# ─────────────────────────────────────────────────────────────────────────────
_COMPOUND_SEPARATORS = frozenset({"&&", "||", ";", "&", "|"})

# `cd` 의 결과가 뒤 명령까지 이어지는 구분자.
#   `|`·`&`  — 서브셸에서 실행되어 부모 셸의 작업 디렉터리가 바뀌지 않는다.
#   `||`     — 뒤 명령이 실행되는 것은 `cd` 가 실패했을 때뿐이라 위치가 그대로다.
_CD_PROPAGATING_SEPARATORS = frozenset({"&&", ";"})


_HEREDOC_START = re.compile(r"<<-?\s*(['\"]?)(\w+)\1")


def scan_line(line: str, quote: str) -> tuple[str, str]:
    """한 줄을 훑어 (줄 끝의 따옴표 상태, 시작한 here-doc 의 종료 표시)를 돌려준다.

    따옴표 밖의 `<<` 만 here-doc 으로 본다. 따옴표 안의 `<<EOF` 를 here-doc 으로 오인하면
    뒤따르는 줄이 데이터로 취급돼 검사를 건너뛰게 된다.
    """
    heredoc = ""
    escaped = False
    index = 0
    while index < len(line):
        ch = line[index]
        if escaped:
            escaped = False
        elif ch == "\\" and quote != "'":
            escaped = True
        elif quote:
            if ch == quote:
                quote = ""
        elif ch in "'\"":
            quote = ch
        elif not heredoc and line.startswith("<<", index):
            match = _HEREDOC_START.match(line, index)
            if match:
                heredoc = match.group(2)
                index = match.end()
                continue
        index += 1
    return quote, heredoc


def newlines_to_separators(command: str) -> str:
    """명령을 잇는 줄바꿈을 `;` 로 바꾼다.

    셸에서 줄바꿈은 `;` 와 같은 순차 실행 구분자다. 이걸 그냥 두면 여러 줄 명령이 한 덩어리로
    묶여 첫 낱말만 보고 판정하게 되고, 앞에 아무 줄이나 붙이는 것만으로 검사를 지나간다.

    값에 해당하는 줄바꿈은 건드리지 않는다 — 따옴표 안(여러 줄 commit 메시지)과 here-doc 본문
    (앞 명령의 표준 입력)이 그렇다.
    """
    pieces: list[str] = []
    quote = ""
    heredoc_end = ""

    for index, line in enumerate(command.split("\n")):
        if index == 0:
            pieces.append(line)
        elif heredoc_end or quote:
            pieces.append("\n" + line)  # 값의 일부 — 원래 줄바꿈을 유지한다
        else:
            # 앞이 이미 구분자면 줄바꿈은 명령을 다음 줄로 잇는 개행이다. 여기에 `;` 를 더하면
            # `&&;` 처럼 붙어 한 낱말로 묶이고, 원래 잡히던 구분자마저 사라진다.
            previous = "".join(pieces).rstrip(" \t")
            pieces.append(" " if previous.endswith(("&", "|", ";")) else ";")
            pieces.append(line)

        if heredoc_end:
            if line.strip() == heredoc_end:
                heredoc_end = ""
            continue
        quote, heredoc_end = scan_line(line, quote)
    return "".join(pieces)


def split_compound_commands(command: str) -> list[tuple[str, str]]:
    """(명령, 그 뒤에 오는 구분자) 목록. 마지막 명령의 구분자는 ""."""
    try:
        lexer = shlex.shlex(newlines_to_separators(command), posix=True, punctuation_chars=True)
        # `$`·`{`·`}` 를 단어의 일부로 본다. 떼어내면 `git -C ${HOME}/x` 가 `-C` 의 값을 `$` 로
        # 읽고 `{` 를 하위 명령으로 오인해, 그 명령이 차단 대상에서 빠진다.
        lexer.wordchars += "${}"
        tokens = list(lexer)
    except ValueError:
        return [(command, "")]

    commands: list[tuple[str, str]] = []
    current: list[str] = []
    for token in tokens:
        if token in _COMPOUND_SEPARATORS:
            if current:
                commands.append((shlex.join(current), token))
                current = []
        else:
            current.append(token)
    if current:
        commands.append((shlex.join(current), ""))
    return commands if commands else [(command, "")]


# ─────────────────────────────────────────────────────────────────────────────
# 복합 명령 내 브랜치 전환 추적 (예: `git checkout main && git reset --hard`)
# ─────────────────────────────────────────────────────────────────────────────
def branch_switch_target(tokens: list[str]) -> Optional[str]:
    if len(tokens) < 2 or tokens[0] != "git":
        return None
    sub = tokens[1]
    rest = tokens[2:]
    if sub == "switch":
        for t in rest:
            if t == "--" or t.startswith("-"):
                continue
            return t
        return None
    if sub == "checkout":
        if "--" in rest:
            return None  # `git checkout -- <file>` 은 파일 복원(전환 아님)
        for t in rest:
            if t.startswith("-"):
                continue
            return t
        return None
    return None


# ─────────────────────────────────────────────────────────────────────────────
# 파괴적 명령 탐지기 (브랜치 게이트는 호출부에서 적용)
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


def blocks_git_restore_worktree(tokens: list[str]) -> bool:
    # `git restore <file>` 는 워킹트리 변경을 버린다(checkout -- 의 현대식 등가물).
    # `--staged` 단독(인덱스만 복원)은 워킹트리를 건드리지 않으므로 안전으로 본다.
    if len(tokens) < 2 or tokens[0] != "git" or tokens[1] != "restore":
        return False
    rest = tokens[2:]
    staged = any(t in {"--staged", "-S"} for t in rest)
    worktree = any(t in {"--worktree", "-W"} for t in rest)
    if staged and not worktree:
        return False
    return True


def is_protected_write_subcmd(tokens: list[str]) -> bool:
    """보호 브랜치에서 막아야 할 커밋/히스토리 작성 명령인가 (복구·dry-run 제외)."""
    if len(tokens) < 2 or tokens[0] != "git":
        return False
    if tokens[1] not in _PROTECTED_WRITE_SUBCMDS:
        return False
    rest = tokens[2:]
    if any(flag in rest for flag in _NONMUTATING_SUBCMD_FLAGS):
        return False
    return True


# ─────────────────────────────────────────────────────────────────────────────
# git push 파싱 — 보호 브랜치로의 모든 push 차단
# ─────────────────────────────────────────────────────────────────────────────
_PUSH_VALUE_OPTS = frozenset({"-o", "--push-option", "--receive-pack", "--exec", "--repo"})


def _push_positionals(args: list[str]) -> list[str]:
    positional: list[str] = []
    i = 0
    n = len(args)
    while i < n:
        tok = args[i]
        if tok == "--":
            positional.extend(args[i + 1:])
            break
        if tok.startswith("-"):
            if tok in _PUSH_VALUE_OPTS:
                i += 2
                continue
            i += 1
            continue
        positional.append(tok)
        i += 1
    return positional


def _refspec_target(rs: str, current: str) -> str:
    """refspec 에서 대상(원격) 브랜치명을 뽑아낸다. (+, refs/heads/, HEAD, :delete 처리)"""
    if rs.startswith("+"):
        rs = rs[1:]
    dst = rs.split(":", 1)[1] if ":" in rs else rs
    if dst.startswith("refs/heads/"):
        dst = dst[len("refs/heads/"):]
    if dst == "HEAD":
        dst = current
    return dst


def _blocked_push(branch: str) -> PolicyResult:
    return PolicyResult(
        True,
        f"Repo 정책: 보호 브랜치(`{branch}`)로의 직접 push 는 금지됩니다. "
        f"변경은 피처 브랜치 → PR 머지로만 반영하세요. (보호 브랜치: {_protected_label()})",
    )


def evaluate_push(tokens: list[str], current: str) -> PolicyResult:
    args = tokens[2:]

    # 여러 브랜치를 한 번에 올리는 형태는 보호 브랜치를 포함할 수 있어 차단
    if "--mirror" in args:
        return PolicyResult(True, "Repo 정책: `git push --mirror`는 보호 브랜치를 덮어쓸 수 있어 차단됩니다.")
    if "--all" in args:
        return PolicyResult(True, "Repo 정책: `git push --all`은 보호 브랜치를 포함하므로 차단됩니다.")

    positional = _push_positionals(args)
    refspecs = positional[1:] if positional else []

    if not refspecs:
        # refspec 없음 → 현재 브랜치를 push
        if current in PROTECTED_BRANCHES:
            return _blocked_push(current)
        return PolicyResult(False)

    for rs in refspecs:
        dst = _refspec_target(rs, current)
        if dst in PROTECTED_BRANCHES:
            return _blocked_push(dst)
    return PolicyResult(False)


# ─────────────────────────────────────────────────────────────────────────────
# 브랜치 인식 정책
# ─────────────────────────────────────────────────────────────────────────────
def evaluate_blacklist_tokens(tokens: list[str], current: str) -> PolicyResult:
    if not tokens:
        return PolicyResult(False)

    # push 는 대상 브랜치 기준으로 판단(현재 브랜치와 무관)
    if len(tokens) >= 2 and tokens[0] == "git" and tokens[1] == "push":
        return evaluate_push(tokens, current)

    protected = current in PROTECTED_BRANCHES

    # 보호 브랜치에서 커밋/머지/리베이스 등 직접 변경 금지
    if protected and is_protected_write_subcmd(tokens):
        return PolicyResult(
            True,
            f"Repo 정책: 보호 브랜치(`{current}`)에서 `git {tokens[1]}`(직접 변경)은 금지됩니다. "
            f"피처 브랜치에서 작업한 뒤 PR 로 머지하세요.",
        )

    if blocks_git_reset_hard(tokens):
        if protected:
            return PolicyResult(True, f"Repo 정책: 보호 브랜치(`{current}`)에서 `git reset --hard`는 차단됩니다.")
        return PolicyResult(False)

    if blocks_git_checkout_restore(tokens):
        if protected:
            return PolicyResult(True, f"Repo 정책: 보호 브랜치(`{current}`)에서 `git checkout --`는 차단됩니다.")
        return PolicyResult(False)

    if blocks_git_restore_worktree(tokens):
        if protected:
            return PolicyResult(True, f"Repo 정책: 보호 브랜치(`{current}`)에서 `git restore`(워킹트리)는 차단됩니다.")
        return PolicyResult(False)

    if blocks_rm_rf(tokens):
        if protected:
            return PolicyResult(True, f"Repo 정책: 보호 브랜치(`{current}`)에서 `rm -rf` 계열 명령은 차단됩니다.")
        return PolicyResult(False)

    return PolicyResult(False)


def evaluate_bash(command: str, cwd: str = "") -> PolicyResult:
    project = project_common_dir()
    base_dir = os.path.realpath(cwd or os.getcwd())
    # None = 경로를 확정하지 못한 상태. 그때는 기점에 남아 있는 것으로 보고 판정을 유지한다.
    current_dir: Optional[str] = base_dir
    # 같은 줄에서 전환한 브랜치를 디렉터리별로 기억한다. hook 은 명령 실행 전에 돌아 전환이
    # 아직 반영되지 않았고, 한 worktree 의 전환이 다른 worktree 판정에 새면 안 된다.
    switched: dict[str, str] = {}

    def branch_of(directory: Optional[str]) -> str:
        key = directory if directory is not None else base_dir
        if key in switched:
            return switched[key]
        return resolve_context(key)[0]

    for sub, separator in split_compound_commands(command):
        tokens, c_dirs = normalize_tokens_with_dirs(sub)
        if not tokens:
            continue

        # `cd` 는 차단 대상이 아니라 위치 상태만 바꾼다. 저장소 판별보다 먼저 처리해야
        # 다른 저장소를 경유해 돌아오는 경로에서 추적이 끊기지 않는다.
        if tokens[0] == "cd":
            if separator in _CD_PROPAGATING_SEPARATORS:
                current_dir = cd_target(tokens, current_dir)
            continue

        # `git -C` 는 그 명령 하나에만 적용된다(`cd` 와 달리 뒤로 이어지지 않는다).
        target_dir = current_dir
        for path in c_dirs:
            target_dir = resolve_dir(path, target_dir)
            if target_dir is None:
                break

        if not in_project_repo(target_dir, project):
            continue  # 다른 저장소를 향하는 명령 — 이 저장소의 정책 대상이 아니다

        result = evaluate_blacklist_tokens(tokens, branch_of(target_dir))
        if result.blocked:
            return result

        # 같은 줄에서 브랜치를 전환하면 이후 명령은 전환된 브랜치 기준으로 판단한다.
        # 전환은 그 명령이 가리킨 디렉터리에만 적용된다.
        target_branch = branch_switch_target(tokens)
        if target_branch is not None:
            switched[target_dir if target_dir is not None else base_dir] = target_branch
    return PolicyResult(False)


# ─────────────────────────────────────────────────────────────────────────────
# 진입점
# ─────────────────────────────────────────────────────────────────────────────
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

    tool_name = payload.get("tool_name", "") or ""
    tool_input = payload.get("tool_input", {})
    if not isinstance(tool_input, dict):
        return 0

    if tool_name == "Bash":
        command = tool_input.get("command", "")
        if not isinstance(command, str) or not command.strip():
            return 0
        cwd = payload.get("cwd")
        result = evaluate_bash(command, cwd if isinstance(cwd, str) else "")
        if result.blocked:
            return emit_block(result.reason)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
