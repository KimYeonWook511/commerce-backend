# Step 2: exception-handler-level-policy

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/boundary-logging-standardization/prd.md`
- `/docs/tasks/boundary-logging-standardization/architecture.md`
- `/docs/tasks/boundary-logging-standardization/adr.md`
- `/docs/tasks/boundary-logging-standardization/api-spec.md`
- `/docs/tasks/boundary-logging-standardization/db-schema.md`
- `/src/main/java/com/commerce/common/exception/GlobalExceptionHandler.java` — 정비 대상
- `/src/main/java/com/commerce/common/exception/CustomException.java` — status 필드 확인
- `/src/main/java/com/commerce/common/exception/ErrorCode.java` (또는 interface) — getStatus() 시그니처 확인
- 이전 step 결과: `/src/main/java/com/commerce/common/log/filter/AccessLogFilter.java`, `/src/main/java/com/commerce/common/log/filter/AccessLogFilterConfig.java`

태스크 문서만으로 부족한 공통 맥락이 있으면 아래를 추가로 읽는다.

- `/docs/logging-conventions.md` §4 (예외 로깅 표준)
- `/docs/exception-strategy.md`

## 작업

`GlobalExceptionHandler`의 9개 핸들러를 컨벤션 §4 표에 맞춰 정비하고, 핸들러별 로그 검증 테스트를 추가한다.

### 핸들러별 정책

| 핸들러 | 현재 | 변경 후 |
|---|---|---|
| `handleCustomException(CustomException)` | `log.error("커스텀 예외 발생: {}", ex.getMessage())` (4xx/5xx 무차별) | **status 분기**: status ≥ 500 → `log.error("커스텀 예외 발생", ex)` (stack 포함). status < 500 → 무로그. |
| `handleException(Exception)` | `log.error("처리되지 않은 예외 발생: {}", ex.getMessage())` (stack 누락 버그) | `log.error("처리되지 않은 예외 발생", ex)` (stack 포함). |
| `handleMethodArgumentNotValidException` | `log.error("요청 값 검증 실패: {}", ex.getMessage())` | **무로그** (400). |
| `handleHttpMessageNotReadableException` | `log.error("요청 본문 파싱 실패: {}", ex.getMessage())` | **무로그** (400). |
| `handleOptimisticLockingFailureException` | `log.warn("낙관적 락 충돌 발생: {}", ex.getMessage())` | **유지** (WARN no-stack, 컨벤션 §4 표 일치). |
| `handleDataIntegrityViolationException` | `log.error("데이터 무결성 위반 (안전망)", ex)` | **유지**. |
| `handleDataAccessException` | `log.error("DAO 예외 (안전망)", ex)` | **유지**. |

### CustomException status 분기 구현

`CustomException.getErrorCode().getStatus()`를 사용한다(기존 시그니처). `HttpStatus.is5xxServerError()` 또는 status value ≥ 500 비교로 분기.

```java
@ExceptionHandler(CustomException.class)
public ResponseEntity<ApiResponse<Void>> handleCustomException(CustomException ex) {
    ErrorCode errorCode = ex.getErrorCode();
    if (errorCode.getStatus().is5xxServerError()) {
        log.error("커스텀 예외 발생", ex);
    }
    return ResponseEntity.status(errorCode.getStatus())
        .body(ApiResponse.error(errorCode));
}
```

(정확한 시그니처는 `ErrorCode.getStatus()` 반환 타입을 확인 후 따른다 — `HttpStatus`라면 위 코드, `int`라면 `errorCode.getStatus() >= 500`)

### 테스트 신규

`src/test/java/com/commerce/common/exception/GlobalExceptionHandlerTest.java`를 신규 작성한다.

- `ListAppender<ILoggingEvent>`로 `GlobalExceptionHandler` logger를 캡처.
- 각 핸들러 호출 후 로그 이벤트 개수와 레벨, throwable 포함 여부 검증.

검증 케이스 (9개):
1. `handleCustomException`: 4xx ErrorCode → 로그 0건
2. `handleCustomException`: 5xx ErrorCode → 로그 1건, level=ERROR, throwable 포함
3. `handleException`: 로그 1건, level=ERROR, throwable 포함 (stack 보존 검증)
4. `handleMethodArgumentNotValidException`: 로그 0건
5. `handleHttpMessageNotReadableException`: 로그 0건
6. `handleOptimisticLockingFailureException`: 로그 1건, level=WARN, throwable 미포함
7. `handleDataIntegrityViolationException`: 로그 1건, level=ERROR, throwable 포함
8. `handleDataAccessException`: 로그 1건, level=ERROR, throwable 포함
9. 응답 status 코드 변경 없음 검증 (각 핸들러)

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - 5xx 시스템 예외는 모두 stack trace를 포함하는가? (`log.error("...", ex)` 형식)
   - 4xx CustomException은 로그가 남지 않는가?
   - `OptimisticLockingFailureException`은 WARN no-stack인가?
   - 메시지에 `ex.getMessage()`를 concatenation으로 붙이지 않는가? (placeholder `{}` 사용)
   - 응답 status, ErrorCode, body 형식은 변경 없는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 4xx CustomException에 WARN/INFO 로그를 추가하지 마라. 이유: ADR 결정 2 — WARN 분류는 운영 데이터 누적 후 별도 작업.
- 핸들러에서 ErrorCode를 도메인별로 화이트리스트 분기하지 마라. 이유: 핸들러가 도메인 결합도가 커진다 (ADR 결정 2).
- `ErrorCode` 인터페이스에 `loggable()` 같은 메서드를 추가하지 마라. 이유: 본 작업 범위 밖이며, 분류 기준이 미정인 상태에서 dead code 위험.
- 응답 status, ApiResponse 형식, ErrorCode 종류를 변경하지 마라. 이유: 본 작업은 로그만 정비하며 API 계약을 건드리지 않는다.
- `e.printStackTrace()` 또는 `log.error("..." + e.getMessage())` 형식을 사용하지 마라. 이유: 컨벤션 §4·§7.
- 기존 테스트를 깨뜨리지 마라.
