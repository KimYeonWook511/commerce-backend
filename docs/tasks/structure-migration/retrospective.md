# 회고록: structure-migration

## 1. 작업 요약

PR #246에서 정의한 헥사고날 목표 패키지 구조로 전 도메인을 재배치했다. **순수 이동·동작 불변**(클래스 리네임 없음, 로직 변경 없음, 호출 구조 불변)으로, 재배치 후 ArchUnit 위반이 0이 되어 freeze baseline을 떼고 strict로 전환했다.

8개 step(단일 phase `0-main`):

- **step1** `move-persistence-adapters`: 전 도메인 JPA adapter·Jpa repo → `infrastructure/persistence/`.
- **step2** `move-cache-messaging-notification`: Redis → `cache/`, Kafka producer·config·relay msg → `messaging/`, `NotificationPort` 구현 → `notification/`.
- **step3** `relocate-inbound-adapters`: `@Scheduled` → `presentation/scheduler/`, `@KafkaListener` → `presentation/consumer/`, Spring Batch → `presentation/batch/`.
- **step4** `move-controllers-to-http`: Controller·request → `presentation/http/`.
- **step5** `relocate-exceptions`: 도메인 예외 → `domain/exception/`, 인프라 기술 예외 → `infrastructure/`, advice → `presentation/`.
- **step6** `move-naverpay-pg`: NaverPay infrastructure 전체 → `infrastructure/pg/`.
- **step7** `split-application-usecase-service`: `@Transactional` 보유 여부로 `usecase/`(tx 없음) / `service/`(tx 보유) 분류.
- **step8** `finalize-archunit-strict`: freeze 래퍼 제거, batch 예외처(ADR-053) 추가, `archunit.properties`·`archunit_store/` 삭제.

목적: PR #246의 "ArchUnit 도입은 별도 후속으로 분리한다"의 종착. 최종 상태에서 `test + integrationTest + batchTest` 전부 통과로 동작 불변을 증명했다.

---

## 2. 결정한 정책 (ADR-053)

- **ADR-053**: Spring Batch fault-tolerance(`OrderExpirationBatchConfig`의 `.retry/.skip`)의 DAO 예외 참조를 `daoExceptionsConfinedToPersistence`·`controllersDoNotCatchConflictExceptions` 두 규칙에서 `GlobalExceptionHandler`처럼 명시적 예외처로 인정한다. 예외 범위는 한 클래스로 좁게 잡는다.

그 외 모든 분류는 기존 결정(ADR-006 Service suffix 유지, ADR-008 NOT_SUPPORTED tx 분리)과 `docs/package-structure-guide.md`를 그대로 적용한 기계적 이동이라 새 결정이 없다.

---

## 3. 주요 발견 및 논의

### batch DAO 예외는 "변환"이 아니라 "예외처"다

`.retry(OptimisticLockingFailureException.class)`를 도메인 예외로 바꾸자는 본능은 옳아 보이지만 적용 불가다. 변환은 **예외를 직접 catch하는 위치**(예: adapter `saveChecked`)에서만 가능한데, `.retry(...)`는 catch가 아니라 프레임워크에 "이 타입이 나면 재시도해라"라고 **선언적으로 신고**하는 코드다. 충돌은 batch 내부 작업의 flush에서 터지고 그걸 잡는 것도 Spring Batch라 변환할 예외가 손에 없다. `GlobalExceptionHandler`와 같은 부류(프레임워크 경계에 예외 타입을 넘기는 곳)라, 영구 예외처로 인정하는 게 맞다. 도메인 예외 변환 후속 작업은 불필요하다.

### batch가 presentation으로 가면서 규칙 **두 개**에 걸렸다

step8 reviewer가, batch가 `presentation/batch/`로 이동하면서 `daoExceptionsConfinedToPersistence`뿐 아니라 `controllersDoNotCatchConflictExceptions`(presentation은 낙관 락 예외 의존 금지)에도 걸린다는 걸 잡았다. step 설계 문서는 전자만 짚었으나, 예외처를 두 규칙 모두에 넣어야 strict가 통과한다.

### `failOnEmptyShould` 때문에 usecase 패키지를 반드시 채워야 했다

`archunit.properties`(`failOnEmptyShould=false`)를 제거하면 기본값이 `true`가 되어, `transactionalOnlyInServicePackage`(`application.usecase`에 `@Transactional` 금지) 규칙이 검사 대상 ≥1을 요구한다. `@Transactional`이 전혀 없는 순수 orchestrator 8개(`PaymentReconciliationService` 등)가 usecase를 채워 이 조건을 자연히 만족했다.

### (A)순수 이동과 (B)로직 변경의 경계

application 분류는 "`@Transactional` 보유 여부"라는 기계적 기준으로 순수 이동이 가능했다. `OrderCancelService`처럼 tx를 열면서 다른 service를 호출하는 "혼합" 클래스도, 호출 구조를 건드리지 않고 `@Transactional` 보유 → `service/`로 그대로 뒀다. class-level readOnly + 메서드 NOT_SUPPORTED orchestrator(`OrderCreateService` 등)도 annotation을 보유하므로 `service/`에 뒀다 — usecase로 옮기려면 annotation 제거(=B)가 필요해 이번 범위 밖이다. 이상적 분할(orchestrator/tx)은 별도 (B) PR로 미뤘다.

### 리네임은 안 했고, 그게 옳았다

`AddCartItemProcessor`(tx)를 `service/`로 옮길 때 ADR-006상 `Service` suffix로 리네임하는 게 "이상적"이나, 같은 도메인의 기존 `AddCartItemService`(orchestrator)와 충돌한다. 리네임은 순수 이동이 아니므로 보류했다. 사용자가 후속으로 `{행위}{도메인}UseCase/Service` 컨벤션 일괄 리네임을 계획 중이며, 이는 ADR-006을 supersede하므로 별도 PR(문서 수정 포함)로 진행한다. 이번 PR이 usecase/service 폴더로 정렬해 둔 덕에 그 리네임이 더 쉬워졌다.

### 세션 사용량 한도로 중단 → 복구

step5(relocate-exceptions)의 developer+AC+reviewer는 통과했으나 **commit agent가 세션 사용량 한도**(당시 5:40pm 리셋)에 걸려 커밋을 못 만들었고, 이어 step6도 같은 한도로 status 갱신 없이 죽어 error로 끝났다. 복구는 토큰 부족이 아니라 한도였음을 `commit_agent.log`로 확인한 뒤: (1) 미커밋 step5 산출물(rename 20 + import 갱신 100)을 컴파일 검증 → `refactor:` 커밋으로 수동 등록, (2) step6를 `pending` 리셋, (3) step6부터 `execute.py` 재개. 부분 잔재(pg/·usecase/·service/ 경로)가 없음을 먼저 확인해 step5 산출물만 깨끗이 커밋할 수 있었다.

### Gemini review: 이동이 남긴 same-package import

PR review 4건은 모두 "패키지 이동 후 같은 패키지로 옮겨진 클래스를 여전히 import"하는 redundant self-import였다. 3건은 실재해 제거(accept), 1건은 diff 기준 지적이나 현재 파일엔 이미 없어 reject. developer agent가 import를 갱신하며 same-package import는 지우지 못하고 남긴 패턴이다.

---

## 4. 변경 범위 정리

- **infrastructure**: `persistence/`(JPA adapter·repo), `cache/`(Redis), `messaging/`(Kafka producer·config·relay), `notification/`, `pg/`(NaverPay) 분리.
- **presentation**: `http/`(Controller·request), `scheduler/`(@Scheduled), `batch/`(Spring Batch), `consumer/`(@KafkaListener) 분리.
- **application**: `usecase/`(tx 없는 orchestrator) / `service/`(@Transactional) 분류. command/port/result/dto는 현 위치 유지.
- **domain**: 도메인 예외 `domain/exception/`로. auth·naverpay는 domain 패키지 신설.
- **ArchUnit**: freeze → strict, batch 예외처 2규칙, 임시 산출물 삭제.
- 범위 제외: 클래스 리네임, `payment/postprocess/`·`payment/provider/`, 최상위 `common/`·`security/`.

---

## 5. 미결 과제

- **application 클래스 리네임 PR**: `{행위}{도메인}UseCase`/`Service` 컨벤션 일괄 적용 + `Processor`→적절한 suffix. ADR-006 supersede + `package-structure-guide.md` 네이밍 문장 갱신 필요. 별도 PR.
- **혼합 클래스 orchestrator/tx 분할(B)**: tx를 열면서 다른 service를 호출하는 클래스(`OrderCancelService`/`OrderExpirationService` 등)의 분할은 로직 변경이라 별도 PR로 판단 필요.

---

## 6. 회고

### 잘된 점

- step을 "이동 종류"(persistence/inbound/http/exception/pg/application-split) 단위로 갈라, 각 step이 독립 컴파일·테스트로 검증되고 커밋도 목적별로 1:1 분리됐다.
- "`@Transactional` 보유 여부"라는 기계적 분류 기준을 미리 합의해, application 분리가 판단 없이 순수 이동으로 떨어졌다.
- batch DAO 예외를 "변환 vs 예외처"로 끝까지 따져 ADR-053으로 근거를 남겼다 — 비슷한 선언적 프레임워크 경계가 또 나와도 재사용 가능한 원칙이 됐다.
- 세션 한도 중단을 토큰 문제로 단정하지 않고 로그로 원인을 특정한 뒤, 검증된 step만 수동 커밋하고 부분 잔재 없음을 확인해 깨끗이 재개했다.

### 개선할 점

- step 설계 문서가 `daoExceptionsConfinedToPersistence`만 짚고 `controllersDoNotCatchConflictExceptions`는 놓쳤다. batch가 presentation으로 이동한다는 사실에서 두 규칙이 모두 영향받음을 미리 예측했어야 했다.
- developer agent가 same-package import를 지우지 못해 Gemini review 거리를 4건 남겼다. step 지시에 "이동 후 같은 패키지가 된 import는 제거" 항목을 명시했으면 사전에 막혔다.
- step6(naverpay 서브트리 대량 이동)이 한 step에 파일이 많아 세션 한도와 겹쳤다. 대량 이동 step은 더 잘게 나누는 편이 중단 시 복구 비용이 작다.
