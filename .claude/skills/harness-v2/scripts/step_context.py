from __future__ import annotations

import re
from pathlib import Path
from typing import Optional

TASK_DOC_FILES = [
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
    """CLAUDE.md의 `참고 문서` 섹션에 나열된 markdown 경로를 반환한다."""
    claude_md = resolve_doc(root_path, "CLAUDE.md")
    if not claude_md:
        return []

    text = claude_md.read_text(encoding="utf-8")
    marker = "## 참고 문서"
    idx = text.find(marker)
    section = text[idx:] if idx != -1 else ""
    matches = re.findall(r"`([^`]+\.md)`", section)
    docs: list[Path] = []
    for match in matches:
        path = root_path / match
        if path.exists():
            docs.append(path)
    return docs


def list_task_docs(task_dir: Path) -> list[Path]:
    """Task 폴더에서 기본 문서 5개 중 존재하는 문서만 순서대로 반환한다."""
    docs: list[Path] = []
    for filename in TASK_DOC_FILES:
        path = task_dir / filename
        if path.exists():
            docs.append(path)
    return docs


def load_step_documents(root_path: Path, task_dir: Path, step_text: str) -> str:
    """현재 step에 필요한 최소 문서만 developer 컨텍스트로 주입한다."""
    sections: list[str] = []

    claude_md = resolve_doc(root_path, "CLAUDE.md")
    if claude_md:
        sections.append(f"## 프로젝트 규칙 (CLAUDE.md)\n\n{claude_md.read_text(encoding='utf-8')}")

    for doc in list_task_docs(task_dir):
        rel_path = doc.relative_to(root_path).as_posix()
        sections.append(f"## Task 문서 ({rel_path})\n\n{doc.read_text(encoding='utf-8')}")

    referenced_docs: list[Path] = []
    for doc in list_agents_reference_docs(root_path):
        rel_path = doc.relative_to(root_path).as_posix()
        if rel_path in step_text:
            referenced_docs.append(doc)

    for doc in referenced_docs:
        rel_path = doc.relative_to(root_path).as_posix()
        sections.append(f"## 관련 문서 ({rel_path})\n\n{doc.read_text(encoding='utf-8')}")

    return "\n\n---\n\n".join(sections) if sections else ""


def build_previous_step_context(index: dict) -> str:
    """이전 완료 step의 summary를 다음 step용 컨텍스트로 구성한다."""
    lines = [
        f"- Step {step['step']} ({step['name']}): {step['summary']}"
        for step in index.get("steps", [])
        if step.get("status") == "completed" and step.get("summary")
    ]
    if not lines:
        return ""
    return "## 이전 Step 산출물\n\n" + "\n".join(lines) + "\n\n"
