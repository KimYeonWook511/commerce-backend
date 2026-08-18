# 아키텍처

이 문서는 백엔드가 전체적으로 어떻게 구성되고 요청이 어떻게 흐르는지를 보여주는 지도다. "코드를 어디에 둘까"의 배치 규칙은 `docs/package-structure-conventions.md`가 정의하고, 이 문서는 "무엇이 어디에 있고 요청이 어떻게 흐르는가"를 다룬다.

구조는 4계층 레이어드(`presentation → application → domain ← infrastructure`)이며, 모든 의존은 안쪽 domain을 향한다. 계층 간 경계는 port/adapter로 다스린다.

## 패키지 구조

```
src/main/java/com/commerce/
├── common/            # 공통 설정, 예외, JPA base entity, Kafka, 유틸
│   └── security/      # 인증/인가 웹 진입점(필터·인가 인터셉터·인증 컨텍스트·resolver) — leaf, 도메인·auth 무의존
├── auth/              # 인증 유스케이스 (회원가입·로그인·토큰 재발급), JWT 구현(토큰 인증 port 구현), refresh token
├── member/            # 회원 등록·조회
├── product/           # 상품 공개 조회, 관리자 상품 관리
├── stock/             # 재고 차감·복구·관리자 조정, 변경 이력
├── order/             # 주문 생성·취소·만료 배치
├── cart/              # 장바구니 항목 추가·변경·삭제·조회, 주문 시 항목 제거 연동
├── payment/           # 결제 시작·승인 확정·환불·결제사 호출 기록, 결제·환불 대사
│   └── infrastructure/pg/<결제사>/   # 결제사 전용 타입이 사는 유일한 자리
└── outbox/            # Outbox 이벤트 저장·발행
    └── stock/         # 재고 복구 이벤트 생성·릴레이·소비
```

각 도메인은 아래 레이어 구조를 따른다. **레이어마다 나누는 축이 다르다**: application은 *책임*으로, domain은 *엔티티*로, infrastructure는 *외부 대상*으로, presentation은 *진입 방식*으로 나눈다. 전 도메인을 똑같이 잘게 쪼개지 않고, 책임·경계가 실제로 공존하는 도메인(예: payment)만 아래 깊이로 나눈다. 단순 CRUD 도메인은 평평하게 둔다.

```
<domain>/
├── presentation/        # inbound adapter — 얇게 위임 (tx·로직·로그 없음)
│   ├── http/            # Controller, request DTO (외부가 규격을 정하는 진입점만 provider 하위로)
│   ├── scheduler/       # @Scheduled (cron 트리거만, 위임)
│   ├── batch/           # Spring Batch Job/Step 정의 (위임)
│   └── consumer/        # @KafkaListener (메시지 트리거만, 위임)
├── application/
│   ├── usecase/         # orchestrator — tx 없음. 흐름 조립 + 충돌 정책 선택 (skip은 private 메서드)
│   ├── service/         # tx 단위작업 — @Transactional. 충돌 시 전파(catch 안 함)
│   ├── port/            # 외부 시스템 연동 인터페이스(outbound): PG, cache, messaging producer, email 등
│   ├── dto/             # command(=Command DTO), result
│   └── config/          # 운영 설정값을 읽어 domain/policy/의 정책을 빈으로 등록
├── domain/
│   ├── <entity>/        # 엔티티 + 전이 로직
│   ├── repository/      # repository port (도메인 모델 영속성)
│   ├── policy/          # 순수 도메인 정책 (상태로 분류 계산 — tx 모름)
│   └── exception/       # 도메인 예외 (모든 레이어가 의존 → 가장 안쪽)
└── infrastructure/      # outbound adapter — 외부 대상별
    ├── persistence/     # JpaXxxRepository, XxxRepositoryAdapter (예외 변환·saveAndFlush·락)
    ├── pg/<결제사>/     # 결제사 연동 — 결제사 전용 타입은 이 아래에만
    ├── cache/           # Redis 등
    ├── messaging/       # Kafka/RabbitMQ producer 구현
    └── notification/    # 알림 채널 구현
```

배치 기준 요약(상세·근거는 `docs/package-structure-conventions.md` 단일 출처):

- **무엇이 유스케이스를 깨우든(HTTP·cron·batch·message) 진입점은 전부 inbound adapter** → `presentation/` 아래에 두고 얇게 위임한다. `@Scheduled`/배치 Job/`@KafkaListener`를 application Service에 직접 달지 않는다.
- **인터페이스(port)는 안쪽 레이어에, 구현체는 바깥 레이어에** — 항상 다른 레이어로 가른다. 캐시·messaging producer 등 "외부 기능"은 `application/port/` + `infrastructure/`, "도메인 모델 영속성"은 `domain/repository/` + `infrastructure/persistence/`.
- **메시징 방향이 위치를 가른다**: producer(보내기)는 outbound → `application/port/` + `infrastructure/messaging/`, consumer(받기)는 inbound → `presentation/consumer/`.
- **외부 공급자(결제사 등)로 묶더라도 레이어가 바깥, 공급자가 안이다** — `infrastructure/pg/<결제사>/`(→ PR#305). 공급자 이름이 최상위 패키지가 되고 그 안에 네 레이어가 들어가면 레이어 경계가 무너진다. 예외는 **공급자가 규격을 정하는 진입점**뿐이다 — 승인 복귀 경로는 우리가 정할 수 없고 경로가 갈리면 받는 클래스도 갈리므로 `presentation/http/<결제사>/`에 이름이 남는다.
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

## 서비스·유스케이스를 가르는 축과 네이밍

**서비스는 도메인 경계로 가른다**(→ PR#305). 한 도메인 안에서 끝나는 트랜잭션은 한 클래스에 모으고, **다른 도메인까지 바꾸는 것만** 클래스를 따로 둔다 — 같은 클래스에 두면 "여기 이미 저쪽을 만지네" 하고 둘째가 들어와 밖에 닿는 자리라는 뜻을 잃는다. **유스케이스는 조회 조건으로 가른다** — 대상을 고르는 조건이 다르면 다른 유스케이스다. 기준 본문의 단일 출처는 `docs/package-structure-conventions.md`.

- 네이밍은 **역할별 접미사**를 따른다 (→ PR#248):
  - `application/usecase/`(흐름 조립·정책 선택, tx 없음) → **`…UseCase`** (`ConfirmApprovalUseCase`, `ReconcileRefundUseCase`)
  - `application/service/`(tx 단위작업, `@Transactional`) → **`…Service`**. 도메인 안에서 끝나는 전이는 도메인 이름으로(`PaymentService`, `RefundService`), 다른 도메인까지 바꾸는 것은 그 행위 이름으로(`CancelPaidOrderService`) 둔다.
- 접미사가 패키지(역할)와 일치하므로 import·스택 트레이스·로그처럼 패키지 경로가 안 보이는 곳에서도 흐름(UseCase)인지 tx 단위작업(Service)인지 드러난다. 빈 등록 stereotype도 역할별로 가른다 — UseCase는 `@Component`, Service는 `@Service`로 등록해 역할을 한 번 더 드러낸다(둘은 기능 동일).
- 처음부터 지나치게 잘게 나누지 않는다. 클래스가 늘면 서비스끼리 부를 일이 늘고 그 호출이 그대로 의존이 된다. 커져서 읽기 어려워지면 그때 나눈다.
- 단순 작업(조율 없이 tx 한 번)은 usecase를 두지 않고 Controller가 service를 직접 호출한다. usecase는 조율(외부 호출·여러 tx 단계·충돌 정책·격리 루프)이 있을 때만 둔다.

---

## 도메인별 서비스

각 도메인의 application 계층은 위 "서비스 네이밍 원칙"에 따라 유스케이스 단위 Service로 구성된다. 도메인별 서비스의 정확한 전체 목록은 코드(`com.commerce.<domain>.application`)가 단일 출처이며, 특정 기능의 구현 맥락은 해당 task의 `docs/tasks/<task>/architecture.md`를 참조한다. 본 문서는 개별 서비스를 하나하나 다 적지 않는다.

---

## 데이터 흐름

이 섹션은 **코드를 읽어도 한눈에 안 보이는 것** — 책임의 *순서*, *트랜잭션 경계 안팎*, *왜 그 순서인가* — 만 기록한다. 클래스·메서드명·호출 그래프는 코드(`com.commerce.<domain>`)가 기준이므로 적지 않는다(적어 두면 코드가 바뀔 때 문서가 안 맞게 된다).

단순 위임 흐름(상품 공개/관리자 조회, 관리자 상품·재고 관리, 장바구니 추가·조회·수량변경·삭제 등)은 `Controller → service(…Service) → Repository`의 평탄한 위임이라(usecase 없음) 코드가 그대로 출처다. 본 섹션은 경계·순서·보상이 얽힌 흐름만 다룬다.

```
# 결제 시작 — 순서·경계 (클래스명은 코드가 출처)
1. 멱등키 선점 (Redis in-flight 마커). 선점 저장소 장애면 DB 유일 제약 경로로 물러나 그대로 진행
2. 회원 소유 주문 조회 → 같은 (회원, 멱등키) 결제가 이미 있으면 같은 요청인지 대조하고 그 결과를 그대로 응답
3. [트랜잭션 안] 결제 행 INSERT — 이 행이 그 주문의 **활성 슬롯**을 잡아 이중결제를 막는다 (→ PR#305)
   - 동시 중복 요청은 유일 제약이 차단하고, persistence adapter가 도메인 예외로 번역한다
4. 결제창 정보 반환. 커밋 뒤(트랜잭션 밖) 선점 마커 해제
   - 결과를 모르는 결제가 그 주문에 있으면 새 결제 시작을 PAYMENT_RESULT_PENDING(409)로 막는다

# 승인 확정 — 순서·경계 (두 진입점이 공유)
회원의 결제창 복귀 / 대사의 결과 회수 → **같은 확정 자리**. 각자 갖고 있으면 거부 갈래가 두 벌이 되어
한쪽만 고쳤을 때 실시간으로 들어온 결제와 대사가 주운 결제에서 돈이 나가고 안 나가고가 갈린다.
1. [트랜잭션 밖] 결제사 승인 호출(복귀 경로) 또는 이력 조회(대사)
2. 응답에 실린 결제 키·회원이 이 결제의 것인지 대조 — 대조 없이 확정하면 남의 결제로 이 주문이 완료된다
3. [트랜잭션 안] 결제 성공 + 주문 완료. 결제 도메인 밖까지 바꾸는 유일한 자리라 전용 서비스에 둔다
4. 반려(금액 불일치·주문이 그 결제를 받을 수 없음)는 [트랜잭션 안] 결제 종결 + 환불 생성으로 종결한다
   — 결제 도메인 안에서 끝나므로 결제 서비스에 둔다. 되돌릴 금액이 0이면 환불을 만들지 않고 통지만 한다

# 결제된 주문 취소 → 환불 — 순서·경계
1. 취소 멱등키 선점 (Redis in-flight 마커, 유일 제약이 안전망)
2. [트랜잭션 안] 주문 취소 + 환불 의도 생성 + 재고 복구를 한 번에 커밋 (→ PR#305)
   - 환불 생성은 결제의 도메인 메서드다. 한도를 판정하고 누적 환불액을 올려 결제 버전이 바뀐다
   - 환불을 만드는 문이 이 트랜잭션 안에만 있어, 후처리는 "환불 행이 있으면 집행"을 정당성 재확인 없이 믿는다
   - 재고 복구가 맨 뒤다 — 비관 락을 쥔 시간을 짧게 하려고 순서를 고정한다
3. [트랜잭션 밖, 커밋 뒤] 환불 발송. 못 보내도 취소는 성공이고 발송 배치가 이어받는다
4. 같은 취소 멱등키가 다시 오면 앞서 만든 환불의 결과를 그대로 돌려주고 결제사를 다시 부르지 않는다

# 환불 발송 — 순서·경계 (주문 취소·승인 반려·발송 배치·대사가 공유)
1. [트랜잭션 밖] 한 번이라도 부른 건은 이력을 읽어 그 시도가 나갔는지 확인. 나갔으면 여기서 멈춘다
2. [트랜잭션 안] 부르기 직전 전이(응답 대기 · 부른 시각)를 **부르기 전에 커밋**한다
   - 이 커밋이 없으면 둘이 같은 환불을 함께 보내고, 부르는 도중 죽었을 때 한 번도 안 부른 건과 구분되지 않는다
   - 이 커밋에 지면 부르지 않는다 — 진 것이 곧 "다른 쪽이 이미 집었다"는 뜻이다
3. [트랜잭션 안] 호출 기록 INSERT (응답 칸은 비운다). 기록에 실패하면 부르지 않고 다음 주기로 미룬다
4. [트랜잭션 밖] 결제사 환불 호출
5. [트랜잭션 안] 결과 반영. 다시 시도할 수 있는 실패면 시도 번호를 올려 다음 호출이 새 키로 나간다
6. [트랜잭션 안] 호출 기록 UPDATE (응답 시각·결과) — 판정의 정본을 먼저 커밋하려고 마지막에 둔다
   * 결제사 호출 멱등키는 사건 키에 시도 번호를 붙여 파생한다. 결과를 모르는 동안은 같은 키로 보내
     같은 돈이 두 번 나가지 않는다

# 주문 만료 배치 — 경계
- 활성 슬롯을 쥔 결제가 걸린 주문은 BlockingPaymentChecker port 로 만료 대상에서 제외
  → 만료-대사 경합(돈은 빠졌는데 주문 취소) 방지 (order 소유 port·payment adapter 구현).
  판정 기준이 승인 성공이 아니라 활성 슬롯이라, 결제창만 띄운 건도 막힌 것으로 본다 — 빼면 회원이
  결제창에서 인증하는 사이 주문이 만료되고 그 뒤 승인이 나가 돈이 빠졌다 되돌려진다 (→ PR#305)
- 만료 → 주문 취소 → 재고 복구 이벤트 생성 → Outbox relay → Kafka consumer 가 재고 복구
  (이벤트 유실 방지를 위해 in-process 이벤트가 아닌 Outbox + Kafka, 5장 참고)

# 결제·환불 대사 (reconciliation) — 순서·경계 (→ PR#305)
1. [트랜잭션 밖] 후처리 정책이 준 조회 조건(집은 횟수 범위·임계 시각)으로 대상을 고른다.
   조회가 이미 그 조건으로 좁혔으므로 집어 온 행을 다시 분류하지 않는다
2. [건별, 트랜잭션 안] "집었다" 기록(집은 시각·회차)을 **먼저 커밋**한다 — 결과 반영과 묶으면
   호출·응답 처리가 깨질 때 집은 사실까지 롤백되어 회차가 안 오르고, 다시 집는 간격이 첫 값에 머문다
3. [트랜잭션 밖] 결제사 **이력 조회** (승인 재요청이 아니라 이미 일어난 결과 확인 — 이중과금 방지)
4. 결제는 위 "승인 확정" 자리를 그대로 부르고, 환불은 이력에 그 시도가 없을 때 위 "환불 발송" 순서로 이어진다
5. 건별 독립 트랜잭션 — 한 건 실패가 루프를 멈추지 않고 낙관 락도 건별로 걸린다.
   같은 건을 둘이 집으면 낙관 락으로 한쪽만 반영된다
6. **회수 횟수에 상한을 두지 않는다.** 다시 집는 간격만 회차마다 벌어지되 상한에서 멈추고, 오래 끄는 건은
   운영자 통지가 맡는다. 통지는 **보낸 뒤에** 시각을 남긴다 — 먼저 찍으면 못 보낸 건이 다시 대상이 되지 않는다
```

---

## 도메인 책임

각 도메인이 **무엇을 책임지나**와 핵심 설계 결정만 기록한다. 구체 클래스·메서드·상태값·트랙 이력은 코드(`com.commerce.<domain>`)와 ADR이 단일 출처다.

> 공통 원칙(모든 도메인에 적용, → PR#166): cross-aggregate 참조는 객체가 아니라 `Long` ID(또는 값)로 한다. same-aggregate 관계만 객체 참조를 유지한다. cross-aggregate FK는 제거됐고(운영 DB 적용은 별도 결정), 조회는 사용처별 batch composition 또는 컬럼 직접 사용으로 대체한다. 도메인별 적용 세부는 각 `docs/tasks/*-jpa-association-decouple/` 참조.

- **`auth`** — 인증 유스케이스의 owner. 비밀번호 검증, JWT 발급·검증, refresh token 저장. 회원 생성·조회는 `member`에 위임한다. `common.security`가 정의한 토큰 인증 port를 JWT로 구현한다(auth → common 한 방향).
- **`common.security`** — HTTP 요청 인증/인가를 웹 진입점에 강제하는 authz shared kernel. 자기 웹 구성을 스스로 등록하는 **leaf**로, 도메인도 auth도 토큰 기술(JWT)도 모른다. "토큰 → 누구인가"라는 단 하나의 호출만 port로 뒤집어 auth가 구현한다. 인가 어휘(`Role`)를 소유하고 도메인·auth가 이를 위로 의존한다.
- **`product`** — 공개 상품 조회와 관리자 상품 관리(등록·수정·soft delete). 상세는 상품 정보 + 현재 재고를 조합한다.
- **`stock`** — 상품별 재고, 주문 경로의 차감·복구, 관리자 조정, 변경 이력. `Product : Stock = 1:1`. `StockHistory`는 별도 aggregate.
- **`order`** — 주문 생성·취소·만료. 생성은 멱등 키로 중복 방어. 만료는 Spring Batch로 스케줄링하되, 활성 슬롯을 쥔 결제가 걸린 주문은 `BlockingPaymentChecker` port로 만료 대상에서 제외해 만료-대사 경합(돈은 빠졌는데 주문 취소)을 막는다(→ PR#237·PR#305). 생성 tx 내에서 `CartItemRemover` port로 주문된 항목만 cart에서 제거한다.
  - 사용자 취소: INIT은 재고만 복구하고, PAID는 환불까지 포함한다. 조율 usecase(tx 없음)가 단위작업 service(tx)를 호출해 한 tx 안에 `환불 의도 생성 + order.cancel() + 재고 복구`를 원자적으로 커밋하고, 커밋 후 best-effort 결제사 환불을 실행한다. 실패·불확실·중단은 환불 발송 배치와 대사에 위임한다(환불 보장은 영속된 의도 + 후처리). 응답에는 이번 환불액과 남은 취소 가능 금액이 함께 나가고, 같은 취소 멱등키가 다시 오면 앞서 만든 환불의 결과를 그대로 돌려준다(→ PR#305). 주문 행은 fetch join 없이 단일 행 락으로 잠가 락 범위를 좁힌다(→ PR#258). order.application이 stock service와 payment의 리포지토리·유스케이스에 의존(기존 order→stock 패턴과 동일).
- **`cart`** — 장바구니 항목 추가(UPSERT)·조회(최신 가격 재조립·구매 불가 마킹)·수량 변경·삭제. 주문-cart 연동은 `CartItemRemover` port(order 소유)를 cart adapter가 구현해 의존 방향을 보존한다.
- **`payment`** — 결제 시작·승인 확정·환불·결제사 호출 기록, 그리고 결제·환불 대사. 도메인은 **두 aggregate root**로 갈린다(→ PR#305): `Payment`(승인 생명주기와 그 주문의 활성 슬롯) + `Refund`(결제를 식별자로 참조하고 자기 전이 메서드와 자기 낙관 락을 갖는다). 한 결제에 환불이 여러 건 생길 수 있어 부분환불을 담을 수 있다. 결제 예약(reservation)은 사라졌고 그 역할(신원 확인·결제창 준비물)을 결제 행이 물려받았으며, 레거시 결제 경로와 그 테이블은 제거됐다. 승인·환불 호출은 응답 원본까지 **결제사 호출 기록** 한 곳에 남는다. 결과 불명은 UNKNOWN으로 흔적을 보존하고 그 주문의 새 결제 시작을 `PAYMENT_RESULT_PENDING`(409)로 막는다. 결정 근거는 `docs/adr/`, 예외·충돌 처리는 `docs/exception-strategy.md`·`docs/optimistic-lock-design.md`.
  - 결제사 경계: 나가는 인터페이스는 **결제사 이름 없는 port 하나**(승인·환불·이력 조회)이고, 결제사 전용 타입은 `infrastructure/pg/<결제사>/` 안에서만 나온다. 어댑터는 예외를 던지지 않고 결제사 응답을 **됐다 / 모른다 / 다시 시도할 수 있는 실패 / 다시 시도할 수 없는 실패** 넷으로 접어 도메인에 넘긴다 — 두 실패를 가르지 않으면 다시 보낼지 사람에게 넘길지 정할 수 없다. 응답을 받기 전에 전송 계층에서 먼저 갈리며(읽기 타임아웃·5xx는 "모른다"), 어느 응답 코드가 어느 갈래인지는 코드가 단일 출처다. 다시 물어볼 시점 같은 판단은 어댑터 밖(도메인 정책)에 둬 결제사가 늘어도 대사가 안 바뀐다.
  - 트랜잭션 경계: 결제사 호출은 항상 트랜잭션 밖이고, **부르기 직전 상태 전이를 따로 커밋한 뒤에** 부른다. 유스케이스는 흐름만 조립하고 트랜잭션을 열지 않는다. 승인 결과가 결제 도메인 밖(주문)까지 바꾸는 자리만 전용 서비스로 갈라 두고, 결제·환불 안에서 끝나는 전이는 각 도메인의 한 클래스에 모은다. 그 메서드들은 다른 트랜잭션 서비스를 부르지 않고 리포지토리와 도메인 객체를 직접 다룬다.
  - 락: 결제와 환불이 각자 낙관 락을 갖고, 가르는 기준은 **환불 한도가 바뀌는가**다. 환불을 만들 때만 결제 행의 누적 환불액이 올라 버전이 바뀌고 그것이 동시 요청 방어다(진 쪽은 환불까지 함께 롤백된다). 환불 상태 전이는 결제 버전을 올리지 않는다 — 올리면 대사가 도는 동안 회원의 환불 요청이 충돌로 밀린다.
  - 후처리 정책: "언제 다시 보나·언제 알리나·언제 만료하나"를 판정하는 정책은 `domain/policy/`에 두고 외부 의존 없이 시각만 받아 **판정만** 한다. 실행은 대사 유스케이스가 정한다. 운영 설정값을 받아야 해 빈 등록은 `application/config/`가 맡는다.
  - 대사: `@Scheduled` 진입점(presentation/scheduler)이 결제·환불 대사와 통지·만료·발송을 깨운다. 결과를 모르는 건은 결제사 **이력 조회**로 확정하고(재요청 아님, 이중과금 방지), 회수를 멈추지 않는다 — 멈추면 환불은 돈이 안 돌아간 채 한도가 막히고, 승인은 활성 슬롯을 쥔 채 남아 그 주문을 영영 결제할 수 없다. 운영자 통지는 `NotificationPort`로 나가고 보낸 뒤에 시각을 남긴다. 이 진입점들은 결제 후처리 전용 스레드 풀에서 돈다 — 이 도메인만 결제사를 부르므로, 공용 풀에 두면 결제사가 느려질 때 주문 만료와 재고 복구까지 함께 멈춘다.
- **`outbox`** — 재고 복구 이벤트를 Outbox 패턴으로 처리(생성·Kafka 릴레이·소비를 분리).

---

## Application 계층 로깅

Application Service는 유스케이스 완료 시점에 도메인 이벤트 INFO 로그를 남긴다. 메시지는 한국어 본문 + 영어 식별자 필드 + SLF4J placeholder `{}` 형식을 따른다.

```java
log.info("주문 생성 orderId={} memberId={} itemCount={}", orderId, memberId, itemCount);
log.info("승인 확정 paymentId={} orderId={} approvedAmount={}", ...);
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
- **여러 tx 단위작업을 한 tx로 묶을 때**는 usecase에 `@Transactional`을 달지 않고(외부 호출이 tx에 빨려들고 규칙 위반), 둘을 감싸는 전용 메서드를 `service` 패키지에 만들어 거기에만 tx를 단다(승인 확정 — order+payment 한 tx, 결제된 주문 취소 — order+payment+stock 한 tx). 묶음 메서드는 리포지토리/도메인 객체를 직접 다뤄 한 메서드 안에서 완결한다.
- **충돌·유일 제약 위반을 도메인 예외로 변환하는 전용 저장 경로**(`saveChecked` 류)는 flush 시점을 adapter 프레임 안으로 당기기 위해 영속화 명시 호출 원칙의 기본(`save`, → PR#166) 대신 `saveAndFlush`를 쓴다. 이 변환은 `infrastructure/persistence/` adapter에서만 한다 — 어느 제약에 부딪혔는지는 제약 이름을 볼 수 있는 그 자리만 가를 수 있다.
- 충돌은 의미가 1:1로 떨어지는 전용 경로에서만 의미 코드로 번역하고, 그렇지 않으면 일반 충돌 코드(`CONCURRENTLY_MODIFIED`)로 두고 필요 시 재조회로 상태를 판정한다.

---

## HTTP 요청 처리 Filter

application Filter는 `FilterRegistrationBean`으로 명시 등록하고 `Ordered` 기반 순서를 가진다. `@Component` 자동 등록은 쓰지 않는다(암묵적 등록 순서 의존과 `LOWEST_PRECEDENCE` 충돌 회피).

| 순서 | Filter | 역할 |
|---|---|---|
| 1 | `TraceIdFilter` | 모든 요청에 UUID traceId 발급 → MDC push, 응답 헤더 `X-Trace-Id` 부착 |
| 2 | `AccessLogFilter` | 모든 요청에 시작/종료 접근 로그 |
| 3 | `TokenAuthenticationFilter` | 인증 필요 경로의 Bearer 토큰 검증 → 인증 컨텍스트 저장 |

순서가 구조적으로 중요하다: `TraceIdFilter`·`AccessLogFilter`가 `TokenAuthenticationFilter`보다 바깥(먼저)에 있어, 인증 실패(401) 요청에도 traceId와 접근 로그가 남는다.

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

- 의존 방향: `domain`은 `application`·`infrastructure`·`presentation`·Spring 런타임(`@Transactional`, `KafkaTemplate`, `EntityManager`·HTTP/web 타입 등)을 참조하지 않는다. 단 엔티티의 JPA 매핑 애너테이션(`@Entity`/`@Version` 등 선언적 메타데이터)은 허용한다(위 패키지 구조의 결정 근거 참고).
- 트랜잭션 경계: `@Transactional`은 `application.service` 패키지에만 둔다. `application.usecase`·`presentation`에는 두지 않는다(usecase의 private skip 메서드도 tx를 열지 않는다).
- 예외 노출 경계: 구현체에 묶인 구체 예외(`org.springframework.orm`·`org.hibernate`·`jakarta.persistence` 예외)는 `application`·`domain`·`presentation`에서 참조하지 않는다. 특정 구현에 묶이지 않은 DAO 추상 예외(`org.springframework.dao`)는 application이 다뤄도 되나 `domain`은 그조차 참조하지 않는다. presentation은 낙관 락 충돌 예외 계층을 catch하지 않고 전파한다.
- flush 경로: `saveAndFlush` 호출은 `infrastructure.persistence` adapter에서만 한다.
- 진입점 격리: `@Scheduled`·`@KafkaListener`·Spring Batch Job 정의는 `presentation` 하위에만 둔다(application Service에 직접 달지 않는다).
- 기술 누수 차단: `application`은 `KafkaTemplate`·Redis 클라이언트 등 기술 타입을 직접 참조하지 않는다(`application.port` 인터페이스로만).

규칙 본문(정확한 패키지 매칭·예외 허용 목록)은 테스트 코드가, 각 규칙의 근거는 관련 ADR이 단일 출처다.

---

## 저장소 및 인프라 의존성

- 영속 데이터는 MySQL에 저장한다.
- 토큰은 Redis에 저장한다. 주문 생성·주문 취소·결제 시작의 멱등성은 Redis 에 in-flight 마커만 저장 (TTL 60초). 멱등성 진실은 DB unique 제약이다. Redis 장애 시 infra adapter 가 전용 기술 예외로 변환, application 이 catch 해 DB unique 안전망 경로로 fallback 진행 (단독 요청 정상 응답 가능). 결제 도메인은 자기 선점 port를 따로 두어 주문 도메인 것을 주입받지 않는다 — 어느 도메인 것도 아닌 공용 층을 만들지 않는다(→ PR#305).
- 재고 복구 이벤트는 Outbox 모듈을 중심으로 Kafka로 전달한다.
- 외부 결제는 결제사 이름 없는 port 하나로 나가고, 네이버페이 구현체는 `payment/infrastructure/pg/naverpay`에 가둔다.

---

## 인프라 경계

- 이 문서는 현재 백엔드가 의존하는 인프라만 기록한다.
- 실제 인프라 리소스와 운영 설정은 현재 레포지토리 밖에서 관리한다.
