# 태스크 PRD

## 태스크명

- `unique-find-first-policy`

## 배경

- PR #106 (db-constraint-violation-handling) 의 후속 작업이다. 회고록(`docs/tasks/db-constraint-violation-handling/retrospective.md`)에서 "Application이 인프라 예외 타입(`DuplicateKeyException`)에 직접 의존한다"는 부채를 Issue #105로 분리했고, 이번 태스크에서 그 부채를 해소한다.
- 원안은 `DuplicateKeyException` catch를 Adapter 계층으로 옮기는 것이었으나, 논의 결과 5곳의 처리 동작(멱등 흡수/도메인 예외 변환/silent skip)이 다르고 도메인 매핑 지식이 Adapter로 새는 문제를 피하기 위해 더 단순한 정책으로 재정의한다.
- 새 정책: 5곳 모두 동일한 본질 흐름 — `DB find → 없으면 insert → 충돌 시 500`. 사전 `find`가 정상 흐름을 처리하고, `insert` 시점의 unique race window는 안전망 500으로 가시화한다.

## 목표

- Application 계층 5곳의 `DuplicateKeyException` catch를 모두 제거해 인프라 예외 타입 직접 의존을 해소한다.
- 정상 멱등/중복 시나리오는 사전 `find`로 처리하고, race window unique 충돌은 안전망 500으로 통합 처리한다.
- 다른 DAO 예외(`BadSqlGrammarException`, `CannotAcquireLockException` 등)도 stack trace + 500으로 일관 처리되도록 `GlobalExceptionHandler`에 `DataAccessException` 부모 안전망을 추가한다.
- 향후 새 unique 제약 도입 시 패턴 선택 기준(트랜잭션 짧음 + 충돌 확률 낮음 = find-first / 충돌 잦음 = try-save-catch)을 ADR로 명문화한다.

## 범위

### 포함 범위

- Application 5곳의 catch 제거 또는 find-first 리팩토링:
  - `MemberRegistrationService`
  - `PaymentApprovalService`
  - `PaymentAttemptService` (`getOrCreateApproveAttempt`, `getOrCreateCancelAttempt`)
  - `OrderCreateService` (`attemptCreateOrder`)
  - `StockRestoreOutboxConsumeService` (`markProcessed`)
- `OrderCreateService`에 Redis reserve 이후 DB `findByMemberIdAndIdempotencyKey` 사전 체크 추가 (TTL 만료 후 정당한 멱등 재요청 흡수용)
- `StockRestoreOutboxConsumeService` 용 `ProcessedEventRepository.existsByEventIdAndConsumerType` (또는 기존 메서드 재사용) 도입
- `GlobalExceptionHandler` 에 `DataAccessException` 부모 안전망 + `CommonErrorCode.DATA_ACCESS_ERROR(COMMON-500-2)` 신설
- 영향 받는 단위 테스트의 `DuplicateKeyException` mock 시나리오 갱신
- Testcontainers 회귀 방어 통합 테스트 갱신 (안전망 500 도달 검증)
- `NaverPayServiceIntegrationTest`의 `paymentAttemptService` spy 스텁 제거 검토
- 루트 docs 갱신: `docs/architecture.md`, `docs/ADR.md`, `commerce-backend/CLAUDE.md`
- 이전 태스크 폴더(`docs/tasks/db-constraint-violation-handling/`) 의 prd/adr/api-spec/architecture 에 정책 폐기 anchor 한 줄 추가 (retrospective 와 phases 는 immutable)

### 제외 범위

- 워크스페이스 공유 문서(`commerce-workspace/docs/`) 갱신은 본 세션 범위 밖. 워크스페이스 CLAUDE.md의 "계약 싱크" 역할에 따라 Frontend 세션에서 처리한다.
- `Exception.class` fallback 핸들러 자체의 stack trace 로깅 누락은 본 태스크에서 다루지 않는다 (DAO 안전망 추가로 DAO 카테고리 한정 stack trace는 확보됨).
- DB 스키마 변경 없음.
- 외부 API 응답 코드 명세 변경 없음 (race window 응답 코드는 `docs/api-spec.md` 와 `docs/api-contract.md` 에 명시되어 있지 않음).

## 주요 시나리오

- **정상 회원 가입**: `existsByEmail` 사전 체크 통과 → 정상 저장 → 200
- **이미 등록된 이메일 가입 시도**: `existsByEmail` true → 4xx `DUPLICATE_EMAIL`
- **동시 가입 race window**: 두 요청이 동시에 `existsByEmail` 통과 → 한쪽 save 성공, 다른 쪽 unique 위반 → 안전망 500 (코드 버그로 가시화)
- **PaymentApproval 정상 멱등 흡수**: `findByMerchantPayKey` 결과 존재 → 검증 후 기존 payment 반환 (200)
- **PaymentAttempt 멱등 재요청**: `findApproveAttempt` 결과 존재 → amount 검증 후 기존 attempt 반환 (200), race 시 안전망 500
- **OrderCreate 정상 멱등 재요청 (TTL 안)**: Redis reserve 실패 → `getCompletedOrderId` 로 기존 order 반환 (200)
- **OrderCreate TTL 만료 후 정당한 재요청**: Redis reserve 성공 → DB `findByMemberIdAndIdempotencyKey` 결과 존재 → Redis complete 갱신 후 반환 (200)
- **OrderCreate race**: Redis 통과 + DB find empty + insert 시 충돌 → `RuntimeException` catch 가 Redis 정리 후 rethrow → 안전망 500
- **StockRestoreOutbox 중복 이벤트**: `existsByEventIdAndConsumerType` true → silent skip (false 반환)
- **다른 DAO 예외 발생** (`BadSqlGrammarException`, `CannotAcquireLockException`): 새 `DataAccessException` 안전망에서 stack trace + 500 (`COMMON-500-2`)

## 요구사항

- 5곳 모두에서 `DuplicateKeyException` 명시 catch를 제거한다.
- Application 코드는 `org.springframework.dao.*` 패키지 임포트를 갖지 않는다 (Application 은 인프라 예외 타입을 모른다).
- `GlobalExceptionHandler` 의 `DataIntegrityViolationException` 핸들러는 그대로 유지되어 unique race window 와 NOT NULL/FK/CHECK 위반을 모두 흡수한다 (`DuplicateKeyException` 은 그 하위라 자동 매칭).
- `DataAccessException` 부모 핸들러는 그 외 DAO 예외만 잡는다 (구체 핸들러가 먼저 매칭됨).
- 단위 테스트와 통합 테스트는 새 정책에 맞춰 시나리오를 갱신한다.
- 모든 변경은 race window 한정 행위 변경이며, 정상 멱등 흐름은 모두 보존된다.

## 제약사항

- `JpaConfig` 의 `SQLErrorCodeSQLExceptionTranslator` 빈은 그대로 유지 (안전망에서 unique 위반이 `DuplicateKeyException` 으로 정확히 분류되어 로깅 됨).
- 회고 문서(`retrospective.md`) 와 phase 실행 기록(`phases/**`) 은 immutable.
- `commerce-workspace/docs/` 는 본 세션에서 수정하지 않는다.
- 행위 변경:
  - Member: race window 4xx → 500
  - PaymentApproval: race window 4xx → 500
  - PaymentAttempt × 2: race window 200(멱등 흡수) → 500
  - OrderCreate: 행위 변경 없음 (TTL 만료 후 재요청은 DB find 사전 체크로 200 유지, race 는 기존도 rethrow → 500)
  - StockRestoreOutbox: race window 200(silent skip) → 500
  - PR 본문에 race window 한정 변경임을 명시한다.
