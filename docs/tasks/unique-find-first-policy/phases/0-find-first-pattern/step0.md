# Step 0: data-access-safety-net

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/unique-find-first-policy/prd.md`
- `/docs/tasks/unique-find-first-policy/architecture.md`
- `/docs/tasks/unique-find-first-policy/adr.md`
- `/docs/tasks/unique-find-first-policy/api-spec.md`
- `/docs/tasks/unique-find-first-policy/db-schema.md`
- `/src/main/java/com/commerce/common/exception/GlobalExceptionHandler.java`
- `/src/main/java/com/commerce/common/exception/CommonErrorCode.java`
- `/src/main/java/com/commerce/common/exception/ErrorCode.java`
- `/src/main/java/com/commerce/common/ApiResponse.java`

이전 step 없음 (첫 번째 step).

## 작업

본 step 은 이후 step 들의 안전망 선행 작업이다. `GlobalExceptionHandler` 에 `DataAccessException` 부모 핸들러를 추가하고 새 ErrorCode 를 신설한다.

### 1. `CommonErrorCode.java` 에 새 ErrorCode 추가

`src/main/java/com/commerce/common/exception/CommonErrorCode.java` 의 enum 에 다음 값을 추가한다:

```java
DATA_ACCESS_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-500-2", "데이터 접근 중 오류가 발생했습니다")
```

- 기존 `DATA_INTEGRITY_VIOLATION(COMMON-500-1)` 다음 위치에 둔다.
- 코드 컨벤션과 들여쓰기는 기존 enum 항목과 동일하게 맞춘다.

### 2. `GlobalExceptionHandler.java` 에 `DataAccessException` 부모 핸들러 추가

`src/main/java/com/commerce/common/exception/GlobalExceptionHandler.java` 에 새 핸들러를 추가한다. 기존 `handleDataIntegrityViolationException` 핸들러 바로 다음 위치(혹은 가장 마지막)에 둔다.

```java
// 안전망: 위 구체 DAO 핸들러(DataIntegrityViolationException, OptimisticLockingFailureException)에
// 매칭되지 않는 모든 DAO 예외(BadSqlGrammarException, CannotAcquireLockException 등)를 받는다.
// stack trace 와 함께 500 으로 응답해 운영 모니터링에서 DAO 카테고리(COMMON-500-2)로 분류된다.
@ExceptionHandler(DataAccessException.class)
public ResponseEntity<ApiResponse<Void>> handleDataAccessException(
    DataAccessException ex
) {
    log.error("DAO 예외 (안전망)", ex);
    return ResponseEntity.status(CommonErrorCode.DATA_ACCESS_ERROR.getStatus())
        .body(ApiResponse.error(CommonErrorCode.DATA_ACCESS_ERROR));
}
```

- `import org.springframework.dao.DataAccessException;` 추가.
- 기존 `DataIntegrityViolationException` / `OptimisticLockingFailureException` 핸들러는 그대로 유지한다 — 더 구체적 타입이라 Spring 이 먼저 매칭하므로 부모 핸들러로 떨어지지 않는다.
- `DuplicateKeyException` 명시 등록은 하지 않는다 — `DataIntegrityViolationException` 의 하위라 자동 흡수.

### 3. 단위 테스트 추가 (선택, 기존 테스트가 핸들러를 검증한다면 보강)

기존에 `GlobalExceptionHandler` 단위 테스트가 있다면, `DataAccessException` 부모 핸들러가 다른 DAO 예외(예: `BadSqlGrammarException`) 를 잡아 500 + `COMMON-500-2` 로 응답하는지 검증 케이스를 추가한다. 기존 `DataIntegrityViolationException` 핸들러 회귀 검증 케이스도 유지한다.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `CommonErrorCode.DATA_ACCESS_ERROR` 가 추가되었고 status / code / message 가 정의된 대로인가?
   - `GlobalExceptionHandler` 에 `DataAccessException` 부모 핸들러가 추가되었고 stack trace 로깅 + 500 응답을 반환하는가?
   - 기존 `DataIntegrityViolationException` / `OptimisticLockingFailureException` 핸들러가 그대로 유지되었는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `Exception.class` fallback 핸들러를 본 step 에서 수정하지 마라. 이유: 본 태스크 범위 밖. `Exception` fallback 의 stack trace 누락 개선은 별도 과제로 분리된다.
- `DuplicateKeyException` 전용 핸들러를 추가하지 마라. 이유: `DataIntegrityViolationException` 의 하위라 다형성으로 자동 흡수된다. 별도 4xx 응답 정책이 필요한 경우에만 추가한다.
- `JpaConfig.java` 를 수정하지 마라. 이유: `SQLErrorCodeSQLExceptionTranslator` 빈은 PR #106 결정대로 그대로 유지된다.
- 기존 테스트를 깨뜨리지 마라.
