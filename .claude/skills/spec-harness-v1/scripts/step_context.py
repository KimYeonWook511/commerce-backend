from __future__ import annotations

import re
from pathlib import Path
from typing import Optional

SPEC_DOC_FILES = [
    "spec.md",
    "architecture.md",
    "adr.md",
    "api-spec.md",
    "db-schema.md",
]

# 코딩 시 항상 지켜야 하는 핵심 컨벤션 문서.
# 전문이 아니라 각 문서의 "## 핵심 원칙 (요약)" 섹션만 추출해 항상 주입한다.
# (전문은 step 문서가 경로를 명시하면 list_agents_reference_docs 경로로 별도 주입된다.)
CODING_CONVENTION_DOCS = [
    "docs/logging-conventions.md",
    "docs/exception-strategy.md",
    "docs/test-code-conventions.md",
    "docs/package-structure-conventions.md",
]

CONVENTION_SUMMARY_HEADING = "## 핵심 원칙 (요약)"


def extract_summary_section(text: str) -> Optional[str]:
    """문서에서 '## 핵심 원칙 (요약)' 섹션 본문만 추출한다. 없으면 None."""
    idx = text.find(CONVENTION_SUMMARY_HEADING)
    if idx == -1:
        return None
    after = text[idx + len(CONVENTION_SUMMARY_HEADING):]
    # 다음 동급(##) 헤딩 전까지가 요약 섹션
    next_heading = re.search(r"\n## ", after)
    body = after[: next_heading.start()] if next_heading else after
    body = body.strip()
    # 요약 섹션 끝의 구분선(---)은 md 가독성용이라 문서에는 두되, 주입 시에는 토큰 절약을 위해 제거한다.
    if body.endswith("---"):
        body = body[:-3].strip()
    return body or None


def load_convention_summaries(root_path: Path) -> list[tuple[str, str]]:
    """핵심 컨벤션 문서들의 요약 섹션을 (상대경로, 요약본문) 목록으로 반환한다."""
    summaries: list[tuple[str, str]] = []
    for rel in CODING_CONVENTION_DOCS:
        path = root_path / rel
        if not path.exists():
            continue
        summary = extract_summary_section(path.read_text(encoding="utf-8"))
        if summary:
            summaries.append((rel, summary))
    return summaries


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


def list_spec_docs(spec_dir: Path) -> list[Path]:
    """spec 폴더에서 기본 문서 5개 중 존재하는 문서만 순서대로 반환한다."""
    docs: list[Path] = []
    for filename in SPEC_DOC_FILES:
        path = spec_dir / filename
        if path.exists():
            docs.append(path)
    return docs


def load_step_documents(root_path: Path, spec_dir: Path, step_text: str) -> str:
    """현재 step에 필요한 최소 문서만 developer 컨텍스트로 주입한다."""
    sections: list[str] = []

    claude_md = resolve_doc(root_path, "CLAUDE.md")
    if claude_md:
        sections.append(f"## 프로젝트 규칙 (CLAUDE.md)\n\n{claude_md.read_text(encoding='utf-8')}")

    for doc in list_spec_docs(spec_dir):
        rel_path = doc.relative_to(root_path).as_posix()
        sections.append(f"## spec 문서 ({rel_path})\n\n{doc.read_text(encoding='utf-8')}")

    # 핵심 코딩 컨벤션 요약을 항상 주입한다 (전문이 아니라 요약 섹션만).
    # agent가 logging/exception/testing 컨벤션을 안 보고 자기 판단으로 코딩하는 것을 막는다.
    convention_summaries = load_convention_summaries(root_path)
    if convention_summaries:
        block = "## 필수 코딩 컨벤션 (핵심 원칙 요약 — 반드시 준수)\n\n"
        block += (
            "아래는 항상 지켜야 하는 컨벤션 요약이다. 자기 판단보다 우선한다. "
            "도메인별 사례·세부가 필요하면 각 문서 전문을 step에서 참조한다.\n\n"
        )
        for rel, summary in convention_summaries:
            block += f"### {rel}\n\n{summary}\n\n"
        sections.append(block.rstrip())

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
