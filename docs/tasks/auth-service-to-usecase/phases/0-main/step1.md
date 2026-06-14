# Step 1: auth-service-to-usecase

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도를 파악하라:

- `docs/tasks/auth-service-to-usecase/prd.md`
- `docs/tasks/auth-service-to-usecase/adr.md`
- `docs/adr.md` — ADR-054(UseCase/Service 역할 이원화·빈 애너테이션 기준) 항목

변경 대상 파일 (현재 코드):
- `src/main/java/com/commerce/auth/application/service/AuthLoginService.java`
- `src/main/java/com/commerce/auth/application/service/AuthTokenReissueService.java`
- `src/main/java/com/commerce/auth/presentation/http/AuthController.java`
- `src/test/java/com/commerce/auth/application/service/AuthLoginServiceTest.java`
- `src/test/java/com/commerce/auth/application/service/AuthTokenReissueServiceTest.java`
- `src/test/java/com/commerce/auth/presentation/http/AuthControllerTest.java`

## 작업

### 1. AuthLoginUseCase 신규 생성

`src/main/java/com/commerce/auth/application/usecase/AuthLoginUseCase.java`를 생성한다.

- 패키지: `com.commerce.auth.application.usecase`
- 클래스명: `AuthLoginUseCase`
- 애너테이션: `@Component` (`@Service` 아님)
- `@Transactional` 제거 (readOnly 포함)
- 내부 로직(필드·메서드 시그니처·구현·`@Slf4j`·로그 호출)은 기존 `AuthLoginService`와 동일하게 유지

`src/main/java/com/commerce/auth/application/service/AuthLoginService.java` 파일을 삭제한다.

### 2. AuthTokenReissueUseCase 신규 생성

`src/main/java/com/commerce/auth/application/usecase/AuthTokenReissueUseCase.java`를 생성한다.

- 패키지: `com.commerce.auth.application.usecase`
- 클래스명: `AuthTokenReissueUseCase`
- 애너테이션: `@Component`
- `@Transactional` 제거 (readOnly 포함)
- 내부 로직은 기존 `AuthTokenReissueService`와 동일하게 유지

`src/main/java/com/commerce/auth/application/service/AuthTokenReissueService.java` 파일을 삭제한다.

### 3. AuthController 수정

`src/main/java/com/commerce/auth/presentation/http/AuthController.java`를 수정한다.

- import: `...application.service.AuthLoginService` → `...application.usecase.AuthLoginUseCase`
- import: `...application.service.AuthTokenReissueService` → `...application.usecase.AuthTokenReissueUseCase`
- 필드 타입: `AuthLoginService` → `AuthLoginUseCase`
- 필드 타입: `AuthTokenReissueService` → `AuthTokenReissueUseCase`

### 4. 테스트 파일 이동 및 수정

**AuthLoginServiceTest → AuthLoginUseCaseTest**

`src/test/java/com/commerce/auth/application/usecase/AuthLoginUseCaseTest.java`를 새로 생성한다.
- 기존 `AuthLoginServiceTest`의 내용을 옮기되 아래를 변경한다:
  - 패키지: `com.commerce.auth.application.service` → `com.commerce.auth.application.usecase`
  - 클래스명: `AuthLoginServiceTest` → `AuthLoginUseCaseTest`
  - `@InjectMocks` 대상 타입: `AuthLoginService` → `AuthLoginUseCase`
  - import 변경

`src/test/java/com/commerce/auth/application/service/AuthLoginServiceTest.java`를 삭제한다.

**AuthTokenReissueServiceTest → AuthTokenReissueUseCaseTest**

`src/test/java/com/commerce/auth/application/usecase/AuthTokenReissueUseCaseTest.java`를 새로 생성한다.
- 패키지·클래스명·`@InjectMocks` 타입·import를 위와 동일한 방식으로 변경

`src/test/java/com/commerce/auth/application/service/AuthTokenReissueServiceTest.java`를 삭제한다.

**AuthControllerTest**

`src/test/java/com/commerce/auth/presentation/http/AuthControllerTest.java`를 수정한다.
- import: `...service.AuthLoginService` → `...usecase.AuthLoginUseCase`
- import: `...service.AuthTokenReissueService` → `...usecase.AuthTokenReissueUseCase`
- `@MockitoBean` 필드 타입: `AuthLoginService` → `AuthLoginUseCase`
- `@MockitoBean` 필드 타입: `AuthTokenReissueService` → `AuthTokenReissueUseCase`

주의: `@MockBean`이 아닌 `@MockitoBean`이다. Spring Boot 3.4+에서 변경됨.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 커맨드를 실행한다.
2. 아래를 확인한다:
   - `AuthLoginService`, `AuthTokenReissueService` 클래스와 파일이 삭제됐는가
   - `AuthLoginUseCase`, `AuthTokenReissueUseCase`가 `usecase/` 패키지에 있는가
   - `AuthController`의 import·필드 타입이 바뀌었는가
   - `@Component`·`@Transactional` 미선언 여부는 `ArchitectureRulesTest`가 자동 검증한다 (`usecaseClassesShouldEndWithUseCase`, `transactionalOnlyInServicePackage` 규칙)
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `@Transactional(readOnly=true)`를 새 UseCase에 남기지 마라. 이유: `MemberQueryService`가 자체 `@Transactional(readOnly=true)`를 보유하므로 호출된 쪽에서 이미 tx가 관리된다. 상위 tx 경계를 추가로 열 필요가 없고, ArchUnit `transactionalOnlyInServicePackage` 규칙이 `usecase/`의 `@Transactional`을 금지한다.
- `@Service`를 새 UseCase에 남기지 마라. 이유: ADR-054에서 usecase/는 `@Component`로 등록한다.
- 메서드 시그니처와 내부 로직을 변경하지 마라. 이유: 이 step은 구조 재배치이며 동작 변경을 포함하지 않는다.
- 기존 `AuthLoginService`, `AuthTokenReissueService` 파일을 rename 없이 남겨두지 마라. 이유: 동명 빈이 두 개 생겨 스프링 컨텍스트 로딩이 깨진다.
- `docs/adr.md`의 ADR-021 배경 설명에 `AuthLoginService`가 예시로 언급되어 있으나 수정하지 마라. 이유: 채택 당시의 역사적 맥락 기술이며 이번 리팩터의 동기화 대상이 아니다.
