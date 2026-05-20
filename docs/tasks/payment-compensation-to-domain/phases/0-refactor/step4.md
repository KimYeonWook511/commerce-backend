# Step 5: write-retrospective

## 읽어야 할 파일

먼저 아래 파일들을 읽고 태스크 전체 흐름을 파악하라:

- `docs/tasks/payment-compensation-to-domain/prd.md`
- `docs/tasks/payment-compensation-to-domain/architecture.md`
- `docs/tasks/payment-compensation-to-domain/adr.md`
- `docs/tasks/payment-compensation-policy/retrospective.md` (이전 보상 task 회고 참고)
- `docs/tasks/payment-attempt-service-split/retrospective.md` (task A 회고 참고)

## 작업

`docs/tasks/payment-compensation-to-domain/retrospective.md`를 작성한다.

아래 5단 구조를 따른다:

1. **작업 요약**: 무엇을 했는가 (변경 파일 목록, 주요 내용)
2. **설계 결정**: 이 task에서 내린 주요 결정과 근거 (ADR 참조)
3. **발견**: 구현 중 발견한 사실, 예상과 달랐던 점, 코드에서 파악한 기존 설계 의도
4. **미결 과제**: 이 task에서 다루지 않은 후속 과제 목록
5. **개선 제안**: 이 task 이후 개선할 수 있는 구체적인 방향

**미결 과제 포함 필수 항목**:

- PaymentGateway port 완전 inversion (PG 둘 이상 추가 시 자연 승격 방향)
- `PaymentReference` Value Object 도입 (두 Aggregate 협력 키 명시화)
- ArchUnit으로 `PaymentAttempt` 도메인 메서드 가시성 강제 (ADR-014 정책 코드 강제)

## Acceptance Criteria

문서 작성 완료 시 acceptance criteria 없음 (문서만).

## 검증 절차

1. `docs/tasks/payment-compensation-to-domain/retrospective.md`가 생성됐는가?
2. 5단 구조(작업 요약, 설계 결정, 발견, 미결 과제, 개선 제안)를 모두 포함하는가?
3. 미결 과제 3가지가 명시됐는가?

## 금지사항

- 다른 task의 회고 파일을 수정하지 마라. 이유: 역사 기록이라 사후 소급 수정하지 않는다.
- `docs/ddd/` 하위 파일을 수정하지 마라.
