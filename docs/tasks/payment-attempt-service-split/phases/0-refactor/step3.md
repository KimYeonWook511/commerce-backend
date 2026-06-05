# Step 4: sync-root-docs-task-a

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도를 파악하라:

- `docs/tasks/payment-attempt-service-split/prd.md`
- `docs/tasks/payment-attempt-service-split/adr.md`
- `docs/adr.md` — ADR-011(find-first), ADR-012(mark 메서드 선조건 검증) 확인
- `docs/architecture.md` — line 83-84(도메인별 서비스 테이블), line 107-110(결제 승인 흐름)
- `docs/exception-strategy.md` — line 25(find-first 적용 대상), line 79(failApproveAttemptIfRequested 언급)
- `docs/testing-conventions.md` — line 97(`PaymentAttemptService` 언급)

## 작업

### 1. `docs/adr.md`

**(a) ADR-012 본문 수정**

ADR-012는 "PaymentAttempt mark 메서드 선조건 검증" 정책을 정의한다.
- mark 메서드 4개(`markApproveSucceeded`/`markApproveFailed`/`markCancelSucceeded`/`markCancelFailed`)가 `succeed`/`fail` 2개 + `verifyApprovedResponse`로 통합됐음을 반영
- type 가드 제거(status 가드 유지) 결정을 반영
- ADR-012의 핵심 결정("REQUESTED 외 전이 거부 + failCode 보호")은 유지됨을 명시

**(b) ADR-011 후속 노트**

ADR-011은 find-first 패턴 적용 대상을 명시한다.
- 적용 대상 목록의 `PaymentAttemptService` 언급을 `PaymentApprovalAttemptService` + `PaymentCancellationAttemptService`로 갱신
- 패턴 자체는 변경 없음

### 2. `docs/architecture.md`

**(a) line 83-84 도메인별 주요 서비스 테이블**

payment 도메인의 `PaymentAttemptService` → `PaymentApprovalAttemptService`, `PaymentCancellationAttemptService`로 분리 표기

**(b) line 107-110 결제 승인 데이터 흐름**

`→ PaymentAttemptService (시도 이력 기록)` 표기를 분리된 두 서비스 참조로 갱신

### 3. `docs/exception-strategy.md`

**(a) line 25 find-first 패턴 적용 대상**

`PaymentAttemptService` → `PaymentApprovalAttemptService`, `PaymentCancellationAttemptService` 갱신

**(b) line 79 `failApproveAttemptIfRequested` 호출처 명시**

`PaymentAttemptService.failApproveAttemptIfRequested` → `PaymentApprovalAttemptService.failIfRequested` 갱신

### 4. `docs/testing-conventions.md`

line 97의 `PaymentAttemptService` 언급:
- "두 메서드 try-save-catch → find-first 리팩토링" 부분을 `PaymentApprovalAttemptService`/`PaymentCancellationAttemptService`의 각 `getOrCreate` 메서드로 갱신

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria를 실행한다.
2. 아래를 확인한다:
   - `docs/adr.md`에 `PaymentAttemptService` 언급이 없거나 갱신됐는가?
   - `docs/architecture.md` 서비스 테이블과 결제 승인 흐름이 분리된 두 서비스를 반영하는가?
   - `docs/exception-strategy.md`의 find-first 적용 대상과 failIfRequested 언급이 갱신됐는가?
   - `rg "PaymentAttemptService" docs/` 결과에서 갱신되지 않은 언급이 없는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `docs/ddd/` 하위 회고 문서를 수정하지 마라. 이유: 역사 기록이라 사후 소급 수정하지 않음
- `docs/tasks/unique-find-first-policy/`, `docs/tasks/payment-attempt-state-transition-policy/` 문서를 수정하지 마라. 이유: 역사 기록
- `docs/exception-strategy.md`의 보상 catch 2차 예외 처리 섹션 전반은 task B에서 다루므로 이 step에서 건드리지 마라
- ADR-014, ADR-015 관련 작업은 task B 범위이므로 이 step에서 추가하지 마라
- 기존 테스트를 깨뜨리지 마라
