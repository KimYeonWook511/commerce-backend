# Step 3: sync-root-docs

## 읽어야 할 파일

- `/docs/tasks/payment-attempt-state-transition-policy/adr.md`
- `/docs/ADR.md` (기존 ADR-010, ADR-011 형식 참고)

step1에서 생성/수정된 파일:

- `/src/main/java/com/commerce/payment/exception/PaymentErrorCode.java`
- `/src/main/java/com/commerce/payment/domain/PaymentAttempt.java`

## 작업

`docs/ADR.md`에 ADR-012 항목을 ADR-011 다음에 추가한다. 기존 ADR-010, ADR-011 형식(간결한 5~7줄 요약)과 일치시킨다.

추가할 내용:

```markdown
### ADR-012: PaymentAttempt mark 메서드는 상태 전이와 type 정합성을 도메인에서 검증한다
- **결정**: `PaymentAttempt`의 mark 메서드 4개(`markApproveSucceeded`, `markApproveFailed`, `markCancelSucceeded`, `markCancelFailed`)는 호출 시점에 (1) `status == REQUESTED`, (2) `type`이 메서드 의도와 일치를 검증한다. 위반 시 `PaymentException`(`PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED` / `PAYMENT_ATTEMPT_TYPE_MISMATCH`, 500)으로 거부. 멱등 자기 전이도 거부.
- **배경**: 기존 mark 메서드는 검증 없이 status/failCode를 덮어써 FAILED → SUCCEEDED 시 failCode가 사라지는 위험이 있다. 정상 흐름은 application 계층 switch가 막아주지만 도메인 모델 자체에는 안전망이 없다.
- **이유**: 멱등성은 상위 레이어(`PaymentAttemptService.getOrCreateApproveAttempt` + `NaverPayApprovalService.processApproveAttempt` switch)에서 처리되므로 mark는 멱등을 책임지지 않는다. Order 도메인의 명시적 선조건 검증 패턴과 일관. 도메인 무결성 위반은 내부 결함 신호라 외부 입력 mismatch(ADR-010, 409)와 구분되도록 500.
- **트레이드오프**: 새 검증 도입 시 catch 블록 안에서 mark가 호출되는 호출처(예: `NaverPayApprovalService.failApproveAndCancelApprovedPayment`)는 race window에서 mark가 throw해도 보상 트랜잭션이 중단되지 않도록 적절히 보호해야 한다. 본 PR은 해당 함수 내부 한 곳을 try-catch로 보호한다. 보상 catch 2차 예외 처리의 일반 원칙(의사결정 트리)과 상위 catch 1차 예외 `log.error` 누락 보강은 #111(후속 Issue)에서 정의 예정. 상세는 `docs/tasks/payment-attempt-state-transition-policy/adr.md` 참조.
```

## Acceptance Criteria

```bash
grep -n "ADR-012" docs/ADR.md
```

ADR-012 항목이 존재하면 통과.

## 검증 절차

1. `docs/ADR.md`에서 ADR-012가 ADR-011 다음에 위치하는지 확인한다.
2. 기존 ADR-010, ADR-011과 동일한 형식(굵은 항목 제목: 결정/배경/이유/트레이드오프)인지 확인한다.
3. 후속 Issue 번호 `#111`이 트레이드오프 항목에 명시됐는지 확인한다.

## 금지사항

- 기존 ADR-001 ~ ADR-011 내용을 수정하지 마라. 이유: ADR은 불변 결정 기록이다.
- ADR-012에 구현 디테일(코드 라인 번호, 함수 내부 로직)을 장황하게 적지 마라. 이유: 상세는 `docs/tasks/payment-attempt-state-transition-policy/adr.md`에 있으며, 루트 ADR은 결정 요약에 집중한다.
