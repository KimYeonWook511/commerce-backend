from __future__ import annotations

import subprocess
from pathlib import Path

import agent_runner


def build_prompt(step_name: str, summary: str) -> str:
    return (
        "당신은 커밋 담당 agent입니다.\n\n"
        "현재 step 작업이 완료됐습니다.\n"
        f"- step 이름: {step_name}\n"
        f"- developer summary: {summary}\n\n"
        "아래를 수행하세요:\n"
        "1. docs/commit-conventions.md를 읽어 커밋 컨벤션을 파악한다.\n"
        "2. git status / git diff로 실제 변경 내용을 직접 확인한다.\n"
        "3. 변경 내용과 컨벤션을 바탕으로 적절한 커밋 단위와 메시지를 스스로 판단한다.\n"
        "4. 코드 변경분과 task 문서(`docs/tasks/<task-name>/` 아래) 변경분이 모두 있고 "
        "두 변경의 목적이 다르면 별도 commit으로 분리한다. "
        "코드 commit(feat/fix/refactor 등) → task 문서 commit(docs:) 순서로 만든다.\n"
        "   - 예외 1: step의 메인 산출물이 task 문서인 경우 "
        "(write-retrospective의 retrospective.md, sync-root-docs의 task 문서 보정 등)는 한 commit으로 묶는다.\n"
        "   - 예외 2: 코드 변경과 문서 보정이 같은 의도를 가지는 경우 "
        "(예: API 시그니처 변경 + api-spec.md 동기화)는 한 commit으로 묶을 수 있다.\n"
        "5. git status에 보이는 task 문서 변경분은 누락하지 않는다. "
        "step과 무관한 보류 영역으로 두지 말고 이번 단계의 commit에 반드시 포함한다.\n"
        "6. git add + git commit을 수행한다.\n\n"
        "금지사항:\n"
        "- git push, git pull, 브랜치 생성/변경/삭제는 절대 하지 마라.\n"
        "- commit 외 다른 git 작업은 수행하지 마라.\n"
        "- phase index 파일(`docs/tasks/<task-name>/phases/index.json`, "
        "`docs/tasks/<task-name>/phases/<phase-name>/index.json`)은 staging하지 마라. "
        "두 파일은 phase 종료 시 finalize 단계에서 chore 커밋으로 한 번에 처리된다.\n"
        "- commit body는 작성하지 마라. subject 한 줄만 작성한다. "
        "변경 의도는 PR 본문에서 단일 관리되므로 자동화 commit에서 body를 추측해 적지 않는다.\n"
    )


def _get_head(root: str) -> str:
    result = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, cwd=root)
    return result.stdout.strip() if result.returncode == 0 else ""


def run(root: str, phase_dir: Path, step: dict, model: str = "haiku", attempt: int = 1) -> None:
    """commit agent를 subprocess로 실행한다."""
    step_num = step["step"]
    step_name = step.get("name", f"step{step_num}")
    summary = str(step.get("summary", "")).strip()

    prompt = build_prompt(step_name, summary)

    before_head = _get_head(root)
    agent_runner.run_agent(
        prompt=prompt,
        model=model,
        cwd=root,
        role="commit_agent",
        logs_dir=phase_dir / "logs",
        step_num=step_num,
        attempt=attempt,
        allowed_tools="Bash(git *) Read",
    )
    after_head = _get_head(root)
    if before_head and before_head == after_head:
        print(f"  ⚠ commit agent가 커밋을 생성하지 않았습니다. 로그: {phase_dir / 'logs' / 'commit_agent.log'}")
