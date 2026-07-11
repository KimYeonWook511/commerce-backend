# 아키텍처

이 문서는 백엔드가 전체적으로 어떻게 구성되고 요청이 어떻게 흐르는지를 보여주는 지도다. "코드를 어디에 둘까"의 배치 규칙은 `docs/package-structure-conventions.md`가 정의하고, 이 문서는 "무엇이 어디에 있고 요청이 어떻게 흐르는가"를 다룬다.

구조는 4계층 레이어드(`presentation → application → domain ← infrastructure`)이며, 모든 의존은 안쪽 domain을 향한다. 계층 간 경계는 port/adapter로 다스린다.

## 패키지 구조

```
src/main/java/com/commerce/
├── common/            # 공통 설정, 예외, JPA base entity, Kafka, 유틸
├── security/          # JWT 인증 필터, 인가 인터셉터, 인증 컨텍스트, argument resolver
├── auth/              # 인증 유스케이스 (회원가입·로그인·토큰 재발급), JWT 구현, refresh token
├── member/            # 회원 등록·조회
├── product/           # 상품 공개 조회, 관리자 상품 관리
├── stock/             # 재고 차감·복구·관리자 조정, 변경 이력
├── order/             # 주문 생성·취소·만료 배치
├── cart/              # 장바구니 항목 추가·변경·삭제·조회, 주문 시 항목 제거 연동
├── payment/           # 결제 준비·승인·시도 이력
│   └── naverpay/      # 네이버페이 PG 연동 (Gateway, Client, Controller)
└── outbox/            # Outbox 이벤트 저장·발행
    └── stock/         # 재고 복구 이벤트 생성·릴레이·소비
```

각 도메인은 아래 레이어 구조를 따른다. **레이어마다 나누는 축이 다르다**: application은 *책임*으로, domain은 *엔티티*로, infrastructure는 *외부 대상*으로, presentation은 *진입 방식*으로 나눈다. 전 도메인을 똑같이 잘게 쪼개지 않고, 책임·경계가 실제로 공존하는 도메인(예: payment)만 아래 깊이로 나눈다. 단순 CRUD 도메인은 평평하게 둔다.

```
<domain>/
├── presentation/        # inbound adapter — 얇게 위임 (tx·로직·로그 없음)
│   ├── http/            # Controller, request DTO
│   ├── scheduler/       # @Scheduled (cron 트리거만, 위임)
│   ├── batch/           # Spring Batch Job/Step 정의 (위임)
│   └── consumer/        # @KafkaListener (메시지 트리거만, 위임)
├── application/
│   ├── usecase/         # orchestrator — tx 없음. 흐름 조립 + 충돌 정책 선택 (skip은 private 메서드)
│   ├── service/         # tx 단위작업 — @Transactional. 충돌 시 전파(catch 안 함)
│   ├── port/            # 외부 시스템 연동 인터페이스(outbound): PG, cache, messaging producer, email 등
│   └── dto/             # command(=Command DTO), result
├── domain/
│   ├── <entity>/        # 엔티티 + 전이 로직
│   ├── repository/      # repository port (도메인 모델 영속성)
│   ├── policy/          # 순수 도메인 정책 (상태로 분류 계산 — tx 모름)
│   └── exception/       # 도메인 예외 (모든 레이어가 의존 → 가장 안쪽)
└── infrastructure/      # outbound adapter — 외부 대상별
    ├── persistence/     # JpaXxxRepository, XxxRepositoryAdapter (예외 변환·saveAndFlush·락)
    ├── pg/              # 결제 PG 연동
    ├── cache/           # Redis 등
    ├── messaging/       # Kafka/RabbitMQ producer 구현
    └── notification/    # 알림 채널 구현
```

배치 기준 요약(상세·근거는 `docs/package-structure-conventions.md` 단일 출처):

- **무엇이 유스케이스를 깨우든(HTTP·cron·batch·message) 진입점은 전부 inbound adapter** → `presentation/` 아래에 두고 얇게 위임한다. `@Scheduled`/배치 Job/`@KafkaListener`를 application Service에 직접 달지 않는다.
- **인터페이스(port)는 안쪽 레이어에, 구현체는 바깥 레이어에** — 항상 다른 레이어로 가른다. 캐시·messaging producer 등 "외부 기능"은 `application/port/` + `infrastructure/`, "도메인 모델 영속성"은 `domain/repository/` + `infrastructure/persistence/`.
- **메시징 방향이 위치를 가른다**: producer(보내기)는 outbound → `application/port/` + `infrastructure/messaging/`, consumer(받기)는 inbound → `presentation/consumer/`.
- **`ApplicationEventPublisher`는 외부 브로커가 아닌 in-process 디커플링** → port로 감싸지 않고 application이 직접 발행하되, 리스너의 트랜잭션 시점(동기 / `@TransactionalEventListener(AFTER_COMMIT)` / `REQUIRES_NEW`)을 명시 관리한다. 프로세스 경계를 넘고 유실 불가한 이벤트는 in-process가 아닌 Outbox + Kafka 를 쓴다.
- **도메인 예외는 `domain/exception/`**(엔티티 가드와 adapter 변환이 함께 던지고 모든 레이어가 의존). 인프라 fallback용 기술 예외(예: `OrderIdempotencyStoreUnavailableException`)만 `infrastructure/`에 둔다.
- **엔티티는 `domain/`에 두고 JPA 매핑 애너테이션(`@Entity`/`@Id`/`@Version` 등) 사용을 허용한다.** 순수 POJO 도메인 객체 + 별도 매핑 클래스(JpaEntity)와 변환 코드를 두는 비용이 현재 규모에서 비효율적이라 판단해, domain 은 "선언적 매핑 메타데이터"까지만 허용한다(Spring 생태계 관습 일관성, → PR#91). 단 동작하는 JPA 런타임(`EntityManager` 로 직접 쿼리·flush 등)은 domain 에 두지 않는다 — 이 경계는 ArchUnit 으로 강제한다(아래 "아키텍처 규칙 강제" 참고).

---

## 계층 구조

```
presentation → application → domain ← infrastructure
```

- `presentation`: 요청 수신, 입력 검증, service 위임, 응답 반환
- `application`: 유스케이스 흐름 조율, 트랜잭션 경계. 외부 시스템 연동이 필요할 때는 `application/port/` 인터페이스(Port)로만 의존한다.
- `domain`: 상태 변경 규칙, 비즈니스 로직, repository port 정의
- `infrastructure`: Spring Data JPA repository, repository adapter, 외부 시스템 구현. Port 인터페이스의 실제 구현체가 여기에 위치한다.

`domain`은 `infrastructure`를 직접 참조하지 않는다.  
`infrastructure`의 `XxxRepositoryAdapter`가 `domain.repository.XxxRepository`를 구현하고 `JpaXxxRepository`에 위임한다.

### Repository와 Port 구분

| | Repository | Port |
|---|---|---|
| 위치 | `domain/repository/` | `application/port/` |
| 소유 이유 | 도메인 모델의 영속성 관심사 | Service가 필요한 외부 기능 |
| 관심사 | 도메인 모델 저장·조회 | 외부 시스템 연동 추상화 |
| 예시 | `OrderRepository` | `IdempotencyStore`, `PaymentGateway` |

Port 인터페이스 설계 원칙:
- 처음에는 하나의 인터페이스로 시작한다
- 특정 Service에만 필요한 메서드가 생기는 순간, 기존 인터페이스를 상속 확장하여 분리한다
- 이름은 의도가 드러나는 방향으로 자유롭게 선택한다 (`IdempotencyStore`, `EmailSender` 등, `Port` suffix 불필요)

---

## 서비스 네이밍 원칙

하나의 application service 클래스는 하나의 유스케이스 행위만 담당한다.

- 네이밍은 **역할별 접미사**를 따른다 (→ PR#248):
  - `application/usecase/`(흐름 조립·정책 선택, tx 없음) → **`{행위}{도메인}UseCase`** (`NaverPayApprovalUseCase`, `PaymentReconciliationUseCase`)
  - `application/service/`(tx 단위작업, `@Transactional`) → **`{행위}{도메인}Service`** (`PaymentTransitionService`, `CreateOrderService`)
- 접미사가 패키지(역할)와 일치하므로 import·스택 트레이스·로그처럼 패키지 경로가 안 보이는 곳에서도 흐름(UseCase)인지 tx 단위작업(Service)인지 드러난다. 빈 등록 stereotype도 역할별로 가른다 — UseCase는 `@Component`, Service는 `@Service`로 등록해 역할을 한 번 더 드러낸다(둘은 기능 동일).
- 처음부터 지나치게 잘게 나누지 않되, 트랜잭션 흐름·변경 이유·호출 맥락·충돌 처리 정책(전파/skip/retry)이 달라지는 시점에 분리한다.
- 단순 작업(조율 없이 tx 한 번)은 usecase를 두지 않고 Controller가 service를 직접 호출한다. usecase는 조율(외부 호출·여러 tx 단계·충돌 정책·격리 루프)이 있을 때만 둔다.

---

## 도메인별 서비스

각 도메인의 application 계층은 위 "서비스 네이밍 원칙"에 따라 유스케이스 단위 Service로 구성된다. 도메인별 서비스의 정확한 전체 목록은 코드(`com.commerce.<domain>.application`)가 단일 출처이며, 특정 기능의 구현 맥락은 해당 task의 `docs/tasks/<task>/architecture.md`를 참조한다. 본 문서는 개별 서비스를 하나하나 다 적지 않는다.

---

## 데이터 흐름

이 섹션은 **코드를 읽어도 한눈에 안 보이는 것** — 책임의 *순서*, *트랜잭션 경계 안팎*, *왜 그 순서인가* — 만 기록한다. 클래스·메서드명·호출 그래프는 코드(`com.commerce.<domain>`)가 기준이므로 적지 않는다(적어 두면 코드가 바뀔 때 문서가 안 맞게 된다).

단순 위임 흐름(상품 공개/관리자 조회, 관리자 상품·재고 관리, 장바구니 추가·조회·수량변경·삭제 등)은 `Controller → service(…Service) → Repository`의 평탄한 위임이라(usecase 없음) 코드가 그대로 출처다. 본 섹션은 경계·순서·보상이 얽힌 흐름만 다룬다.

```
# 결제 reserve — 순서·경계 (클래스명은 코드가 출처)
1. 주문 확인 + 결제 가능 상태 검증
2. UNKNOWN 행 차단 검사 (UNKNOWN 있는 주문은 reserve 차단 — PAYMENT_RESULT_PENDING 409)
3. 재사용 가능 Reservation 탐색, 없으면 RESERVED INSERT
   - 동시 중복 요청(따닥)은 uk_payment_reservation_reserved_key UNIQUE 가 차단

# 결제 승인 (네이버페이) — 순서·경계
1. (memberId, merchantPayKey) 로 Reservation 역조회 (Order 안 거침)
   - 남의/없는 키는 PAYMENT_RESERVATION_NOT_FOUND 로 존재 비노출
2. PG 호출 전 차단 검사:
   - UNKNOWN 행 차단
   - USED Reservation: 같은 pgPaymentId 는 멱등 200 / 다른 pgPaymentId 는 ALREADY_USED 차단
   - 이미 성공한 주문은 PAYMENT_DUPLICATE 차단 (approved_order_key 존재)
3. [트랜잭션 안] Reservation 사용 처리(@Version) + Payment(APPROVE, REQUESTED) INSERT
   - 동시 이중 use 진 쪽은 saveUsed 에서 ALREADY_USED 로 PG 호출 전 차단
   - 이 충돌→ALREADY_USED 번역은 use 가 그 행의 유일한 동시 쓰기 경로임을 전제 (5장 예외 정책 참고)
4. [트랜잭션 밖] PG approve 호출
5. 승인 시도 상태 반영 → 승인 완료 반영
   - saveApproved 의 uk_payment_approved_order_key 위반은 adapter 가 PAYMENT_DUPLICATE 로 번역
6. 승인 확정·종착·보상은 실시간·대사가 공유하는 provider 중립 조율 facade가 담당 (→ PR#262). 거부는 주문 상태 재조회 없이 errorCode로 분기 (completePayment가 사유별 코드):
   - 취소 주문·이중결제 → fail-first 보상 (PG cancel)
   - 정상 승인 후 transient 기록 실패(@Version 충돌 포함)는 보상 없이 전파·REQUESTED 유지로 reconcile 위임

# 주문 만료 배치 — 경계
- 미확정 APPROVE 결제(UNKNOWN/stale REQUESTED) 걸린 주문은 BlockingPaymentChecker port 로
  만료 대상에서 제외 → 만료-대사 경합(돈은 빠졌는데 주문 취소) 방지 (order 소유 port·payment adapter 구현)
- 만료 → 주문 취소 → 재고 복구 이벤트 생성 → Outbox relay → Kafka consumer 가 재고 복구
  (이벤트 유실 방지를 위해 in-process 이벤트가 아닌 Outbox + Kafka, 5장 참고)

# 결제 대사 (reconciliation) — 순서·경계 (→ PR#237)
1. 스캔: stale 미확정 결제 후보 (UNKNOWN ≈1분 / REQUESTED ≈15분 하한, ≈6시간 상한 초과는 escalation 제외). `KEEP_WAITING`으로 끝난 건은 `next_reconcile_at` 직교 필드로 고정 backoff를 걸어 스캔 게이트가 제외 → 누적 wait 건이 새 후보를 굶기지 않고 같은 건의 PG 반복 조회를 줄인다 (→ PR#263)
2. [건별, 트랜잭션 밖] PG 이력 조회 (getApprovalHistory — 승인 재요청이 아니라 이미 일어난 결과 확인, 이중과금 방지)
3. 후처리 정책(대상 식별·flow 결정 — src/main 단일 출처)으로 확정/보상/대기 결정
4. 확정·종착·보상은 승인과 같은 provider 중립 facade를 공유 (→ PR#262): 확정은 PG 재호출 없이 SUCCEEDED + Order PAID
5. 이미 CANCELED / 중복: 보상 취소(PG cancel) + FAILED 종착 + NotificationPort 통지. 비중복 PAID는 환불 없이 통지+FAILED (→ PR#262)
6. 건별 독립 트랜잭션으로 처리(한 건 실패가 루프를 멈추지 않음) — 비-INIT 주문 거부는 주문 상태 재조회 없이 errorCode로 분기해 종착시켜 무한 재시도 차단
```

---

## 도메인 책임

각 도메인이 **무엇을 책임지나**와 핵심 설계 결정만 기록한다. 구체 클래스·메서드·상태값·트랙 이력은 코드(`com.commerce.<domain>`)와 ADR이 단일 출처다.

> 공통 원칙(모든 도메인에 적용, → PR#166): cross-aggregate 참조는 객체가 아니라 `Long` ID(또는 값)로 한다. same-aggregate 관계만 객체 참조를 유지한다. cross-aggregate FK는 제거됐고(운영 DB 적용은 별도 결정), 조회는 사용처별 batch composition 또는 컬럼 직접 사용으로 대체한다. 도메인별 적용 세부는 각 `docs/tasks/*-jpa-association-decouple/` 참조.

- **`auth`** — 인증 유스케이스의 owner. 비밀번호 검증, JWT 발급·검증, refresh token 저장. 회원 생성·조회는 `member`에 위임한다.
- **`security`** — HTTP 요청 인증/인가 adapter. 토큰을 검증해 인증 컨텍스트에 싣고, 인가 인터셉터·argument resolver가 이를 사용한다.
- **`product`** — 공개 상품 조회와 관리자 상품 관리(등록·수정·soft delete). 상세는 상품 정보 + 현재 재고를 조합한다.
- **`stock`** — 상품별 재고, 주문 경로의 차감·복구, 관리자 조정, 변경 이력. `Product : Stock = 1:1`. `StockHistory`는 별도 aggregate.
- **`order`** — 주문 생성·취소·만료. 생성은 멱등 키로 중복 방어. 만료는 Spring Batch로 스케줄링하되, 미확정 결제(UNKNOWN/stale REQUESTED)가 걸린 주문은 `BlockingPaymentChecker` port로 만료 대상에서 제외해 만료-대사 경합(돈은 빠졌는데 주문 취소)을 막는다(→ PR#237). 생성 tx 내에서 `CartItemRemover` port로 주문된 항목만 cart에서 제거한다.
  - 사용자 취소: INIT은 재고만 복구하고, PAID는 환불까지 포함한다. 조율 usecase(tx 없음)가 단위작업 service(tx)를 호출해 한 tx 안에 `환불 의도(CANCEL REQUESTED) 영속화 + order.cancel() + 재고 복구`를 원자적으로 커밋하고, 커밋 후 best-effort PG 환불을 실행한다. 실패·불확실·중단은 결제 CANCEL 대사에 위임한다(환불 보장은 영속된 의도+대사). 주문 행은 fetch join 없이 단일 행 락으로 잠가 락 범위를 좁힌다(→ PR#258). order.application이 stock·payment service에 의존(기존 order→stock 패턴과 동일).
- **`cart`** — 장바구니 항목 추가(UPSERT)·조회(최신 가격 재조립·구매 불가 마킹)·수량 변경·삭제. 주문-cart 연동은 `CartItemRemover` port(order 소유)를 cart adapter가 구현해 의존 방향을 보존한다.
- **`payment`** — 결제 예약(reserve)·승인·시도 이력. `naverpay`는 provider 서브패키지(PG 호출과 내부 상태 반영 분리). 도메인은 두 엔티티로 분리(→ PR#205): `PaymentReservation`(결제창 준비물, `RESERVED→USED`) + `Payment`(PG 사건 append-only). 완료 판단은 *성공한 APPROVE 행 존재(EXISTS)* 기반(→ PR#118·PR#205). `status`는 일어난 사실만 담고 후처리 분류는 정책이 계산한다 — 보상·escalation 종착에 새 상태를 두지 않는다(→ PR#236·PR#237). 결과 불명 시 UNKNOWN으로 흔적을 보존하고 해당 주문의 reserve/approve를 `PAYMENT_RESULT_PENDING`(409)로 차단한다. 세부는 `docs/tasks/payment-order-redesign/`, 예외·충돌 처리는 `docs/exception-strategy.md`·`docs/optimistic-lock-design.md`.
  - 승인 확정 조율: 실시간 승인·대사가 공유하는 provider 중립 facade(payment.application)가 승인 사실 확정과 거부 보상을 조율한다. 주문 거부는 `order.completePayment`의 errorCode로 받아 분기하며 주문 상태를 재조회하지 않는다(결제→주문 단방향). PAID 성공-주체 dead 분기를 제거하고 비중복 PAID는 통지+fail로 둔다. gateway resolver·공통 승인 진입 UseCase는 2번째 provider 도입 시 후속이다(→ PR#262).
  - 보상: 이중결제(uk 위반)는 adapter가 도메인 예외로 번역하고 application이 fail-first로 보상한다. 정상 승인 후 transient 기록 실패(@Version 충돌 포함)는 보상 없이 전파하고 approve를 REQUESTED로 두어 대사에 위임한다(완료 우선). 보상 흐름은 tx를 열지 않고 단계별 독립 commit으로 진행하며, 충돌은 tx 경계 밖에서 skip한다(→ PR#97·PR#125·PR#226).
  - 대사(reconciliation): `@Scheduled` 트리거(presentation/scheduler)가 깨우는 서비스가 stale 미확정 결제를 PG **이력 조회**(재요청 아님, 이중과금 방지)로 확정·보상하며, 건별 독립 tx로 한 건 실패가 루프를 멈추지 않는다(→ PR#237). 운영자 통지는 `NotificationPort`로 hook만 확보. 승인(APPROVE)뿐 아니라 **standalone CANCEL**(REQUESTED/UNKNOWN — 사용자 취소 환불 의도가 PG 호출 전/중 중단으로 남은 것)도 대사 대상이다. PG 상태를 조회해 이미 취소면 확정, 아직 승인이면 재시도하며, 확정적 환불 실패(FAILED)는 자동 재시도 대신 escalation으로 통지한다(→ PR#258, 후속 #208).
- **`outbox`** — 재고 복구 이벤트를 Outbox 패턴으로 처리(생성·Kafka 릴레이·소비를 분리).

---

## Application 계층 로깅

Application Service는 유스케이스 완료 시점에 도메인 이벤트 INFO 로그를 남긴다. 메시지는 한국어 본문 + 영어 식별자 필드 + SLF4J placeholder `{}` 형식을 따른다.

```java
log.info("주문 생성 orderId={} memberId={} itemCount={}", orderId, memberId, itemCount);
log.info("결제 승인 완료 merchantPayKey={} provider={} pgPaymentId={} orderId={}", ...);
```

- **Controller**: 로그 없음 (얇은 위임 레이어)
- **Domain**: 로그 없음 (순수 도메인 보호, SLF4J 의존 금지)
- **Infrastructure**: 외부 시스템 경계 요청/소비 시작·완료 (INFO), 실패·retry (WARN/ERROR)

로깅 컨벤션 전체(레벨 기준, 레이어별 책임, 예외 로깅 표준, 민감 정보 마스킹, 메시지 패턴 등)의 단일 진실의 원천은 `docs/logging-conventions.md`다.

### 도메인 이벤트 INFO 로그 적용 범위

도메인 상태를 전환하는 유스케이스 Service는 완료 시점에 INFO 로그를 남긴다. 단순 조회·위임 서비스(상태 전환 없음)는 INFO 로그를 두지 않는다. 어떤 컴포넌트에 적용돼 있는지의 정확한 목록은 코드가 단일 출처이며, 적용 기준·메시지 패턴의 단일 진실의 원천은 `docs/logging-conventions.md`다.

---

## Application 계층 트랜잭션·영속화 컨벤션

Application Service의 트랜잭션 경계와 영속화 호출 방식은 method-level `@Transactional`과 `repository.save(entity)` 명시 호출 원칙을 따른다(→ PR#166). 정책 본문·근거·트레이드오프는 ADR을 단일 출처로 한다.

낙관적 락(@Version) 충돌 처리는 아래 경계 규칙을 따른다. 근거·상세는 `docs/optimistic-lock-design.md`(또는 해당 ADR)를 단일 출처로 한다.

- **트랜잭션 경계 안에서는 충돌을 catch하지 않는다.** 변환된 도메인 예외를 전파시켜 깨끗이 rollback한다. 경계 안에서 catch하면 `REQUIRES_NEW`라도 `UnexpectedRollbackException`이 난다.
- **충돌의 skip/retry/전파 결정(정책)은 트랜잭션 경계 밖**(usecase)에서 한다. 같은 tx 단위작업(`service` 패키지)을 호출 맥락에 따라 전파(→409)·skip(보상)·retry(고경합)로 재사용한다. skip·retry 모두 **한 곳이면 그 Service의 private 메서드, 여러 곳이면 helper**(`OptimisticRetry`, `support`/`common`)로 둔다 — 별도 `policy` 패키지는 만들지 않는다.
- **여러 tx 단위작업을 한 tx로 묶을 때**는 usecase에 `@Transactional`을 달지 않고(외부 호출이 tx에 빨려들고 규칙 위반), 둘을 감싸는 전용 메서드를 `service` 패키지에 만들어 거기에만 tx를 단다(`PaymentApprovalService.succeedApproval` — order+payment 한 tx). 묶음 메서드는 리포지토리/도메인 객체를 직접 다뤄 한 메서드 안에서 완결한다.
- **충돌을 도메인 예외로 변환하는 전용 저장 경로**(`saveUsed`/`saveApproved`/`saveChecked` 류)는 flush 시점을 adapter 프레임 안으로 당기기 위해 영속화 명시 호출 원칙의 기본(`save`, → PR#166) 대신 `saveAndFlush`를 쓴다. 이 변환은 `infrastructure/persistence/` adapter에서만 한다.
- 충돌은 의미가 1:1로 떨어지는 전용 경로(예: reservation use)에서만 의미 코드(`ALREADY_USED`)로 번역하고, 그렇지 않으면 일반 충돌 코드(`CONCURRENTLY_MODIFIED`)로 두고 필요 시 재조회로 상태를 판정한다.

---

## HTTP 요청 처리 Filter

application Filter는 `FilterRegistrationBean`으로 명시 등록하고 `Ordered` 기반 순서를 가진다. `@Component` 자동 등록은 쓰지 않는다(암묵적 등록 순서 의존과 `LOWEST_PRECEDENCE` 충돌 회피).

| 순서 | Filter | 역할 |
|---|---|---|
| 1 | `TraceIdFilter` | 모든 요청에 UUID traceId 발급 → MDC push, 응답 헤더 `X-Trace-Id` 부착 |
| 2 | `AccessLogFilter` | 모든 요청에 시작/종료 접근 로그 |
| 3 | `JwtAuthenticationFilter` | 인증 필요 경로의 Bearer 토큰 검증 → 인증 컨텍스트 저장 |

순서가 구조적으로 중요하다: `TraceIdFilter`·`AccessLogFilter`가 `JwtAuthenticationFilter`보다 바깥(먼저)에 있어, 인증 실패(401) 요청에도 traceId와 접근 로그가 남는다.

인증된 요청의 `memberId`는 인증 Filter가 MDC에 넣고(populate) 이후 도메인 로그와 접근 로그에 자동 포함된다. MDC 정리는 스코프 경계 기준 두 규칙을 따른다: (a) 최외곽 요청 Filter가 요청 끝 `finally`에서 `MDC.clear()`로 스레드 스코프를 통째 비우고(모든 안쪽 스코프가 풀린 지점이라 안전, 스레드 풀 잔류 방지), (b) 최외곽이 아닌 nested 스코프(도메인 유스케이스 키, 비동기 경계 복원분)는 자신이 push한 키만 제거한다(운영 코드에서 nested `MDC.clear()` 금지 — 바깥·형제 스코프 키를 함께 날림). 이 모델은 최외곽 Filter가 MDC를 만지는 가장 바깥으로 유지됨을 전제한다. 구체 정리 규칙은 `docs/logging-conventions.md`가 단일 출처다.

### 비동기 경계 traceId 전파

HTTP 요청 traceId는 스레드 로컬 MDC라 비동기 경계에서 자동 전파되지 않으므로, 경계마다 명시적으로 전달한다(→ PR#149·PR#157):

- **Kafka**: producer가 traceId를 헤더 `X-Trace-Id`에 부착하고 consumer가 MDC로 복원한다.
- **Outbox**: 원본 traceId를 `tbl_outbox_event.trace_id`에 저장한 뒤 relay 시 MDC로 복원해 Kafka 헤더로 전파한다.
- **`@TransactionalEventListener`**: 이벤트 객체에 traceId를 동봉한다. 현재 사용처 0건(`order-idempotency-cache-simplification`에서 제거, 향후 도입 시 갱신).

각 경계의 의사코드 수준 흐름과 운영 정책(스케줄러 로그 제외 범위 등)은 `docs/logging-conventions.md`와 해당 ADR(→ PR#149·PR#157), 관련 task architecture가 출처다.

---

## 예외 처리 정책

예외 처리 정책(find-first, 안전망 계층, 보상 catch 2차 예외 처리)은 `docs/exception-strategy.md`를 참고한다.

로깅 컨벤션(레이어별 로그 책임, 레벨 기준, 예외 로깅 표준, 민감 정보 마스킹 등)은 `docs/logging-conventions.md`를 참고한다.

환경별 appender·encoder·rolling·마스킹 등 로깅 인프라 설정의 단일 진실의 원천은 `src/main/resources/logback-spring.xml`이다. `application-{local,prod,test}.yml`에는 `logging:` 섹션을 두지 않는다.

---

## 아키텍처 규칙 강제 (ArchUnit)

위 레이어·트랜잭션 경계 규칙 중 **기계적으로 검증 가능한 것**은 문서 서술이 아니라 ArchUnit 테스트로 강제한다. 문서는 "어떤 규칙이 왜 있는가"의 포인터만 갖고, "무엇이 강제되나"의 구현은 테스트 코드가 단일 출처다(`src/test/.../architecture/ArchitectureRulesTest`).

강제 대상 규칙(요지):

- 의존 방향: `domain`은 `application`·`infrastructure`·`presentation`·Spring 런타임(`@Transactional`, `KafkaTemplate`, `EntityManager` 등)을 참조하지 않는다. 단 엔티티의 JPA 매핑 애너테이션(`@Entity`/`@Version` 등 선언적 메타데이터)은 허용한다(위 패키지 구조의 결정 근거 참고).
- 트랜잭션 경계: `@Transactional`은 `application.service` 패키지에만 둔다. `application.usecase`·`presentation`에는 두지 않는다(usecase의 private skip 메서드도 tx를 열지 않는다).
- 예외 노출 경계: 구현체에 묶인 구체 예외(`org.springframework.orm`·`org.hibernate`·`jakarta.persistence` 예외)는 `application`·`domain`·`presentation`에서 참조하지 않는다. 특정 구현에 묶이지 않은 DAO 추상 예외(`org.springframework.dao`)는 application이 다뤄도 되나 `domain`은 그조차 참조하지 않는다. presentation은 낙관 락 충돌 예외 계층을 catch하지 않고 전파한다.
- flush 경로: `saveAndFlush` 호출은 `infrastructure.persistence` adapter에서만 한다.
- 진입점 격리: `@Scheduled`·`@KafkaListener`·Spring Batch Job 정의는 `presentation` 하위에만 둔다(application Service에 직접 달지 않는다).
- 기술 누수 차단: `application`은 `KafkaTemplate`·Redis 클라이언트 등 기술 타입을 직접 참조하지 않는다(`application.port` 인터페이스로만).

규칙 본문(정확한 패키지 매칭·예외 허용 목록)은 테스트 코드가, 각 규칙의 근거는 관련 ADR이 단일 출처다.

---

## 저장소 및 인프라 의존성

- 영속 데이터는 MySQL에 저장한다.
- 토큰은 Redis에 저장한다. 주문 멱등성은 Redis 에 in-flight 마커만 저장 (TTL 60초). 멱등성 진실은 `tbl_order.(member_id, idempotency_key)` unique 제약. Redis 장애 시 infra adapter 가 `OrderIdempotencyStoreUnavailableException` 으로 변환, application 이 catch 해 DB unique 안전망 경로로 fallback 진행 (단독 요청 정상 응답 가능).
- 재고 복구 이벤트는 Outbox 모듈을 중심으로 Kafka로 전달한다.
- 외부 결제는 네이버페이 PG 연동 모듈(`payment/naverpay`)을 통해 처리한다.

---

## 인프라 경계

- 이 문서는 현재 백엔드가 의존하는 인프라만 기록한다.
- 실제 인프라 리소스와 운영 설정은 현재 레포지토리 밖에서 관리한다.
