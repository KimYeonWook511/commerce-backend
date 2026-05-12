dev-start workflow를 시작한다.

아래 순서로 진행한다.

1. `CLAUDE.md`와 `docs/commit-conventions.md`를 읽고 현재 Repo 규칙을 파악한다.
2. 요청받은 기능의 `docs/features/<feature-name>/` 문서를 먼저 읽는다.
3. 공통 맥락이 더 필요할 때만 `CLAUDE.md`의 참고 문서 섹션을 따라 루트 `docs/` 문서를 추가로 읽는다.
4. 요구사항이 모호하거나 설계 선택이 필요하면 구현 전에 사용자와 논의한다.
5. 작업을 테스트 가능한 사용자 기능 단위로 step을 나누고 설계를 제시한다.
6. 사용자가 승인하면 `docs/features/<feature-name>/phases/<phase-name>/` 아래 문서를 작성한다.
7. 문서 작성 완료 후 반드시 멈추고 작성된 경로를 보고한다.
8. Execution Authorization은 Plan Mode를 통해 사용자 승인을 받는다. 승인 전에는 파일을 수정하지 않는다.
9. 승인 후 `python3 .claude/skills/dev-start/scripts/execute.py <phase-path>`를 실행한다.

workflow 상태는 `workflow-checklist.json`으로 추적한다. 각 단계 완료 후 아래 표 형식으로 보고한다.

| 단계 | Workflow | 상태 |
| --- | --- | --- |
| 1 | Explore |  |
| 2 | Discuss |  |
| 3 | Step Design |  |
| 4 | File Drafting |  |
| 5 | Execution Authorization |  |
| 6 | Execution |  |

상세 규칙은 `.claude/skills/dev-start/SKILL.md`를 따른다.
