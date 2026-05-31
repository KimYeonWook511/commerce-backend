# Step 2: tighten-schema-validation-and-test-tag-isolation

## 읽어야 할 파일

- `/docs/tasks/payment-attempt-unique-key-length/prd.md`
- `/docs/tasks/payment-attempt-unique-key-length/adr.md`
- `/src/main/resources/application-test.yml`
- `/src/main/resources/application-local.yml`
- `/src/main/resources/application-prod.yml` (변경 대상 아님, 정합 참조용)
- `/build.gradle`
- 이전 step 산출물: `PaymentAttempt` entity 변경분.

태스크 문서만으로 부족한 공통 맥락이 있으면 아래 문서를 추가로 읽는다.

- `/docs/testing-conventions.md`

## 작업

두 가지 안전망 보강을 한 step에 묶는다.

### (a) `hibernate.hbm2ddl.halt_on_error` 적용

`application-test.yml`, `application-local.yml`의 `spring.jpa.properties.hibernate` 트리 아래에 다음을 추가한다.

```yaml
spring:
  jpa:
    properties:
      hibernate:
        hbm2ddl:
          halt_on_error: true
```

이미 같은 트리 안에 다른 키(`format_sql` 등)가 있는 곳에 자연스럽게 합쳐 넣는다. yml의 indent 구조를 깨지 않는다.

`application-prod.yml`에는 추가하지 않는다 (운영 미가동 + 추후 Flyway 도입과 함께 처리).

### (b) `dockerTest`에서 concurrency tag 제외

`build.gradle`의 `tasks.register('dockerTest', Test)` 블록 내부 `useJUnitPlatform { includeTags "docker" }`를 다음과 같이 수정한다.

```groovy
useJUnitPlatform {
    includeTags "docker"
    excludeTags "concurrency"
}
```

`@Tag` 자체 재설계는 본 task의 scope를 넘으며 이슈 #177에서 다룬다.

## Acceptance Criteria

```bash
./gradlew test
./gradlew dockerTest
./gradlew concurrencyTest
```

세 명령 모두 통과해야 하고, 동일 테스트 클래스(`NaverPayServiceConcurrencyTest`)가 `dockerTest`와 `concurrencyTest` 양쪽에서 중복 실행되지 않아야 한다.

## 검증 절차

1. 위 세 명령을 순서대로 실행한다.
2. 아래를 확인한다.
   - `application-test.yml`, `application-local.yml`에 `halt_on_error: true`가 추가되었는가?
   - `application-prod.yml`에는 추가되지 않았는가?
   - `dockerTest`의 실행 클래스 목록에서 `NaverPayServiceConcurrencyTest`가 제외되었는가? (`build/test-results/dockerTest/` 결과 파일로 확인)
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- prod yml에 `halt_on_error`를 추가하지 마라. 이유: 운영 미가동 + 추후 Flyway 도입 시 ddl-auto: validate로 가면 의미가 사라지므로 본 task에서는 적용 영역을 좁게 둔다.
- tag 자체를 재설계하지 마라 (다른 클래스의 @Tag 변경 금지). 이유: 이슈 #177에서 다룰 작업이다.
- 다른 task 정의(`test`, `naverPaySandboxTest`, `concurrencyTest`, `ciTest`)의 `includeTags`/`excludeTags`를 변경하지 마라. 이유: 본 task는 `dockerTest`의 disjoint 처리에 한정된다.
- application yml의 다른 키(format_sql, batch 설정 등)를 변경하지 마라. 이유: 본 task scope 밖.
