# Task PRD

> 이 문서는 이 기능의 **의도를 담은 정본**이며, 작업 종료 후 동결한다.
> 이후 다른 Task에서 이 결정이 바뀌면 이 문서를 거슬러 수정하지 않고,
> 아래 상태/포인터 한 줄만 추가한다. 본문은 당시 기록으로 보존한다.

- 상태: active
- 변경 포인터: 없음

---

## Task명

- `structure-migration`

## 배경

- PR #246에서 헥사고날(포트앤어댑터) 목표 구조를 정의한 설계 문서 6종(`package-structure-guide.md` 등)과 `ArchitectureRulesTest`(ArchUnit)를 도입했다.
- 현재 코드는 아직 평평한 구조(adapter가 `infrastructure` 루트에, `@Scheduled`/`@KafkaListener`가 도메인 곳곳에, application이 평평한 `Service` 묶음)라, ArchUnit 4개 규칙이 `FreezingArchRule` baseline(`archunit_store/` 스냅샷)으로 "현재 위반은 통과, 새 위반만 차단" 상태다.
- 이 task는 그 목표 구조로 **전 도메인을 재배치**해 ArchUnit 위반을 0으로 만들고 freeze를 떼어 strict로 전환한다.

## 목표

- 새 설계 문서가 정의한 목표 패키지 구조로 전 도메인을 재배치한다.
- **순수 이동·동작 불변**: 클래스 리네임 없음, 로직 변경 없음, 호출 구조 불변.
- ArchUnit을 strict로 전환하고 마이그레이션 임시 산출물(freeze 래퍼, `archunit.properties`, `archunit_store/`)을 제거한다.

## 범위

포함 범위
- infrastructure 세분화: JPA adapter→`persistence/`, Redis→`cache/`, Kafka producer·config·relay msg→`messaging/`, NotificationPort 구현→`notification/`, naverpay PG→`pg/`.
- presentation 세분화: Controller→`http/`, `@Scheduled`→`scheduler/`, Spring Batch→`batch/`, `@KafkaListener`→`consumer/`.
- application 분리: `@Transactional` 보유 여부로 `usecase/`(tx 없는 orchestrator) / `service/`(tx 단위작업)로 가른다.
- 예외 재배치: 도메인 예외→`domain/exception/`, 인프라 기술 예외→`infrastructure/`, 도메인 advice→`presentation/`.
- ArchUnit 마감: freeze 제거 + strict 전환 + 임시 산출물 삭제.

제외 범위
- **클래스 리네임**(예: `Processor`→`Service`). ADR-006(Service suffix 유지)과 별개의 cleanup이며 이름 충돌이 얽혀 순수 이동 성격을 깨므로 제외한다.
- **로직 변경(B)**: 혼합 클래스의 orchestrator/tx 분할, Order 낙관 락 도메인 예외 변환 등은 별도 PR.
- `payment/postprocess/`, `payment/provider/`, 최상위 `common/`·`security/`: 마이그레이션 대상 아님 + ArchUnit 위반 없음 → 현 위치 유지.

## 주요 시나리오

- 개발자가 `./gradlew test`로 `ArchitectureRulesTest`를 돌리면 strict 모드에서 위반 0으로 통과한다.
- 기존 단위/통합/배치 테스트가 그대로 통과해 동작 불변을 증명한다.

## 요구사항

- 모든 이동은 `git mv`로 수행해 히스토리를 보존하고, main·test 양쪽의 package 선언과 import를 갱신한다.
- application 분류 기준은 기계적이다: `@Transactional`(클래스/메서드 어디든) 있으면 `service/`, 전혀 없으면 `usecase/`.
- `@Transactional`은 `service/`에만 존재한다(usecase·presentation에 없음).
- Spring Batch fault-tolerance 설정(`.retry/.skip`)의 Spring DAO 예외 참조는 ArchUnit 규칙 예외처로 인정한다(ADR-L1).

## 제약사항

- (A)순수 이동과 (B)로직 변경을 한 PR에 섞지 않는다.
- ADR-006: application 클래스명은 `Service` suffix 유지(`UseCase`로 리네임하지 않음). 역할은 패키지(`usecase`/`service`)로 표현한다.
- 완료 기준: ArchUnit strict 위반 0 · freeze·`archunit.properties`·`archunit_store/` 제거 · 기존 테스트 그대로 통과.
