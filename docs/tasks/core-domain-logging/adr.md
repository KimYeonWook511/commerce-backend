# 태스크 ADR

## 결정 제목

- core-domain-logging: P3 핵심 도메인 로깅 보강의 7가지 설계 결정

## 배경

Epic "운영용 로깅 체계 도입"(#133)의 P3 작업에 진입하면서, 컨벤션 §3·§7만으로 명시적 답이 나오지 않는 7가지 결정이 필요했다. P0 컨벤션 문서가 단일 진실의 원천이지만, 실제 코드 베이스에 적용할 때 분기·중복·노이즈에 대한 판단이 필요하다.

## 결정 내용

### 1. task 이름과 브랜치 type
**채택**: `chore/core-domain-logging`
- 이슈 #130 title이 `chore`이고, 운영 가시성 확보 목적이라 컨벤션상 `chore` 정합.

### 2. PR / step 분할
**채택**: 단일 phase / 도메인별 step / PR 1개에 commit 5개 + docs 1
- 도메인별로 commit을 분리해 review 가능성과 부분 revert 가능성을 확보.
- step1(Order+OrderCreateProcessor+Outbox), step2(Stock), step3(Payment), step4(Auth+Member), step5(Product), step6(sync-root-docs), step7(write-retrospective)

### 3. INFO 이벤트 목록 정의 방식
**채택**: prd/architecture에 사전 시그니처 박기
- worker는 시그니처를 그대로 코드에 옮긴다. 메시지 본문·필드 순서·식별자명을 모두 사전 합의.
- 사후 메시지 일관성 보장.

### 4. DEBUG 추가 범위
**채택**: 추가하지 않음
- 컨벤션 §2는 DEBUG를 외부 API/SQL 디버깅·로컬 진단으로 한정. Application 계층은 INFO 중심.

### 5. 단순 조회/위임 5개 서비스 처리
**채택**: 완전 제외 (`@Slf4j` 미부착, 코드 무변경)
- `OrderQueryService`, `MemberQueryService`, `ProductQueryService`, `TokenAuthenticationService`, `OutboxService`
- 컨벤션 §3 "유스케이스 시작·완료" 정신과 일치. dead code 방지.

### 6. OrderCreateService 멱등/신규 분리
**채택**: 신규는 `OrderCreateProcessor.execute()`, 멱등은 `OrderCreateService` 두 분기에 별도 메시지
- 신규(`주문 생성`)와 멱등(`주문 멱등 응답`)은 본질적으로 다른 이벤트
- 작업 대상에 `OrderCreateProcessor`(Component)를 추가

### 7. Member 이중 로그
**채택**: `MemberRegistrationService`("회원 등록 완료")와 `AuthSignUpService`("회원 가입 성공") 둘 다 INFO
- 도메인 entity 영속화 이벤트와 유스케이스 완료 이벤트를 레이어별로 분리

### 추가: AuthSignUp 이메일 처리
**채택**: 이메일 미포함, `memberId={}`만
- signUp 성공 시점은 memberId 발급 후이므로 컨벤션 §5의 "어쩔 수 없는 경우만 부분 마스킹" 예외 대상이 아님.

### 추가: OrderConcurrencyService 8개 메서드
**채택**: 공통 헬퍼에서 `strategy` 필드로 통일 메시지
- 8개 진입 메서드 각각 INFO 1줄 대신, 공통 헬퍼 1곳 + pessimistic-batch 1곳에서 strategy 라벨로 출력
- 8개 strategy 라벨 사전 합의: `without-lock`, `synchronized`, `synchronized-tx`, `reentrant-tx`, `optimistic`, `pessimistic`, `pessimistic-ordered`, `pessimistic-batch`

### 추가: PaymentApprovalService 멱등 분기
**채택**: 신규(`결제 승인 완료`)와 멱등(`결제 승인 멱등 흡수`) 별도 메시지
- 결제는 금전 거래라 멱등 대체 발생 자체가 운영 추적 가치 있음(외부 PG 재호출 신호)

## 근거

- 컨벤션 §3은 Application 계층 책임을 "유스케이스 시작·완료 INFO"로 규정. 단순 조회·위임은 도메인 상태 전환이 아니므로 §3 정합성 위반.
- 컨벤션 §7은 "명사형 + 상태/동사" 패턴을 요구. 신규/멱등을 한 메시지로 합치면 grep 시 멱등 흡수까지 잡혀 운영 노이즈 발생.
- 사전 시그니처 박기는 worker별 메시지 톤 차이를 사전 차단. 일관성을 보장하면 grep·집계가 가능.
- `OrderCreateProcessor`를 이슈 본문 18개 목록에 추가한 이유: 실제 신규 주문 생성 진입점이 Component이고 Service 분류에서 누락된 것뿐, 도메인 책임은 동일.
- Member 이중 로그는 일견 중복이지만, 컨벤션 §3은 도메인과 유스케이스를 분리한다. 단일 호출자 가정에 의존하지 않고 미래 진입점 변경에 견고.

## 결과

### 기대 효과
- 14개 컴포넌트, 약 28개 메서드에 일관된 메시지 패턴 적용
- grep으로 `"주문 생성"`, `"결제 승인 완료"`, `"재고 차감"` 등 비즈니스 이벤트를 traceId·memberId와 함께 추적 가능
- 멱등 흡수와 신규 생성을 메시지 본문으로 즉시 구분 → 외부 PG 재호출, 클라이언트 재요청 패턴 가시화
- Member 이중 로그로 도메인 이벤트와 유스케이스 이벤트 레이어 분리

### Trade-off
- Member 이중 로그가 같은 흐름에서 INFO 2줄을 출력 → 운영 로그 라인 수 증가. 단일 호출자(현재 AuthSignUpService)의 경우 정보 중복으로 보일 수 있음.
  - **수용 이유**: 도메인/유스케이스 분리는 컨벤션 §3 정신과 일치하고, 운영 분석 시 `MemberRegistrationService` 로그만으로 신규 회원 수 집계가 가능.
- `OrderCreateProcessor`(Component)를 이슈 본문 목록 외 추가 → "28개 중 9개" 기준 재해석 필요.
  - **수용 이유**: 신규 주문 생성 INFO를 빠뜨릴 수 없음. ADR에 명시.
- 단순 조회/위임 5개 서비스를 제외 → 이슈 본문 검증 기준("28개 모두 `@Slf4j`")과 충돌.
  - **수용 이유**: dead code 방지 + 컨벤션 §3 정합성. ADR에 명시하여 PR 본문으로 합의.
