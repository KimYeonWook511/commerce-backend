from __future__ import annotations

"""harness-v4 git 조작 헬퍼.

v3는 executor 객체(.root, .run_git)를 받았으나, v4 execute.py는 클래스가 아니라
함수 기반 서브커맨드 모음이므로 root(str)를 직접 받는 형태로 조정한다.
로직 자체는 v3와 동일.
"""

import subprocess


def run_git(root: str, *args) -> subprocess.CompletedProcess:
    """git 명령을 실행하고 stdout/stderr를 캡처한다."""
    return subprocess.run(
        ["git", *args],
        cwd=root,
        capture_output=True,
        text=True,
    )


def normalize_pathspec(pathspec: str) -> str:
    """`/**` 접미사가 붙은 경로는 git add 가능한 디렉터리 경로로 정규화한다."""
    normalized = pathspec.strip().strip("`").strip()
    if normalized.endswith("/**"):
        return normalized[:-3].rstrip("/")
    return normalized


def stage_paths(root: str, pathspecs: list[str]) -> None:
    """지정한 pathspec만 선택적으로 스테이징한다."""
    staged_targets = []
    for pathspec in pathspecs:
        normalized = normalize_pathspec(pathspec)
        if normalized:
            staged_targets.append(normalized)
    if not staged_targets:
        return
    run_git(root, "add", "--all", "--", *staged_targets)
