# Task ADR (staging)

> 이 파일은 이번 Task에서 **새로 채택된** 결정만 쌓는 staging 로그다.
> 루트 ADR을 복사해 오지 않는다. 여기 번호(L1, L2…)는 task 내 임시 번호이며,
> Stage 8(Root Sync)에서 루트 전역 번호(ADR-XXXX)로 다시 부여하며 루트에 append한다.
> 결정이 없으면 이 파일은 헤더만 두고 비워둔다.
> 탐색만 하고 채택하지 않은 안은 별도 레코드로 만들지 않고, 채택된 결정의 `고려한 대안`에 적는다.

---

## ADR-L1: escalation 종착·통지를 새 status 대신 `escalatedAt` 직교 필드로 표현한다

- 상태: accepted
- supersedes: 없음
- superseded-by: 없음

### 배경

- ADR-044가 escalation의 운영 가시성(통지·종착)과 "결론 났나(확정/미상)/과금됐나(보상)" 축 분리를 #238에서 재검토하도록 미뤘다(ADR-039 재검토 trigger). 6시간 초과 UNKNOWN/REQUESTED 건을 한 번 통지하고 자동 대사에서 영구 제외하려면, "이미 운영자에게 넘겼다"는 표시를 어딘가 기록하고 스캔이 그걸 걸러야 한다. 그 표시를 어디에 둘지 결정이 필요했다.

### 고려한 대안

- **새 status(`ESCALATED`)**: status 하나만 보면 escalation 여부를 즉시 판단할 수 있어 직관적이다. 그러나 ADR-044가 같은 이유로 철회한 `MANUAL_REVIEW`를 되살리는 것이며, escalation돼도 결론은 여전히 **미상(UNKNOWN)**인데 status를 바꾸면 "아직 미상"이라는 사실이 가려진다. "결론 났나" 축과 "처리됐나" 축을 한 값에 뭉개 ADR-039/044가 경계한 바를 반복한다. 기각.
- **별도 테이블(`payment_escalation`)**: escalation 이력·단계·담당자를 풍부하게 남길 수 있다. 그러나 row 생성 + 스캔 시 조인 + 두 테이블 일관성 관리 비용이 든다. 현재 필요는 "한 번 통지 + 종착"뿐이라 과한 분리다(YAGNI). 기각.

### 결정 내용

- `Payment`에 `escalatedAt`(nullable timestamp) 컬럼을 추가한다. escalation 처리 시 **status는 UNKNOWN/REQUESTED 그대로 유지**하고 `escalatedAt`에만 시각을 기록한다.
- `escalatedAt` 기록은 **조건부 UPDATE**로 한다: `UPDATE Payment SET escalatedAt=:now WHERE id=:id AND escalatedAt IS NULL AND status IN (UNKNOWN,REQUESTED)`. 영향 행 수가 1일 때만 통지한다.
- escalation 스캔 쿼리는 `escalatedAt IS NULL`로 미처리 건을 1차 필터링하고, 멱등의 진실 원천은 조건부 UPDATE의 영향 행 수다(동시 race에서도 1행만 갱신 허용).

### 근거

- `status`는 "결제에 일어난 사실"(요청/성공/실패/미상)만 담고, "운영자에게 위임됐나"는 직교 축이다. 이를 별 컬럼으로 분리하면 ADR-039 "status는 사실, 후처리 분류는 정책이 계산"의 정신과 일치한다. 컬럼 1개 + 스캔 조건 1개라는 최소 비용으로 멱등 통지와 종착 표시를 동시에 달성하고, `PaymentStatus` 4개를 유지해 ADR-044를 준수한다.
- 멱등을 조건부 UPDATE의 영향 행 수로 보장하는 것은, ADR-L2가 SUCCEEDED 이중화를 `uk_payment_approved_order_key` unique로 막은 것과 같은 DB 레벨 멱등 패턴이다. `Payment`에 `@Version`이 없어 메모리 객체 가드로는 동시 race(다중 인스턴스/동시 트랜잭션)에서 중복 통지를 막지 못한다.

### 결과

- `PaymentStatus` enum이 4개로 유지돼 상태 모델이 단순하다. 6시간 초과 건의 운영 가시성(통지·종착)이 확보된다. 동시 race에서도 조건부 UPDATE가 1행만 갱신을 허용해 통지가 정확히 1회 발생한다(동시성 테스트로 검증). escalation에 처리 단계·재통지 이력·담당자 같은 부속 데이터가 필요해지면 그때 `payment_escalation` 테이블로 승격할 여지를 남긴다. `escalatedAt` 기록(커밋) 후 통지가 best-effort라, 통지 전송이 유실되면 재통지되지 않는다(진실 원천은 `escalatedAt`, 통지는 부가 push — ADR-L6 정합).
