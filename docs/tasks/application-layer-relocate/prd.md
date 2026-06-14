# PRD: application 계층 역할별 접미사·트랜잭션 선언 정리

## 배경

`structure-migration`(PR #246/#247)에서 전 도메인 application 계층을 `@Transactional` 보유 여부로 `usecase/`·`service/` 패키지로 물리 이동했다(순수 이동, 리네임 없음). 그 결과:

- `usecase/` 패키지에 `…Service` 이름 클래스 8개가 남아 **패키지(역할)와 클래스명이 어긋난다**.
- `service/`에 `…Processor` 3개가 남아 역할(tx 단위작업)과 이름이 어긋난다.
- 기존 도메인은 class-level `@Transactional(readOnly = true)` + method override 패턴이 광범위하게 남아 있다. ADR-021은 신설 Service·cart에만 적용됐고 **기존 도메인은 후속 트랙으로 분리**됐다.
- `NOT_SUPPORTED`로 tx를 열지 않는 orchestrator 일부가 애너테이션이 있다는 이유만으로 `service/`에 남아 있다(AuthSignUp/OrderCreate/StockRestoreOutboxRelay).

## 목표

1. **역할별 접미사 이원화**: `usecase/`는 `…UseCase`, `service/`는 `…Service`. 빈 애너테이션도 역할별로 — UseCase=`@Component`, Service=`@Service`. (ADR-006 supersede)
2. **class-level `@Transactional` 전 도메인 제거** → method-level만. (ADR-021 적용 범위 확장)
3. **NOT_SUPPORTED orchestrator를 usecase로 분류**. 분류 기준을 "`@Transactional` 애너테이션 유무"가 아니라 "**실제 tx를 여는가**"로 정제한다.

## 범위 (In)

- 클래스·테스트 리네임, 패키지 이동, 빈 애너테이션 변경
- class-level `@Transactional` 제거 + 조회 메서드 method-level `@Transactional(readOnly = true)` 명시
- NOT_SUPPORTED orchestrator 3개(AuthSignUp/OrderCreate/StockRestoreOutboxRelay) → `usecase/` 이동 + `@Transactional` 제거
- `OutboxService`(조율 없는 단순 pass-through) 제거 — 호출처(`OrderExpirationService`)가 `StockRestoreOutboxCreateService` 직접 호출
- ArchUnit 규칙 추가: 네이밍(usecase=…UseCase, service=…Service), application class-level `@Transactional` 금지

> 클래스명 **접미사만** 바꾼다(`…Service`→`…UseCase`). 어순(`{도메인}{행위}` 등)은 그대로 두며, 어순 통일은 별도 PR로 분리한다.

## 비목표 (Out)

- API 계약·DB 스키마 변경 없음
- 비즈니스 로직 변경 없음(외부 동작 보존)
- skip/retry helper 구조 변경 없음 (private 메서드 / support helper 정책 유지)

## 동작 보존 원칙

모든 변경은 외부 동작 불변(refactor)이다. NOT_SUPPORTED 제거는 호출처가 진입점(Controller/Scheduler)뿐이라 바깥 tx가 없어 "tx 없이 실행"되는 현 동작과 동일함을 확인했다. 각 step은 `./gradlew test` + `integrationTest`(필요 시 `batchTest`)로 검증한다.
