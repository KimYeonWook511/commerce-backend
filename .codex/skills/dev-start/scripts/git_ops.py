from __future__ import annotations

import subprocess


def run_git(executor, *args) -> subprocess.CompletedProcess:
    """git 명령을 실행하고 stdout/stderr를 캡처한다."""
    return subprocess.run(
        ["git", *args],
        cwd=executor.root,
        capture_output=True,
        text=True,
    )


def checkout_branch(executor):
    """`feature/<feature-name>` 브랜치를 준비한다."""
    branch = executor.branch_name

    current = executor.run_git("rev-parse", "--abbrev-ref", "HEAD")
    if current.returncode != 0:
        print("  ERROR: git을 사용할 수 없거나 git repo가 아닙니다.")
        print(f"  {current.stderr.strip()}")
        raise SystemExit(1)

    if current.stdout.strip() == branch:
        return

    existing = executor.run_git("rev-parse", "--verify", branch)
    result = (
        executor.run_git("checkout", branch)
        if existing.returncode == 0
        else executor.run_git("checkout", "-b", branch)
    )
    if result.returncode != 0:
        print(f"  ERROR: 브랜치 '{branch}' checkout 실패.")
        print(f"  {result.stderr.strip()}")
        print("  Hint: 변경사항을 stash하거나 commit한 후 다시 시도하세요.")
        raise SystemExit(1)

    print(f"  Branch: {branch}")


def normalize_pathspec(pathspec: str) -> str:
    """`/**` 접미사가 붙은 경로는 git add 가능한 디렉터리 경로로 정규화한다."""
    normalized = pathspec.strip().strip("`").strip()
    if normalized.endswith("/**"):
        return normalized[:-3].rstrip("/")
    return normalized


def matches_pathspec(path: str, pathspec: str) -> bool:
    """상대 경로가 허용 pathspec에 포함되는지 확인한다."""
    normalized = normalize_pathspec(pathspec)
    if not normalized:
        return False
    if path == normalized:
        return True
    return path.startswith(f"{normalized}/")


def list_worktree_paths(executor) -> list[str]:
    """현재 워킹트리의 tracked/untracked 변경 파일 목록을 repo-relative 경로로 반환한다."""
    tracked = set(filter(None, executor.run_git("diff", "--name-only").stdout.splitlines()))
    staged = set(filter(None, executor.run_git("diff", "--cached", "--name-only").stdout.splitlines()))
    untracked = set(filter(None, executor.run_git("ls-files", "--others", "--exclude-standard").stdout.splitlines()))
    return sorted(tracked | staged | untracked)


def build_review_diff(executor, pathspecs: list[str], limit: int = 12000) -> str:
    """주어진 pathspec 범위의 diff를 reviewer 입력용으로 만든다."""
    normalized = [normalize_pathspec(path) for path in pathspecs if normalize_pathspec(path)]
    if not normalized:
        return ""

    pieces: list[str] = []

    tracked = executor.run_git("diff", "--", *normalized)
    if tracked.stdout.strip():
        pieces.append(tracked.stdout.strip())

    staged = executor.run_git("diff", "--cached", "--", *normalized)
    if staged.stdout.strip():
        pieces.append(staged.stdout.strip())

    untracked = [
        path
        for path in list_worktree_paths(executor)
        if any(matches_pathspec(path, pathspec) for pathspec in normalized)
        and path in set(filter(None, executor.run_git("ls-files", "--others", "--exclude-standard").stdout.splitlines()))
    ]
    for path in untracked:
        file_path = executor.root_path / path
        if not file_path.is_file():
            continue
        try:
            content = file_path.read_text(encoding="utf-8")
        except OSError:
            continue
        pieces.append(f"diff --git a/{path} b/{path}\nnew file mode 100644\n--- /dev/null\n+++ b/{path}\n{content}")

    diff_text = "\n\n".join(piece for piece in pieces if piece).strip()
    if len(diff_text) <= limit:
        return diff_text
    return diff_text[:limit] + "\n...[truncated]"


def validate_worktree_scope(executor, editable_paths: list[str], metadata_paths: list[str], context: str):
    """허용 경로와 메타데이터 경로 밖의 변경이 있으면 즉시 중단한다."""
    allowed = [normalize_pathspec(path) for path in editable_paths]
    metadata = [normalize_pathspec(path) for path in metadata_paths]
    changed_paths = list_worktree_paths(executor)

    disallowed: list[str] = []
    for path in changed_paths:
        if any(matches_pathspec(path, allowed_path) for allowed_path in allowed):
            continue
        if any(matches_pathspec(path, metadata_path) for metadata_path in metadata):
            continue
        disallowed.append(path)

    if disallowed:
        print(f"  ERROR: {context} 중 허용 범위 밖 변경이 발견되었습니다.")
        for path in disallowed:
            print(f"  - {path}")
        print("  Fix: step 문서의 `수정 가능 경로`를 조정하거나 범위 밖 변경을 정리한 뒤 다시 시도하세요.")
        raise SystemExit(1)


def stage_paths(executor, pathspecs: list[str]):
    """허용 pathspec만 선택적으로 스테이징한다."""
    staged_targets = []
    for pathspec in pathspecs:
        normalized = normalize_pathspec(pathspec)
        if normalized:
            staged_targets.append(normalized)

    if not staged_targets:
        return

    executor.run_git("add", "--all", "--", *staged_targets)


def commit_step(executor, step_num: int, step_name: str, editable_paths: list[str]):
    """코드 변경과 메타데이터 변경을 분리해 2단계 커밋한다."""
    output_rel = f"{executor.phase_relpath}/step{step_num}-output.json"
    ac_output_rel = f"{executor.phase_relpath}/step{step_num}-ac-output.json"
    review_output_rel = f"{executor.phase_relpath}/step{step_num}-review-output.json"
    phase_index_rel = f"{executor.phase_relpath}/index.json"
    feature_index_rel = f"{executor.feature_phases_relpath}/index.json"
    metadata_paths = [output_rel, ac_output_rel, review_output_rel, phase_index_rel, feature_index_rel]

    validate_worktree_scope(executor, editable_paths, metadata_paths, context=f"step {step_num} commit")
    stage_paths(executor, editable_paths)
    executor.run_git("reset", "HEAD", "--", *metadata_paths)

    if executor.run_git("diff", "--cached", "--quiet").returncode != 0:
        feat_msg = executor.FEAT_MSG.format(phase=executor.phase_name, num=step_num, name=step_name)
        feat_commit = executor.run_git("commit", "-m", feat_msg)
        if feat_commit.returncode == 0:
            print(f"  Commit: {feat_msg}")
        else:
            print(f"  WARN: 코드 커밋 실패: {feat_commit.stderr.strip()}")

    stage_paths(executor, metadata_paths)
    if executor.run_git("diff", "--cached", "--quiet").returncode != 0:
        chore_msg = executor.CHORE_MSG.format(phase=executor.phase_name, num=step_num)
        chore_commit = executor.run_git("commit", "-m", chore_msg)
        if chore_commit.returncode != 0:
            print(f"  WARN: housekeeping 커밋 실패: {chore_commit.stderr.strip()}")
