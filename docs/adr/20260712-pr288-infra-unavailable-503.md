# 인증 저장소(Redis) 일시 장애는 UNAVAILABLE(503) 공통 예외 자동 매핑으로 처리한다

- Status: accepted
- Date: 2026-07-12

## Context

PR#97에서 refresh token의 Redis 저장/조회 실패를 strict하게 즉시 실패시키기로 했고(그때는 `AuthException(INTERNAL_ERROR)`, 500), 이후 전용 예외(`RefreshTokenStoreUnavailableException`)와 도메인 전용 `@RestControllerAdvice`(`AuthExceptionHandler`)가 그 응답을 매핑하는 구조가 도입됐다.

두 가지 문제가 있었다. 첫째, 인프라 일시 장애를 500(예상 못한 서버 오류)으로 응답하는 것은 의미가 부정확하다 — Redis가 잠깐 죽은 것은 재시도 가능한 503(Service Unavailable)이 맞다. 둘째, 도메인마다 전용 advice가 늘면 `@Order` 부여·핸들러·단위 테스트가 반복되어 컨벤션 누락 위험이 쌓인다. 이슈 #198은 이를 공통 인프라 장애 베이스 예외 추출로 풀려 했으나, 베이스 없이 카테고리만으로 해결할 수 있다.

## Decision

인프라 일시 장애를 표현하는 `ErrorCategory.UNAVAILABLE`(→ 503 Service Unavailable)을 신설한다.

refresh token 저장소 장애는 adapter가 `DataAccessException`을 잡아 `AuthException(REFRESH_STORE_UNAVAILABLE)`으로 변환해 던지고, `GlobalExceptionHandler`가 `CustomException`을 503으로 자동 매핑한다. 전용 예외 클래스와 도메인 전용 advice는 폐기한다.

공통 인프라 장애 베이스 예외는 만들지 않는다. 상속 전략은 "application이 그 장애를 catch해서 삼키느냐"로 갈린다 — fallback을 위해 catch해야 하는 케이스(Order 멱등성 저장소)는 자동 매핑을 피하는 전용 `RuntimeException` 타입을 유지하고, catch하지 않는 케이스(Auth)만 공통 예외의 자동 매핑을 쓴다.

## Consequences

PR#97의 strict 즉시 실패 정책 자체는 유지되나, 응답이 500에서 503으로 바뀌고 던지는 방식이 전용 예외+advice에서 공통 `AuthException` 자동 매핑으로 바뀐다(그 부분을 이 결정이 갱신한다).

도메인이 늘어도 인프라 일시 장애용 advice를 새로 만들 필요가 없다. 로깅은 adapter가 요청 컨텍스트를 WARN으로 남기고 상세 스택은 끝단 핸들러가 ERROR로 한 번 남겨 역할을 나눈다(스택 중복 회피).

향후 fallback 없는 인프라 일시 장애가 늘면 같은 패턴(`UNAVAILABLE` + 공통 예외)을 재사용한다. catch해서 삼키는 fallback 케이스가 3곳 이상으로 늘면 그때 전용 타입의 공통 베이스 추출을 재검토한다.
