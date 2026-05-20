# Step 5: write-retrospective

## 읽어야 할 파일

먼저 아래 파일들을 읽고 완료된 변경 내용을 파악하라:

- `/docs/tasks/db-constraint-violation-handling/prd.md`
- `/docs/tasks/db-constraint-violation-handling/architecture.md`
- `/docs/tasks/db-constraint-violation-handling/adr.md`
- `/docs/tasks/db-constraint-violation-handling/phases/0-narrow-catch-and-redefine-safeguard/index.json` (완료된 step 목록)

기존 회고록 형식 참고:
- `docs/tasks/payment-attempt-idempotency/retrospective.md`
- `docs/tasks/order-idempotency/` (있다면)

## 작업

### `docs/tasks/db-constraint-violation-handling/retrospective.md` 작성

아래 항목을 포함하여 회고록을 작성한다.

1. **작업 요약**: 변경 내용과 목적을 한 단락으로 요약한다.
2. **결정한 정책**: 이 task에서 확립한 예외 처리 정책 (architecture.md의 핵심 내용 요약).
3. **주요 발견 및 논의**: 작업 중 발견한 비자명한 사실, 엣지 케이스 논의 내용을 기록한다.
   - `Order` 엔티티의 unique 제약이 두 개였던 점 (비즈니스 + 기술적)
   - `DataIntegrityViolationException`과 `DuplicateKeyException`의 계층 관계
   - fallback 재조회 실패 시나리오 (다른 unique 위반, race condition 데이터 소멸)
   - GlobalExceptionHandler의 응답 코드 불일치 발견 (409 → 500)
4. **변경 범위 정리**: 수정한 파일 목록.
5. **미결 과제**: 이번에 제외한 항목 (Issue #99 등).
6. **회고**: 잘된 점, 개선할 점.

회고록은 역사 기록이므로 작성 후 수정하지 않는다.

## Acceptance Criteria

```bash
# 회고록 파일이 생성됐는지 확인
ls docs/tasks/db-constraint-violation-handling/retrospective.md
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - 회고록에 주요 결정 사항과 발견 내용이 포함됐는지 확인한다.
   - 미결 과제(Issue #99)가 명시됐는지 확인한다.
3. 결과에 따라 step 상태를 갱신한다.

## 커밋 단위

1. `docs: db-constraint-violation-handling 회고록을 작성한다`

## 금지사항

- 기존 테스트를 깨뜨리지 마라.
- 회고록에 작성된 내용을 사후에 소급 수정하지 마라. 이유: 회고록은 그 시점의 기록이다.
