# 회고: payment-attempt-unique-key-length

## 발견 경위

- `order-idempotency-cache-simplification` task 진행 중 `./gradlew dockerTest` AC 실행에서 `NaverPayServiceConcurrencyTest` 7/8 실패 발견.
- develop HEAD 깨끗한 상태에서도 동일 재현되어 order 변경과 무관한 payment 도메인 기존 결함으로 분리 인지.

## 진단 흐름

- 최초 가설: race window 발생 → 사전 `find`가 2건 반환 (이슈 #176 초기 본문).
- 검증 과정: 단일 테스트만 분리 실행 + Hibernate DDL 로그 dump → schema 생성 단계의 WARN 로그 발견.
  ```
  Specified key was too long; max key length is 3072 bytes
  ```
- 진단 정정: race window가 아니라 `tbl_payment_attempt`의 unique constraint가 처음부터 schema에 적용되지 않은 상태였음.

## 근본 원인

- `VARCHAR(255)` × 4컬럼 × utf8mb4(4byte) = 4080 bytes > InnoDB 한도 3072 bytes.
- Hibernate 기본 핸들러 `ExceptionHandlerLoggedImpl`이 WARN으로만 로그하고 부팅을 계속해 schema에 unique가 없는 채로 운영.

## 해결

- `PaymentAttempt`의 4개 컬럼 length 명시 (unique key columnNames 순서로 64/32/64/32, 합 768 bytes).
- `hibernate.hbm2ddl.halt_on_error: true`를 local에 적용 (test는 Testcontainer fresh MySQL의 ALTER FK DROP 무해 실패와 충돌 제외, prod는 Flyway 도입과 함께 처리).
- `NaverPayServiceConcurrencyTest`에 `countAttempts == 1` 데이터 invariant 추가. race 발생 자체의 가시화 단언(`anyMatch DataIntegrityViolationException`)은 환경 의존성으로 CI flake 위험이 있어 제거하고, 발생 예외 분류는 기존 `assertRaceOrPaymentError` helper로 검증.
- `NaverPayServiceConcurrencyTest`에 클래스 단위 HikariCP 설정 명시 (`maximum-pool-size=30`, `minimum-idle=10`, `connection-timeout=30000`) — 20 thread + 보상 흐름의 connection 부담 수용.
- `dockerTest`에 `excludeTags "concurrency"` 한 줄 추가 (tag 차원 자체 정리는 이슈 #177로 분리).

## 학습

- `@Column(length=...)`을 명시하지 않으면 multi-column unique constraint에서 silent하게 schema 생성이 실패할 수 있다.
- ddl-auto의 schema 에러는 기본적으로 silent 처리된다. `halt_on_error`가 없으면 운영 schema 정합성이 깨진 채 계속 작동할 수 있다.
- ADR-011 같은 "DB 안전망 위임" 정책은 그 안전망이 실제로 작동하는지 테스트로 가시화해야 한다. `countAttempts == 1` 데이터 invariant가 그 가시화의 환경 독립적 형태.
- "race가 실제로 일어났는지"를 단언하는 건 환경 의존적이라 CI flake 위험이 있다. 강제로 race를 유도하기 위해 `@MockitoSpyBean` + `CountDownLatch` barrier도 시도했으나, `@Repository`(Spring CGLIB proxy) 위에 Mockito spy를 다시 wrap하면 `callRealMethod`의 reflection 경로가 `UndeclaredThrowableException`을 만들어 단언 호환성이 깨졌다. 결정성을 더 정밀하게 강제하려면 service 레이어 hook이나 adapter 분리가 필요한데 본 task scope를 넘는다. 결국 race 발생 가시화는 포기하고 데이터 invariant + 예외 분류 검증으로 안전망을 유지.
- 다른 concurrency 테스트(`OrderConcurrencyServiceTest` 등)는 이미 클래스 단위로 HikariCP 설정을 inline 명시하는 패턴을 쓰고 있었다. 동시성 테스트는 기본 pool size(10)로는 부족하므로 클래스 단위 설정이 컨벤션.
- 이슈 본문의 초기 가설은 디버깅 진행에 따라 정정될 수 있다. 잘못된 가설을 그대로 두면 다음 작업자가 같은 함정을 반복하므로 진단 정정 노트를 본문 상단에 명시했다.
- PR 리뷰의 P2 코멘트(codex)에서 anyMatch 환경 의존성을 지적받았다. 외부 리뷰가 본 작업자가 놓친 환경 가정의 약점을 잡아주었다.

## 향후 트랙

- Flyway 도입 시 prod schema 정합성 점검은 그 흐름에서 처리한다.
- `@Tag` 차원 정리는 이슈 #177에서 진행한다.
- 신규 multi-column unique 도입 시 본 task의 ADR을 참고해 length를 산정한다.
