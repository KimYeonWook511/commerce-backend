# Step 6: write-retrospective

## 읽어야 할 파일

먼저 아래 파일들을 읽고 작업 내용과 결정 사항을 정리하라:

- `/docs/tasks/payment-compensation-policy/prd.md`
- `/docs/tasks/payment-compensation-policy/adr.md`
- `/docs/tasks/payment-compensation-policy/architecture.md`
- `/docs/tasks/payment-attempt-state-transition-policy/retrospective.md` — 회고 형식 참고
- step 1~4에서 변경된 파일들

## 작업

`docs/tasks/payment-compensation-policy/retrospective.md`를 작성한다.

아래 5단 구조를 따른다:

### 1. 작업 요약
- 무엇을 변경했는가 (변경 파일 목록, 핵심 변경 내용)
- 왜 변경했는가 (근본 원인 — "1 막으면 2 뚫린다" 패턴, race window, ADR-D 임시 처방 정착)

### 2. 설계 결정 요약
- ADR-1 ~ ADR-4의 핵심 결정과 선택하지 않은 대안을 요약
- DDD Aggregate 관점에서 Payment/PaymentAttempt의 독립 불변식 존중 설명

### 3. 발견한 것
- 탐색 중 발견한 예상 외 구조나 패턴
- Payment 존재 체크가 미래 분산 환경에서도 유효한 이유

### 4. 미결 과제
- ArchUnit 도입 (mark 가시성 코드 강제)
- PaymentReference Value Object 도입 (두 Aggregate 협력 키 명시화)
- 해소된 이슈: #114, #115, #116, #117 close 여부 명시

### 5. 개선 제안
- PaymentAttempt 상태 전이 표 문서화 가능성
- 미래 Payment 도메인 분리 시 isCompensationRequired 외부 API 승격 경로

## 수정 가능 경로

- `docs/tasks/payment-compensation-policy/retrospective.md` (신규 생성)

## Acceptance Criteria

```bash
ls docs/tasks/payment-compensation-policy/retrospective.md
```

## 검증 절차

1. 회고 파일이 생성됐는지 확인한다.
2. 아래를 확인한다:
   - 5단 구조를 갖추고 있는지
   - ADR에 기록된 결정이 회고에서 설명되는지
   - 미결 과제가 명시됐는지
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 이 회고를 `docs/ddd/` 아래에 만들지 마라. 이유: `docs/ddd/`는 DDD 마이그레이션 회고 전용이며, 이 task 회고는 `docs/tasks/payment-compensation-policy/retrospective.md`에 위치해야 한다.
- 기존 task 회고(`docs/tasks/payment-attempt-state-transition-policy/retrospective.md` 등)를 수정하지 마라. 이유: 역사 기록이므로 사후 소급 수정하지 않는다.
