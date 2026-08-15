# Naming Map: payment-refund-model

> 지금 있는 이름 하나하나를 새 모델에서 어떻게 할지 정한 표. **이름의 정본은 여기다.**
> 구조와 배치는 `architecture.md`, 도메인 어휘는 `ubiquitous-language-glossary.yaml`,
> 접미사·패키지 규칙은 `docs/package-structure-conventions.md`를 따른다.

---

## 왜 이 문서가 있나

이번 작업은 결제 도메인을 **지우고 다시 세우는 규모**다.

```
main   payment 74개
test   payment 45개

payment 밖에서 payment를 쓰는 곳
main       주문 취소 서비스 3줄 · 주문 취소 유스케이스 5줄
test       4개 파일
```

밖과 닿는 면이 여덟 줄이라 통째로 들어내는 것이 실제로 가능하다. **문제는 안쪽이다.**
step이 열하나이고 대상이 119개라, 이름을 step마다 정하면 **같은 개념이 step마다 다른 이름을 받는다.**

그리고 관성은 이미 작동하고 있었다. 설계 문서의 코드 예시에 `findApproveSucceededByOrderId`가
들어와 있었는데, `Approve`는 **이번에 없앨 결제 종류 값에서 온 어휘**다. 없어질 개념의 이름이 새 코드가
쓸 메서드 이름으로 실려 온 것이다.

---

## 판정 원칙

**근거를 잃은 이름은 남기지 않는다.** 이름이 가리키던 개념이 사라지면 그 이름도 사라진다.

| 이번에 사라지는 것 | 그래서 뜻을 잃는 이름 |
| --- | --- |
| 결제 종류 값(승인·취소를 한 테이블에서 값으로 갈랐다) | 이름 속의 `Approve` |
| 취소가 결제의 한 종류라는 구도 | 이름 속의 `Cancel`(환불을 가리키던 것) |
| 예약이라는 별도 엔티티 | 이름 속의 `Reservation`·`Reserve` |
| 결제사가 최상위 묶음이라는 배치 | 네 계층 밖에 있던 결제사 이름 |

**"이름만 바꿔 남기는 것"은 옮긴 것이 아니다.** 자리와 책임이 그대로면 이름을 고쳐도 같은 문제가 남는다.

**옛 이름을 새 코드에 쓰지 않는다.** 익숙하다는 이유로 끌고 오면, 남는 것은 사라진 개념을 가리키는
표지판뿐이다.

**동작을 그대로 두는 것과 이름을 그대로 두는 것은 다른 결정이다.** 밖에서 보이는 동작을 유지하라는
지시가 안쪽 이름을 유지하라는 뜻이 아니다.

**`pg` 접두어는 "결제사가 준 값"을 뜻한다.** 우리가 만든 값과 결제사가 만든 값이 이름만으로 갈려야
한다 — 둘을 섞으면 판정에 남의 값을 쓰거나 남의 값에 우리 규칙을 걸게 된다.

| 우리가 만든 값 | 결제사가 준 값 |
| --- | --- |
| `paymentKey` 결제 키 | `pgPaymentId` 결제사 결제 번호 |
| `refundKey` 환불 사건 키 | `pgTransactionId` 결제사 거래 번호 |
| `idempotencyKey` 밖에서 받은 요청 키 | — |
| `pgIdempotencyKey` **우리가 만들어 결제사에 보내는 값** | |

- **마지막 줄이 예외처럼 보이지만 아니다.** 그 값은 우리가 짓지만 **우리 도메인이 무엇을 식별하는 데
  쓰지 않고, 결제사가 중복을 판단하라고 채워 주는 값**이다. 기준은 "누가 만들었나"가 아니라
  **"그 값이 누구의 식별 체계에 속하나"**다. `paymentKey`·`refundKey`도 결제사에 보내지만 그것은
  우리가 우리 것을 가리키려고 만든 값이라 접두어가 없다.
- **`pgTransactionId`는 결제와 환불 양쪽에 있다.** 결제 행에서는 승인이라는 거래를, 환불 행에서는
  취소라는 거래를 가리킨다. 둘 다 결제사가 발급한다.
- **이름이 겹치면 접두어 없는 쪽이 우리 것이다.** 결제사 명세에 `paymentId`가 있다고 해서 우리 대리키를
  `paymentId`로 적으면, 읽는 사람이 결제사 값으로 본다. 자기 aggregate의 대리키는 `id`다.

---

## 도메인

| 지금 | 이후 | 근거 |
| --- | --- | --- |
| `Payment` | **`Payment`** (뜻이 바뀐다) | 행 하나가 **결제 시도 하나**가 되고, 환불을 만들 때 한도를 판정한다 |
| `PaymentStatus` | **`PaymentStatus`** (값이 일곱으로) | `READY`·`IN_PROGRESS`·`UNKNOWN`·`SUCCEEDED`·`FAILED`·`REJECTED`·`EXPIRED` |
| `PaymentType` | **삭제** | 환불이 별도 aggregate가 되어 종류로 가를 것이 없다 |
| `PaymentFailCode` | **둘로 갈린다** — `PaymentCloseCode` · `RefundReviewCode` | 지금은 한 목록을 결제·환불이 공유하는데 값의 절반이 서로에게 의미가 없다 |
| `PaymentProvider` | **`PaymentPg`** | 우리가 API로 부르는 상대는 언제나 PG이고, `pg_` 접두어가 이미 다수다 |
| `PaymentReservation` | **삭제** | 결제 행이 시작 시점에 생기므로 예약이 하던 일을 물려받는다 |
| `PaymentReservationStatus` | **삭제** | 위와 같다 |
| `repository/PaymentReservationRepository` | **삭제** | 위와 같다 |
| `PaymentRepository` | **`PaymentRepository`** (조회 메서드는 전부 다시 짠다) | 아래 "리포지토리 메서드" 참고 |
| `exception/PaymentErrorCode`·`PaymentException` | 그대로 | 도메인 예외 규약은 안 바뀐다 |

**새로 생기는 것**

```
Refund              환불 사건. aggregate root. 결제를 식별자로 참조한다
RefundStatus        REQUESTED · IN_PROGRESS · UNKNOWN · SUCCEEDED · MANUAL_REVIEW
RefundReason        환불이 왜 생겼나
RefundRequester     누가 요청했나
PgCallLog           결제사 호출 기록. 둘 다의 밖
PgCallType          승인이냐 환불이냐
PgErrorType         응답을 못 받았을 때 무슨 일이 있었나
policy/             후처리 판정 정책이 앉는 자리
```

### 리포지토리 메서드

**`Approve`·`Cancel`이 들어간 메서드 이름을 전부 다시 짠다.** 종류 값이 사라지므로 그 단어가
가리킬 것이 없다.

| 지금 | 이후 |
| --- | --- |
| `findApproveSucceededByOrderId` | **성공한 결제를 회원과 주문으로 찾는다**는 뜻의 이름. **회원을 이름에 넣는다** — 소유 확인이 이 조회를 타고 흐르므로 이름에서 빠지면 호출부가 조건을 빠뜨려도 드러나지 않는다 |
| `findCancelPayment` | 환불은 별도 aggregate라 **환불 리포지토리로 간다** |
| `findStaleCancelPaymentsForReconciliation` | 환불 대사 대상 조회. **환불 쪽으로 간다** |
| `findCancelEscalationCandidates` | 통지 대상 조회. **환불 쪽으로 간다** |

- **환불 리포지토리에 합계 조회를 두지 않는다.** 한도 판정이 결제 행의 누적 환불액만 읽기 때문이다.
  대신 **기존 사건을 찾는 조회가 둘** 필요하다 — 요청 키로(회원 요청), 요청자로(승인 반려).
- **그 결제에서 아직 결과를 모르는 환불들을 돌려주는 조회가 하나 더 있다.** 환불 가능 금액 초과 거절을
  이력으로 가를 때, 그 어긋남을 설명할 후보가 이것이다. **합계를 세지 않는다** — 돌려주는 것은 이력의
  취소 항목과 맞춰 볼 사건 목록이고, 한도는 여전히 결제 행만 읽는다.
- **결제 리포지토리에 결제 키로만 찾는 조회가 생긴다.** 승인 응답의 결제 키가 어긋났을 때 그 주인의
  결제 행을 회수하는 경로다. **회원으로 좁히지 않는 유일한 조회**이며, 밖에서 부를 수 있는 진입점이
  아니다. 회원 요청 경로가 쓰는 조회는 이것과 별개로 **회원을 이름에 넣어** 따로 둔다 — 하나로 합치면
  승인 경로의 소유 확인이 조용히 사라진다.
- **결제사 번호로 결제 행을 찾는 조회는 만들지 않는다.** 그 값에는 유일 제약이 없어 여럿이 돌아오고,
  회수 대상 행에는 아직 번호가 없어(지금 심으려는 참이다) 늘 빈 결과가 나온다. 방향은 **결제 행에서
  번호를 꺼내 이력을 읽는 쪽 하나**다.
- **주어진 주문들 중 활성 슬롯을 쥔 결제가 있는 것을 돌려주는 조회가 생긴다.** 주문 만료 배치가 무엇을
  건너뛸지 판정하는 자리이며, 주문이 선언한 port의 구현이 이것을 부른다. **회원으로 좁히지 않는다** —
  배치가 부르는 자리라 요청자가 없다.

**주문이 선언한 port는 이름을 바꾸지 않는다.** `BlockingPaymentChecker`는 **주문의 어휘**다 — 자기
만료 배치가 무엇에 막히는지를 주문이 부르는 이름이고, port 이름은 그것을 쓰는 쪽이 정한다. 우리가
구현하면서 결제의 말로 바꾸면 주문 코드까지 함께 고쳐야 하고, 그것은 이번 범위가 아니다. **어댑터가
바로 그 번역을 맡는다** — 주문의 말로 물어오면 결제의 말(활성 슬롯)로 찾아 돌려준다.

**저장 메서드는 무엇에 기대는지로 가른다.** 셋 다 안에서는 `saveAndFlush`를 부르지만 **부르는 쪽이 그것을
쓰는 이유가 다르고, 이름이 그 이유를 말해야 한다.**

| 이름 | 무엇에 기대나 | 어디서 |
| --- | --- | --- |
| `save` | **아무것도.** 구현은 셋이 같지만 이 자리는 그것에 기대지 않는다 | 충돌을 그대로 전파해 409로 내보내는 경로 |
| `saveChecked` | **낙관 락 충돌을 이 호출 안에서 확정하는 것** | 충돌을 잡아 물러나야 하는 경로 |
| `saveFlushed` | **이 저장이 뒤 저장보다 먼저 DB에 나가는 것** | 앞 결제의 슬롯을 비우고 새 결제에 같은 주문을 심을 때 |

- **`saveFlushed`가 필요한 이유**: Hibernate는 코드에 적힌 줄 순서로 쓰기를 내보내지 않고 **삽입을
  갱신보다 먼저** 내보낸다. 그래서 슬롯을 비우는 갱신을 먼저 적어도 **새 결제의 삽입이 앞질러 도착해**
  아직 차 있는 슬롯에 부딪힌다. 비우는 쪽을 먼저 flush해야 한다.
- **왜 이름을 가르나**: 셋은 안이 같아서 **무엇에 기대는 자리인지 이름 말고는 드러나지 않는다.**
  "여기는 충돌을 안 잡네" 하고 평범한 `save`로 바꾸는 순간 **쓰기 순서가 조용히 깨져** 유일 제약에
  걸린다. 주석은 지워지지만 이름은 남는다.
- **한 자리가 둘 다 필요하면 `saveChecked`를 쓴다.** 그것이 이미 flush를 당겨오므로 순서도 함께 얻는다.
  **다만 그 자리에 순서도 걸려 있다는 것을 주석 한 줄로 남긴다** — 나중에 충돌 처리가 바뀌어
  `saveChecked`가 빠질 때 순서까지 함께 사라지는 것을 막는다.

**설계에서 이미 정해진 이름** — step 문서와 `architecture.md`가 코드 예시로 못 박아 둔 것이다.

| 이름 | 무엇 | 왜 이 이름인가 |
| --- | --- | --- |
| `Payment.openRefund(existing, amount, reason, idempotencyKey)` | 회원 요청으로 환불을 만드는 도메인 메서드 | 같은 요청 키면 **그것을 그대로 돌려준다(누적액을 다시 더하지 않는다).** 없으면 만들고 **한도를 판정하고 누적 환불액을 더한다.** 판정에 필요한 값이 결제 안에 다 있어 합계를 받지 않는다. 넘겨받은 기존 사건이 **이 결제의 것인지 먼저 대조한다** — 조회를 한 번 잘못 좁히면 남의 환불이 이번 요청의 결과로 돌아간다 |
| `Payment.openRejectionRefund(existing, reason)` | 승인 반려로 환불을 만드는 도메인 메서드 | **금액을 받지 않는다** — 그 시점의 남은 한도(승인 금액 − 누적 환불액)를 결제가 스스로 계산하고 **누적 환불액도 함께 더한다.** **0이면 만들지 않고 비어 있는 결과를 돌려주며, 그때는 호출자가 결제 전이도 하지 않는다** — 데이터가 어긋난 경우에만 나오는 상태다. 금액은 상태에서 파생되지만 **사유는 파생되지 않아**(주문 취소인가 금액 불일치인가) 인자로 받는다 |
| `Payment.reject(closeCode, closeDetail)` | 승인 반려. 결제를 종결한다 | 환불 생성은 위 메서드가 맡으므로 이 메서드는 결제만 바꾼다. **이미 종착이면 전이를 건너뛰고 그 사실을 돌려준다** — 전이가 안 됐다고 되돌릴 근거까지 롤백하면 이미 나간 돈이 그대로 남는다. 그 조합이 정상이 아니라는 것은 호출자가 커밋 뒤에 알린다 |
| `Payment.recordApproval(approvedAmount, pgTransactionId)` | 결제사가 승인한 사실을 결제 행에 남긴다 | **반려가 환불을 열기 전에 먼저 부른다.** 남은 한도가 이 값에서 나오고, 결제가 이미 종착이라 전이를 건너뛰는 경로에서도 이 값이 있어야 얼마를 돌려줄지 정해진다. 성공·외부 취소 종결은 자기 전이 안에서 이것을 거친다 |
| `Refund.open(...)` | 환불의 정적 팩토리 | **생성 불변식이 사는 유일한 관문이다** — 금액이 0보다 큰지, **요청자를 가리지 않고 요청 키가 있는지**를 여기서 지킨다. 위 두 메서드는 **반드시 이것을 통해** 환불을 만든다. 결제 안에서 값을 조립하면 관문이 둘로 갈린다 |
| `Refund.attemptKey()` / `Refund.ownsHistoryEntry(...)` | 결제사에 보낼 시도 키를 만들고, 이력 항목이 이 사건의 것인지 판정한다 | **만들기와 맞추기가 한 규칙에 묶여야 한다.** 접두어 구분자가 양쪽에서 갈리면 **이력에서 우리 시도를 못 찾고 그 사실이 조용히 지나간다** — 그러면 이중환불 방어가 무력해진다 |
| `Payment.reclaim(pgPaymentId)` | 남의 승인 응답으로 드러난 이 결제의 승인을 회수한다 | 번호를 심고 결과 불명으로 둔다. **확정하지 않는다** — 주문 상태에 따라 갈리는 판정은 승인 확정 흐름의 몫이다 |

---

## application/service — tx 단위작업

지금은 **전이 하나에 클래스 하나**로 열두 개가 있고, 이름에 종류 값이 박혀 있다. 새 모델에서는 전이가
열넷으로 늘어난다.

### 가르는 기준은 하나다

> **한 트랜잭션이 이 도메인 안에서 끝나는가, 다른 도메인까지 바꾸는가**

가드가 있는지, 시각을 찍는지, 누가 부르는지는 보지 않는다. **그것들은 도메인과 usecase의 관심사이고,
서비스는 트랜잭션 경계를 이름 있는 자리로 만드는 껍데기다.**

```
PaymentService              결제.  그리고 환불 생성 — 둘 다 이 도메인 안이다
RefundService               환불의 전이
PaymentApprovalService      ★ 주문까지 바꾼다.  다른 도메인에 닿는 유일한 자리다
                              complete          결제 성공 + 주문 완료
PgCallLogService            결제사 호출 기록.  독립 트랜잭션

                            12개 → 4개
```

**결제를 다루는 것은 특별한 이유가 없으면 한 자리에 둔다.** 같은 타입의 인스턴스가 여럿인 것도,
같은 도메인의 다른 타입을 함께 저장하는 것도 가르는 이유가 못 된다 — 결제 시작(앞 결제 종결 + 새 결제
생성)·회수(우리 결제 종결 + 남의 결제 회수)·반려(결제 종결 + 환불 생성)가 그 모양인데, **갈라 놓으면
클래스만 늘고 드러나는 것이 없다.** 커져서 읽기 어려워지면 그때 나눈다.

- **잘게 쪼개면 서비스끼리 부를 일이 늘어난다.** 이 저장소는 서비스 간 호출을 금지하지 않으므로
  (69번), 클래스 수가 그대로 의존의 수가 된다. 합쳐 두면 그 호출이 애초에 생기지 않는다.

**다른 도메인을 건드리는 것만 클래스로 갈라 둔다.** 같은 클래스에 두면 "여기 이미 주문을 만지네" 하고
둘째가 들어오고, 그 순간 그 클래스는 더 이상 결제의 자리가 아니게 된다. **파일이 갈려 있으면 밖에
닿는 곳이 파일 단위로 드러나고, 나중에 모듈을 쪼갤 때도 그 파일만 보면 된다.**

**저장소에서 다른 도메인까지 바꾸는 트랜잭션은 둘이다** — 승인 확정(주문+결제)과 결제된 주문
취소(주문+결제+환불+재고). 뒤엣것은 주문이 주도하므로 주문 도메인에 그대로 둔다.

**환불을 만드는 트랜잭션이 결제를 함께 저장하는 이유**는 한도 판정 때문이다. **누적 환불액이 올라**
결제 버전이 바뀌고, 동시에 온 두 요청 중 하나가 충돌해 그 환불까지 같은 트랜잭션에서 롤백된다.

**판별법**: 껍데기가 `find → 도메인 메서드 → save` 세 줄을 넘으면 묶음이라는 신호다.

### 옛 클래스가 어디로 가나

| 지금 | 이후 | 근거 |
| --- | --- | --- |
| `ReservePaymentService` | `PaymentService.create` | 예약 개념이 사라진다. 하는 일은 결제 행을 만드는 것이다 |
| `CreateApprovePaymentService` | **삭제** | 승인 시점에 결제 행을 만들던 것인데, 새 모델은 **시작 때 이미 있다.** 그 자리는 `markInProgress`가 대신한다 |
| `SucceedPaymentApprovalService` | `PaymentApprovalService.complete` | 주문과 함께 커밋하므로 경계를 넘는 자리로 |
| `FailApprovePaymentService` | `PaymentService.fail` | 종류 값이 없으니 `Approve`가 가릴 것이 없다 |
| `MarkUnknownApprovePaymentService` | `PaymentService.markUnknown` | 같다 |
| `EscalateApprovePaymentService` | `PaymentService.recordNotified` | `escalate`는 업무 언어가 아니다. 하는 일은 통지 시각 기록이다 |
| `DelayPaymentReconcileService` | `PaymentService.recordReconciled` | 다음 조회를 미루던 것에서 **마지막으로 집은 시각 기록**으로 뜻이 바뀌었다 |
| `SucceedCancelPaymentService` | `RefundService.complete` | 환불이 별도 엔티티다 |
| `MarkUnknownCancelPaymentService` | `RefundService.markUnknown` | 같다 |
| `FailCancelPaymentService` | `RefundService.flagForReview` | 환불에는 실패로 끝나는 종착이 없다 |
| `EscalateCancelPaymentService` | `RefundService.recordNotified` | 위와 같다 |
| `GetOrCreateCancelPaymentService` | **삭제** | 환불을 만드는 트랜잭션이 그 일을 한다. 자기 트랜잭션을 가진 별도의 문이 문제였다 |

### 메서드

**이름은 널리 쓰이는 표현으로 짓는다.** 이 저장소에만 통하는 말을 만들지 않는다.

```
PaymentService                  결제만.  인스턴스가 여럿인 것도 여기다
    start              앞의 READY 결제를 종결해 슬롯을 비우고, 새 결제를 만들어 그 자리를 잡는다.
                       한 트랜잭션이다 — 나누면 그 틈에 남이 슬롯을 잡거나, 앞엣것만 종결된 채
                       회원이 결제창도 못 받는 상태가 남는다.  비우는 저장이 먼저 나가야 한다
    create             앞 결제가 없을 때의 생성 + 슬롯 점유
    markInProgress     첫 승인 호출 직전.  응답 대기로 전이 + 부른 시각 + 시도 번호
    recordRequested    대사가 승인을 다시 부르기 직전.  상태는 그대로 두고 부른 시각만
    fail               승인이 성립하지 않았거나, 성립한 승인이 밖에서 이미 취소됐다.
                       종결 코드 + 슬롯 반납.  뒤엣것은 승인 금액도 함께 받는다
    markUnknown        응답을 못 받았다
    recordRetryableFailure
                       다시 시도할 수 있는 실패를 받았다.  상태는 그대로 두고
                       시도 번호만 올린다 — 다음 호출이 새 키로 나가게 하는 자리다
    expire             아예 안 불렀다.  종결 + 슬롯 반납
    failAndReclaim     우리 결제를 실패로 종결 + 남의 결제 회수.  응답의 결제 키가 우리 것이 아닐 때다.
                       반려가 아니다 — 우리 결제에는 승인이 안 났고, 나간 돈은 그 키의 주인 것이라
                       우리가 되돌릴 대상이 아니다.  정상 운영에서 나올 수 없는 조합이라 통지한다.
                       나누면 앞엣것만 커밋됐을 때 그 결제가 아무도 안 보는 상태로 남는다
    reject             결제를 반려로 종결 + 환불 생성.  한도를 판정하고 누적 환불액을 올린다.
                       환불도 같은 도메인이라 여기 둔다 — 그리고 환불을 만드는 판정이 결제 안에 있다.
                       발견한 정합성 이상과 만들어진 환불을 값으로 돌려준다 — 통지도 결제사 호출도
                       커밋 뒤로 미루려는 것이다
    recordReconciled   대사가 집었다.  확정했든 못 했든, 그 자리에서 다시 불렀든
    recordNotified     통지를 보낸 뒤

RefundService                   환불만
    markInProgress     첫 발송의 호출 직전.  응답 대기로 전이 + 부른 시각 + 시도 번호
    recordRequested    다시 부르기 직전.  상태와 시도 번호는 그대로 두고 부른 시각만.
                       결제 쪽과 같은 이유다 — 대사 유예를 이 값으로 재므로 안 찍으면
                       방금 보낸 건을 다른 주기가 또 집는다
    complete           환불 성공
    flagForReview      자동으로 더 못 함.  검토 코드를 채운다
    markUnknown        응답을 못 받았다
    recordRetryableFailure
                       결제 쪽과 같다
    recordReconciled   대사가 집었다.  결제 쪽과 같다
    recordNotified     통지를 보낸 뒤

PaymentApprovalService          ★ 다른 도메인까지 바꾼다.  그 유일한 자리다
    complete           결제 성공 + 주문 완료

PgCallLogService
    record
```

**결제를 성공시키는 자리가 하나뿐이다.** 지금은 결제만 바꾸는 `succeed`와 주문까지 바꾸는
`succeedApproval`이 공존하는데, 코드를 확인하니 **결제가 성공하는데 주문이 안 바뀌는 경로가 없다**
— `payment.succeed()`를 직접 부르는 곳은 환불(옛 모델의 CANCEL 결제)뿐이다. 둘이 남아 있으면 매번 어느
것을 부를지 판단해야 하고, 틀리면 주문이 안 바뀐다.

**`fail`과 `expire`는 합치지 않는다.** 둘 다 종결이고 슬롯을 반납하지만 뜻이 다르다 — 불렀는데 안 된
것과 아예 안 부른 것이다. 하나로 합치면 호출자가 상태를 정하게 되는데 그것은 도메인이 정할 일이다.
슬롯 반납을 빠뜨릴 위험은 **두 전이가 도메인 안에서 같은 내부 처리를 거치게** 해서 막는다.

**부르기 직전 자리가 결제에서 둘로 갈린다.** 첫 호출과 대사의 재요청이 남기는 것이 다르기 때문이 아니라
(둘 다 부른 시각을 찍는다) **첫 호출의 전이가 겹친 호출을 막는 장치**이기 때문이다. 하나로 합쳐 응답
대기 상태에서도 통과시키면, 회원의 결제창 복귀 둘이 겹쳤을 때 뒤에 온 쪽이 앞선 호출을 못 보고 결제사를
한 번 더 부른다. 재요청은 이미 대사 유예를 지난 건에만 걸리므로 그 전이가 필요 없다.

**시각 갱신을 별도 서비스로 빼지 않는다.** 같은 컬럼을 두 클래스가 건드리게 되기 때문이다. **언제
무엇을 찍는지는 도메인 메서드가 정하고**, 서비스는 그것을 트랜잭션으로 묶기만 한다.

**검토 상태에서 되살리는 메서드를 만들지 않는다.** 모델은 그 전이를 허용하지만(`data-model.md`의
환불 전이표) **이번 범위에 그것을 일으킬 주체가 없다** — 관리자가 손으로 정리하는 화면이 범위 밖이다.
만들어 두면 **부를 자리도 권한 규칙도 없는 상태 변경 메서드**가 남고, 나중에 그 화면을 붙이는 사람이
권한을 정하는 자리 없이 그것을 그대로 부르게 된다. **전이표와 시도 번호 규칙은 그대로 둔다** — 그 경로를
열 때 무엇을 지켜야 하는지가 거기 적혀 있고, 그때 관리자 신원·권한을 함께 정한다.

**집었다는 기록은 결제사를 부르기 전에 따로 커밋한다.** 결과 반영과 한 트랜잭션으로 묶으면, 호출이
실패하거나 응답 처리가 깨졌을 때 **집은 사실까지 함께 롤백되어 회차가 오르지 않는다.** 그러면 다시 집는
간격이 영영 첫 값에 머물러, 장애가 길어질수록 우리가 더 세게 두드린다. 승인 호출이 시도 번호와 부른
시각을 부르기 직전에 커밋하는 것과 같은 이유다.

```java
@Transactional
public void recordReconciled(Long id, LocalDateTime pickedAt) {
    Payment p = repo.<…>(id).orElseThrow(…);
    p.recordReconciled(pickedAt);   // 집었다는 사실. 회차도 여기서 오른다
    repo.saveChecked(p);            // 낙관 락 충돌이면 여기서 물러난다
}
// ↓ 커밋 뒤 결제사 호출 (트랜잭션 밖)
// ↓ 이력이 성공으로 말하면 PaymentApprovalService.complete 를 부른다
//   그 안에서 결제 성공과 주문 완료가 한 트랜잭션으로 커밋된다
```

**두 번째 트랜잭션을 여기에 두지 않는다.** 결제만 성공시키는 자리를 만들면 **대사로 확정한 건은 주문이
결제완료로 안 넘어간다** — 돈은 나갔는데 주문이 안 잡힌 상태다. 승인 결과를 확정하는 자리는 실시간
경로든 대사 경로든 하나이며, 그 자리가 주문까지 함께 바꾼다.

**단위작업은 내부 식별자로 다시 로드한다.** 대사가 대상을 훑을 때 이미 그 행을 읽었으므로 값을 알고
있고, **밖에서 온 값으로 한 건을 집는 조회를 회수 경로 하나로 유지**하려면 여기서 결제 키를 쓰면
안 된다. 그 조회 개수가 소유 확인이 새지 않았는지 대조하는 기준이기 때문이다.

- **이름은 `id`다.** 자기 aggregate의 대리키라 무엇의 것인지 밝힐 필요가 없다. **`paymentId`로 적지
  않는다** — 결제사가 주는 값의 이름이 `paymentId`라 그 값으로 읽힌다. 다른 aggregate가 결제를 가리킬
  때만 `paymentId`를 쓴다(환불이 그 경우다).

**집는 자리에 새 메서드를 만들지 않는다.** `recordReconciled`가 이미 그 일을 가리키고, 달라진 것은
그것을 **부르는 시점과 커밋 경계**뿐이다.

**집는 자리에서 낙관 락이 한 번 걸린다.** 두 주기가 같은 건을 동시에 집으면 진 쪽은 **결제사를 부르기
전에** 물러난다. 이것이 겹친 호출을 막는 유일한 장치이며, **완전하지는 않다** — 앞선 주기가 아직 부르고
있는 사이 회차 간격이 지나면 다음 주기가 그 건을 집는다. 남는 위험은 `data-model.md`의 알려진 취약점에
적었다.

**건마다 따로 커밋한다.** 여러 건을 한 트랜잭션으로 묶어 집으면 하나가 실패할 때 전부 롤백되고, 낙관
락이 건별로 걸리지 않는다.

### `RefundService`는 환불만 로드한다

**결제와 환불이 각자 aggregate root다.** 환불 상태를 바꾸는 일은 한도를 바꾸지 않으므로 결제를 건드리지
않는다.

```java
@Transactional
public void complete(Long refundId, LocalDateTime at) {
    Refund r = refundRepository.findById(refundId).orElseThrow(…);
    r.complete(at);                        // 전이 규칙은 환불 자신의 메서드에
    refundRepository.saveChecked(r);       // 자기 낙관 락
}
```

**환불을 만드는 것만 결제와 함께 커밋한다.** 그때만 한도가 바뀌고, 그때 오르는 결제 버전이 동시 요청을
막는다.

```
환불을 만든다        결제 로드 → 결제가 판정 → 누적 환불액을 더해 환불·결제 함께 저장
환불 하나를 고친다   환불만 로드해 전이시키고 저장한다
환불을 읽는다        직접 조회한다
```

---

## application/usecase — 흐름 조립

**구조는 지금 그대로다.** `usecase/`는 트랜잭션을 열지 않고(ArchUnit이 강제), 결제사를 부르고, 충돌을
트랜잭션 밖에서 받아 판단한다.

### 흐름 열하나

**조회 조건이 다르면 유스케이스가 다르다.** 지금은 대사 하나가 결제 회수·환불 회수·통지를 겸해 486줄에
주입이 열셋이다.

| | usecase | 트리거 |
| --- | --- | --- |
| 결제 | `StartPaymentUseCase` | 회원이 결제를 시작한다 |
| | `RequestApprovalUseCase` | 회원이 결제창에서 돌아왔다 |
| | `ConfirmApprovalUseCase` | **밖에서 안 불린다.** 승인 요청과 대사가 공유한다 |
| | `ClosePaymentUseCase` | 승인 반려 둘과 결제 키 불일치 |
| | `ReconcilePaymentUseCase` | `IN_PROGRESS`·`UNKNOWN` + 읽은 지 경과 |
| | `NotifyPaymentUseCase` | 생성 후 승급 + 알린 지 경과 |
| | `ExpirePaymentUseCase` | `READY` + 생성 후 경과 |
| 환불 | `ExecuteRefundUseCase` | **환불 하나를 보낸다.** 네 진입점이 공유한다 |
| | `DispatchRefundUseCase` | `REQUESTED` 를 조회해 각각을 보낸다. **시간 조건이 없다** |
| | `ReconcileRefundUseCase` | `IN_PROGRESS`·`UNKNOWN` + 읽은 지 경과 |
| | `NotifyRefundUseCase` | 생성 후 승급 + 알린 지 경과 |

**발송을 배치가 따로 훑는다.** 주문 취소와 승인 반려가 커밋 뒤에 바로 보내지만, 그 호출이 실패하면
환불이 `REQUESTED`로 남는다. 훑는 자리가 없으면 **그 환불이 영영 안 나간다.**

**발송에 시간 조건을 두지 않는다.** 이 상태로 오는 것은 **시도 번호가 0인 건**(요청 흐름이 커밋하고
죽었다)과 **사람이 되살린 건**뿐이라, 되풀이 나갈 자리가 없다. 앞엣것은 이력을 안 읽고 바로 나가고
뒤엣것만 읽는다. **재전송의 백오프는 대사 쪽에 있다** — 다시 보내는 것은 대사가 그 자리에서 하고,
그 주기가 다시 집는 간격에 눌린다.

### 옛 클래스가 어디로 가나

| 지금 | 이후 | 근거 |
| --- | --- | --- |
| `ApproveNaverPayUseCase` | `RequestApprovalUseCase` | 결제사 이름은 `infrastructure/pg/` 안에서만. **`approve`와 `confirm`이 나란히 있으면 어느 것이 앞인지 이름으로 안 갈려** 요청·확정 대비로 바꾼다 |
| `ConfirmApprovalUseCase` | 그대로 | 예약을 보고 갈리던 분기가 결제 행 상태 기준으로 다시 선다 |
| `CompensateApprovalUseCase` | `ClosePaymentUseCase` | 보상 catch가 하던 일이 **종결 코드를 남기는 것**으로 정리됐다 |
| `ReconcilePaymentUseCase` | **넷으로 갈린다** — 결제 회수·결제 통지·환불 회수·환불 통지 | 조회 조건이 전부 다르다 |
| `RefundExecutionUseCase` | `ExecuteRefundUseCase` | **동사를 앞으로.** 저장소 다수가 그 어순이고 이것만 명사화돼 있다 |
| (없음) | `StartPaymentUseCase` | 멱등 선점이 앞에 붙어 컨트롤러가 서비스를 직접 부르던 모양으로는 안 된다 |
| (없음) | `ExpirePaymentUseCase` · `DispatchRefundUseCase` | 방치된 결제 종결, 환불 발송 훑기 |

### 유스케이스가 유스케이스를 부르는 규칙

**여러 진입점이 같은 작업을 공유할 때만 부른다.** 순서를 이어 붙이려고 부르는 것은 그 유스케이스 안에서
한다.

```
RequestApproval  ─┐
                  ├─► ConfirmApproval      실시간 승인과 대사가 확정을 공유한다
ReconcilePayment ─┘

ClosePayment     ─┐
CancelOrder(order)│
DispatchRefund    ├─► ExecuteRefund        네 진입점이 발송을 공유한다
ReconcileRefund  ─┘   대사는 이력을 읽어 없으면 그 자리에서 부른다
```

**부르기 전에 지금까지의 사실이 커밋돼 있어야 한다.** 뒤엣것이 실패해도 앞선 사실이 남아 대사가 마저
할 수 있어야 한다.

| 호출 | 앞선 것 | 뒤엣것이 실패하면 |
| --- | --- | --- |
| `RequestApproval` → `ConfirmApproval` | 부르기 직전 전이가 커밋됨 | `IN_PROGRESS`로 남아 **대사가 확정** |
| `ClosePayment` → `ExecuteRefund` | 결제 종결 + 환불 생성이 커밋됨 | `REQUESTED`로 남아 **발송 배치가 재발송** |
| `CancelOrder` → `ExecuteRefund` | 주문 취소 + 환불 의도가 커밋됨 | 위와 같다 |
| `ReconcileRefund` → `ExecuteRefund` | 이력에 그 시도가 없음을 확인함 | 상태가 그대로 남아 **다음 주기에 다시 집는다** |

**같은 도메인 안에서 함께 커밋해야 하면 그 묶음 전용 메서드를 만들고 리포지토리와 도메인 객체를 직접
다룬다.** 서비스를 쪼개 부르지 않는다 — 한 트랜잭션이 무엇을 바꾸는지가 한 메서드에 다 보여야 한다.

**다른 도메인의 단위작업은 부른다.** 주문 취소가 재고 복구를, 승인 반려가 환불 생성을 그렇게 부르고
있고 저장소 전체에 여덟 자리다. 그쪽 규칙을 이쪽에 복제하지 않으려는 것이다.

- **불려 가는 서비스가 `@Transactional`을 갖고 있어도 부르는 쪽 트랜잭션에 참여한다.** 독립이 필요하면
  그렇게 선언해야 하고, 안 그러면 바깥이 롤백될 때 함께 딸려간다. **금지가 아니라 알고 부르는 것이다.**

```
                              지금      이후
service (payment)              12        4
usecase (payment)               5       11
가장 큰 usecase               486줄    150줄 안팎
가장 많은 주입                 13개      4개
밖까지 바꾸는 클래스           흩어짐    PaymentApprovalService 하나
```

---

## application/port — 밖으로 나가는 인터페이스

| 지금 | 이후 | 근거 |
| --- | --- | --- |
| `PgCanceller` | **삭제** | 함수 하나를 파라미터로 넘기는 모양이라, 그 함수를 만드는 쪽이 결제사를 알아야 한다 |
| `result/CancelOutcome` | **결제사 호출 결과 dto로 다시 짠다** | 지금은 결제·환불이 공유하는 실패 코드를 담는다. 그 enum이 둘로 갈린다 |
| `NotificationPort` | 그대로 | |

**새로 생기는 것**

```
PaymentGatewayPort          approve · refund · readHistory 셋을 한 인터페이스에. 결제사 이름이 없다
                            셋 다 호출 출처를 함께 받는다 — 읽기 제한 시간이 진입점마다 다르다
dto/                        그 호출의 결과. 결제사 어휘가 없다
dto/PgCallSource            그 호출을 어느 자리에서 부르나. 읽기 제한 시간이 진입점마다 달라
                            어댑터가 이 값으로 클라이언트를 고른다
PaymentIdempotencyStore     결제 시작 멱등키를 선점한다
PaymentProviderConfigReader 결제사별 호출 설정을 읽는다
```

- **선점 port는 주문의 것과 짝을 맞춘다.** 주문에 `OrderIdempotencyStore`(`reserve`·`clear`)가 이미
  있고 하는 일이 같다. 이름이 갈리면 같은 장치를 두 관습으로 부르게 된다. 구현도 그쪽을 따라
  `infrastructure/cache/`에 둔다.
- **`…Port` 접미어는 게이트웨이 하나만 쓴다.** 이 저장소의 port는 **역할을 나타내는 행위자 이름**이
  관습이다(`OrderIdempotencyStore`·`CartItemRemover`·`PgCanceller`). 게이트웨이가 예외인 것은 성격이
  다른 호출 셋을 한 인터페이스에 묶은 자리라 행위자 하나로 부를 수 없어서다.

---

## infrastructure

**결제사 전용 타입에 대한 의존은 `infrastructure/pg/<결제사>/` 안에서만 나온다.** 지금은 결제사가
최상위 묶음이고 그 안에 네 계층이 통째로 들어가 있다. **이름 자체는 표현 계층의 승인 복귀 진입점에도
남는다** — 아래 "앞서 미뤄 두었다가 여기서 정한 것"이 그 예외를 정한다.

| 지금 | 이후 |
| --- | --- |
| `naverpay/application/port/NaverPayGateway` | **삭제** — `PaymentGatewayPort`가 대신한다 |
| `naverpay/infrastructure/pg/NaverPayGatewayImpl` | `infrastructure/pg/naverpay/GatewayAdapter` |
| `naverpay/infrastructure/pg/NaverPayClientConfig` | `infrastructure/pg/naverpay/ClientConfig` |
| `naverpay/infrastructure/pg/NaverPayProperties` | `infrastructure/pg/naverpay/Properties` |
| `naverpay/infrastructure/pg/client/**` | `infrastructure/pg/naverpay/client/**` |
| `naverpay/infrastructure/pg/code/**` | 결제사 응답 코드 표. 같은 자리로 |
| `naverpay/application/port/result/NaverPay*Result` | **삭제** — port dto가 결제사 어휘 없이 대신한다 |
| `naverpay/application/dto/NaverPayApprove*` | 같다 |
| `naverpay/application/usecase/ApproveNaverPayUseCase` | **결제사 이름 없는 승인 흐름으로** |
| `naverpay/domain/exception/NaverPay*` | 어댑터가 예외를 던지지 않으므로 **자리와 존재를 다시 본다** |
| `provider/PaymentProviderProperties`·`…Resolver` | **결제사 설정 port의 구현으로.** 네 계층 밖에 있어 ArchUnit이 닿지 않던 자리다 |
| `postprocess/**` 5개 | `domain/policy/`로. 같은 이유다 |
| `persistence/BlockingPaymentCheckerAdapter` | 주문이 쓰는 port의 구현. **판정을 활성 슬롯 기준으로 다시 짠다** — 슬롯을 쥔 결제가 있는 주문을 돌려준다. **이름은 그대로 둔다**(아래) |
| `notification/LogNotificationAdapter` | 그대로 |

**결제사 이름이 클래스 이름에서 빠진다.** 패키지가 이미 그것을 말하므로 `GatewayAdapter`로 충분하다.

---

## presentation

| 지금 | 이후 |
| --- | --- |
| `ReservePaymentController` | **`PaymentController`로.** 경로에서도 예약을 걷어낸다 — 이 저장소의 컨트롤러는 `OrderController`·`CartController`처럼 도메인 이름을 쓴다 |
| `http/request/ReservePaymentRequest` | **`StartPaymentRequest`로.** 자원은 결제지만 이 요청이 뜻하는 행위는 "시작한다"이다 |
| `naverpay/presentation/http/NaverPayController` | **확인이 필요하다**(아래) |
| `scheduler/PaymentReconciliationScheduler` | **`PaymentPostProcessScheduler`로.** 진입점은 하나로 두고 메서드를 나눈다(아래). 대사만 부르는 자리가 아니라 통지·만료까지 깨우므로 이름이 대사를 가리키면 좁다 |

---

## 테스트 45개

**클래스 이름은 위 표를 그대로 따라간다.** 없어지는 클래스의 테스트는 함께 없어지고, 이름이 바뀌면
테스트 이름도 바뀐다.

| 지금 | 어떻게 |
| --- | --- |
| `PaymentReservationTest`·`PaymentReservationRepository*Test` (3) | **삭제** |
| `GetOrCreateCancelPaymentServiceTest` | **삭제** |
| `*ApprovePaymentServiceTest`·`*CancelPaymentServiceTest` (7) | 위 표의 새 이름으로 |
| `PaymentPostProcess*PolicyTest` (2) | 정책이 `domain/policy/`로 가면 테스트도 따라간다 |
| `PaymentProviderPropertiesResolverTest` | 결제사 설정 port 구현의 테스트로 |
| `NaverPay*Test` (6) | `infrastructure/pg/naverpay/` 아래로 |
| 동시성 테스트 (5) | 대상 클래스가 바뀌므로 이름과 시나리오를 다시 짠다 |
| `PaymentPersistenceTestSupport` 등 픽스처 (2) | 엔티티가 바뀌므로 다시 짠다 |

**새로 필요한 것**: 환불 엔티티·환불 전이·환불 대사·결제사 호출 기록·멱등 선점의 테스트.

**메서드 이름은 규칙을 그대로 따른다** — 영어 `행위_조건_결과` + 한국어 `@DisplayName`.
시나리오 번호나 요구 식별자를 남기지 않는다.

---

## payment 밖에서 고쳐야 하는 것

| 파일 | 지금 무엇을 쓰나 | 어떻게 |
| --- | --- | --- |
| 주문 취소 서비스 | 환불을 "찾거나 만드는" 서비스, 결제 엔티티, 결제 리포지토리 | 결제의 도메인 메서드를 쓰도록 |
| 주문 취소 유스케이스 | 취소 결과 dto, 환불 실행 유스케이스, 결제 엔티티, **결제사 게이트웨이** | **결제사 이름이 주문 계층까지 올라와 있다.** 이번에 끊는다 |
| 주문 테스트 4개 | 위를 따라간다 | |

---

## 손대지 않기로 한 것

**`usecase/` + `service/` 구조 자체는 바꾸지 않는다.** 이 배치의 근거는 둘이고, 지금도 유효하다.

```
① 낙관 락 충돌을 트랜잭션 밖에서 잡아야 한다.  안에서 잡으면 rollback-only 라 커밋이 안 된다
② 결제사 호출이 트랜잭션 안에 있으면 안 된다.  행 락을 쥔 채 외부 응답을 기다리게 된다
```

**서비스가 얇은 것은 결함이 아니라 이 설계의 결과다.** 담을 게 없어서가 아니라, 담으면 안 되는 것이
양옆에 있어서다 — 판단이 들어오는 순간 그것은 도메인이거나 usecase의 일이다.

- **트랜잭션 템플릿을 유스케이스에서 직접 쓰지 않는다.** 껍데기 클래스는 사라지지만 **경계가 람다 안으로
  숨어** ArchUnit이 검사할 대상을 잃고, 유스케이스 어디서든 트랜잭션을 열 수 있게 되어 위 ②를 막던
  구조가 사라진다. `@Transactional`이 붙은 별도 빈의 값은 코드가 아니라 **경계에 이름과 자리를 준다**는
  데 있다.
- **인바운드 포트(`port/in/`)를 도입하지 않는다.** 이 저장소는 `application/port/`를 밖으로 나가는 것
  전용으로 쓰고, 들어오는 쪽은 유스케이스 클래스 자체가 진입점이다. 도입하면 **구현이 하나뿐인
  인터페이스가 여덟 개** 생긴다.
- **`…UseCase` 접미사를 바꾸지 않는다.** 저장소의 유스케이스 열넷이 전부 그 접미사다. `Orchestrator`가
  "트랜잭션을 안 연다"를 더 잘 드러내지만, **결제만 바꾸면 저장소에서 혼자 다른 모양이 된다.**
  다섯 도메인을 함께 볼 때 다룰 값이 있어 후속 과제로 남긴다.

## 앞서 미뤄 두었다가 여기서 정한 것

**1. 승인 콜백 컨트롤러는 `presentation/http/naverpay/` 아래에 결제사 이름을 단 클래스로 둔다.**

배치 규칙 문서가 이 경우의 정본을 이미 그려 두었다 — **결제사별 컨트롤러 분리까지가 천장**이다.
경로가 결제사마다 갈리는 것은 **결제사가 우리에게 돌려보내는 규격**이라 우리가 정할 수 없고, 진입점이
갈리면 그것을 받는 클래스도 갈린다.

**그래서 "결제사 이름이 인프라 밖에서 보이지 않는다"는 문장을 좁힌다.** 참인 것은 **결제사 전용 타입에
대한 의존이 인프라 밖으로 나가지 않는다**는 것이다 — 응답 타입·에러 코드·클라이언트가 그렇다.
경로 문자열과 그것을 받는 진입점 이름은 예외이며, 그 예외는 우리가 고를 수 있는 것이 아니다.
**아키텍처 규칙이 검사하는 것도 타입 의존이지 이름이 아니다.**

**2. 대사 스케줄러 진입점은 하나로 두고 메서드를 나눈다.**

유스케이스는 조회 조건으로 갈라 넷이지만(결제 회수·결제 통지·환불 회수·환불 통지) 그것을 부르는
진입점까지 넷으로 나눌 이유가 없다. **클래스 넷이 각각 유스케이스 하나만 부르는 껍데기가 된다.**
주기를 따로 정해야 하면 메서드마다 붙이면 되고, **한 클래스에 모여 있어야 무엇이 언제 도는지가 한눈에
보인다.** 만료 종결과 환불 발송도 같은 자리에 둔다.
