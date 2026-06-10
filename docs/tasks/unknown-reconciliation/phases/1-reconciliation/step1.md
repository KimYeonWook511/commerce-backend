# Step 1: promote-postprocess-policy

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/unknown-reconciliation/prd.md`
- `/docs/tasks/unknown-reconciliation/architecture.md`
- `/docs/tasks/unknown-reconciliation/adr.md` (특히 ADR-L7)
- `/docs/tasks/unknown-reconciliation/db-schema.md`

승격 대상 정책 클래스(현재 `src/test`):

- `/src/test/java/com/commerce/payment/postprocess/target/PaymentPostProcessTarget.java`
- `/src/test/java/com/commerce/payment/postprocess/target/PaymentPostProcessTargetPolicy.java`
- `/src/test/java/com/commerce/payment/postprocess/flow/PaymentPostProcessFlow.java`
- `/src/test/java/com/commerce/payment/postprocess/flow/PaymentPostProcessFlowPolicy.java`
- `/src/test/java/com/commerce/payment/postprocess/flow/PaymentVerificationStatus.java`

해당 정책 테스트(이전 후 import 갱신 대상):

- `/src/test/java/com/commerce/payment/postprocess/PaymentPostProcessTargetPolicyTest.java`
- `/src/test/java/com/commerce/payment/postprocess/PaymentPostProcessFlowPolicyTest.java`

레이어 구조 참고: `/docs/architecture.md`

## 작업

후처리 결정 정책(Target/Flow Policy + 관련 enum)을 `src/test`에서 `src/main`으로 **순수 이전(동작 변화 없음)**한다.

- 위 5개 클래스를 `src/main/java/com/commerce/payment/postprocess/` 아래로 이동한다. 하위 패키지(`target/`, `flow/`) 구조와 클래스명은 그대로 유지한다.
  - `com.commerce.payment.postprocess.target.PaymentPostProcessTarget`
  - `com.commerce.payment.postprocess.target.PaymentPostProcessTargetPolicy`
  - `com.commerce.payment.postprocess.flow.PaymentPostProcessFlow`
  - `com.commerce.payment.postprocess.flow.PaymentPostProcessFlowPolicy`
  - `com.commerce.payment.postprocess.flow.PaymentVerificationStatus`
- 정책 클래스가 Spring 빈으로 주입되어야 하면 `@Component`를 부여한다(다음 step에서 `PaymentReconciliationService`가 주입받는다). 단 상태 없는 순수 정책이므로 빈으로 둘지 `new`로 쓸지는 다음 step의 사용 방식에 맞춘다 — 이 step에서는 위치 이전과 컴파일 통과까지만 책임지고, 빈 등록은 사용처가 생기는 step 2에서 확정해도 된다.
- 시간 상수(`UNKNOWN_RECONCILE_DELAY`, `REQUESTED_STALE_DELAY`, `ESCALATION_DELAY`)는 **그대로 유지**한다. 운영 config 외부화는 이번 step 범위가 아니다.
- 기존 정책 테스트 2개(`PaymentPostProcessTargetPolicyTest`, `PaymentPostProcessFlowPolicyTest`)는 `src/test`에 남기고, import를 main 패키지로 갱신한다. 테스트 내용/검증은 바꾸지 않는다.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - 정책 클래스가 `src/main/java/com/commerce/payment/postprocess/` 아래에 위치하는가?
   - 기존 정책 테스트가 main 클래스를 가리키도록 import가 갱신됐고 그대로 통과하는가?
   - 정책 로직(분기·상수·반환값)이 이전 전과 동일한가? (순수 이전이어야 한다)
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 정책 로직(분기 조건, 시간 상수, 반환 target/flow)을 바꾸지 마라. 이유: 이 step은 위치 이전만 담당하며, 로직 변경은 리뷰에서 동작 변화로 잡혀야 한다.
- 정책 클래스를 `src/test`에 복제로 남기지 마라. 이유: 단일 출처를 깨면 정책이 표류한다(ADR-L7).
- 기존 테스트를 깨뜨리지 마라.
