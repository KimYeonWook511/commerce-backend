# Step 4: write-retrospective

## 읽어야 할 파일

이 step을 시작하기 전 아래 파일들을 읽어 작업 전체를 되돌아봐라:

- `docs/features/payment-attempt-idempotency/prd.md`
- `docs/features/payment-attempt-idempotency/architecture.md`
- `docs/features/payment-attempt-idempotency/adr.md`
- `docs/ADR.md` (ADR-010 확인)
- `src/main/java/com/commerce/payment/application/PaymentAttemptService.java` (최종 상태)
- `src/test/java/com/commerce/payment/application/PaymentAttemptServiceTest.java` (최종 상태)
- `src/test/java/com/commerce/payment/application/concurrency/PaymentAttemptServiceConcurrencyTest.java` (최종 상태)

## 작업

`docs/features/payment-attempt-idempotency/retrospective.md`를 새로 작성한다.

회고록에 포함해야 할 내용:

1. **작업 요약**: 무엇을 왜 변경했는지
2. **주요 설계 결정과 근거**: ADR-A~D의 핵심 선택 이유 (코드 레벨 복잡도 없이 간결하게)
3. **분리된 follow-up**: issue #99, #100 내용과 분리 이유
4. **회고**: 이 작업에서 잘 된 점, 더 빠르게 할 수 있었던 점, 다음에 개선할 점

## Acceptance Criteria

```bash
ls docs/features/payment-attempt-idempotency/retrospective.md
```

## 검증 절차

1. 위 명령으로 파일이 생성되었는지 확인한다.
2. 아래를 확인한다:
   - 4개 항목(작업 요약, 설계 결정, follow-up, 회고)이 모두 포함되어 있는가?
   - 코드 스니펫 없이 서술 위주로 작성되었는가?
3. 결과에 따라 step 상태를 갱신한다.

## 커밋

```
docs: payment-attempt-idempotency 회고록을 작성한다
```

## 금지사항

- 기존 파일을 수정하지 마라. 이 step은 retrospective.md 신규 작성만 한다.
- 코드 구현을 변경하지 마라. 이유: 이 step은 문서 작성 전용이다.
