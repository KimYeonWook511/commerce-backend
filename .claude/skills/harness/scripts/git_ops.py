from __future__ import annotations

from pathlib import Path
import subprocess
import uuid


def create_worktree(root_path: Path, worktree_path: Path, branch: str) -> Path:
    """격리된 실행용 git worktree를 생성한다."""
    # 브랜치가 이미 존재하면 체크아웃, 없으면 새로 생성
    result = subprocess.run(
        ["git", "-C", str(root_path), "worktree", "add", str(worktree_path), branch],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        result = subprocess.run(
            ["git", "-C", str(root_path), "worktree", "add", "-b", branch, str(worktree_path)],
            capture_output=True, text=True,
        )
    if result.returncode != 0:
        print(f"  ERROR: worktree 생성 실패: {result.stderr.strip()}")
        raise SystemExit(1)
    return worktree_path


def remove_worktree(root_path: Path, worktree_path: Path):
    """실행이 끝난 git worktree를 제거한다."""
    result = subprocess.run(
        ["git", "-C", str(root_path), "worktree", "remove", "--force", str(worktree_path)],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        print(f"  WARNING: worktree 정리 실패: {worktree_path}\n  {result.stderr.strip()}")


def run_git(executor, *args) -> subprocess.CompletedProcess:
    """git 명령을 실행하고 stdout/stderr를 캡처한다."""
    return subprocess.run(
        ["git", *args],
        cwd=executor.root,
        capture_output=True,
        text=True,
    )


def preflight_git_write(executor):
    """실행 중인 execute.py 프로세스가 git 메타데이터에 쓸 수 있는지 확인한다."""
    git_dir_result = executor.run_git("rev-parse", "--git-dir")
    if git_dir_result.returncode != 0:
        print("  ERROR: git을 사용할 수 없거나 git repo가 아닙니다.")
        print(f"  {git_dir_result.stderr.strip()}")
        raise SystemExit(1)

    git_dir = Path(git_dir_result.stdout.strip())
    if not git_dir.is_absolute():
        git_dir = Path(executor.root) / git_dir

    probe_path = git_dir / f".claude-write-test-{uuid.uuid4().hex}"
    try:
        probe_path.write_text("ok", encoding="utf-8")
    except OSError as exc:
        print("  ERROR: execute.py 프로세스가 git 메타데이터 디렉터리에 쓸 수 없습니다.")
        print(f"  Path: {git_dir}")
        print(f"  Reason: {exc}")
        print("  Fix: execute.py 명령 자체를 권한 상승으로 다시 실행하세요.")
        raise SystemExit(1)
    finally:
        probe_path.unlink(missing_ok=True)


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


def commit_step(executor, step_num: int, message: str, editable_paths: list[str]):
    """현재 step의 기능 변경만 커밋한다."""
    output_rel = f"{executor.phase_relpath}/step{step_num}-output.json"
    ac_output_rel = f"{executor.phase_relpath}/step{step_num}-ac-output.json"
    review_output_rel = f"{executor.phase_relpath}/step{step_num}-review-output.json"
    phase_index_rel = f"{executor.phase_relpath}/index.json"
    workflow_checklist_rel = f"{executor.phase_relpath}/workflow-checklist.json"
    feature_index_rel = f"{executor.feature_phases_relpath}/index.json"
    metadata_paths = [output_rel, ac_output_rel, review_output_rel, phase_index_rel, workflow_checklist_rel, feature_index_rel]

    validate_worktree_scope(executor, editable_paths, metadata_paths, context=f"step {step_num} commit")
    stage_paths(executor, editable_paths)
    executor.run_git("reset", "HEAD", "--", *metadata_paths)

    if executor.run_git("diff", "--cached", "--quiet").returncode != 0:
        step_commit = executor.run_git("commit", "-m", message)
        if step_commit.returncode == 0:
            print(f"  Commit: {message}")
        else:
            print(f"  ERROR: 코드 커밋 실패: {step_commit.stderr.strip()}")
            raise SystemExit(1)
