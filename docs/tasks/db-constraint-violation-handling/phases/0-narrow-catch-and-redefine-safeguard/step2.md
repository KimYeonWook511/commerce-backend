# Step 3: redefine-safeguard-handler

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도를 파악하라:

- `/docs/tasks/db-constraint-violation-handling/prd.md`
- `/docs/tasks/db-constraint-violation-handling/architecture.md`

이전 step에서 변경된 파일:
- `src/main/java/com/commerce/payment/application/PaymentAttemptService.java`
- `src/main/java/com/commerce/order/application/OrderCreateService.java`

변경 대상 파일:
- `src/main/java/com/commerce/common/exception/GlobalExceptionHandler.java`
- `src/main/java/com/commerce/common/exception/CommonErrorCode.java`
- `src/test/java/com/commerce/outbox/infrastructure/JpaProcessedEventRepositoryTest.java`
- `src/test/java/com/commerce/member/infrastructure/MemberRepositoryJpaAdapterTest.java`

## 작업

### 1. GlobalExceptionHandler — 안전망 의미 재정의

`GlobalExceptionHandler.java:71-78`을 수정한다.

변경 사항:
- 응답 상태를 `CommonErrorCode.DATA_INTEGRITY_VIOLATION`(500으로 갱신됨)으로 유지하면서 응답 흐름 정리
- 핸들러 메서드에 안전망 의미 주석 추가
- `log.error("...", ex.getMessage())` → `log.error("...", ex)`로 stack trace까지 로깅

```java
// 안전망: application 계층에서 catch 누락 시 fallback. 정상 흐름에서 도달하지 않는다.
// 도달 시 코드 버그를 의미하므로 500으로 응답하고 stack trace를 기록한다.
@ExceptionHandler(DataIntegrityViolationException.class)
public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolationException(
    DataIntegrityViolationException ex
) {
    log.error("데이터 무결성 위반 (안전망): {}", ex.getMessage(), ex);
    return ResponseEntity.status(CommonErrorCode.DATA_INTEGRITY_VIOLATION.getStatus())
        .body(ApiResponse.error(CommonErrorCode.DATA_INTEGRITY_VIOLATION));
}
```

`OptimisticLockingFailureException` 핸들러는 수정하지 않는다.

### 2. CommonErrorCode — 상태 코드 500으로 변경

`CommonErrorCode.java:8`의 `DATA_INTEGRITY_VIOLATION`을 수정한다.

```diff
- DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT, "COMMON-409", "데이터 무결성 위반입니다"),
+ DATA_INTEGRITY_VIOLATION(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-500-1", "데이터 무결성 위반이 발생했습니다"),
```

수정 전 `COMMON-409` 코드 참조가 다른 곳에 있는지 grep으로 확인한다:
```bash
grep -rn "COMMON-409" src/
```

### 3. Repository 슬라이스 테스트 어서션 좁히기

JPA가 unique 위반 시 `DuplicateKeyException`을 던짐을 스펙으로 명세화한다.

**`JpaProcessedEventRepositoryTest.java:37`**:
```diff
- import org.springframework.dao.DataIntegrityViolationException;
+ import org.springframework.dao.DuplicateKeyException;

- .isInstanceOf(DataIntegrityViolationException.class);
+ .isInstanceOf(DuplicateKeyException.class);
```

테스트 메서드명도 동기화한다:
```diff
- void save_whenEventIdAndConsumerTypeDuplicated_throwDataIntegrityViolationException() {
+ void save_whenEventIdAndConsumerTypeDuplicated_throwDuplicateKeyException() {
```

**`MemberRepositoryJpaAdapterTest.java:55, 63`**:
```diff
- import org.springframework.dao.DataIntegrityViolationException;
+ import org.springframework.dao.DuplicateKeyException;

- void save_whenEmailDuplicated_throwDataIntegrityViolationException() {
+ void save_whenEmailDuplicated_throwDuplicateKeyException() {

- .isInstanceOf(DataIntegrityViolationException.class);
+ .isInstanceOf(DuplicateKeyException.class);
```

## Acceptance Criteria

공통 예외/응답 변경이 포함되므로 전체 테스트를 실행한다.

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `DATA_INTEGRITY_VIOLATION` 상태 코드가 500인지 확인한다.
   - `GlobalExceptionHandler`에 안전망 주석이 추가됐는지 확인한다.
   - Repository 슬라이스 테스트가 `DuplicateKeyException`으로 어서션하는지 확인한다.
   - `COMMON-409` 참조가 다른 곳에 남아 있지 않은지 확인한다:
     ```bash
     grep -rn "COMMON-409" src/
     ```
3. 결과에 따라 step 상태를 갱신한다.

## 커밋 단위

1. `refactor: DataIntegrityViolationException 안전망 핸들러를 500으로 재정의한다`
   - GlobalExceptionHandler 수정 + CommonErrorCode 수정
2. `test: Repository 슬라이스 테스트 unique 위반 어서션을 DuplicateKeyException으로 좁힌다`
   - JpaProcessedEventRepositoryTest + MemberRepositoryJpaAdapterTest 수정

## 금지사항

- `OptimisticLockingFailureException` 핸들러를 수정하지 마라. 이유: 낙관적 락 충돌은 클라이언트 재시도 가능한 정상 시나리오이며 이번 범위 밖이다.
- `DuplicateKeyException` 전용 핸들러를 GlobalExceptionHandler에 추가하지 마라. 이유: unique 위반도 application에서 처리하고 presentation에 인프라 예외를 노출하지 않는다는 CLAUDE.md 규칙에 위반된다.
- 응답 메시지에 `ex.getMessage()`를 직접 노출하지 마라. 이유: DB 스키마 정보(테이블/컬럼명)가 클라이언트에 노출될 수 있다.
- 기존 테스트를 깨뜨리지 마라.
