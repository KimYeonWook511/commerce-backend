# Step 4: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽어라:

- `docs/features/payment-attempt-idempotency/adr.md`
- `docs/adr.md` (현재 마지막 항목 확인 — ADR-009까지 존재)

## 작업

`docs/adr.md` 파일 끝에 ADR-010을 추가한다.

```markdown
### ADR-010: PaymentAttempt 멱등 재요청 amount mismatch는 명시적 예외로 거부
- **결정**: `(merchantPayKey, provider, paymentId, type)` 멱등 키에 대한 재요청이 기존 attempt의 amount와 다르면 `PAYMENT_ATTEMPT_AMOUNT_MISMATCH`(409 Conflict)를 던진다. 기존 attempt 상태(REQUESTED/FAILED/SUCCEEDED)와 무관하게 적용한다.
- **배경**: 기존에는 unique 제약 충돌 시 catch 블록에서 기존 attempt를 그대로 반환했다. amount가 다른 경우에도 침묵 처리되어 호출자 측 산출 오류나 PG 응답 검증/보상 취소 흐름에서 어떤 amount를 기준으로 삼을지 모호해진다. 멱등성 계약("같은 요청 → 같은 결과") 위반이 가시화되지 않는 문제다.
- **이유**: 호출자 측 mismatch(내부 원인)는 PG 응답 mismatch(`PAYMENT_AMOUNT_MISMATCH`, 400, 외부 원인)와 의미·모니터링 기준이 다르다. 별도 코드로 분리하면 알람/대시보드에서 원인 추적이 가능하다. 409 Conflict는 "이미 기록된 상태와 충돌한다"는 의미가 정확하다. amount 변경이 필요하면 새 `merchantPayKey`로 새 요청을 발급하는 게 정상 흐름이다.
- **트레이드오프**: 호출자가 잘못된 amount로 재시도하면 즉시 4xx로 실패한다. 기존에는 침묵 처리되어 후속 흐름에서 뒤늦게 발견될 수 있었다.
```

## Acceptance Criteria

변경 후 아래를 확인한다:
```bash
grep "ADR-010" docs/adr.md
```

## 검증 절차

1. `docs/adr.md`에 ADR-010이 정상 추가되었는지 확인한다.
2. ADR 형식이 ADR-001~ADR-009와 일관되는지 확인한다 (`**결정**`, `**배경**`, `**이유**`, `**트레이드오프**` 항목).
3. 결과에 따라 step 상태를 갱신한다.

## 커밋

```
docs: PaymentAttempt 멱등 금액 불일치 정책 ADR을 추가한다
```

## 금지사항

- 기존 ADR 항목(ADR-001~ADR-009)을 수정하지 마라. 이유: 기존 ADR은 역사 기록이므로 사후 소급 수정하지 않는다.
- `docs/api-spec.md`를 수정하지 마라. 이유: 결제 에러 코드 전체 정비는 별도 작업(본 PR 범위 밖)이다.
