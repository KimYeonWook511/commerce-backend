# Step 3: naverpay-logging-standardization

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/boundary-logging-standardization/prd.md`
- `/docs/tasks/boundary-logging-standardization/architecture.md`
- `/docs/tasks/boundary-logging-standardization/adr.md`
- `/docs/tasks/boundary-logging-standardization/api-spec.md`
- `/docs/tasks/boundary-logging-standardization/db-schema.md`
- `/src/main/java/com/commerce/payment/naverpay/infrastructure/NaverPayGatewayImpl.java` — 정비 대상
- `/src/main/java/com/commerce/payment/naverpay/exception/NaverPayException.java`
- `/src/main/java/com/commerce/payment/naverpay/infrastructure/code/NaverPayApproveCode.java`
- 이전 step 결과: AccessLogFilter, GlobalExceptionHandler 변경분

태스크 문서만으로 부족한 공통 맥락이 있으면 아래를 추가로 읽는다.

- `/docs/logging-conventions.md` §3 (Infrastructure 레이어 정책), §7 (메시지 작성 규칙)

## 작업

`NaverPayGatewayImpl`의 영문 메시지 7건을 한국어로 통일하고, 표준 라운드트립 패턴을 일괄 적용한다.

### 라운드트립 패턴

세 메서드(`approve`, `getApprovalHistory`, `cancel`) 모두 다음 패턴을 따른다.

```java
// 진입 시 (호출 직전 INFO)
log.info("네이버페이 {} 요청 paymentId={}", "승인", paymentId);

// 정상 응답 시 (INFO)
log.info("네이버페이 {} 응답 paymentId={} code={}", "승인", paymentId, response.getCode());

// 응답 실패 코드 시 (WARN, stack 없음)
log.warn("네이버페이 {} 실패 paymentId={} code={} message={}",
    "승인", paymentId, response.getCode(), code.getDescription());

// 호출 자체 예외 시 (WARN, stack 없음)
log.warn("네이버페이 {} 호출 실패 paymentId={} message={}",
    "승인", paymentId, ex.getMessage());

// 응답 파싱 실패 시 (WARN, stack 없음)
log.warn("네이버페이 {} 응답 파싱 실패 paymentId={}", "승인", paymentId);
```

action은 메서드별로 `"승인"`, `"이력조회"`, `"취소"`를 사용한다.

### 메서드별 변경

#### `approve(String paymentId)`

- `log.info("NaverPay approve request: paymentId={}", paymentId)` → `log.info("네이버페이 승인 요청 paymentId={}", paymentId)`
- `log.warn("NaverPay approve request failed: ...", ...)` → `log.warn("네이버페이 승인 호출 실패 paymentId={} message={}", paymentId, ex.getMessage())`
- 정상 분기에서 응답 INFO 추가: `log.info("네이버페이 승인 응답 paymentId={} code={}", paymentId, response.getCode())` (success 분기 진입 직후)
- `log.warn("NaverPay approve response parsing failed: paymentId={}", paymentId)` → `log.warn("네이버페이 승인 응답 파싱 실패 paymentId={}", paymentId)`
- `log.warn("NaverPay approve failed: ...", ...)` → `log.warn("네이버페이 승인 실패 paymentId={} code={} message={}", paymentId, response.getCode(), code.getDescription())`

#### `getApprovalHistory(String paymentId)`

- `log.info("NaverPay approval history request: ...")` → `log.info("네이버페이 이력조회 요청 paymentId={}", paymentId)`
- 정상 응답 INFO 신규 추가: `log.info("네이버페이 이력조회 응답 paymentId={} code={}", paymentId, response.getCode())`
- `log.warn("NaverPay approval history request failed: ...", ...)` → `log.warn("네이버페이 이력조회 호출 실패 paymentId={} message={}", paymentId, ex.getMessage())`
- 응답 실패 코드 시 WARN 신규: `log.warn("네이버페이 이력조회 실패 paymentId={} code={}", paymentId, response.getCode())`

#### `cancel(String paymentId, int cancelAmount, String cancelReason)`

- `log.info("NaverPay payment cancel request: ...")` → `log.info("네이버페이 취소 요청 paymentId={} cancelAmount={}", paymentId, cancelAmount)`
  - `cancelReason`은 사용자 입력 텍스트로 PII 가능성이 있어 로그에서 제외한다.
- `log.warn("NaverPay payment cancel request failed: ...")` → `log.warn("네이버페이 취소 호출 실패 paymentId={} message={}", paymentId, ex.getMessage())`
- 정상 응답 INFO 신규 추가: `log.info("네이버페이 취소 응답 paymentId={} code={}", paymentId, response.getCode())`
- `log.warn("NaverPay cancel failed: ...")` → `log.warn("네이버페이 취소 실패 paymentId={} code={} message={}", paymentId, response.getCode(), code.getDescription())`

### 테스트

`src/test/java/com/commerce/payment/naverpay/infrastructure/NaverPayGatewayImplTest.java` 신규 작성한다.

- `NaverPayClient`를 Mockito로 mock.
- ListAppender로 `NaverPayGatewayImpl` logger 캡처.
- 케이스:
  - approve 정상 → 요청 INFO + 응답 INFO 2건, 메시지가 한국어인지 확인.
  - approve 실패 코드 → 요청 INFO + 실패 WARN.
  - approve NaverPayException → 요청 INFO + 호출 실패 WARN.
  - cancel/history 각 1건씩 정상 라운드트립 확인.
- 기존 application 통합 테스트(`NaverPayServiceIntegrationTest`, `NaverPayApprovalServiceTest`)가 깨지지 않는지 확인.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - 영문 메시지 7건이 모두 한국어로 변경되었는가?
   - 세 메서드 모두 요청 INFO + 응답 또는 실패 로그가 라운드트립으로 남는가?
   - `cancelReason`이 로그에서 빠졌는가?
   - 메시지 형식이 `네이버페이 {action} {state} paymentId={} ...` 패턴을 따르는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 호출 실패를 ERROR로 변경하지 마라. 이유: ADR 결정 4 — 거래 종료 ERROR는 호출자(`PaymentService`) 책임.
- `PaymentService` 또는 다른 호출자 코드를 수정하지 마라. 이유: 본 작업 범위 밖.
- `cancelReason`을 로그에 남기지 마라. 이유: 사용자 입력 텍스트로 PII 가능성. 컨벤션 §5.
- DEBUG 레벨로 응답 body 로깅을 추가하지 마라. 이유: 본 작업 제외 범위 (PRD 참조).
- 응답 파싱 NPE에 stack trace를 부착하지 마라 (`log.warn("...", ex)` 금지). 이유: 컨벤션 §4 WARN no-stack 정책, 그리고 NPE 메시지는 디버깅 가치가 적다.
- 기존 결과 객체 반환 분기(`failed`, `processing`, `alreadyComplete` 등)를 변경하지 마라. 이유: 본 작업은 로그만 정비한다.
- 기존 테스트를 깨뜨리지 마라.
