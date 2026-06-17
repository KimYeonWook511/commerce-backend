# 회고 — PAID 주문 취소·환불

PR #258. 결제 완료(PAID) 주문을 사용자가 취소하면 전액 환불 + 재고 복구가 일어나도록 빈 흐름을
채우고, 이를 뒷받침하는 standalone CANCEL 결제 대사를 신설했다.

## 무엇을 만들었나

- `Order.cancel()` PAID 허용, 조율 service(단일 tx: 환불 의도 영속화 + 취소 + 재고 복구) + 조율
  usecase(커밋 후 best-effort PG 환불) + controller 라우팅.
- 환불 실행 경로(영속된 CANCEL을 PG로 실행), `findApproveSucceededByOrderId`.
- standalone CANCEL 대사(`type=CANCEL` stale 스캔 + reconcile 루프 — 죽어 있던 `CANCEL_RECONCILE`
  정책 분기를 live화) + FAILED escalation.

## 핵심 학습

### 1. "기대고 있는 안전망이 실제로 존재하는가"를 검증하라

초안 설계의 환불 보장은 전적으로 "기존 CANCEL 대사가 영속된 CANCEL REQUESTED를 집어 재시도한다"는
전제에 기대고 있었다. 그런데 코드를 확인하니 **기존 대사는 `type='APPROVE'`만 스캔하고
`CANCEL_RECONCILE`은 SKIP**했다 — standalone CANCEL을 구동하는 경로가 아예 없었다. 안전망이 글로만
존재하고 코드엔 없었던 것이다. 리뷰 에이전트가 이 BLOCKER를 짚었고, 직접 코드로 재확인해 확정했다.
→ 설계가 어떤 인프라에 기댈 때, 그 인프라가 *실제로* 그 일을 하는지 코드로 확인한다. "있을 것이다"는
가정은 돈 흐름에서 치명적이다. 결국 CANCEL 대사 신설(ADR-059)이 본 작업의 핵심 축이 됐다.

### 2. append-only 원장 — 환불은 승인을 수정하지 않고 별도 레코드로

환불을 "승인 행을 취소됨으로 mutate"가 아니라 "별도 CANCEL 레코드 append"로 표현했다(ADR-057).
승인 성공은 불변 사실로 보존돼 감사·분쟁 대응·미래 부분취소에서 일관되고, 우리가 택한 단일 tx 환불
의도(ADR-056)도 이 별도 레코드 구조 위에서만 성립한다. 보상 경로의 `runPgCancel`은 approve를 FAILED로
마킹하므로 그대로 재사용하지 않고 별도 환불 실행 경로를 뒀다(회귀 위험 회피).

### 3. 멱등은 다층 방어 — 그리고 이미 하드였다

환불 멱등은 (1) CANCEL 단일 생성, (2) REQUESTED 상태 가드, (3) 불확실은 UNKNOWN 보존, (4) 대사의
"재전송 전 PG 조회"(query-before-retry), (5) PG의 alreadyCanceled 응답의 5겹으로 막힌다. 논의 중
초안 ADR은 "CANCEL 생성에 DB unique가 없어 order 락으로만 직렬화(소프트)"라 적었으나 **오진이었다** —
기존 `uk_payment_merchant_pay_key_provider_pg_payment_id_type`의 `type`에 CANCEL이 포함돼 이미 하드
보장이었다(ADR-060). 사용자 질문("승인 REQUESTED는 중복 생성되나?")을 따라가다 실제 스키마를 확인해
정정했다. → 멱등 근거를 "추론"하지 말고 실제 제약을 확인한다.

### 4. 락 전략 — RTT 한 번보다 좁은 락 범위 (ADR-061)

PR 리뷰에서 `distinct + join fetch + FOR UPDATE` 락 쿼리가 도마에 올랐다. 리뷰 봇은 "distinct 제거"를
제안했으나, 그건 join fetch의 row fan-out(주문이 아이템 수만큼 복제)을 놓친 것이라 `Optional` 매핑에서
`NonUniqueResultException`을 부른다. 진짜 문제는 join fetch를 락 쿼리에 합치면 락이 자식(order_item)
행까지 plan·인덱스 순서로 번져 **락 범위가 넓어지는 것**이었다. fetch join 1쿼리는 RTT를 아끼지만,
취소는 사용자 단발 동작이라 RTT 1회 추가는 미미한 반면 락 범위를 주문 행 하나로 좁히는 이득(미래
데드락 예방)이 크다고 보고 단일 행 락 + 아이템 lazy 로드로 분리했다. 락 순서·데드락 검증은 #259로 분리.

### 5. 테스트 parity — H2는 엔티티에서 스키마를 만든다

멱등을 받치는 4-col unique는 Flyway(prod/local)엔 있으나 Payment 엔티티 `@Table`엔 없어, test
프로파일(H2 `create-drop`, 엔티티에서 스키마 생성)에는 제약이 없었다. 엔티티에 unique를 미러링해
H2 테스트에서도 멱등이 검증되게 했다(스키마 변경 아님). → "테스트 통과"가 운영 MySQL 거동을 보장하지
않는 silent zone이 있다(이슈 #189와 같은 결).

## 스코프 규율

- **부분취소**: 제외. (mpk, pgPaymentId) 기반 unique로는 표현 불가 — 취소 요청 단위 고유 키 +
  "Σ취소 ≤ 승인액" 한도 검증(잠금 하)이 필요. 멱등키는 재시도 중복을, 잠금+한도 검증은 동시 과다취소를
  막는 별개 장치임을 정리해 다음 작업으로 미뤘다.
- **FAILED 환불 자동 재시도+백오프**: 제외. 이번엔 FAILED를 escalation으로 surface하는 선까지(#208 item-3).
- **fetch join FOR UPDATE 락 순서·데드락 검증**: #259.

## 프로세스 메모

- harness-v4 dynamic workflow로 3 step(dev→AC→review→commit→record)을 완주 후 finalize·push.
- Stage 8 루트 동기화 때 worktree가 아닌 메인 체크아웃(develop)에 문서를 편집한 실수가 있었다.
  메인은 깨끗하게 원복하고 변경분을 worktree(feature 브랜치)로 옮겨 커밋했다. → worktree 작업 중
  루트 문서 편집은 경로에 `worktrees/<branch>/`가 들어갔는지 확인한다.
