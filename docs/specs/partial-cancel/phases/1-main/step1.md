# Step 1 — disable-open-in-view

## 목표

요청 단위 영속성 컨텍스트를 끈다. 잠금 앞 조회가 캐시한 낡은 값이 잠금 뒤 읽기에 섞이는 것을 **구조로** 막아, 뒤 step들이 세울 수량 축 방어가 순서 규칙에 기대지 않게 한다.

## 관련 문서

- `docs/specs/partial-cancel/architecture.md` — "요청 단위 영속성 컨텍스트를 끈다"
- `docs/specs/partial-cancel/data-model.md` — 동시성 절
- `docs/specs/partial-cancel/adr.md` — 같은 결정
- `docs/optimistic-lock-design.md` — 잠금·경합 처리 규약
- `src/main/resources/application.yml` — 프로필 공통 설정이 사는 자리
- `src/main/resources/application-local.yml`, `application-prod.yml`, `application-test.yml` — 프로필별 설정
- `build.gradle` — 모든 테스트 태스크가 `spring.profiles.active=test`로 도는 것을 확인할 자리
- `src/main/java/com/commerce/order/domain/Order.java`, `OrderItem.java` — 저장소의 지연 로딩 연관 둘
- `src/main/java/com/commerce/payment/application/usecase/StartPaymentUseCase.java` — 트랜잭션 밖에서 품목을 읽는 유일한 자리

## 검증 대상

기존 동작 유지. 이 step은 새 기능을 만들지 않고 설정 하나를 바꾼다.

- 모든 기존 검증이 그대로 통과한다 (SC-007이 요구하는 회귀 없음의 일부)

## 구현 지시

1. **프로필 공통 파일에 넣는다.** `application.yml`의 `spring:` 아래에 `jpa.open-in-view: false`를 둔다. **프로필별 파일은 건드리지 않는다** — `application-local.yml`·`application-prod.yml`의 주석 줄도 그대로 남긴다.
   - 프로필마다 따로 적으면 안 되는 이유가 있다. 모든 테스트 태스크가 `test` 프로필로 도는데 `application-test.yml`에는 이 속성이 없어, 프로필별로 적으면 **검증만 설정이 켜진 채 돈다.** 이 값은 환경마다 달라야 하는 값이 아니라 달라지면 안 되는 값이다.
2. **적용됐는지 확인하는 검증을 만든다.** `test` 프로필로 뜬 컨텍스트에서 그 속성이 `false`로 해석되는 것을 단언한다. 사람이 한 번 보고 넘어가면 뒤에 그 줄이 사라져도 아무도 모른다.
   **태그를 붙이지 않는다.** 격리 태그가 붙으면 기본 검증 태스크에서 빠져 CI가 이 단언을 돌지 않고, 그러면 손으로 돌릴 때만 존재하는 그물이 된다.
3. **전체 검증을 돌려 깨지는 곳을 찾는다.** 트랜잭션 밖에서 지연 로딩 연관을 건드리는 자리가 있으면 거기서 `LazyInitializationException`이 난다.
4. **깨지는 곳이 있으면 고치지 말고 보고한다.** 이 step은 설정을 바꾸고 그 영향을 확인하는 자리다. 다른 도메인의 코드를 함께 고치기 시작하면 이 변경의 범위가 흐려진다.
5. **`adr.md`가 헤아린 지연 로딩 연관 셋을 그대로 대조한다.** `Order.orderItems`가 트랜잭션 밖에서 읽히는 자리(`StartPaymentUseCase`)에 품목 포함 조회 `findByIdAndMemberIdWithItems`가 여전히 걸려 있는지, `OrderItem.order`를 역방향으로 읽는 자리가 생기지 않았는지 확인한다. 셋째(취소 품목 내역)는 step2가 만든다.
6. **검증 통과는 이 설정이 무해하다는 증명이 아니다.** 이 저장소의 검증은 요청 경계를 지나지 않아 이 설정이 작동하는 자리를 밟지 않는다. 실제 근거는 위 5의 대조다.

## 주의사항

- 하지 마라: 깨지는 자리를 지연 로딩 회피 조회로 바꾸거나 트랜잭션 경계를 옮겨 통과시키는 것. 이유: 그건 다른 도메인의 설계 변경이고 이 spec의 범위 밖이다. 보고하고 멈춘다.
- 하지 마라: 이 step에서 부분취소 관련 코드를 건드리는 것. 이유: 설정 변경의 영향만 따로 보이게 해야 뒤 step에서 문제가 생겼을 때 원인을 가릴 수 있다.
- 하지 마라: 설정을 프로필별 파일에 적는 것. 이유: 검증이 도는 `test` 프로필에 그 속성이 없어, 프로필별로 적으면 운영만 꺼지고 검증은 켜진 채 돈다.

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
./gradlew concurrencyTest
```

이 명령들은 모두 `test` 프로필로 돈다. 지시 1대로 공통 파일에 넣었다면 그 프로필에도 설정이 적용된다.

**그 적용 여부를 확인하는 검증을 하나 더한다.** `test` 프로필로 뜬 컨텍스트에서 `spring.jpa.open-in-view`가 `false`로 해석되는 것을 단언하는 검증이다. 위 세 명령은 요청 경계를 지나지 않아 이 설정이 사라져도 전부 통과하는데, 뒤 step들이 그 설정에 기대 잠금 범위를 넓히지 않기로 한다. **받침이 조용히 빠지는 것을 잡는 유일한 그물이 이 한 줄이다.**
