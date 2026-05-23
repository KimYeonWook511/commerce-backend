# core-domain-logging 회고

## 배경

이 작업은 이슈 #130으로 진행한 application 계층 핵심 도메인 INFO 로깅 보강 태스크다. 로깅 Epic #133의 P3 작업으로, P0(로깅 컨벤션 문서, 이슈 #127), P1(logback 설정, 이슈 #128), P2(TraceIdFilter, 이슈 #129; MDC `memberId` 통일, 이슈 #140)가 완료된 뒤 진행했다.

P0~P2에서 모든 HTTP 요청에 `traceId`·`memberId`가 MDC에 push되고 JSON 로그에 자동 부착되는 인프라가 준비된 상태였다. 그러나 application 계층의 비즈니스 이벤트 INFO 로그가 비어 있어, 장애 발생 시 "어느 요청이 어느 단계에서 무엇을 했는가"를 추적할 수 없었다.

이슈 #130에서 27개 application Service 중 9개에만 `@Slf4j`가 적용된 현황을 확인했다(잔여 18개). 이번 작업은 그 중 도메인 이벤트가 의미 있는 13개 Service + 신규 주문 생성을 담당하는 `OrderCreateProcessor`(Component) 총 14개 컴포넌트를 대상으로 했다.

작업 전 저장소 상태는 다음과 같았다.

- `TraceIdFilter`·`JwtAuthenticationFilter`가 `traceId`·`memberId`를 MDC에 push — JSON 로그에 자동 부착됨
- application 계층 14개 컴포넌트에 `@Slf4j` 미부착 또는 INFO 로그 부재
- 장애 발생 시 traceId로 요청을 특정할 수는 있지만, 어떤 비즈니스 이벤트가 발생했는지 알 수 없는 상태
- grep 가능한 도메인 이벤트 메시지 없음 (`"주문 생성"`, `"결제 승인 완료"` 등)

---

## 결정사항 요약

본 태스크 내부에서 내린 구현 차원의 결정은 `adr.md`에 기록되어 있다. 회고록에서는 결과만 인용한다.

| 항목 | 결정 | 근거 |
|------|------|------|
| task 이름 / 브랜치 | `chore/core-domain-logging` | 이슈 #130 title이 `chore`, 운영 가시성 확보 목적 |
| PR / step 분할 전략 | 단일 phase / 도메인별 step / PR 1개 (commit 5개 + docs 1) | 도메인별 commit 분리로 review 가능성·부분 revert 확보 |
| INFO 이벤트 사전 시그니처 박기 | prd/architecture에 메시지 본문·필드 순서·식별자명 사전 합의 | worker 간 메시지 톤 차이 차단, grep·집계 가능성 보장 |
| DEBUG 추가 범위 | 추가하지 않음 | 컨벤션 §2는 DEBUG를 외부 API/SQL 디버깅으로 한정. Application 계층은 INFO 중심 |
| 단순 조회/위임 5개 서비스 | 완전 제외 (`@Slf4j` 미부착, 코드 무변경) | 컨벤션 §3 "유스케이스 시작·완료" 정신과 일치. dead code 방지 |
| Order 신규/멱등 분리 | 신규는 `OrderCreateProcessor.execute()`, 멱등은 `OrderCreateService` 두 분기에 별도 메시지 | 신규(`주문 생성`)와 멱등(`주문 멱등 응답`)은 본질적으로 다른 이벤트 |
| Payment 신규/멱등 분리 | `결제 승인 완료`(신규)와 `결제 승인 멱등 흡수`(멱등) 별도 메시지 | 결제는 금전 거래라 멱등 대체 발생 자체가 운영 추적 가치 있음(외부 PG 재호출 신호) |
| Member 이중 로그 | `MemberRegistrationService`("회원 등록 완료")와 `AuthSignUpService`("회원 가입 성공") 둘 다 INFO | 도메인 entity 영속화 이벤트와 유스케이스 완료 이벤트를 레이어별로 분리 |
| AuthSignUp 이메일 처리 | 이메일 미포함, `memberId={}`만 | signUp 성공 시점은 memberId 발급 후. 컨벤션 §5 부분 마스킹 예외 대상 아님 |
| OrderConcurrencyService strategy 라벨 | 공통 헬퍼에서 `strategy` 필드로 통일 메시지 (라벨 8개 사전 합의) | 8개 진입 메서드 각각 INFO 1줄 대신 공통 헬퍼 1곳에서 strategy 라벨로 출력 |

---

## 진행 중 트레이드오프

### 단순 조회/위임 5개 서비스 제외 vs "28개 모두 `@Slf4j`" 이슈 본문 기준

이슈 #130 본문은 27개 Service 중 잔여 18개를 작업 대상으로 열거했다. 엄격히 따르면 5개 조회/위임 서비스(`OrderQueryService`, `MemberQueryService`, `ProductQueryService`, `TokenAuthenticationService`, `OutboxService`)도 포함되어야 한다. 그러나 이 서비스들은 도메인 상태 전환이 없는 단순 조회·위임 역할이라 컨벤션 §3의 "유스케이스 시작·완료 INFO" 기준에 해당하지 않는다. `@Slf4j`를 부착해도 로그를 남길 근거가 없으므로 dead code가 되는 문제가 있다. 이슈 본문 기준과 충돌하지만 ADR에 명시하고 PR 본문으로 합의하는 방향을 선택했다.

### Order 신규/멱등 분리 — 분리 메시지 vs 통일 + status 필드 vs 멱등 무로그

세 가지 방안을 검토했다.

1. **분리 메시지** (채택): `주문 생성`과 `주문 멱등 응답`을 별개 메시지로 구분.
2. **통일 + status 필드**: 하나의 메시지 포맷에 `status=new` 또는 `status=idempotent` 필드를 추가.
3. **멱등 무로그**: 신규 생성에만 로그를 남기고 멱등 응답은 로깅 생략.

방안 2는 grep 시 `"주문 생성 status=new"`와 `"주문 생성 status=idempotent"`를 구분하려면 필드 값까지 포함해 grep해야 하므로 운영 편의가 낮다. 방안 3은 클라이언트 재요청 패턴이 보이지 않아 운영 가시성이 낮다. 방안 1은 메시지 본문으로 즉시 구분되어 grep·집계가 가장 명확하다.

### Member 이중 로그 — 둘 다 vs MemberRegistration만 vs AuthSignUp만

현재 `AuthSignUpService.signUp()`은 `MemberRegistrationService.register()`의 유일한 호출자다. 이 경우 두 곳 모두 INFO를 남기면 같은 흐름에서 INFO 2줄이 출력된다.

- **MemberRegistration만**: 도메인 entity 영속화 이벤트는 남지만 유스케이스 완료 이벤트가 누락된다.
- **AuthSignUp만**: 유스케이스 완료는 보이지만 admin 등록 등 향후 진입점이 추가될 때 도메인 이벤트가 누락된다.
- **둘 다** (채택): 컨벤션 §3은 도메인과 유스케이스를 분리한다. `MemberRegistrationService` 로그만으로 신규 회원 수 집계가 가능하고, 향후 admin 등록 진입점이 추가되더라도 도메인 이벤트가 누락되지 않는다. INFO 2줄 중복은 운영 분석 시 레이어 구분을 위한 의도된 설계다.

### OrderCreateProcessor를 작업 대상에 추가한 근거

이슈 #130 본문의 잔여 18개 목록에 `OrderCreateProcessor`(Component)가 빠져 있었다. `OrderCreateProcessor`는 신규 주문 생성의 실제 진입점이며, `OrderCreateService`는 멱등 검사 후 Processor에 위임하는 구조다. 신규 주문 생성 INFO(`주문 생성`)를 빠뜨리면 가장 중요한 비즈니스 이벤트가 누락된다. Service/Component 분류의 기계적 차이 때문에 이슈 본문에서 누락된 것으로 판단하고 작업 대상에 추가했다. 해당 판단은 ADR에 명시했다.

### DEBUG 추가 여부

외부 HTTP 호출(`NaverPay`, `TossPayments` 등)의 request/response, JPA SQL 쿼리 디버깅, 성능 측정 목적의 타이밍 로그를 DEBUG로 추가할지 검토했다. 컨벤션 §2는 DEBUG를 외부 API/SQL 디버깅·로컬 진단으로 명시적으로 한정하고, Application Service는 INFO 중심으로 규정한다. Application 계층 INFO 로그와 함께 DEBUG를 추가하면 역할 경계가 모호해진다. DEBUG는 본 작업 범위 밖으로 명시적으로 제외했다.

---

## 후속 작업 제안

- **P4 #132 운영 로그 파이프라인**: 본 작업으로 application 계층 INFO 볼륨 추정이 가능해졌다. ELK, Loki 등 로그 수집 파이프라인 구성 시 도메인 이벤트 메시지를 기반으로 대시보드·알림 규칙을 설계할 수 있다.
- **비동기·이벤트 경계 traceId 전파**: `@Async`는 `TaskDecorator`로, Kafka consumer는 헤더에 traceId를 실어 consumer 측에서 MDC로 복원하는 방식으로, `@TransactionalEventListener`는 이벤트 publish 시점의 MDC를 복사해 전달하는 방식으로 각각 다룬다. 비동기 경계에서 traceId가 유실되면 본 작업의 INFO 로그가 동일 traceId로 묶이지 않아 추적이 끊긴다.
- **외부 HTTP 호출 out-bound `X-Trace-Id` 전파**: RestTemplate, WebClient, FeignClient 등에서 외부 시스템(NaverPay, TossPayments 등) 호출 시 현재 MDC의 traceId를 `X-Trace-Id` 헤더로 전달한다. PG사 로그와 자체 로그를 동일 traceId로 연결하면 결제 장애 추적이 크게 개선된다.
- **`MdcKeys` 상수 클래스 추출**: 현재 MDC 키가 `"traceId"`·`"memberId"` 2개다. 향후 `orderId`, `paymentId` 등 키가 늘어나는 시점에 `MdcKeys` 상수 클래스를 추출하면 키 불일치 위험을 제거하고 리팩토링 비용을 줄일 수 있다.
- **`OutboxService` 등 위임 layer 정리 검토**: 단순 위임만 하는 서비스가 얇은 위임 구조로 유지될 가치가 있는지, 아니면 caller에서 직접 port를 호출하는 방식으로 정리할지 검토한다. 이번 작업에서 5개 서비스를 제외하면서 이 구조가 표면화됐다.
