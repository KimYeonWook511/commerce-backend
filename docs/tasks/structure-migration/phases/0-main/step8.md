# Step 8: finalize-archunit-strict

## 읽어야 할 파일

- `/docs/tasks/structure-migration/prd.md`
- `/docs/tasks/structure-migration/adr.md` (ADR-L1 — batch 예외처)
- `/docs/package-structure-guide.md` (7장 ArchUnit)
- `/src/test/java/com/commerce/architecture/ArchitectureRulesTest.java` (편집 대상)
- `/src/test/resources/archunit.properties` (삭제 대상)

## 작업

전 도메인 재배치가 끝나 ArchUnit 위반이 0이 됐으므로, freeze baseline을 떼고 strict로 전환하고 마이그레이션 임시 산출물을 제거한다.

### 1. `ArchitectureRulesTest` freeze 제거 → strict

- `check(ArchRule rule)` 헬퍼를 `FreezingArchRule.freeze(rule).check(productionClasses)`에서 `rule.check(productionClasses)`로 바꾼다.
- `import com.tngtech.archunit.library.freeze.FreezingArchRule;`를 제거한다.
- 클래스 Javadoc의 `BASELINE(freeze)` 문단을 strict 전환 사실에 맞게 갱신한다(freeze로 봐주던 상태가 끝났음을 반영, 기존 규칙 설명은 보존).

### 2. `daoExceptionsConfinedToPersistence`에 batch 예외처 추가 (ADR-L1)

- `daoExceptionsConfinedToPersistence` 규칙에서 `GlobalExceptionHandler` 제외 라인 옆에 batch config 제외를 추가한다:
  ```java
  .and().areNotAssignableTo("com.commerce.order.presentation.batch.OrderExpirationBatchConfig")
  ```
- 근거 주석을 단다: Spring Batch fault-tolerance(`.retry`/`.skip`)는 프레임워크에 예외 타입을 선언적으로 신고하는 경계라 변환 대상이 없으며, `GlobalExceptionHandler`와 같은 부류의 영구 예외처다.
- `OrderExpirationBatchConfig`의 FQN은 Step 3에서 옮긴 뒤 기준(`com.commerce.order.presentation.batch.OrderExpirationBatchConfig`)이다.

### 3. 임시 산출물 제거

- `src/test/resources/archunit.properties`를 삭제한다(`failOnEmptyShould=false` 포함). 삭제 후 `failOnEmptyShould`는 기본값 `true`가 되며, 모든 `.should()` 규칙이 검사 대상 ≥1을 가져야 한다.
- `archunit_store/` 디렉터리를 통째로 삭제한다(`git rm -r archunit_store`).

## Acceptance Criteria

```bash
./gradlew test
```

- `ArchitectureRulesTest`가 strict(freeze 없이)로 위반 0 통과해야 한다. 이게 마이그레이션 종료의 증거다.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `ArchitectureRulesTest`에 `FreezingArchRule` 참조가 남아 있지 않은가?
   - `archunit.properties`·`archunit_store/`가 제거됐는가? (`ls archunit_store` 실패, `git status`에 삭제 반영)
   - `failOnEmptyShould=true` 기본값에서 `transactionalOnlyInServicePackage`가 통과하는가?(Step 7에서 `application.usecase`가 채워져 검사 대상 ≥1)
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 규칙 자체의 의미를 바꾸지 마라(예외처 추가 외). 이유: 이 step은 freeze 해제와 batch 예외처(ADR-L1)만 반영한다.
- batch 예외처를 패키지(`..presentation.batch..`) 전체로 넓히지 마라. 이유: ADR-L1대로 `OrderExpirationBatchConfig` 한 클래스로 좁게 잡아 다른 DAO 예외 누수는 계속 차단한다.
- 위반을 통과시키려고 production 코드를 수정하지 마라. 이유: 위반이 남았다면 앞 step의 이동 누락 신호이므로 멈추고 보고한다.
- 기존 테스트를 깨뜨리지 마라.
