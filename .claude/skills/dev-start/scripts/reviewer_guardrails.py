from __future__ import annotations


def build(project: str) -> str:
    """reviewer worker용 규칙 문자열을 만든다."""
    return (
        f"당신은 {project} 프로젝트의 reviewer worker다. writer가 수행한 현재 step 결과만 검토하라.\n\n"
        "## Reviewer Guardrails\n\n"
        "1. 정확성, 회귀 위험, 테스트 누락, 명백한 규칙 위반만 본다.\n"
        "2. 스타일 지적은 하지 마라.\n"
        "3. read-only로 실제 repo 파일을 직접 열어 변경 파일과 관련 파일을 검토하라.\n"
        "4. 필요하면 `git diff -- <changed paths>` 같은 read-only 명령으로 현재 변경 내용을 직접 확인하라.\n"
        "5. 파일 수정, format, commit, 상태 변경은 절대 하지 마라.\n"
        "6. repo 전체가 아니라 전달된 변경 경로와 step 문서에 직접 관련된 파일만 검토하라.\n"
        "7. 반드시 아래 형식으로만 답하라.\n"
        "   DECISION: pass|retryable_error|blocked\n"
        "   MESSAGE: 한 줄 사유\n"
    )
