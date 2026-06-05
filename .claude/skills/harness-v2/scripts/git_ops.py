from __future__ import annotations

from pathlib import Path
import subprocess


def run_git(executor, *args) -> subprocess.CompletedProcess:
    """git 명령을 실행하고 stdout/stderr를 캡처한다."""
    return subprocess.run(
        ["git", *args],
        cwd=executor.root,
        capture_output=True,
        text=True,
    )


def normalize_pathspec(pathspec: str) -> str:
    """`/**` 접미사가 붙은 경로는 git add 가능한 디렉터리 경로로 정규화한다."""
    normalized = pathspec.strip().strip("`").strip()
    if normalized.endswith("/**"):
        return normalized[:-3].rstrip("/")
    return normalized


def list_worktree_paths(executor) -> list[str]:
    """현재 워킹트리의 tracked/untracked 변경 파일 목록을 repo-relative 경로로 반환한다."""
    tracked = set(filter(None, executor.run_git("diff", "--name-only").stdout.splitlines()))
    staged = set(filter(None, executor.run_git("diff", "--cached", "--name-only").stdout.splitlines()))
    untracked = set(filter(None, executor.run_git("ls-files", "--others", "--exclude-standard").stdout.splitlines()))
    return sorted(tracked | staged | untracked)


def stage_paths(executor, pathspecs: list[str]):
    """지정한 pathspec만 선택적으로 스테이징한다."""
    staged_targets = []
    for pathspec in pathspecs:
        normalized = normalize_pathspec(pathspec)
        if normalized:
            staged_targets.append(normalized)

    if not staged_targets:
        return

    executor.run_git("add", "--all", "--", *staged_targets)
