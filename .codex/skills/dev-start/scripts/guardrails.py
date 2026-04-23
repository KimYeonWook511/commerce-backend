from __future__ import annotations

import re
from pathlib import Path
from typing import Optional

FEATURE_DOC_FILES = [
    "prd.md",
    "architecture.md",
    "adr.md",
    "api-spec.md",
    "db-schema.md",
]


def resolve_doc(root_path: Path, *candidates: str) -> Optional[Path]:
    """후보 경로 중 실제로 존재하는 첫 문서를 반환한다."""
    for candidate in candidates:
        path = root_path / candidate
        if path.exists():
            return path
    return None


def list_agents_reference_docs(root_path: Path) -> list[Path]:
    """AGENTS.md의 `참고 문서` 섹션에 나열된 markdown 경로를 반환한다."""
    agents = resolve_doc(root_path, "AGENTS.md")
    if not agents:
        return []

    matches = re.findall(r"`([^`]+\.md)`", agents.read_text(encoding="utf-8"))
    docs: list[Path] = []
    for match in matches:
        path = root_path / match
        if path.exists():
            docs.append(path)
    return docs


def list_feature_docs(feature_dir: Path) -> list[Path]:
    """기능 폴더에서 기본 문서 5개 중 존재하는 문서만 순서대로 반환한다."""
    docs: list[Path] = []
    for filename in FEATURE_DOC_FILES:
        path = feature_dir / filename
        if path.exists():
            docs.append(path)
    return docs


def load_guardrails(root_path: Path, feature_dir: Path, step_text: str) -> str:
    """현재 feature 문서와 step에 직접 관련된 최소 문서만 preamble에 주입한다."""
    sections: list[str] = []

    agents = resolve_doc(root_path, "AGENTS.md")

    if agents:
        sections.append(f"## 프로젝트 규칙 (AGENTS.md)\n\n{agents.read_text(encoding='utf-8')}")

    for doc in list_feature_docs(feature_dir):
        rel_path = doc.relative_to(root_path).as_posix()
        sections.append(f"## 기능 문서 ({rel_path})\n\n{doc.read_text(encoding='utf-8')}")

    referenced = []
    for doc in list_agents_reference_docs(root_path):
        rel_path = doc.relative_to(root_path).as_posix()
        if rel_path in step_text:
            referenced.append(doc)

    for doc in referenced:
        sections.append(f"## 관련 문서 ({doc.relative_to(root_path).as_posix()})\n\n{doc.read_text(encoding='utf-8')}")

    return "\n\n---\n\n".join(sections) if sections else ""


def build_step_context(index: dict) -> str:
    """이전 완료 step의 summary를 다음 step용 컨텍스트로 구성한다."""
    lines = [
        f"- Step {step['step']} ({step['name']}): {step['summary']}"
        for step in index.get("steps", [])
        if step.get("status") == "completed" and step.get("summary")
    ]
    if not lines:
        return ""
    return "## 이전 Step 산출물\n\n" + "\n".join(lines) + "\n\n"


def build_preamble(
    project: str,
    phase_name: str,
    phase_index_relpath: str,
    max_retries: int,
    feat_msg_template: str,
    guardrails: str,
    step_context: str,
    prev_error: str | None = None,
) -> str:
    """실행기 공통 작업 지시문을 만든다."""
    commit_example = feat_msg_template.format(phase=phase_name, num="N", name="<step-name>")
    retry_section = ""
    if prev_error:
        retry_section = (
            "## 이전 시도 실패\n\n"
            f"{prev_error}\n\n"
            "같은 실패를 반복하지 않도록 원인을 해결한 뒤 다시 진행하라.\n\n---\n\n"
        )

    return (
        f"당신은 {project} 프로젝트의 개발자입니다. 아래 step을 수행하세요.\n\n"
        f"{guardrails}\n\n---\n\n"
        f"{step_context}{retry_section}"
        "## 작업 규칙\n\n"
        "1. 이전 step에서 작성된 코드를 확인하고 일관성을 유지하라.\n"
        "2. 이 step에 명시된 작업만 수행하라. 추가 기능이나 파일을 만들지 마라.\n"
        "3. 기존 테스트를 깨뜨리지 마라.\n"
        "4. AC(Acceptance Criteria) 검증을 직접 실행하라.\n"
        f"5. `/{phase_index_relpath}`의 해당 step status를 업데이트하라.\n"
        "   - AC 통과 -> `completed` + `summary`\n"
        f"   - {max_retries}회 시도 후에도 실패 -> `error` + `error_message`\n"
        "   - 사용자 개입 필요 -> `blocked` + `blocked_reason`\n"
        "6. git add/commit/push/checkout은 실행하지 마라. 커밋은 실행기가 처리한다.\n"
        "7. 실행 결과는 `stepN-output.json`으로 기록되며, output/index 변경은 별도 housekeeping 커밋으로 처리된다.\n"
        "8. 참고용 커밋 예시는 다음과 같다.\n"
        f"   {commit_example}\n\n---\n\n"
    )
