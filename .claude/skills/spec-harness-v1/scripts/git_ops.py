from __future__ import annotations

"""spec-harness-v1 git 조작 헬퍼.

execute.py는 클래스가 아니라
함수 기반 서브커맨드 모음이므로 root(str)를 직접 받는 형태로 조정한다.
로직 자체는 단순하다.
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
