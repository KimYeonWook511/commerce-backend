# 회고록: unknown-reconciliation

## 1. 작업 요약

결과 불명(UNKNOWN)·응답 저장 전 끊긴(stale REQUESTED) APPROVE 결제를 PG 이력 조회로 확정하는 **대사(reconciliation) 배치**(`@Scheduled` 서비스 루프)와, **만료-대사 타이밍 가드**를 구현했다. "주문 만료 취소 후 지연 승인" 경합(#222)에서 돈·주문·재고 정합성을 보장하는 것이 목표다.

두 축으로 막는다.
- **원천 차단(A)**: 만료 배치가 미확정 결제 걸린 주문을 만료 대상에서 제외(`BlockingPaymentChecker` port, order 소유·payment 구현 의존 역전).
- **사후 보상(C)**: A가 뚫리는 극단 경합에서 대사가 승인 확정한 결제의 주문이 이미 취소됐으면 PG 환불로 복구.

대사는 PG에 **승인을 재요청하지 않는다** — 이력 조회(`getApprovalHistory`)로 이미 일어난 결과를 확인해 우리 기록을 맞추고, 보상이 필요할 때만 PG 취소(`cancel`)를 호출한다. 이중과금 방지의 핵심 설계다.

목적: 미확정 결제가 영구히 매달려 주문 만료·재고를 막거나, 만료 취소된 주문에 결제만 성공해 돈이 박제되는 것을 막는다 (#222, #208 batch #1, ADR-040~048).

---

## 2. 결정한 정책 (ADR-040~048)

- **ADR-040**: 대사를 Spring Batch가 아닌 `@Scheduled` 서비스 루프로. 건별 단건 트랜잭션 + PG 호출은 경계 밖.
- **ADR-041**: 분산 락(ShedLock) 미도입. 이중 처리는 멱등성(`uk_payment_approved_order_key` + 상태 전이 가드)으로 방어. 다중 인스턴스 진입 시 후속.
- **ADR-042**: 만료 배치가 미확정 결제 주문 제외(의존 역전, chunk IN 조회로 N+1 회피).
- **ADR-043**: 대사 SUCCEEDED 확정 후 주문 CANCELED면 보상 환불 + `FAILED`+failCode(`ORDER_CANCELED`)로 종착.
- **ADR-044**: 대사 종착에 `PaymentStatus.MANUAL_REVIEW`를 도입하지 않고 ADR-039 준수. status는 사실(REQUESTED/SUCCEEDED/FAILED/UNKNOWN)만.
- **ADR-045**: 통지는 `NotificationPort` 추상화 + no-op 로그. 실제 채널 adapter는 후속.
- **ADR-046**: 후처리 결정 정책을 `src/test`에서 `src/main`으로 승격(단일 출처).
- **ADR-047**: escalation은 새 상태 대신 스캔 시간 윈도우 상한(≈6시간)으로 자동 제외. 하한은 UNKNOWN ≈1분 / REQUESTED ≈15분.
- **ADR-048**: 대사 중 주문이 비-INIT이면 건너뛰지 않고 종착 상태로 전이(무한 재시도 차단).

핵심 관통 원칙: **`status`는 "결제에 일어난 사실"만 담고, 후처리 대상 분류(대사/보상/수동/없음)는 정책이 `(status + failCode + 시간 + CANCEL row)`로 매번 계산한다**(ADR-039 정신). 분류 결과를 status에 박지 않는다.

---

## 3. 주요 발견 및 논의

### MANUAL_REVIEW 도입 → ADR-039 미확인 → 철회

가장 큰 시행착오. escalation·보상 종착을 표현하려 `PaymentStatus.MANUAL_REVIEW`를 새로 도입했다가, 루트 ADR-039("보상된 APPROVE는 FAILED+failCode 유지, 새 상태 도입 기각 — YAGNI·정보 무손실")와 정면 충돌함을 뒤늦게 확인하고 철회했다(Phase 4 전체가 이 정렬 작업). 같은 문제에 이미 내려진 전역 결정을 안 보고 정반대로 간 것이다. 이를 계기로 상태 모델을 "사실 vs 분류" 두 층으로 재정리했다(ADR-044).

### 통합 테스트가 단위 mock이 못 잡은 "돈 박제"를 잡았다

독립 코드리뷰 에이전트가 발견한 버그: 중복 결제 시 승인 확정이 `order.completePayment()` 도달 전 저장 단계의 unique 제약 위반으로 `PaymentException(PAYMENT_DUPLICATE)`를 던지는데, 대사는 `OrderException`만 catch해 결제 예외가 일반 catch로 빠지고 결제가 미확정으로 남아 매 주기 재스캔 → PG 청구된 중복 결제가 영구 미환불(돈 박제). 단위 테스트는 승인 확정을 mock으로 "주문완료 불가 예외"를 강제해 비현실 경로를 검증했기에 못 잡았고, 실 DB 통합 테스트였다면 잡혔다. catch 추가 + 단위 테스트 현실 경로 교정으로 수정.

### 대사 승인 확정 시 키/금액 검증 비대칭

실시간 승인은 PG 응답의 merchantPayKey·금액을 검증하고 불일치 시 보상하는데, 대사는 검증 없이 확정해 불일치 박제 위험이 있었다. 실시간과 대칭으로 검증 추가.

### 멀티 에이전트 코드리뷰가 단일 리뷰어 사각을 메웠다

GitHub 자동 리뷰어(gemini)는 표면 이슈는 잡았지만 위 reachability 버그(돈 박제)는 못 잡았다. 독립 코드리뷰 에이전트(claude)와 codex가 각각 다른 사각을 발견했다 — claude는 reachability·트랜잭션 원자성, codex는 만료 차단 누락·스캔 하한 불일치. 돈·정합성 변경은 자동 리뷰어 하나에 의존하지 않는다.

### codex가 짚은 만료-대사 추가 경합 (P1-2 / P1-3)

- **P1-2**: 만료 차단 쿼리가 UNKNOWN만 봤는데, 미확정 REQUESTED(승인 호출 후 결과 저장 전 중단되어 과금됐을 수 있음)도 차단해야 경합을 막는다 → 차단 대상에 포함.
- **P1-3**: 스캔이 REQUESTED도 1분 하한으로 긁어, 진입 지연(15분) 전 REQUESTED가 `id ASC` 첫 페이지를 차지하고 매 주기 버려져 뒤 후보가 고사(starvation) → REQUESTED 하한을 정책(15분)과 일치시켜 해소. 정책은 이미 분리돼 있었고 스캔 쿼리만 어긋나 있었다.

### 결제-주문 결합이 대사 보상 로직 복잡도로 드러났다

`handleOrderNotCompletable`이 order를 재조회해 CANCELED/PAID/없음/기타 4분기로 환불을 판단하는데, `order.completePayment()`가 이미 INIT 가드로 판단하는 뒤에서 또 재분석하는 판단 중복이다. 결제가 주문 상태에 결합돼 조합이 폭발한 것. 이번엔 동작하는 4분기로 두고, Tell-Don't-Ask·facade 조율로 결합을 끊는 근본 개선은 후속 #240으로 분리했다.

---

## 4. 변경 범위 정리

| 영역 | 변경 내용 |
|---|---|
| `PaymentReconciliationService` | 대사 핵심 — 스캔 → PG 이력 조회 → 정책 결정 → 확정/보상/종착. 중복 결제(`PAYMENT_DUPLICATE`) catch, 키/금액 검증, 비-INIT 종착 전이 |
| `BlockingPaymentChecker` port + `BlockingPaymentCheckerAdapter` | 만료 배치용 미확정 결제 주문 조회(order 소유·payment 구현 의존 역전) |
| `NotificationPort` + `LogNotificationAdapter` | 통지 추상화 + no-op 로그 |
| `payment.postprocess.*` (TargetPolicy/FlowPolicy + enum) | `src/test` → `src/main` 승격(단일 출처) |
| `JpaPaymentRepository` | 대사 스캔 쿼리(UNKNOWN 1분 / REQUESTED 15분 하한, 6시간 상한), 만료 차단 쿼리(UNKNOWN+REQUESTED) |
| `PaymentPostProcessTargetPolicy` | `REQUESTED_STALE_DELAY` public 승격(스캔 하한과 단일 출처) |
| 통합/단위 테스트 | `ReconciliationScanQueryIntegrationTest`, `BlockingPaymentCheckerAdapterIntegrationTest`, `PaymentReconciliationServiceTest` 등 |
| `docs/adr.md` | ADR-040~048 append + 색인 행 |
| `docs/architecture.md` | 대사 흐름 + 결제·주문 도메인 책임 반영 |

---

## 5. 미결 과제

- **#238**: escalation(6시간 초과 미확정) 운영 종착·통지 + 결제 상태 분리 검토. 종착되면 만료 차단도 자동 해제되는 구조라 over-blocking 갭이 닫힌다.
- **#239**: 윈도우 내 누적 starvation/backoff. 코드리뷰에서 추가된 스캔 쿼리 인덱스 부재(풀스캔+filesort)와 그 뿌리인 승인/취소 단일 테이블 결합(테이블 분리 검토)도 함께 기록.
- **#240**: 결제-주문 결합 제거(Tell-Don't-Ask·facade·Saga 보상). `handleOrderNotCompletable` 4분기 판단 중복 해소.

---

## 6. 회고

### 잘된 점

- 돈 정합성을 원천 차단(A)과 사후 보상(C) 이중으로 막았다. A만으로 충분하다는 유혹을 "희박해도 안전장치" 원칙으로 눌렀고, 보상은 검증된 기존 경로를 재사용해 신규 위험을 줄였다.
- 대사가 PG에 승인을 재요청하지 않고 이력 조회로만 확정하는 설계를 일관되게 지켜 이중과금 가능성을 원천 차단했다.
- 통합 테스트가 단위 mock이 통과시킨 "돈 박제" 버그를 잡았다. 비현실 mock 경로의 한계를 실제로 드러낸 사례다.
- 독립 멀티 에이전트 리뷰가 자동 리뷰어 하나가 놓친 reachability·경합 버그를 잡았다.
- `status`를 "사실"로 한정하고 분류를 정책 계산으로 미뤄, 상태 enum을 4개로 단순하게 유지했다.

### 개선할 점

- **상태/도메인 정책 변경 전 루트 최신 ADR을 먼저 봤어야 했다.** task 문서만 보고 ADR-039를 놓쳐 MANUAL_REVIEW를 도입했다 철회하는 비용을 치렀다. 전역 결정이 닿는 작업은 탐색 단계에서 루트 `docs/adr.md`(특히 최신·높은 번호)를 변경 키워드로 먼저 확인해야 한다.
- **돈·정합성 경로의 단위 테스트가 mock으로 비현실 경로를 검증하면 위험하다.** repository 쿼리·상태 전이·경합이 걸린 변경은 통합/동시성 테스트를 명시적으로 함께 돌려야 한다(이번에 batch·concurrency까지 돌려 P1-2/P1-3의 회귀 없음을 확인한 것이 그 교훈의 적용).
- 결제가 주문 상태에 결합돼 대사 보상 로직(`handleOrderNotCompletable`)이 복잡해졌다. 근본 해소(#240)를 미룬 채 동작하는 4분기로 둔 것은 의도된 타협이나, 다음 결제 작업 전 우선순위로 둔다.
