# Step 2: tighten-schema-validation-and-test-tag-isolation

## 읽어야 할 파일

- `/docs/tasks/payment-attempt-unique-key-length/prd.md`
- `/docs/tasks/payment-attempt-unique-key-length/adr.md`
- `/src/main/resources/application-test.yml` (변경 대상 아님, 정합 참조용)
- `/src/main/resources/application-local.yml`
- `/src/main/resources/application-prod.yml` (변경 대상 아님, 정합 참조용)
- `/build.gradle`
- 이전 step 산출물: `PaymentAttempt` entity 변경분.

태스크 문서만으로 부족한 공통 맥락이 있으면 아래 문서를 추가로 읽는다.

- `/docs/testing-conventions.md`

## 작업

두 가지 안전망 보강을 한 step에 묶는다.

### (a) `hibernate.hbm2ddl.halt_on_error`를 local에만 적용

`application-local.yml`의 `spring.jpa.properties.hibernate` 트리 아래에 다음을 추가한다.

```yaml
spring:
  jpa:
    properties:
      hibernate:
        hbm2ddl:
          halt_on_error: true
```

이미 같은 트리 안에 다른 키(`format_sql` 등)가 있는 곳에 자연스럽게 합쳐 넣는다. yml의 indent 구조를 깨지 않는다.

`application-test.yml`과 `application-prod.yml`에는 **추가하지 않는다**.

**이유 (적용 환경을 local로 좁힌 근거)**:

- `application-test.yml`은 Testcontainer MySQL을 띄우는 dockerTest 환경에서 `ddl-auto: create-drop`이 적용된다. Hibernate가 부팅 시 schema drop 단계로 `ALTER TABLE ... DROP FOREIGN KEY ...`를 실행하는데, MySQL은 이 구문에 `IF EXISTS`를 지원하지 않아 신선한 컨테이너에서 `Table doesn't exist`로 실패한다. `halt_on_error: true`가 이 진짜 무해한 drop 실패까지 잡아 Spring 컨텍스트 로드를 실패시킨다.
- `application-local.yml`은 `ddl-auto: update`라 부팅 시 drop을 수행하지 않으므로 위 충돌이 발생하지 않는다.
- `application-prod.yml`은 추후 Flyway 도입과 함께 `ddl-auto: validate`로 전환하면서 `halt_on_error`의 적용 영역이 자연스럽게 사라진다.

**Fragility 인지**: local의 `ddl-auto`가 미래에 `create-drop`/`create`로 변경되면 같은 ALTER FK DROP 충돌이 재발한다. ddl-auto 변경 시 `halt_on_error` 적용 여부를 함께 재검토해야 한다. 본 task의 ADR에 이 fragile dependency를 명시한다.

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
   - `application-local.yml`에 `halt_on_error: true`가 추가되었는가?
   - `application-test.yml`과 `application-prod.yml`에는 추가되지 않았는가?
   - `dockerTest`의 실행 클래스 목록에서 `NaverPayServiceConcurrencyTest`가 제외되었는가? (`build/test-results/dockerTest/` 결과 파일로 확인)
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `application-test.yml`에 `halt_on_error`를 추가하지 마라. 이유: Testcontainer 환경의 ALTER FK DROP 무해 실패와 충돌해 dockerTest 부팅이 실패한다.
- `application-prod.yml`에 `halt_on_error`를 추가하지 마라. 이유: 운영 미가동 + 추후 Flyway 도입 시 `ddl-auto: validate`로 가면 의미가 사라지므로 본 task에서는 적용 영역을 좁게 둔다.
- tag 자체를 재설계하지 마라 (다른 클래스의 @Tag 변경 금지). 이유: 이슈 #177에서 다룰 작업이다.
- 다른 task 정의(`test`, `naverPaySandboxTest`, `concurrencyTest`, `ciTest`)의 `includeTags`/`excludeTags`를 변경하지 마라. 이유: 본 task는 `dockerTest`의 disjoint 처리에 한정된다.
- application yml의 다른 키(format_sql, batch 설정 등)를 변경하지 마라. 이유: 본 task scope 밖.
- `TestcontainersSupport.registerMySql()`의 `ddl-auto` 설정을 변경하지 마라. 이유: ALTER FK DROP 충돌 회피를 위해 ddl-auto를 바꾸는 우회로는 Hibernate가 `create`에서도 drop+create 순서로 진행해 실효성이 의문이고, lazy singleton 컨테이너를 공유하는 multi-context 환경에서 CREATE TABLE 충돌 위험이 있다.
