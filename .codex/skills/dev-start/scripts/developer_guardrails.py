from __future__ import annotations


def build(
    project: str,
    phase_name: str,
    phase_index_relpath: str,
    max_retries: int,
    feat_msg_template: str,
    prev_error: str | None = None,
) -> str:
    """developer worker용 규칙 문자열을 만든다."""
    commit_example = feat_msg_template.format(phase=phase_name, num="N", name="<step-name>")
    retry_section = ""
    if prev_error:
        retry_section = (
            "## 이전 시도 실패\n\n"
            f"{prev_error}\n\n"
            "같은 실패를 반복하지 않도록 원인을 해결한 뒤 다시 진행하라.\n\n---\n\n"
        )

    return (
        f"당신은 {project} 프로젝트의 developer worker다. 현재 step 구현만 수행하라.\n\n"
        f"{retry_section}"
        "## Developer Guardrails\n\n"
        "1. 이전 step에서 작성된 코드를 확인하고 일관성을 유지하라.\n"
        "2. 이 step에 명시된 작업만 수행하라. 추가 기능이나 파일을 만들지 마라.\n"
        "3. 기존 테스트를 깨뜨리지 마라.\n"
        "4. AC(Acceptance Criteria) 검증을 직접 실행하라.\n"
        f"5. `/{phase_index_relpath}`의 해당 step status를 업데이트하라.\n"
        "   - AC 통과 -> `completed` + `summary`\n"
        f"   - {max_retries}회 시도 후에도 실패 -> `error` + `error_message`\n"
        "   - 사용자 개입 필요 -> `blocked` + `blocked_reason`\n"
        "6. editable path 바깥 파일은 수정하지 마라.\n"
        "7. git add/commit/push/checkout은 실행하지 마라. 커밋은 실행기가 처리한다.\n"
        "8. 실행 결과와 현재 step 상태/summary 갱신은 정상 실행 메타데이터이며 output/index 변경은 별도 housekeeping 커밋으로 처리된다.\n"
        "9. step 요구사항, Acceptance Criteria, feature 문서, root docs, `수정 가능 경로`를 실패 회피 목적으로 임의 수정하지 마라.\n"
        "10. 현재 step 외의 step 상태를 수정하지 마라.\n"
        "11. 최종 실패 복구를 위해 status를 `pending`으로 되돌리지 마라. 실행 중 재시도 reset은 execute.py가 처리한다. 3회 시도 후에도 실패하면 현재 step을 `error` 또는 `blocked`로 남기고 종료하라.\n"
        "12. 참고용 커밋 예시는 다음과 같다.\n"
        f"   {commit_example}\n"
    )
