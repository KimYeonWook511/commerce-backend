# 도메인 예외는 HTTP 상태 대신 의미 분류(ErrorCategory)를 들고, 상태 매핑은 HTTP 경계가 소유한다

- Status: accepted
- Date: 2026-07-11

## Context

예외 노출 경계를 재정의하면서(→ pr281) 도메인은 어떤 영속성 예외도 모르게 됐지만, 도메인 예외가 든 `ErrorCode`는 여전히 `getStatus()`로 `HttpStatus`를 직접 반환했다. 그래서 도메인별 `*ErrorCode` enum(order·auth·payment 등 7개)이 `org.springframework.http.HttpStatus`에 의존했다 — 도메인이 HTTP(전송 계층)를 아는 상태다. 추후 도메인을 별도 모듈로 떼어낼 때 web 의존이 새지 않도록 이 의존을 걷어낸다.

## Decision

- `ErrorCode`는 `HttpStatus` 대신 **의미 분류 `ErrorCategory`**를 든다(`getStatus()` → `getCategory()`). 도메인 예외는 상태코드를 모른다(transport 무지).
- **카테고리 → HttpStatus 매핑은 HTTP를 아는 경계가 소유한다** — `ErrorCategoryHttpStatus.of(...)`를 `GlobalExceptionHandler`·인증 필터·인가 인터셉터·도메인 `@RestControllerAdvice`에서만 호출한다. 매핑은 default 없는 `switch`로 카테고리 누락을 컴파일 타임에 막는다.
- 카테고리 집합은 **현재 외부 응답 상태코드를 정확히 보존**하도록 정한다(상태 보존 7개): `INVALID`(400)·`UNAUTHORIZED`(401)·`FORBIDDEN`(403)·`NOT_FOUND`(404)·`CONFLICT`(409)·`UPSTREAM_ERROR`(502, PG/gateway)·`INTERNAL`(500). 각 카테고리는 "없음/금지/상류 장애" 등 진짜 의미 분류라 상태코드 별칭이 아니다.
- ArchUnit이 domain의 `org.springframework.http`·`org.springframework.web` 의존을 금지해 강제한다.

## Consequences

- **얻는 것**: 도메인이 HTTP를 모르게 되어, 추후 모듈 분리 시 web 의존이 새지 않는다. 상태코드 결정이 매핑 한 곳(default 없는 switch)에 응집돼, 새 상태가 필요하면 카테고리를 추가하고 매핑을 반드시 채워야 컴파일된다.
- **보존**: 외부 응답 상태코드 변경 0(계약 보존). `AuthExceptionHandler`의 저장소 장애 응답도 현행 500(INTERNAL) 유지. `getCode()` 문자열도 그대로.
- **감수**: 카테고리와 HttpStatus를 잇는 매핑 클래스(`ErrorCategoryHttpStatus`)가 새로 생긴다. 다만 이 클래스만 둘을 함께 알고, 나머지 코드는 카테고리만 다룬다.

관련: 예외 노출 경계 재정의(→ pr281)의 후속으로, 그때 미뤄둔 "도메인 예외의 HTTP 의존 제거"를 여기서 완결한다.
