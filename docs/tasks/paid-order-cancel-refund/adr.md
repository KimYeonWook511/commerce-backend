# Task ADR — PAID 주문 취소·환불

이 문서는 이번 작업에서 새로 채택한 결정의 staging이다(임시 번호 L1·L2…).
harness Stage 8(Root Sync)에서 루트 전역 번호(ADR-XXXX)로 `docs/adr.md`에 append된다.

---

## ADR-L1: PAID 주문 취소의 환불 의도를 주문 취소와 단일 tx로 영속화한다

- **결정**: 사용자가 PAID 주문을 취소하면, 조율 service가 한 RDB tx 안에서
  `CANCEL 결제 REQUESTED(환불 의도) 영속화 + order.cancel() + 재고 복구`를 함께 커밋한다.
  실제 PG 환불 호출은 이 tx **밖**(커밋 이후)에서 best-effort로 실행한다.
- **배경**: PG 취소는 외부 I/O라 주문 취소 tx에 넣을 수 없다(ADR-015, 단계별 독립 commit).
  순진하게 "주문 CANCELED 커밋 → 그 다음 환불 트리거" 순서로 하면, 둘 사이에서 프로세스가
  중단될 때 주문은 취소됐는데 환불 기록이 없는 상태가 된다.
- **이유**: 환불 의도(CANCEL REQUESTED)를 주문 취소와 원자적으로 영속화하면, 어느 시점에
  중단돼도 "환불해야 함"이라는 durable 기록이 남는다. 그 기록을 마무리하는 책임은 ADR-L4(CANCEL
  대사)가 진다. 단일 DB라는 조건을 활용해 이벤트/Outbox 없이 cross-aggregate 정합을 확보한다.
- **트레이드오프**: 조율 service의 단일 tx가 order와 payment 두 aggregate의 테이블을 함께
  쓴다(경계 침범). 결합이 커지면(부분취소·다채널) Outbox 이벤트로 승격해 경계를 다시 분리할 수
  있고, 이때 CANCEL REQUESTED 행이 이벤트와 같은 역할을 하므로 전환이 자연스럽다.
- **고려한 대안**: Outbox 이벤트(주문 tx에 "환불 필요" 이벤트를 원자적으로 적고 consumer가 환불) —
  경계가 더 깨끗하지만 환불 전용 이벤트·consumer를 새로 만들어야 해 현재 스코프에 과하다.

## ADR-L2: 사용자 주도 환불은 정상 승인된 approve 결제를 FAILED로 만들지 않고 CANCEL 레코드로만 표현한다

- **결정**: PAID 취소 환불에서 대상 APPROVE 결제의 SUCCEEDED 상태는 그대로 두고, 환불은 별도
  CANCEL 결제 레코드(append-only)로만 표현한다.
- **배경**: 기존 보상(compensation) 경로의 `runPgCancel`은 "애초에 승인되면 안 됐던" 결제를
  되돌리므로 approve를 FAILED로 마킹한다. 사용자 주도 취소는 승인이 정당하게 성공한 결제를
  환불하는 것이라 의미가 다르다.
- **이유**: 결제 테이블은 사건을 쌓는 불변 원장이다. 승인 성공은 일어난 사실이고, 환불도
  별개의 사실이다. 승인 사실을 훼손하지 않아야 감사·분쟁 대응과 부분취소(미래) 확장에서
  일관된다. "결제취소 했는가"의 판단은 CANCEL 레코드 존재·상태로 한다.
- **트레이드오프**: "이 주문 결제가 지금 유효한가"를 알려면 APPROVE·CANCEL 레코드를 집계해야
  한다. 단일 row를 mutate하는 모델보다 조회가 복잡하지만, append-only 원장의 일관된 비용이다.
- **구현 주의**: 기존 `runPgCancel`을 그대로 호출하면 approve fail이 섞인다. 사용자 환불은
  `runPgCancel`을 쪼개 재사용하지 말고 **별도 실행 경로**로 만든다(공유 코드 변경에 따른 보상
  경로 회귀 위험 회피).

## ADR-L3: 취소 응답은 취소 접수 시점(커밋)에서 끊고, PG 환불 결과는 best-effort로 담는다

- **결정**: 취소 API 응답은 조율 service tx 커밋(주문 CANCELED + 환불 의도 영속화) 시점에
  보장되는 "취소 접수"를 기준으로 반환한다. 커밋 후 인라인으로 best-effort PG 환불을 시도해
  happy path에서는 환불 결과까지 응답에 담되, UNKNOWN/실패는 "환불 처리중"으로 응답하고
  CANCEL 대사(ADR-L4)가 마무리한다.
- **이유**: 취소·환불 보장은 영속된 의도 + 대사가 책임지므로, 사용자가 PG 왕복을 끝까지
  기다릴 필요가 없다. 완전 비동기(@Async·백그라운드) 인프라는 현재 불필요하며, 즉시 응답이
  꼭 필요해지면 후속에서 가산적으로 도입한다.
- **응답 계약**: `OrderCancelResult`에 환불 진행 상태 필드(예: 완료 / 처리중)를 추가한다.
  필드 형태는 step3에서 확정하되, 필드의 존재 자체는 이 결정으로 못 박는다.

## ADR-L4: standalone CANCEL 결제 대사를 신설해 환불을 보장한다

- **결정**: `type=CANCEL ∧ status∈{REQUESTED, UNKNOWN}`인 stale CANCEL 결제를 스캔해 PG 재조회·
  재실행으로 종착시키는 대사 경로를 신설한다. ADR-L1의 환불 의도가 PG 호출 전/중 중단으로 남으면
  이 대사가 집어 마무리한다.
- **배경**: 기존 대사(`ReconcilePaymentUseCase`)는 `type='APPROVE'`만 스캔하고, `CANCEL_RECONCILE`
  target은 라이브 루프에서 SKIP된다(`resolvePostProcessTarget`이 항상 `cancelPayment=null`로 호출).
  즉 standalone CANCEL을 구동하는 경로가 코드상 존재하지 않는다. 기존 CANCEL은 모두 *스캔되는
  APPROVE 실패*에 앵커링돼 구동됐으나, 이번 설계의 CANCEL은 **SUCCEEDED APPROVE**(ADR-L2로 절대
  FAILED 안 됨)에 매달려 어떤 기존 스캔에도 걸리지 않는다. 안전망이 실재하지 않는다.
- **이유**: 정책 뼈대(`PaymentPostProcessTargetPolicy`의 CANCEL 분기,
  `PaymentPostProcessFlowPolicy`의 `CANCEL_RECONCILE` 매트릭스)는 이미 있고 배선만 죽어 있다.
  스캔 쿼리(`findStaleCancelPaymentsForReconciliation`)와 reconcile 루프의 CANCEL 처리 분기만
  추가하면 죽은 정책이 live가 된다. 새 정책·새 PG 로직 없이 기존 cancel 상태전이 service들과
  `PgCanceller`·`getApprovalHistory`를 재사용한다.
- **구동 규칙**: stale CANCEL(REQUESTED/UNKNOWN)에 대해 PG 현재 상태 조회 →
  PG 취소됨 → CANCEL SUCCEEDED 확정 / PG 승인 유지 → 취소 재시도 / PENDING·NOT_FOUND → KEEP_WAITING.
  CANCEL이 6시간 초과로 안 풀리면 APPROVE와 동일하게 escalation 통지한다.
- **FAILED 처리**: 환불이 확정적으로 실패(FAILED)하면 자동 재시도하지 않고 escalation 통지로
  사람에게 넘긴다. FAILED는 "취소 불가 기간·이미 정산·인증 오류" 등 같은 요청 재전송으로 안 풀리는
  거절이며(전송 전 거절이 확실한 분류), 조용히 종착시키면 환불 미집행이 묻힌다. 통지로 surface해
  돈 유실을 막는다. 재시도 N회 + 백오프 같은 자동 재처리 엔진은 #208 item-3으로 분리한다.
  (참고: 전체취소 스코프에선 FAILED CANCEL이 4-col unique 슬롯을 점유해 새 CANCEL 재생성이 막히므로,
  자동 재시도가 아니라 escalation이 맞는 처리다.)
- **트레이드오프**: 대사 스캔이 한 종류(CANCEL) 늘어 PG 조회 부하가 증가한다. APPROVE 스캔과
  동일한 cutoff·페이징 정책을 따른다.

## ADR-L5: CANCEL 생성 멱등은 기존 `(merchantPayKey, provider, pgPaymentId, type)` unique로 이미 하드 보장된다

- **결정**: 사용자 취소 환불의 CANCEL 생성 멱등은 기존 DB unique
  `uk_payment_merchant_pay_key_provider_pg_payment_id_type`(`merchant_pay_key, provider,
  pg_payment_id, type`)가 하드로 보장한다. `getOrCreate`의 find + 이 unique로 동시 생성 시 한쪽이
  unique 위반(안전망 500)으로 떨어져 중복 CANCEL 행이 생기지 않는다. order FOR UPDATE 잠금은 보조
  직렬화이며 멱등의 1차 근거가 아니다.
- **배경**: 초안은 "CANCEL 생성에 DB UNIQUE가 없어 order 락으로만 직렬화(소프트)"로 적었으나
  **오진이었다**. V6 마이그레이션이 만든 위 4-col unique의 `type`에 CANCEL도 포함되어, pgPaymentId당
  CANCEL 행은 하나로 강제된다(APPROVE 행과는 type이 달라 충돌하지 않음). 검토자의 "UNIQUE 없음"
  지적도 같은 오진이었다.
- **이유**: 전체취소 스코프에서는 한 결제당 CANCEL이 하나(전액)이므로 이 4-col unique가 정확한
  멱등 키다. 새 제약을 추가할 필요가 없다.
- **테스트 parity**: 이 unique는 Flyway(prod/local)엔 있으나 Payment 엔티티 `@Table`에는 선언돼
  있지 않아, test 프로파일(H2 `create-drop`, 엔티티에서 스키마 생성)에는 제약이 없다. 멱등 동작을
  H2 테스트로 검증하려면 엔티티에 이 unique를 미러링한다(스키마 변경 아님, prod는 `validate`라 무해).
- **트레이드오프**: 부분취소(한 결제에 CANCEL 여럿, 금액만 다름)가 오면 이 unique로는 표현 불가다.
  그때 취소 요청 단위 고유 키 + "Σ취소 ≤ 승인액" 한도 검증(잠금 하)으로 재설계한다(이번 범위 밖).

## ADR-L6: PAID 취소의 주문 락은 fetch join 단일 쿼리 대신 단일 행 락 + 아이템 별도 로드로 분리한다

- **결정**: 취소 흐름에서 주문을 잠글 때 `select distinct o … join fetch o.orderItems … FOR UPDATE`
  (부모+자식 한 쿼리)를 쓰지 않고, `findByIdAndMemberIdForUpdate`로 **주문 행 하나만 잠근 뒤**
  orderItems는 aggregate를 통해 lazy 로드한다.
- **배경**: PR #258 review에서 distinct+join fetch+FOR UPDATE 조합의 락 안전성이 제기됐다.
- **트레이드오프**:
  - fetch join 1쿼리의 장점은 주문+아이템을 **한 번의 DB 왕복(RTT)** 으로 가져와 네트워크 라운드트립을
    아끼는 것이다.
  - 2단계 분리의 비용은 아이템 로드 쿼리가 한 번 더 생겨 **RTT가 1회 추가**되는 것이다.
  - fetch join+FOR UPDATE의 단점은 락이 부모를 넘어 **자식(order_item) 행까지, 실행계획·인덱스 순서에
    의존해** 잡혀 **락 범위가 넓어지는** 것이다. 추후 order_item에 락을 거는 기능이 추가되면 겹치는
    행을 다른 순서로 잠글 여지가 생겨 데드락 위험이 커진다.
- **판단**: 취소는 사용자 단발 동작(hot path 아님)이라 **RTT 1회 추가는 미미**하다. 반면 **락 범위를
  주문 행 하나로 좁히는 것**은 동시성 안전·미래 데드락 예방에서 큰 메리트다(돈 정합성 직렬화 락이라
  더욱). 그래서 "약간의 RTT < 좁은 락 범위"로 보고 2단계 분리를 택한다.
- **부수 효과**: distinct/NonUniqueResult, distinct+FOR UPDATE의 SQL 거동 의존(passthrough 설정·
  Hibernate 버전), 자식 락 순서의 plan 의존성 같은 모호함이 사라져 **락이 검증 가능하게 확실**해진다
  (H2·MySQL·Hibernate 버전 무관).
- **후속**: fetch join+FOR UPDATE의 자식 락 순서·미래 데드락 가능성 검증은 #259로 분리한다.
