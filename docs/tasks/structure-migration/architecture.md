# Task 아키텍처

> 이 문서는 이번 Task 시점의 **변경 제안 스냅샷**이다.
> 시스템의 현재 진실은 루트 `docs/architecture.md`이며, Stage 8(Root Sync)에서 그쪽을 현재 상태로 갱신한다(이번에 안 바뀐 부분은 보존).

---

## 개요

- 레이어(`presentation → application → domain ← infrastructure`)와 의존 방향은 그대로 두고, 각 레이어 **내부를 목표 서브패키지로 세분화**한다.
- 정본 기준은 `docs/package-structure-guide.md`다. 이 문서는 그 기준을 이번 코드베이스에 적용한 매핑 스냅샷이다.

## 변경 대상

- 전 도메인(`auth`, `cart`, `member`, `order`, `outbox`, `outbox/stock`, `payment`, `payment/naverpay`, `product`, `stock`)의 `application` / `infrastructure` / `presentation` / `domain` 하위 패키지.

## 설계 방향

목표 구조(도메인별):

```
<domain>/
├── presentation/
│   ├── http/         Controller + request/
│   ├── scheduler/    @Scheduled
│   ├── batch/        Spring Batch Job/Step 정의 + listener
│   └── consumer/     @KafkaListener
├── application/
│   ├── usecase/      tx 없는 orchestrator (@Transactional 전혀 없음)
│   ├── service/      tx 단위작업 (@Transactional 보유)
│   ├── port/ command/ result/ dto/   (현 위치 유지)
├── domain/
│   ├── <entity>/ repository/ policy/
│   └── exception/    도메인 예외
└── infrastructure/
    ├── persistence/  JPA adapter + Jpa repo (saveAndFlush·예외변환·락)
    ├── cache/        Redis 구현
    ├── messaging/    Kafka producer·container config·relay message
    ├── pg/           PG 연동 (naverpay)
    └── notification/ NotificationPort 구현
```

분류 기준:
- **application**: `@Transactional`(클래스/메서드 어디든) 보유 → `service/`, 전혀 없는 orchestrator → `usecase/`. 기계적 기준이라 순수 이동이다. NOT_SUPPORTED로 class-level readOnly를 override하는 orchestrator(`OrderCreateService`, `AuthSignUpService`, `StockRestoreOutboxRelayService`)도 `@Transactional` annotation을 보유하므로 `service/`에 둔다. orchestrator/tx 분할(B)은 이번 범위 밖.
- **infrastructure**: 외부 대상별로 가른다. JPA adapter는 전부 `persistence/`(saveAndFlush·DAO 예외가 여기 모인다).
- **presentation**: 진입 방식별. inbound adapter는 얇게 위임만.
- **예외**: 도메인 예외(`XxxException`/`XxxErrorCode`)는 `domain/exception/`, 인프라 기술 예외(`...StoreUnavailableException`)는 `infrastructure/`, 도메인 advice(`AuthExceptionHandler`)는 `presentation/`.

## 데이터 흐름

- **변경 없음.** 호출 그래프·트랜잭션 경계·예외 전파 경로는 그대로다. 패키지 위치와 import만 바뀐다.

## 예외 및 실패 처리

- Spring Batch fault-tolerance 설정(`OrderExpirationBatchConfig`의 `.retry(OptimisticLockingFailureException.class)`/`.skip(...)`)은 프레임워크에 예외 타입을 선언적으로 신고하는 경계라 변환 대상이 없다. `daoExceptionsConfinedToPersistence` 규칙에서 `GlobalExceptionHandler`와 동일하게 예외처로 인정한다(ADR-L1).

## 테스트 포인트

- 각 이동 step마다 `./gradlew test`(+영향 시 `integrationTest`/`batchTest`)로 동작 불변을 검증한다.
- 마지막 step에서 ArchUnit strict 위반 0을 검증한다(`ArchitectureRulesTest`).
- Spring context가 정상 부팅되는지(빈 와이어링) `integrationTest`로 확인한다.
