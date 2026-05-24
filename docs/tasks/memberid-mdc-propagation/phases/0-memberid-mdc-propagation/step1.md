# Step 1: register-jwt-auth-filter-explicitly

## 읽어야 할 파일

먼저 아래 파일들을 읽고 설계 의도와 기존 패턴을 파악하라:

- `docs/tasks/memberid-mdc-propagation/prd.md`
- `docs/tasks/memberid-mdc-propagation/architecture.md`
- `docs/tasks/memberid-mdc-propagation/adr.md` — 특히 결정 5, 6
- `src/main/java/com/commerce/security/filter/JwtAuthenticationFilter.java` — 수정 대상
- `src/main/java/com/commerce/common/log/filter/TraceIdFilterConfig.java` — 따라야 할 패턴
- `src/main/java/com/commerce/common/log/filter/AccessLogFilterConfig.java` — 따라야 할 패턴
- `src/test/java/com/commerce/security/SecurityWebMvcTest.java` — `@Import` 변경 대상

## 작업

### 1. `JwtAuthenticationFilter`에서 `@Component` 제거

파일: `src/main/java/com/commerce/security/filter/JwtAuthenticationFilter.java`

- 클래스 선언 위의 `@Component` 어노테이션 제거
- `import org.springframework.stereotype.Component;` import도 함께 제거
- `@RequiredArgsConstructor`는 유지 (생성자 주입 위함)
- 그 외 로직(인증 흐름, 예외 처리, finally 정리)은 무변경

### 2. `JwtAuthenticationFilterConfig` 신규 작성

파일: `src/main/java/com/commerce/security/filter/JwtAuthenticationFilterConfig.java`

`TraceIdFilterConfig`/`AccessLogFilterConfig` 패턴을 그대로 따른다. 단, JwtAuthenticationFilter는 의존성(`TokenAuthenticationService`, `ObjectMapper`)이 있으므로 `@Bean` 메서드 파라미터로 주입받아 인스턴스화한다.

```java
@Configuration
public class JwtAuthenticationFilterConfig {

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilter(
        TokenAuthenticationService tokenAuthenticationService,
        ObjectMapper objectMapper
    ) {
        FilterRegistrationBean<JwtAuthenticationFilter> bean =
            new FilterRegistrationBean<>(new JwtAuthenticationFilter(tokenAuthenticationService, objectMapper));
        bean.addUrlPatterns("/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        return bean;
    }
}
```

규칙:
- `bean.addUrlPatterns("/*")` — 모든 경로 (기존 `@Component` 자동 등록과 동일 범위)
- `bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 30)` — TraceId(+10), AccessLog(+20) 다음 위치 명시
- import: `org.springframework.boot.web.servlet.FilterRegistrationBean`, `org.springframework.context.annotation.Bean`, `org.springframework.context.annotation.Configuration`, `org.springframework.core.Ordered`, `com.commerce.auth.application.TokenAuthenticationService`, `com.fasterxml.jackson.databind.ObjectMapper`

### 3. `SecurityWebMvcTest`의 `@Import` 변경

파일: `src/test/java/com/commerce/security/SecurityWebMvcTest.java`

- `@Import` 배열에서 `JwtAuthenticationFilter.class`를 `JwtAuthenticationFilterConfig.class`로 교체
- 관련 import 추가: `import com.commerce.security.filter.JwtAuthenticationFilterConfig;`
- 기존 import 정리: `import com.commerce.security.filter.JwtAuthenticationFilter;`는 다른 사용처가 없으면 제거
- 기존 시나리오 시그니처/내용 변경 없음 — 회귀 검증 목적

## 수정 가능 경로

- `src/main/java/com/commerce/security/filter/JwtAuthenticationFilter.java`
- `src/main/java/com/commerce/security/filter/JwtAuthenticationFilterConfig.java` (신규)
- `src/test/java/com/commerce/security/SecurityWebMvcTest.java`
- `docs/tasks/memberid-mdc-propagation/**` (task 문서, 필요 시)

## Acceptance Criteria

```bash
./gradlew test
```

이 step은 인증/권한 경계 변경에 해당하므로 전체 테스트(`./gradlew test`) 실행이 필수.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. `SecurityWebMvcTest` 전체 시나리오 통과 확인 (회귀 없음).
3. 등록 정책이 명시적 order로 통일되었는지 확인:
   - `TraceIdFilter` order = `HIGHEST_PRECEDENCE + 10`
   - `AccessLogFilter` order = `HIGHEST_PRECEDENCE + 20`
   - `JwtAuthenticationFilter` order = `HIGHEST_PRECEDENCE + 30`
4. 다른 곳에서 `JwtAuthenticationFilter`를 Bean으로 직접 주입받는 코드가 없는지 확인:
   ```bash
   rg "JwtAuthenticationFilter" src/main src/test
   ```
   예상 출력: `JwtAuthenticationFilter.java`(선언), `JwtAuthenticationFilterConfig.java`(import 및 인스턴스화), `SecurityWebMvcTest.java`(없거나 import 제거됨). 다른 위치에서 `@Autowired private JwtAuthenticationFilter ...` 같은 사용처가 있으면 그 사용처도 정리.
5. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 인증 로직 변경 금지. 이유: 이 step은 등록 방식 refactor만 다룬다. 인증/예외 처리 변경은 Step 2의 책임.
- `MDC.put` 또는 `request.setAttribute` 추가 금지. 이유: 동일 — Step 2 책임.
- `@Component` 제거 외에 다른 어노테이션 변경 금지 (`@RequiredArgsConstructor` 유지).
- 기존 테스트를 깨뜨리지 마라.
