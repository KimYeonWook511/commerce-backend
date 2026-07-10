# 패키지 배치 기준

> 4계층 레이어드 구조(`presentation`·`application`·`domain`·`infrastructure`)에서 각 요소를 **어느 레이어·어느 패키지에 두는가**의 기준 정리. 계층 간 경계는 port/adapter로 다스린다 — 나가는 인터페이스(port)는 그것을 필요로 하는 안쪽 레이어에, 그 구현(adapter)은 기술에 의존하는 바깥 레이어에 둔다.
> 기준 스택: Java 21, Spring Boot 3.x, JPA, MySQL.
> 레이어 의존 방향: `presentation → application → domain ← infrastructure`. 모든 의존은 안쪽 domain을 향한다.

---

## 핵심 원칙 (요약)

- **port는 그것을 필요로 하는 안쪽 레이어에, 구현체는 기술에 의존하는 바깥 레이어에 둔다.** port와 구현은 항상 다른 레이어로 가른다.
- **유스케이스를 깨우는 모든 진입점(HTTP·cron·batch·message)은 presentation의 inbound adapter이고, 얇게 위임만 한다.**
- 레이어별로 **나누는 축이 다르다**: application=**책임**(tx/정책/조립), domain=**엔티티**, infrastructure=**외부 대상**(DB/PG/cache/messaging), presentation=**진입 방식**.
- **처음부터 잘게 나누지 않는다.** 단순 CRUD 도메인(product/cart 등)은 평평하게 두고, 책임·경계가 실제로 공존하는 도메인(payment 등)만 분리한다. 분리는 맥락이 달라지는 시점에 한다.
- **`@Transactional`은 `application/service/`에만 단다.** `usecase/`(orchestrator)는 tx를 열지 않고 흐름 조립·정책 선택만 한다 → self-invocation 함정을 구조로 차단한다.
- 여러 tx 단위작업을 한 tx로 묶어야 하면, usecase에 tx를 달지 말고 **`service/`에 전용 메서드를 만들어 거기에만 tx를 단다.**
- 충돌 반응(skip/retry)은 **한 곳에서만 쓰면 그 클래스의 private 메서드**, 여러 곳이 공유할 때만 별도 helper로 추출한다. 정식 `policy/` 레이어는 만들지 않는다.
- **클래스 접미사가 패키지 역할과 일치한다**: `usecase/`→`…UseCase`(`@Component`), `service/`→`…Service`(`@Service`). retry 헬퍼는 메커니즘 이름(`OptimisticRetry`).

---

## 0. 두 줄 원칙

1. **인터페이스(port)는 그걸 필요로 하는 안쪽 레이어에, 구현체는 기술에 의존하는 바깥 레이어에 — 항상 다른 레이어로 갈라라.**
2. **무엇이 유스케이스를 깨우든(HTTP · cron · batch · message) 진입점은 전부 inbound adapter이고, 얇게 위임만 한다.**

그리고 레이어마다 **나누는 기준 축이 다르다**:

| 레이어 | 나누는 축 | 비고 |
|---|---|---|
| application | **책임**(tx / 정책 / 조립) | 책임이 실제 공존할 때만 분리 |
| domain | **엔티티**(+ 순수 도메인 정책) | 엔티티가 여럿일 때만 |
| infrastructure | **외부 대상**(DB / PG / cache / messaging) | 외부 경계가 여럿일 때만 |
| presentation | **진입 방식**(http / scheduler / batch / consumer) | 거의 안 나눔, provider 분기까지 |

> 전 도메인을 똑같이 잘게 쪼개지 않는다. 단순 CRUD 도메인(product/cart 등)은 평평하게 두고,
> 책임·경계가 실제로 공존하는 도메인(payment 등)만 나눈다.
> ("처음부터 잘게 나누지 않되, 맥락이 달라지는 시점에 분리한다" 원칙과 일치.)

---

## 1. application — 책임으로 나눈다

tx를 여는 레이어와 안 여는 레이어를 **물리적으로 갈라** self-invocation 함정을 구조로 막는 게 1차 목적.

```
payment/application/
├── usecase/        # orchestrator — 진입점. tx 없음. 흐름 조립 + 정책 선택
│   ├── NaverPayApprovalUseCase
│   ├── PaymentApprovalCompensationUseCase   # skip 정책은 이 안의 private 메서드로
│   └── PaymentReconciliationUseCase
├── service/        # tx 단위작업. @Transactional. 충돌 시 전파(catch 안 함)
│   ├── PaymentTransitionService        (markUnknown/fail/succeed)
│   ├── PaymentApprovalService          (succeedApproval — order+payment 한 tx)
│   └── PaymentCancellationService
├── port/           # 외부 시스템 인터페이스 (outbound)
└── dto/            # 입력 command(=Command DTO) / result

(retry/skip이 여러 도메인에 걸쳐 재사용될 때만 application 밖 helper로 — 한 곳뿐이면 private)
support/   또는 common/
└── OptimisticRetry     # retry 헬퍼 (여러 도메인 공유 시에만)
```

> **여러 tx 단위작업을 한 tx로 묶을 때**(예: `payment.succeed()` + `order.completePayment()`가 함께
> 커밋돼야 할 때): usecase에 `@Transactional`을 달지 말고(외부 호출이 tx에 빨려들고 규칙도 깨짐),
> 둘을 감싸는 **전용 메서드를 `service/`에 만들고 거기에만 tx를 단다**(`PaymentApprovalService.succeedApproval`).
> 그 메서드는 다른 tx 서비스를 부르기보다 리포지토리/도메인 객체를 직접 다뤄 한 메서드 안에서 완결한다.

충돌 반응(skip/retry) 배치 — **정식 레이어(`policy/`)를 만들지 않는다**:

- **skip**("충돌이면 조용히 넘어감")은 그것을 쓰는 흐름 하나에만 의미가 있다 → 그 **클래스 안의 private 메서드**로 둔다(예: `PaymentApprovalCompensationUseCase.markUnknownBestEffort(...)`). 흐름 옆에 맥락이 남고 클래스·빈·주입이 안 는다. **여러 Service가 같은 skip을 공유할 때만** 별도 클래스로 추출한다(두 번째 사용처가 분리 시점).
- **retry**("충돌이면 다시 시도")도 skip과 **같은 기준**이다 — 한 곳에서만 쓰면 그 Service 안의 private 메서드, 여러 도메인이 공유하면 별도 helper(`OptimisticRetry`, `support/`·`common/`). retry라서 무조건 helper인 게 아니라, 마침 여러 도메인이 쓰는 경우가 잦을 뿐이다. (단 retry 루프가 부르는 tx 단위작업은 별도 빈이어야 프록시가 적용된다.)

근거:
- **tx 경계가 패키지로 보인다.** "`@Transactional`은 `service/`에만" 을 컨벤션·ArchUnit으로 강제 가능.
- **self-invocation 함정 소멸.** `usecase → service`는 항상 패키지를 넘는 호출 → 프록시 적용 보장. (skip private 메서드가 호출하는 `service` Service도 별도 빈이라 프록시를 탄다.)
- **충돌 반응이 흐름 옆에 명시된다.** skip이 private이라 그 흐름을 읽으면 "충돌을 어떻게 다루는지"가 바로 보인다.

네이밍 주의 (역할별 접미사 이원화, → PR#248):
- **클래스 접미사가 패키지(역할)와 일치한다**: `usecase/`의 클래스는 `…UseCase`, `service/`의 클래스는 `…Service`. 예: `usecase/NaverPayApprovalUseCase`, `service/PaymentTransitionService`.
- 접미사가 역할을 직접 드러내므로 import·스택 트레이스·로그처럼 패키지 경로가 안 보이는 곳에서도 흐름(UseCase)인지 tx 단위작업(Service)인지 구분된다. 빈 등록 stereotype도 역할별로 가른다 — UseCase는 `@Component`, Service는 `@Service`(둘은 기능 동일하나 역할 신호로 분리).
- 패키지명 대안: `usecase`→`flow`/`orchestration`, `service`→`transaction`/`tx`(다만 접미사 이원화로 의도가 이미 드러나므로 평이한 `service`로 충분).
- retry helper는 `Service`를 안 붙이고 메커니즘 이름(`OptimisticRetry`)으로 둔다 — 유스케이스 행위가 아니라 재사용 메커니즘이라서다.

---

## 2. domain — 엔티티로 나눈다

```
payment/domain/
├── payment/         # Payment 엔티티 + 전이 로직 + PaymentStatus
├── reservation/     # PaymentReservation + RESERVED→USED 전이
├── repository/      # repository port
├── policy/          # 순수 도메인 정책 (PaymentPostProcessTargetPolicy 등)
└── exception/       # 도메인 예외 — 3장 참조
```

주의: **도메인 정책 ≠ 동시성/기술 정책.**
- `domain/policy/` = 상태로 분류를 계산하는 **순수 도메인 규칙**(`PaymentPostProcessTargetPolicy` 등). tx를 모른다.
- 동시성 정책(skip/retry)은 별도 `application/policy/` 같은 패키지로 만들지 **않는다**: skip은 흐름 Service의 private 메서드, retry는 `support/` helper로 둔다(1장). 둘을 domain에 섞으면 domain이 tx를 알게 되는 오염이 생긴다.

**Aggregate 경계 — cross-aggregate는 ID로 참조한다** (→ PR#166): 다른 Aggregate는 객체가 아니라 ID(`Long`)로 참조하고, same-aggregate 관계만 객체로 참조한다. (예: `tbl_order_item`은 Order와 한 Aggregate라 객체 참조하지만, Payment·Stock·Product는 각각 별개 Aggregate이므로 `order_id`·`product_id` 같은 ID로만 참조한다.) 이 경계는 DB에서 cross-aggregate FK를 두지 않는 것과 짝을 이룬다(상세는 `docs/db-schema.md`). ArchUnit은 layer 의존 방향까지만 강제하므로(7장), 이 Aggregate 경계는 코드 리뷰로 지킨다.

---

## 3. infrastructure — 외부 대상으로 나눈다

```
payment/infrastructure/
├── persistence/    # DB 경계: JpaXxxRepository + XxxRepositoryAdapter
│   ├── PaymentRepositoryAdapter        (save / saveApproved / saveChecked)
│   └── PaymentReservationRepositoryAdapter  (saveUsed)
├── pg/             # PG 경계: naverpay Gateway/Client 구현
├── cache/          # Redis 캐시 구현
├── messaging/      # Kafka/RabbitMQ producer 구현
└── notification/   # NotificationPort 구현
```

근거 — 충돌 규칙과 직결:
- **기술 예외 → 도메인 예외 변환은 `persistence/` adapter에만.** PG 경계(`pg/`)의 변환은 성격이 다르다
  (HTTP 타임아웃·PG 에러코드를 변환하지, `OptimisticLockException`을 다루지 않음).
  → "`persistence` 밖에서는 JPA/DAO 예외 타입을 모른다" 를 ArchUnit으로 강제 가능.
- **`saveAndFlush` 사용처가 한곳에 모인다.** 충돌 변환 전용 경로(`saveChecked/saveUsed/saveApproved`)가
  전부 `persistence/`에 → "saveAndFlush는 persistence에서만 허용" 강제 가능.
  (기본 컨벤션은 `save`, 충돌을 tx 내부에서 변환해야 하는 전용 경로만 `saveAndFlush`.)
- **락 전략이 adapter 시그니처로 드러난다.** `findByIdForUpdate`(비관 락)가 `persistence/`에.

provider 묶음 주의: `naverpay` 같은 provider별 묶음은 좋지만, 그 안에서도
**infra 코드는 `infrastructure/pg/naverpay`, controller는 `presentation`** 으로 레이어를 유지한다.
"provider로 묶기"가 "레이어를 무너뜨리기"가 되면 안 된다.

---

## 4. presentation — 진입 방식으로 나눈다 (거의 안 나눔)

얇은 위임 레이어. tx도 로직도 로그도 없음.

```
payment/presentation/
├── http/
│   ├── PaymentController
│   └── naverpay/NaverPayController
├── scheduler/      # @Scheduled — 5장
├── batch/          # Spring Batch Job 정의 — 5장
└── consumer/       # @KafkaListener — 6장
```

주의:
- **충돌→HTTP 매핑은 Controller가 아니라 common의 GlobalExceptionHandler가 소유.**
  Controller는 충돌을 모르고 전파만 한다(`OptimisticLockingFailureException → 409` 등은 공통 핸들러에서 일괄).
- provider별 controller 분리(`naverpay/`)까지가 천장. 그 이상(command/query 분리 등)은 과하다.
- 레이어 이름을 정석대로 `adapter/in` 또는 `inbound`로 바꾸는 팀도 많다.
  기존 `presentation`을 유지한다면 그 아래 `http/scheduler/batch/consumer` 서브패키지로 둔다.

---

## 5. 경계 케이스 — 위치 기준

### 5.1 예외

| 종류 | 위치 | 이유 |
|---|---|---|
| 도메인 예외 (`PAYMENT_CONCURRENTLY_MODIFIED`, `PAYMENT_STATUS_TRANSITION_NOT_ALLOWED` 등) | `domain/exception/` | 엔티티 가드 + adapter 변환 + application catch + GlobalExceptionHandler 가 **모두 의존** → 가장 안쪽에 |
| 인프라 기술 예외 (`OrderIdempotencyStoreUnavailableException` 등) | `infrastructure/` | "Redis가 죽었다" 같은 기술 신호. domain이 몰라야 함 |

핵심: **adapter가 변환한 결과물(도메인 예외)은 domain 소유, 변환 전의 기술 예외·fallback 신호는 infra 소유.**
`exception/`을 레이어 동급에 두지 말고 `domain/exception/`으로 내린다(인프라 전용 예외만 infra에 남김).

### 5.2 scheduler / batch — inbound adapter다 (application 아님)

무엇이 유스케이스를 깨우느냐만 다르다: Controller=HTTP, Scheduler=cron, Batch=배치 런타임, Consumer=메시지.
**전부 inbound adapter** → `presentation/` 아래, 진입 트리거만 갖고 application에 위임.

```java
@Component
class PaymentReconciliationScheduler {        // presentation/scheduler/
    private final PaymentReconciliationUseCase reconciliation;  // application/usecase/

    @Scheduled(cron = "...")
    void run() { reconciliation.reconcile(); }  // 위임만. tx도 로직도 없음
}
```

이득: cron 없이 `reconcile()` 직접 호출로 테스트 가능 / 진입점에 tx가 없어
"건별 독립 tx는 service, 루프 격리는 usecase" 구조가 깨끗이 유지됨.
batch도 동일 — `presentation/batch/`에 Job/Step 정의, 실제 처리는 application service에 위임.

> 안티패턴: `@Scheduled`를 application service(`…Service`)에 **직접** 다는 것
> → 진입(adapter) + 흐름(application)이 한 클래스에 붙어버린다.

### 5.3 캐시 (Redis 등) — Port + 구현체

Port vs Repository 구분 그대로:
- **도메인 모델의 영속성** → `domain/repository/` Repository
- **Service가 필요한 외부 기능** → `application/port/` Port

캐시는 후자다. 도메인 진실의 원천이 아니라 **성능용 보조 기능**(캐시가 비어도 진실은 DB).

```
application/port/        ProductCache          # 인터페이스
infrastructure/cache/    RedisProductCache     # 구현 (Redis 의존 격리)
```

주의: 캐시 구현체도 **기술 예외를 경계에서 변환**한다.
Redis 타임아웃이 raw로 application까지 새면 안 되고, 구현체가 잡아서 "캐시 미스로 취급 → DB fallback"
하거나 포트 예외로 변환한다. ("기술 예외는 adapter 경계를 못 넘는다" 원칙의 캐시판.)

### 5.4 Kafka / RabbitMQ — 외부 브로커, 방향별로 갈린다

한 브로커가 producer(보내기)와 consumer(받기)를 다 갖는다. **방향이 위치를 가른다.**

| 방향 | 성격 | 인터페이스 | 구현체/진입점 | 포트로 감싸나 |
|---|---|---|---|---|
| producer | outbound | `application/port/` (`StockRestoreEventPublisher`) | `infrastructure/messaging/` | **예** |
| consumer | inbound | — | `presentation/consumer/` (`@KafkaListener`) | 아니오 (위임만) |

- producer는 "이벤트 발행"이라는 외부 기능 → 캐시/PG와 동일하게 port + infra 구현.
  application은 `Kafka`/`KafkaTemplate`를 몰라야 함(RabbitMQ로 바꿔도 application 불변).
- consumer는 메시지가 유스케이스를 깨우는 진입점 → inbound adapter, 얇게 위임.
- 역직렬화 실패·브로커 예외는 adapter에서 처리(재시도/DLQ/변환), application까지 raw로 새지 않음.
- RabbitMQ도 구조 동일 — 브로커가 무엇이든 "producer=outbound port, consumer=inbound adapter".

### 5.5 ApplicationEventPublisher — 외부 브로커가 아니다, 다르게 취급

프로세스 밖으로 안 나가는 **Spring 컨테이너 내부 in-process 이벤트 버스**. Kafka 자리에 끼우지 않는다.

- **포트로 감싸지 않는다(보통).** 외부 시스템이 아니라 같은 도메인 안의 디커플링 도구라
  한 겹 더 추상화가 과하다. application service가 직접 주입받아 발행.
- **진짜 난점은 위치가 아니라 트랜잭션 시점이다:**
  - 기본 동기 리스너 → **발행자 tx 안**에서 실행. 리스너가 던지면 발행자 tx 롤백.
  - `@TransactionalEventListener(AFTER_COMMIT)` → 발행자 tx **커밋 후** 실행.
    리스너에서 DB 쓰기를 하려면 tx가 끝났으므로 **새 tx 필요(REQUIRES_NEW 등)**.
    → 충돌 처리에서 본 "단계별 독립 commit"과 같은 사고가 필요한 지점.

선택 기준:
- **프로세스 경계 넘고 유실 불가** → **Outbox + Kafka** (이벤트를 같은 tx에서 DB에 박고 릴레이 → 유실 없음).
- **같은 프로세스 내 디커플링, 유실 감내 가능** → `ApplicationEventPublisher` + AFTER_COMMIT,
  단 리스너 tx 경계 명시 관리.
- AFTER_COMMIT은 "커밋 후 리스너 실행 전 프로세스 사망 시 유실" 약점 → 프로세스 간 통신엔 부적합.

---

## 6. 전체 배치 한눈에

```
<domain>/
├── presentation/                 # inbound adapter — 얇게 위임
│   ├── http/        Controller
│   ├── scheduler/   @Scheduled
│   ├── batch/       Spring Batch Job 정의
│   └── consumer/    @KafkaListener
├── application/
│   ├── usecase/     orchestrator (tx 없음, 흐름 조립 + 정책 선택; skip은 private 메서드)
│   ├── service/     tx 단위작업 (@Transactional, 충돌 전파)
│   ├── port/        outbound 인터페이스 (PG/cache/messaging/email)
│   └── dto/
├── domain/
│   ├── <entity>/    엔티티 + 전이 로직
│   ├── repository/  repository port
│   ├── policy/      순수 도메인 정책
│   └── exception/   도메인 예외
└── infrastructure/  outbound adapter — 외부 대상별
    ├── persistence/ JPA adapter (예외 변환 / saveAndFlush / 락)
    ├── pg/          PG 연동
    ├── cache/       Redis
    ├── messaging/   Kafka/RabbitMQ producer
    └── notification/
```

배치 기준 요약:

| 항목 | 인터페이스 | 구현체/진입점 |
|---|---|---|
| 도메인 예외 | — | `domain/exception/` |
| 인프라 기술 예외 | — | `infrastructure/` |
| scheduler / batch | — | `presentation/scheduler` · `presentation/batch` |
| 캐시 | `application/port/` | `infrastructure/cache/` |
| Kafka/MQ producer | `application/port/` | `infrastructure/messaging/` |
| Kafka/MQ consumer | — | `presentation/consumer/` |
| ApplicationEventPublisher | (감싸지 않음) | application 직접 발행 / 리스너 tx 경계 관리 |

---

## 7. 분리를 지키는 법 — ArchUnit

패키지 분리의 진짜 값어치는 "걸 수 있는 경계가 생긴다"는 것. 권장 규칙 예:

- `usecase` 패키지의 클래스에는 `@Transactional` 금지 (tx는 `service`만). skip은 usecase의 private 메서드라 이 규칙에 자연히 포함된다.
- `persistence` 밖에서는 JPA/DAO 예외 타입(`ObjectOptimisticLockingFailureException` 등) 참조 금지.
- `saveAndFlush` 호출은 `persistence`에서만.
- Controller(presentation)는 충돌 예외를 catch하지 않는다.
- `domain`은 `infrastructure`·Spring(`@Transactional`, `KafkaTemplate` 등)을 참조하지 않는다.
- application은 `Kafka`/`Redis` 등 기술 타입을 직접 참조하지 않는다(port로만).
