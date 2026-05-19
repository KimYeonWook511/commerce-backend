# Step 2: write-retrospective

## 읽어야 할 파일

- `/docs/tasks/payment-attempt-state-transition-policy/prd.md`
- `/docs/tasks/payment-attempt-state-transition-policy/adr.md`

이전 step에서 생성/수정된 파일:

- `/src/main/java/com/commerce/payment/domain/PaymentAttempt.java`
- `/src/main/java/com/commerce/payment/exception/PaymentErrorCode.java`
- `/src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`
- `/src/test/java/com/commerce/payment/domain/PaymentAttemptTest.java`
- `/docs/ADR.md`

기존 회고 문서 참고 (형식 일관성):

- `/docs/tasks/payment-attempt-idempotency/retrospective.md`

## 작업

`docs/tasks/payment-attempt-state-transition-policy/retrospective.md` 파일을 작성한다.

아래 항목을 포함한다.

### 1. 작업 요약

- 이번 작업에서 변경한 내용과 목적을 간결하게 정리한다.

### 2. 설계 결정 요약

- ADR-A~D의 핵심 결정과 근거를 간략히 요약한다.
- 이 대화에서 논의한 주요 결정 분기점(멱등 허용 여부, HTTP 500 결정, catch swallow 처리 방식)을 기록한다.

### 3. 발견한 것

- 작업 중 발견한 예상 밖의 사실, 아키텍처 이슈, 개선 가능성 등을 적는다.

### 4. 미결 과제

- 후속 Issue #111로 분리된 작업:
  - 보상 catch 2차 예외 처리 일반 원칙 문서화 (`docs/architecture.md` 예외 처리 섹션 + ADR-013)
  - `NaverPayApprovalService` 라인 130, 145 catch 블록 `log.error` 누락 보강

### 5. 개선 제안

- 향후 개선 가능성이 있는 항목을 적는다.

## Acceptance Criteria

```bash
ls docs/tasks/payment-attempt-state-transition-policy/retrospective.md
```

회고 파일이 존재하면 통과.

## 검증 절차

1. 회고 파일이 생성됐는지 확인한다.
2. 설계 결정 요약 섹션에 ADR-A~D가 모두 다뤄졌는지 확인한다.
3. 미결 과제에 후속 Issue #111이 명시됐는지 확인한다.

## 금지사항

- 회고 문서를 완성된 척 꾸미지 마라. 이유: 작업 중 실제로 발생한 사실을 기록하는 것이 목적이다.
- 기존 회고 문서(`docs/tasks/*/retrospective.md`)를 수정하지 마라. 이유: 회고 문서는 역사 기록이므로 소급 수정하지 않는다.
