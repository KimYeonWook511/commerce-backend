# Task ADR (staging)

> 이 파일은 이번 Task에서 **새로 채택된** 결정만 쌓는 staging 로그다.
> 루트 ADR을 복사해 오지 않는다. 여기 번호(L1, L2…)는 task 내 임시 번호이며,
> Stage 8(Root Sync)에서 루트 전역 번호(ADR-XXXX)로 다시 부여하며 루트에 append한다.

---

## ADR-L1: UNKNOWN 대사 배치를 @Scheduled 서비스 루프로 구현한다

- 상태: accepted
- supersedes: 없음
- superseded-by: 없음

### 배경

- stale UNKNOWN/REQUESTED 결제를 PG 조회로 확정하는 배치가 필요하다. 실행 메커니즘으로 Spring Batch(주문 만료 배치 선례)와 단순 `@Scheduled` 서비스 루프(outbox 스케줄러 선례)가 후보였다.

### 고려한 대안

- Spring Batch: chunk/retry/skip/listener 인프라를 재사용하고 주문 만료 배치와 대칭이나, PG 외부 호출을 chunk 트랜잭션 경계와 분리하는 처리가 추가로 필요하고 구성이 무겁다.

### 결정 내용

- `@Scheduled` 트리거 + 서비스 루프로 구현한다. stale 후보를 조회한 뒤 건별 단건 트랜잭션으로 처리하고, PG 외부 호출은 트랜잭션 경계 밖에서 수행한다.

### 근거

- 대사는 건별 PG 외부 호출 + 실패 격리가 핵심이라 트랜잭션 경계를 건별로 좁히는 편이 안전하다. outbox 스케줄러와 동일 패턴이라 운영 일관성도 확보된다.

### 결과

- 가볍고 트랜잭션 경계가 명확하다. 대량 처리·재시도 정책이 필요해지면 후속에서 Batch로 승격할 수 있다.

---

## ADR-L2: 대사 배치에 분산 락을 이번엔 도입하지 않는다

- 상태: accepted
- supersedes: 없음
- superseded-by: 없음

### 배경

- 다중 인스턴스에서 스케줄러가 동시에 돌면 같은 대상을 중복 처리할 수 있다. 분산 락(ShedLock) 도입 여부를 결정해야 했다.

### 고려한 대안

- ShedLock 즉시 도입: 다중 인스턴스 중복 실행을 원천 차단하나, 의존성·설정·운영 부담이 추가되고 현재 다른 스케줄러(outbox·만료)와 패턴이 어긋난다.

### 결정 내용

- 이번엔 도입하지 않는다. 이중 처리는 멱등성으로 방어한다(`uk_payment_approved_order_key`가 이중 SUCCEEDED를 차단, 상태 전이는 멱등 가드). 다중 인스턴스 운영 진입 시 ShedLock 도입을 후속 과제로 남긴다.

### 근거

- 현재 모든 스케줄러가 분산 락 없이 단일 인스턴스 전제로 동작한다. 돈 정합성의 1차 안전장치는 분산 락이 아니라 멱등성이며, 멱등성은 분산 락 없이도 이중 처리를 안전하게 만든다.

### 결과

- 구성이 가볍고 기존 패턴과 일관된다. 다중 인스턴스 전환 시점에 ShedLock을 추가해야 한다(후속 과제로 명시).

---

## ADR-L3: 주문 만료 배치는 UNKNOWN 결제가 걸린 주문을 만료 대상에서 제외한다 (A)

- 상태: accepted
- supersedes: 없음
- superseded-by: 없음

### 배경

- UNKNOWN 결제가 걸린 INIT 주문이 만료 취소·재고복구된 뒤 대사에서 그 결제가 SUCCEEDED로 확정되면 정합성이 붕괴한다(`#222`). 만료 배치는 현재 `status=INIT`만 본다.

### 고려한 대안

- (B) 시간 상수 정렬만: 대사 주기(1~5분)를 만료(60분)보다 앞당겨 경합 윈도우를 좁히나, 확률을 0으로 만들지 못해 금전 정합성을 보장하지 못한다.
- 만료 reader 쿼리에서 `tbl_payment` 직접 join: 1쿼리로 끝나나 Order↔Payment aggregate 경계와 ADR-020(ID 참조, cross-aggregate FK 제거)을 위반한다.

### 결정 내용

- 만료 배치가 UNKNOWN 결제 걸린 주문을 만료 대상에서 제외한다(원천 차단). 결제 상태 조회는 order가 소유한 query port를 payment adapter가 `existsUnknownByOrderId` 기반으로 구현하는 의존 역전으로 푼다(`CartItemRemover` 선례). 만료 reader가 chunk의 orderId들을 IN으로 한 번에 조회해 N+1을 피한다.

### 근거

- 충돌을 사후가 아니라 원천에서 막는 편이 돈 정합성에 견고하다. 의존 역전은 order→payment 직접 의존을 만들지 않고 기존 경계·의존 방향을 보존한다. B(시간 정렬)는 별도 작업으로 잡지 않아도 대사 주기를 짧게 두는 기본값으로 자연 충족된다.

### 결과

- 만료 조회에 결제 상태 결합 비용(chunk당 1쿼리)이 추가된다. UNKNOWN이 풀리기 전엔 주문이 만료되지 않으므로, 대사가 UNKNOWN을 결국 FAILED로 확정해 차단을 풀어줘야 정상 만료된다(대사 배치와 짝).

---

## ADR-L4: 대사 SUCCEEDED 확정 시 주문이 이미 CANCELED면 보상 취소·환불한다 (C)

- 상태: accepted
- supersedes: 없음
- superseded-by: 없음

### 배경

- A(원천 차단)가 뚫리는 극단 경합에서, 이미 CANCELED된 주문의 UNKNOWN 결제가 대사에서 SUCCEEDED로 확정될 수 있다. 이때 그냥 succeed로 종결하면 돈은 받고 주문은 취소된 상태로 박제된다.

### 고려한 대안

- A만으로 충분하다고 보고 C를 두지 않음: 원천 차단이 뚫린 경우 자동 회복 경로가 없어 수동 환불에 의존하게 된다. 금전 정합성 원칙(희박해도 안전장치)에 어긋난다.

### 결정 내용

- 대사가 UNKNOWN→SUCCEEDED 확정 후 `Order.completePayment()`가 CANCELED 상태로 거부되면, 보상 취소(환불)를 실행하고 MANUAL_REVIEW로 마킹한 뒤 통지한다. 보상 취소는 기존 `PaymentApprovalCompensationService`/`pgCancel` 경로를 재사용한다.

### 근거

- A로 막고 C로 받치는 belt-and-suspenders가 돈 정합성에 가장 견고하다. 보상 취소 경로는 이미 검증된 기존 코드를 재사용해 신규 위험을 줄인다.

### 결과

- 사후 환불 경로가 대사 flow에 추가된다. 보상 취소 자체가 실패하면 MANUAL_REVIEW + 통지로 운영 개입에 위임한다.

---

## ADR-L5: 대사 자동 처리 상한 초과 상태를 PaymentStatus.MANUAL_REVIEW로 일급 표현한다

- 상태: accepted
- supersedes: 없음
- superseded-by: 없음

### 배경

- 대사 대상이 상한(6시간)을 넘도록 PG가 결론을 못 내면 자동 재시도를 멈추고 운영자 확인 대상으로 승급해야 한다(정책의 MANUAL_REVIEW target). 그러나 `PaymentStatus`에는 해당 상태가 없었다.

### 고려한 대안

- 상태는 UNKNOWN 유지 + 로그/통지만: enum/스키마 불변이나, "자동 처리 포기" 상태가 데이터에 남지 않아 운영 조회가 어렵고 매 대사 주기마다 재방문·중복 통지 위험이 있다.

### 결정 내용

- `PaymentStatus`에 `MANUAL_REVIEW`를 추가한다. UNKNOWN/REQUESTED → MANUAL_REVIEW 종착 전이를 두고, reserve/approve 차단 가드에 MANUAL_REVIEW도 포함한다. `@Enumerated(STRING)` + 길이 32 컬럼이라 DDL 변경 없이 값만 추가된다.

### 근거

- 종착 상태를 일급으로 두면 운영 조회·중복 통지 방지·차단 일관성이 명확해진다. 정책 enum도 이미 MANUAL_REVIEW를 1급으로 다룬다.

### 결과

- 차단 가드와 상태 전이 검증이 MANUAL_REVIEW를 함께 고려해야 한다. 컬럼 스키마 변경은 없고 허용 값만 늘어난다.

---

## ADR-L6: 알림은 NotificationPort 추상화로 두고 adapter는 후속으로 분리한다

- 상태: accepted
- supersedes: 없음
- superseded-by: 없음

### 배경

- C(보상 취소)·MANUAL_REVIEW 승급 시 운영자 통지가 필요하다. 실제 채널(디스코드 웹훅 등)을 이번에 붙일지 결정해야 했다.

### 고려한 대안

- 디스코드 웹훅 adapter까지 이번에 구현: 통지가 즉시 동작하나, 운영 웹훅 URL·환경별 설정·전송 실패 처리가 본 task 범위를 넓힌다.
- 알림 port 자체를 두지 않고 로그만: hook 지점이 없어 후속에서 사용처를 다시 찾아 넣어야 한다.

### 결정 내용

- `NotificationPort`(알림 추상화) + no-op(로그) 구현만 둔다. 통지 hook 지점을 대사/보상 flow에 미리 박고, 실제 채널 adapter(디스코드 웹훅 등)는 별도 후속 이슈로 분리한다. 통지는 commit 이후 best-effort이며 전송 실패가 트랜잭션을 막지 않는다.

### 근거

- 진실 원천은 MANUAL_REVIEW 상태 + ERROR 로그이고 알림은 부가 push다. port로 hook만 확보해두면 채널 교체가 adapter 교체로 끝난다.

### 결과

- 이번엔 통지가 로그로만 남는다. 디스코드 등 실제 채널은 후속 이슈에서 adapter만 추가하면 된다.

---

## ADR-L7: 후처리 결정 정책을 src/test에서 src/main으로 승격한다

- 상태: accepted
- supersedes: 없음
- superseded-by: 없음

### 배경

- 후처리 결정 로직(`PaymentPostProcessTargetPolicy`/`PaymentPostProcessFlowPolicy`/관련 enum)이 `#221`에서 현재 모델 기준으로 재설계됐으나 `src/test/.../postprocess/`에만 존재한다. 실제 대사 배치가 이 정책을 사용하려면 main 코드여야 한다.

### 고려한 대안

- 정책을 test에 둔 채 대사 서비스에서 별도 로직 재구현: 정책 중복·표류 위험. 단일 출처를 깬다.

### 결정 내용

- 정책 클래스와 관련 enum을 `src/main`의 payment 도메인 패키지로 이전하고, 기존 정책 테스트는 main 클래스를 가리키도록 정리한다.

### 근거

- 결정 로직의 단일 출처를 main에 두고 대사 서비스가 이를 의존해야 정책과 실행이 어긋나지 않는다.

### 결과

- 정책이 main 코드의 일부가 되어 대사 서비스가 의존한다. 정책 테스트 위치/import가 갱신된다.
