# PRD: auth-service-to-usecase

## 목적

`AuthLoginService`와 `AuthTokenReissueService`가 `service/` 패키지에 있으면서 `usecase/`의 `AuthTokenIssueUseCase`를 주입받는 역전 구조를 해소한다.

두 클래스는 DB write 없이 흐름을 조립하는 orchestrator 역할만 하므로 ADR-054 기준 UseCase에 해당한다. `@Transactional(readOnly=true)`도 실질적으로 불필요하다(단건 DB 조회 하나씩이고, 재조회·락 없음).

## 범위

### 변경 대상

| 현재 | 변경 후 |
|---|---|
| `auth/application/service/AuthLoginService` | `auth/application/usecase/AuthLoginUseCase` |
| `auth/application/service/AuthTokenReissueService` | `auth/application/usecase/AuthTokenReissueUseCase` |

### 변경 내용

- 패키지: `application/service/` → `application/usecase/`
- 클래스명: `…Service` → `…UseCase`
- 애너테이션: `@Service` → `@Component`, `@Transactional(readOnly=true)` 제거
- 주입처: `AuthController` import·타입 변경
- 테스트: 클래스명·패키지 변경, import 수정

### 변경하지 않는 것

- 메서드 시그니처·내부 로직
- DTO, port 인터페이스
- `AuthTokenIssueUseCase` 자체
