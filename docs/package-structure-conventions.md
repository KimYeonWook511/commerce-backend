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
- **서비스는 도메인 경계로 가른다.** 한 도메인 안에서 끝나는 트랜잭션은 한 클래스에 모으고, **다른 도메인까지 바꾸는 것만** 클래스를 따로 둔다. **유스케이스는 조회 조건으로 가른다.**
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
│   ├── RequestApprovalUseCase
│   ├── ClosePaymentUseCase             # skip 정책은 이 안의 private 메서드로
│   └── ReconcilePaymentUseCase
├── service/        # tx 단위작업. @Transactional. 충돌 시 전파(catch 안 함)
│   ├── PaymentService                  (결제 안에서 끝나는 전이 — 아래 "서비스를 무엇으로 가르나")
│   ├── RefundService                   (환불 안에서 끝나는 전이)
│   └── PaymentApprovalService          (complete — order+payment 한 tx)
├── port/           # 외부 시스템 인터페이스 (outbound)
├── dto/            # 입력 command(=Command DTO) / result
└── config/         # 설정값을 읽어 도메인 정책을 빈으로 등록 — 아래

(retry/skip이 여러 도메인에 걸쳐 재사용될 때만 application 밖 helper로 — 한 곳뿐이면 private)
support/   또는 common/
└── OptimisticRetry     # retry 헬퍼 (여러 도메인 공유 시에만)
```

> **여러 tx 단위작업을 한 tx로 묶을 때**(예: `payment.succeed()` + `order.completePayment()`가 함께
> 커밋돼야 할 때): usecase에 `@Transactional`을 달지 말고(외부 호출이 tx에 빨려들고 규칙도 깨짐),
> 둘을 감싸는 **전용 메서드를 `service/`에 만들고 거기에만 tx를 단다**(`PaymentApprovalService.complete`).
> 그 메서드는 다른 tx 서비스를 부르기보다 리포지토리/도메인 객체를 직접 다뤄 한 메서드 안에서 완결한다.

### 서비스를 무엇으로 가르나 — 도메인 경계

**한 트랜잭션이 이 도메인 안에서 끝나는가, 다른 도메인까지 바꾸는가.** 가드가 있는지·시각을 찍는지·누가
부르는지는 보지 않는다 — 그것들은 도메인과 usecase의 관심사이고, 서비스는 트랜잭션 경계를 이름 있는
자리로 만드는 껍데기다.

- **한 도메인 안에서 끝나는 것은 한 클래스에 모은다.** 같은 타입의 인스턴스를 여럿 다루는 것도, 같은
  도메인의 다른 타입을 함께 저장하는 것도 가르는 이유가 못 된다. 갈라 놓으면 클래스만 늘고 드러나는
  것이 없다.
- **다른 도메인까지 바꾸는 것만 클래스를 따로 둔다.** 같은 클래스에 두면 "여기 이미 저쪽을 만지네" 하고
  둘째가 들어오고, 그 순간 그 클래스는 밖에 닿는 자리라는 뜻을 잃는다. 파일이 갈려 있으면 밖에 닿는 곳이
  파일 단위로 드러나고, 나중에 모듈을 쪼갤 때도 그 파일만 보면 된다.
- **미리 잘게 나누지 않는다.** 클래스가 늘면 서비스끼리 부를 일이 늘고, 그 호출은 금지돼 있지 않아 그대로
  의존이 된다. 커져서 읽기 어려워지면 그때 나눈다.
- **판별법**: 껍데기가 `find → 도메인 메서드 → save` 세 줄을 넘으면 묶음이라는 신호다.

> **주의 — 불려 가는 서비스가 `@Transactional`을 갖고 있어도 부르는 쪽 트랜잭션에 참여한다.** 독립이
> 필요하면 그렇게 선언해야 하고, 안 그러면 바깥이 롤백될 때 함께 딸려간다. 금지가 아니라 알고 부르는 것이다.

유스케이스는 다른 축으로 가른다.

- **유스케이스는 조회 조건으로 가른다.** 대상을 고르는 조건이 다르면 다른 유스케이스다.
- **유스케이스가 유스케이스를 부르는 것은 여러 진입점이 같은 작업을 공유할 때만이다.** 순서를 이어 붙이려고
  부르는 것은 그 유스케이스 안에서 한다. 그리고 부르기 전에 지금까지의 사실이 커밋돼 있어야 한다 —
  뒤엣것이 실패해도 앞선 사실이 남아 후처리가 마저 할 수 있어야 한다.

`config/` — **도메인 정책을 빈으로 등록하는 자리**:

- 정책이 **운영 설정값을 받아야 할 때만** 둔다. `domain/policy/`의 정책 클래스에 `@Component`를 붙이면 설정값을 읽는 일까지 domain이 하게 되고, 값을 바꿀 때마다 domain을 건드린다. 등록하는 자리가 값을 읽어 생성자로 넘기면 정책은 받은 값으로 판단만 한다(`payment/application/config/PaymentPolicyConfig`).
- **그 이상으로 넓히지 않는다.** 흐름 조립이나 tx가 들어오면 `usecase/`·`service/`와 책임이 겹친다. 기술 클라이언트 설정(RestTemplate 등)은 그 기술을 쓰는 `infrastructure/` 아래에 둔다.

충돌 반응(skip/retry) 배치 — **정식 레이어(`policy/`)를 만들지 않는다**:

- **skip**("충돌이면 조용히 넘어감")은 그것을 쓰는 흐름 하나에만 의미가 있다 → 그 **클래스 안의 private 메서드**로 둔다(예: 환불 발송 흐름이 부르기 직전 전이의 충돌을 흡수하는 자리). 흐름 옆에 맥락이 남고 클래스·빈·주입이 안 는다. **여러 Service가 같은 skip을 공유할 때만** 별도 클래스로 추출한다(두 번째 사용처가 분리 시점).
- **retry**("충돌이면 다시 시도")도 skip과 **같은 기준**이다 — 한 곳에서만 쓰면 그 Service 안의 private 메서드, 여러 도메인이 공유하면 별도 helper(`OptimisticRetry`, `support/`·`common/`). retry라서 무조건 helper인 게 아니라, 마침 여러 도메인이 쓰는 경우가 잦을 뿐이다. (단 retry 루프가 부르는 tx 단위작업은 별도 빈이어야 프록시가 적용된다.)

근거:
- **tx 경계가 패키지로 보인다.** "`@Transactional`은 `service/`에만" 을 컨벤션·ArchUnit으로 강제 가능.
- **self-invocation 함정 소멸.** `usecase → service`는 항상 패키지를 넘는 호출 → 프록시 적용 보장. (skip private 메서드가 호출하는 `service` Service도 별도 빈이라 프록시를 탄다.)
- **충돌 반응이 흐름 옆에 명시된다.** skip이 private이라 그 흐름을 읽으면 "충돌을 어떻게 다루는지"가 바로 보인다.

네이밍 주의 (역할별 접미사 이원화, → PR#248):
- **클래스 접미사가 패키지(역할)와 일치한다**: `usecase/`의 클래스는 `…UseCase`, `service/`의 클래스는 `…Service`. 예: `usecase/RequestApprovalUseCase`, `service/PaymentService`.
- 접미사가 역할을 직접 드러내므로 import·스택 트레이스·로그처럼 패키지 경로가 안 보이는 곳에서도 흐름(UseCase)인지 tx 단위작업(Service)인지 구분된다. 빈 등록 stereotype도 역할별로 가른다 — UseCase는 `@Component`, Service는 `@Service`(둘은 기능 동일하나 역할 신호로 분리).
- 패키지명 대안: `usecase`→`flow`/`orchestration`, `service`→`transaction`/`tx`(다만 접미사 이원화로 의도가 이미 드러나므로 평이한 `service`로 충분).
- retry helper는 `Service`를 안 붙이고 메커니즘 이름(`OptimisticRetry`)으로 둔다 — 유스케이스 행위가 아니라 재사용 메커니즘이라서다.

---

## 2. domain — 엔티티로 나눈다

```
payment/domain/
├── <엔티티>          # 엔티티 + 전이 로직 + 그 엔티티의 enum (Payment·Refund·PgCallLog 등)
├── repository/      # repository port
├── policy/          # 순수 도메인 정책 (후처리 대상 판정 등)
└── exception/       # 도메인 예외 — 3장 참조
```

> 엔티티가 적으면 엔티티별 하위 패키지를 만들지 않고 `domain/` 아래 평평하게 둔다. 하위 패키지는
> 엔티티 하나에 딸린 타입이 여럿이라 섞여 보이기 시작할 때 만든다.

주의: **도메인 정책 ≠ 동시성/기술 정책.**
- `domain/policy/` = 상태와 시각으로 판정을 계산하는 **순수 도메인 규칙**(후처리 대상 판정·재시도 간격표 등). tx를 모른다.
- 동시성 정책(skip/retry)은 별도 `application/policy/` 같은 패키지로 만들지 **않는다**: skip은 흐름 Service의 private 메서드, retry는 `support/` helper로 둔다(1장). 둘을 domain에 섞으면 domain이 tx를 알게 되는 오염이 생긴다.

**Aggregate 경계 — cross-aggregate는 ID로 참조한다** (→ PR#166): 다른 Aggregate는 객체가 아니라 ID(`Long`)로 참조하고, same-aggregate 관계만 객체로 참조한다. (예: `tbl_order_item`은 Order와 한 Aggregate라 객체 참조하지만, Payment·Stock·Product는 각각 별개 Aggregate이므로 `order_id`·`product_id` 같은 ID로만 참조한다.) 이 경계는 DB에서 cross-aggregate FK를 두지 않는 것과 짝을 이룬다(상세는 `docs/db-schema.md`). ArchUnit은 layer 의존 방향까지만 강제하므로(7장), 이 Aggregate 경계는 코드 리뷰로 지킨다.

**엔티티 생성 — 이름 있는 정적 팩토리로만 만든다.** 도메인 엔티티는 `create`·`createPending`·`createReserved` 처럼 **의도를 드러내는 정적 팩토리**로 생성하고, 생성자는 `private`로 닫는다. 불변식 검증을 생성의 단일 관문에 모으고, 필수 필드 누락을 컴파일 타임에 막기 위함이다.

- **lombok `@Builder`를 엔티티에 붙이지 않는다.** 빌더는 누락을 컴파일 타임에 못 막고 생성 의도가 이름에 드러나지 않는다. 빌더는 필드가 많고 선택적인 객체(요청/응답 DTO·Command·테스트 픽스처)에만 쓰며, 그때도 엔티티가 아니라 그 DTO/픽스처에 붙는다.
- **public setter를 두지 않는다.** 상태 변경은 규칙을 품은 도메인 메서드로만 표현한다.
- **JPA 기본 생성자는 `@NoArgsConstructor(access = PROTECTED)`로 닫는다.** 프레임워크만 쓰고 애플리케이션 코드는 팩토리를 거치게 한다.

이 규칙은 코드 리뷰로 지킨다(`@Builder`는 컴파일 후 사라져 ArchUnit이 직접 잡지 못한다).

---

## 3. infrastructure — 외부 대상으로 나눈다

```
payment/infrastructure/
├── persistence/    # DB 경계: JpaXxxRepository + XxxRepositoryAdapter
│   ├── PaymentRepositoryAdapter        (save / saveChecked / saveFlushed)
│   └── RefundRepositoryAdapter         (save / saveChecked)
├── pg/             # PG 경계: naverpay Gateway/Client 구현
├── cache/          # Redis 캐시 구현
├── messaging/      # Kafka/RabbitMQ producer 구현
└── notification/   # NotificationPort 구현
```

근거 — 충돌 규칙과 직결:
- **기술 예외 → 도메인 예외 변환은 `persistence/` adapter에만.** PG 경계(`pg/`)의 변환은 성격이 다르다
  (HTTP 타임아웃·PG 에러코드를 변환하지, `OptimisticLockException`을 다루지 않음).
  → "구현체에 묶인 예외 타입은 `persistence` 밖(application·domain·presentation)에서 모른다(DAO 추상 예외는 application 허용)" 를 ArchUnit으로 강제 가능.
- **`saveAndFlush` 사용처가 한곳에 모인다.** 전부 `persistence/`에 있어 "saveAndFlush는 persistence에서만
  허용"을 강제할 수 있다. **저장 메서드는 구현이 아니라 부르는 쪽이 무엇에 기대는지로 가른다** —
  충돌을 이 호출 안에서 확정할 것을 기대하면 `saveChecked`, 이 저장이 뒤 저장보다 먼저 나갈 것을
  기대하면 `saveFlushed`, 아무것도 기대하지 않으면 `save`. 상세는 `docs/optimistic-lock-design.md` 1장.
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
class PaymentPostProcessScheduler {           // presentation/scheduler/
    private final ReconcilePaymentUseCase reconciliation;  // application/usecase/

    @Scheduled(cron = "...")
    void reconcile() { reconciliation.reconcile(); }  // 위임만. tx도 로직도 없음
}
```

> **유스케이스가 조회 조건으로 갈려도 진입점까지 같은 수로 나누지 않는다.** 클래스마다 유스케이스 하나만
> 부르는 껍데기가 되고, 한 클래스에 모여 있어야 무엇이 언제 도는지 한눈에 보인다. 주기를 따로 정해야 하면
> 메서드마다 붙인다.

이득: cron 없이 `reconcile()` 직접 호출로 테스트 가능 / 진입점에 tx가 없어
"건별 독립 tx는 service, 루프 격리는 usecase" 구조가 깨끗이 유지됨.
batch도 동일 — `presentation/batch/`에 Job/Step 정의, 실제 처리는 application service에 위임.

> 안티패턴: `@Scheduled`를 application service(`…Service`)에 **직접** 다는 것
> → 진입(adapter) + 흐름(application)이 한 클래스에 붙어버린다.

### 5.3 캐시 (Redis 등) — Port + 구현체

Port vs Repository 구분 그대로:
- **도메인 모델의 영속성** → `domain/repository/` Repository
- **Service가 필요한 외부 기능** → `application/port/` Port

캐시는 후자다. 도메인 진실의 원천이 아니라 **보조 장치**이며, 비어 있거나 죽어도 진실은 DB에 있다(멱등 선점 저장소가 그 예다 — 죽으면 DB 유일 제약 경로로 물러난다).

```
application/port/        OrderIdempotencyStore        # 인터페이스
infrastructure/cache/    RedisOrderIdempotencyStore   # 구현 (Redis 의존 격리)
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
│   ├── dto/
│   └── config/      설정값을 읽어 도메인 정책을 빈으로 등록
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
- 구현체에 묶인 구체 예외(`org.springframework.orm`·`org.hibernate`·`jakarta.persistence` 예외)는 `application`·`domain`·`presentation`에서 참조 금지. DAO 추상 예외(`org.springframework.dao`)는 application 허용, `domain`은 금지.
- `saveAndFlush` 호출은 `persistence`에서만.
- Controller(presentation)는 충돌 예외를 catch하지 않는다.
- `domain`은 `infrastructure`·Spring(`@Transactional`, `KafkaTemplate`, HTTP/web 등)을 참조하지 않는다.
- application은 `Kafka`/`Redis` 등 기술 타입을 직접 참조하지 않는다(port로만).
