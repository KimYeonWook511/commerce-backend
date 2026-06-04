# Step 5: write-retrospective

## 읽어야 할 파일

먼저 아래 파일들을 읽고 task 전체 결정과 step 1~4 결과를 파악하라:

- `docs/tasks/payment-order-redesign/prd.md`
- `docs/tasks/payment-order-redesign/architecture.md`
- `docs/tasks/payment-order-redesign/adr.md`
- `docs/tasks/payment-order-redesign/db-schema.md`
- `docs/tasks/payment-order-redesign/api-spec.md`
- `docs/tasks/payment-order-redesign/phases/0-redesign/index.json` (step 별 summary)

## 작업

회고록을 작성한다. 5단 구조 — *작업 요약 / 설계 결정 / 발견 / 미결 과제 / 개선 제안*.

경로: `docs/tasks/payment-order-redesign/retrospective.md`

### 1. 작업 요약

- 한 단락 — 무엇을 했고 왜 했는지
- 핵심:
  - 결제 도메인을 두 테이블 (`PaymentReservation` + `Payment`) 로 분리
  - merchantPayKey 발급/저장 책임을 Order → PaymentReservation 으로 이동
  - MySQL InnoDB 의 partial unique index 미지원을 NULL 트릭 (`uk_payment_approved_order_key`, `uk_payment_reservation_reserved_key`) 으로 우회
  - reserve 흐름이 Reservation 생성/재사용, 만료는 마킹 없이 필터로만
  - approve 흐름이 Reservation 역조회 + USED 멱등 흡수 + RESERVED → USED 전이 + Payment(APPROVE) 신규 행
  - UNKNOWN 상태 마킹 + 차단 (해소는 후속)
  - `/payments/ready` → `/payments/reserve` 외부 API rename

### 2. 설계 결정

- task ADR 10 개 (`docs/tasks/payment-order-redesign/adr.md`) 의 *핵심* 만 요약
- 한 ADR 당 1~2 줄
- 본문은 task ADR 로 링크
- 특히 ADR-10 (A→B 전환) 의 *왜* 4 위화감을 짧게 적기

### 3. 발견

- 작업 중 발견한 사실/insight 를 적는다
- 후보 (실제 작업 후 발견된 것 기준으로 채움):
  - **A→B 전환의 가치**: 처음엔 *단일 테이블* 의 단순함이 매력으로 보였지만 실제 step 진행 중 *status 의미 혼재* / *pg_payment_id NULL 허용* / *각 조회마다 `type != 'RESERVE'` 필터* 가 누적 비용으로 드러남. *위화감 4 개* 를 별도 ADR (10) 로 박아둠.
  - **Reservation 의 "한 번 전이" 가치**: `RESERVED → USED` 만 허용함으로써 *재시도 = 새 Reservation* 정신이 키 추적의 멱등성을 단순화. FAILED 후 재시도가 *같은 키* 로 들어오는 모호함이 없어짐.
  - **EXPIRED 상태 제거의 가치**: 만료 마킹을 두면 *누가 언제 마킹할지* 의 박제 위험이 새로 생김. expires_at 필터 만으로 충분 — *유효 RESERVED = status=RESERVED ∧ expires_at>now*. ADR-5 의 박제 자동 복구 정신과 정확히 일치.
  - **만료 후 늦은 redirect 진행의 가치**: PG 가 돈을 뺐는데 우리 30m 정책으로 차단하면 *돈은 빠졌는데 주문 미결제* 박제를 우리가 만드는 셈. expires_at 은 *내부 재사용 관리* 용이지 *PG 결과 거절* 사유 아님.
  - **NULL 트릭 캡슐화**: `Payment.succeed()` 의 `status + approved_order_key` 동시 set, `PaymentReservation.markUsed()` 의 `status + reserved_key` 동시 set 이 깨지면 정합성 무너짐. 도메인 메서드 안에 *반드시* 묶고 도메인 테스트로 단언 박아두기 — *두 캡슐화* 가 task 의 핵심 보호 수단.
  - **`Order` 가 결제 식별자를 모르게 만드는 가치**: ADR-2 의 단일 책임 원칙 적용. 같은 주문의 다중 PG 재시도 / amount 변경 시나리오가 모델에 *자연스럽게* 표현됨 (멱등 setter 강제의 모순 해소).
  - **DB unique 컨벤션 일관성의 가치**: 처음 task 문서에서 `uq_` prefix 를 썼지만 기존 V1~V4 마이그레이션의 `uk_` 컨벤션과 어긋남을 인지한 시점에 일괄 정정. 이런 *작은 컨벤션 위반* 도 review 비용을 키움 — task 문서 작성 단계에서 기존 마이그레이션의 prefix 확인이 안전망.
  - **frontend 미개발 상황의 활용**: workspace 의 *Frontend 미개발* 상태가 외부 API rename (`/payments/ready` → `/payments/reserve`) 의 호환 깨는 변경을 *무비용* 으로 가능하게 함. 의미가 흐려진 이름은 *후속 운영에서 더 비싸지므로* 지금 정정하는 게 옳은 선택.

### 4. 미결 과제

후속 issue 로 분리한 항목 + 본 task 에서 모델만 열어둔 항목:

| 항목 | 상태 | 승격/결정 조건 |
|---|---|---|
| `PaymentReconciliationService` — UNKNOWN 해소 (NaverPay 단건 조회 / 배치 대사) | 후속 task | 즉시 issue 발행 권장. UNKNOWN 차단만 있는 현재 상태는 *영영 안 풀릴 수 있음* 의 단점 보유 |
| 결제 취소 (`CANCEL` 흐름 실제 구현) | 미구현 | 현재 결제 흐름 검증 후 |
| 부분취소 로직 | 모델만 열어둠 (Payment.type=CANCEL 행에 amount) | 부분취소 요구 시 |
| 부분취소 도입 시 클라이언트 idempotencyKey 컬럼 | 미도입 | 부분취소 도입 시점. 자연 멱등키 부족해지는 시점 |
| `PaymentSummary` 집계 테이블 | 안 만듦 | 부분취소 도입 + 잔액 SUM 부담 커질 때 |
| PG 응답 원문 보관 테이블 (`PgTransactionLog`) | 로그 대체 | 분쟁/CS 증가 시 |
| 만료된 Reservation 물리 정리 batch | 안 만듦 | 테이블 비대 시점 |
| ArchUnit 으로 `Payment.approvedOrderKey` setter / `PaymentReservation.reservedKey` setter 가시성 강제 | 안 함 | 도메인 캡슐화 정책 위반 사고 발생 시 |
| workspace `docs/api-contract.md` 의 `/payments/reserve` 반영 | Frontend 세션 책임 | 본 PR 머지 후 Frontend 세션이 갱신 |
| `OrderQueryService.getOrderByMerchantPayKey*` 의존 제거에 따른 클래스 잔존 여부 | step 1 에서 처리 | — |

### 5. 개선 제안

- 본 task 에서 한 *두 도메인 분리* 의 단단함을 다음 결제 도메인 작업의 baseline 으로 활용
- `Payment.succeed()` / `PaymentReservation.markUsed()` 의 *도메인 메서드 안에서 두 필드 동시 set* 패턴이 다른 도메인에도 적용 가능 (예: Order 의 status + paid_at)
- UNKNOWN 같은 *세 번째 상태* (success/fail 외) 가 다른 외부 연동에도 필요할 가능성 (메일 발송, 알림 등) — 본 패턴 차용
- 외부 API 의 의미 변화 시 *내부와 외부 이름을 함께* 정정하는 게 일관성 비용을 낮춤 — *ready → reserve* 결정 패턴을 다른 의미 흐려진 endpoint 정정 시 참고

## Acceptance Criteria

```bash
./gradlew test
```

(이 step 은 문서 작성이라 빌드 영향 없음)

## 검증 절차

1. 위 커맨드 통과 — 회귀 없음 확인
2. `docs/tasks/payment-order-redesign/retrospective.md` 가 5단 구조로 작성됨
3. 미결 과제 표가 *명확한 승격 조건* 과 함께 작성됨
4. *발견* 섹션이 실제 작업 중 발견한 사실 기반으로 채워졌는지 (단순 결정 재진술 아닌지) 확인
5. ADR-10 (A→B 전환) 의 4 위화감이 발견 섹션에 반영됐는지 확인
6. 결과에 따라 step 상태 갱신

## 금지사항

- task ADR 본문을 그대로 복사해 옮기지 마라. 이유: 본 회고는 *요약 + 발견 + 미결* 에 집중. 결정 본문은 task ADR 이 owner.
- *작업 중 만난 사소한 어려움* 을 회고에 길게 적지 마라. 이유: 회고는 *다음 작업자/미래의 나* 에게 가치 있는 정보 위주. 단순 작업 일지 아님.
- 머지된 다른 task 의 회고 / 결정을 이번 회고에서 *수정/비판* 하지 마라. 이유: 머지된 task 폴더 불변 원칙. 의견은 *루트 docs* 의 ADR 후속 노트로만 표현 (step 4).
- workspace 의 `progress.md` 갱신 같은 *Frontend 세션 책임* 의 항목을 회고 안에서 처리하지 마라. 이유: 별도 세션이 owner.
