# Step 4: auth-member-domain-logging

## 읽어야 할 파일

- `docs/tasks/core-domain-logging/prd.md`
- `docs/tasks/core-domain-logging/architecture.md`
- `docs/logging-conventions.md` (§5 마스킹 정책 특히 주의)
- `src/main/java/com/commerce/auth/application/AuthLoginService.java`
- `src/main/java/com/commerce/auth/application/AuthSignUpService.java`
- `src/main/java/com/commerce/member/application/MemberRegistrationService.java`
- `src/main/java/com/commerce/auth/application/AuthTokenIssueService.java` — 이미 `@Slf4j` 적용, 메시지 톤 참고

## 작업

### 1. `MemberRegistrationService` — 회원 등록 완료 INFO (도메인 레이어)

파일: `src/main/java/com/commerce/member/application/MemberRegistrationService.java`

- 클래스 상단에 `@Slf4j` 부착
- `register(MemberRegistrationCommand command)`의 `return memberRepository.save(member)`를 분리:
  ```java
  Member savedMember = memberRepository.save(member);
  log.info("회원 등록 완료 memberId={}", savedMember.getId());
  return savedMember;
  ```

### 2. `AuthSignUpService` — 회원 가입 성공 INFO (유스케이스 레이어)

파일: `src/main/java/com/commerce/auth/application/AuthSignUpService.java`

- 클래스 상단에 `@Slf4j` 부착
- `signUp(AuthSignUpCommand command)`의 `return AuthSignUpResult.from(...)` 직전, `authTokenIssueService.issue(member)` 호출 후:
  ```java
  log.info("회원 가입 성공 memberId={}", member.getId());
  return AuthSignUpResult.from(...);
  ```
- 이메일·비밀번호 로그 금지. memberId만 사용.

### 3. `AuthLoginService` — 로그인 성공 INFO

파일: `src/main/java/com/commerce/auth/application/AuthLoginService.java`

- 클래스 상단에 `@Slf4j` 부착
- `login(AuthLoginCommand command)`의 `return AuthLoginResult.from(...)` 직전, `authTokenIssueService.issue(member)` 호출 후:
  ```java
  log.info("로그인 성공 memberId={}", member.getId());
  return AuthLoginResult.from(...);
  ```
- 이메일·비밀번호 로그 금지. 로그인 실패(`INVALID_CREDENTIALS`)는 `GlobalExceptionHandler` 영역.

## 수정 가능 경로

- `src/main/java/com/commerce/auth/application/AuthLoginService.java`
- `src/main/java/com/commerce/auth/application/AuthSignUpService.java`
- `src/main/java/com/commerce/member/application/MemberRegistrationService.java`
- `docs/tasks/core-domain-logging/**`

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드 실행 → 기존 테스트 모두 PASS
2. 3개 파일에 `@Slf4j` 부착 확인
3. INFO 로그 메시지가 사전 시그니처와 정확히 일치 (모두 `memberId={}` 단일 필드)
4. signUp 흐름에서 `회원 등록 완료` (도메인) + `회원 가입 성공` (유스케이스) 두 줄이 순차 출력되는지 확인
5. **민감 정보 마스킹 검증**: 메시지에 `email`, `password`, `accessToken`, `refreshToken` 필드가 절대 포함되지 않았는지 확인
6. `MemberQueryService`, `TokenAuthenticationService`는 손대지 않았는지 확인 (제외 대상)
7. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 이메일·비밀번호·토큰 평문 로그 금지. 이유: 컨벤션 §5 GDPR/PIPA data minimization.
- 로그인 실패 시 이메일 마스킹 로그 추가 금지. 이유: 실패 케이스는 `GlobalExceptionHandler`의 WARN 영역. 본 step은 성공 INFO만.
- `MemberQueryService`, `TokenAuthenticationService`에 `@Slf4j` 부착 금지. 이유: 단순 조회 + 매 요청 호출 → 노이즈 + 컨벤션 §3 정합성 위반.
- 이미 `@Slf4j` 적용된 `AuthTokenIssueService`, `AuthTokenReissueService` 수정 금지. 이유: 본 step 범위 밖.
- 비즈니스 로직 변경 금지.
- 기존 테스트를 깨뜨리지 마라.
