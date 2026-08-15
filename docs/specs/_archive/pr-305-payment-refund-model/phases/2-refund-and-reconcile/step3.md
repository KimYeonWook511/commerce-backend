# Step 3 — remove-legacy

## 목표

**legacy를 통째로 지우고 옛 테이블을 버린다.** 앞 step에서 모든 경로가 새 모델로 옮겨졌으므로 legacy를
부르는 코드가 하나도 없다. 이 step은 **지우기만 한다** — 새 코드를 손대지 않는다.

## 관련 문서

- `docs/specs/payment-refund-model/naming-map.md` — 무엇이 사라지기로 되어 있었는지 대조한다
- `docs/specs/payment-refund-model/db-schema.md` — 남을 테이블 셋
- `docs/persistence-conventions.md` — Flyway 규칙
- `src/main/java/com/commerce/payment/legacy/` — 지울 대상
- `src/test/java/com/commerce/payment/legacy/` — 함께 지울 대상
- `src/test/java/com/commerce/support/CleanupOrder.java` 및 각 도메인의 영속성 테스트 지원 클래스 —
  옛 테이블 자리를 뺀다
- `src/test/java/com/commerce/architecture/ArchitectureRulesTest.java` — legacy 예외를 뺀다

## 검증 대상

- SCN-017 결제사 전용 타입이 결제·주문 계층에 없다 (FR-019)
- SCN-103 옛 구조를 전제한 후처리 분기가 남지 않는다 (FR-021)
- SCN-070 환불 의도를 혼자 만들어 먼저 커밋할 수 있는 진입점이 없다 (FR-003e)

- 밖에서 보이는 동작이 앞 step과 같다 — **전체 테스트가 그대로 통과하는 것**이 검증이다

> **위 셋은 앞 step에서 새 코드 범위로만 확인했다.** legacy가 사라지는 지금 **코드베이스 전체를
> 대상으로 다시 본다** — 옛 문이 하나도 남지 않았는지, 결제사 타입이 계층 밖으로 새지 않는지.

## 구현 지시

1. **먼저 legacy를 부르는 곳이 없음을 확인한다.** 남아 있으면 그것을 옮기는 것이 이 step의 일이 아니라
   **앞 step이 덜 끝난 것**이다. 그 경우 여기서 임시로 잇지 말고 앞 step으로 돌려보낸다.
2. **`payment/legacy/` 아래를 전부 지운다.** main과 test 양쪽이다.
3. **Flyway 스크립트를 하나 쓴다. 옛 결제·예약 테이블을 버린다.**
   - **운영 중인 결제·환불 데이터가 없다는 가정 위에 선다.** 실제로 진행 중인 건이 있으면 폐기된다.
     앞선 마이그레이션이 같은 전제로 이미 파괴적 변경을 한 선례가 있다.
4. **테스트 정리 순서에서 옛 테이블 자리를 뺀다.** 그 테이블이 사라진다.
5. **아키텍처 테스트에서 legacy 예외를 뺀다.** phase 1 step 1에서 넓혔던 패턴과 phase 1 step 3에서 둔 제외
   범위를 원래대로 좁힌다. **좁힌 뒤 규칙이 새 코드를 제대로 검사하는지 확인한다.**
6. **설정에서 legacy를 가리키던 것을 정리한다.** 결제사 호출 설정을 legacy와 구분하려고 이름을 나눠
   두었으면 여기서 되돌린다.
7. **`payment/` 아래에 `legacy`라는 이름이 남지 않는지 확인한다.** 패키지·클래스·프로퍼티·주석 전부다.

## 주의사항

- 하지 마라: 지우면서 새 코드를 고치는 것. 이유: 이 step의 diff는 **삭제만**이어야 무엇이 사라졌는지
  한눈에 보인다. 고칠 것이 있으면 앞 step에서 했어야 한다.
- 하지 마라: legacy를 부르는 곳이 남았는데 임시로 이어 붙이는 것. 이유: 앞 step이 덜 끝난 것이고,
  임시 연결은 그대로 남는다.
- 하지 마라: 이미 적용된 V 스크립트를 고치는 것. 이유: Flyway가 체크섬으로 막는다. 새 번호를 쓴다.
- 하지 마라: 옛 테이블을 이름만 바꿔 남기는 것. 이유: 쓰는 데가 없는 테이블은 다음 사람에게 "왜 있지"를
  묻게 만든다.
- 하지 마라: 아키텍처 테스트의 legacy 예외를 그대로 두는 것. 이유: 대상이 없어졌는데 예외가 남으면
  **새 코드가 그 틈으로 샐 수 있고, 그 사실이 조용히 숨는다.**

## Acceptance Criteria

```bash
./gradlew test
```

```bash
./gradlew integrationTest
```

```bash
./gradlew test --tests "com.commerce.architecture.*"
```
