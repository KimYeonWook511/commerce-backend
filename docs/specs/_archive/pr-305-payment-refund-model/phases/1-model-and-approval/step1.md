# Step 1 — isolate-legacy-payment

## 목표

지금 결제 도메인을 **`payment/legacy/` 아래로 통째로 옮기고, 옛 테이블 이름도 함께 비켜 준다.**
동작은 아무것도 바꾸지 않는다. **이 step이 끝나도 지금과 똑같이 돌아간다.**

## 왜 이 step이 있나

새 모델은 엔티티 필드가 통째로 바뀐다 — 결제 종류 값이 사라지고, 상태값이 달라지고, 실패 코드가 둘로
갈리고, 시각 컬럼이 새로 생긴다. **옛 엔티티를 그 자리에서 고치면 그것을 참조하는 119개가 한꺼번에
깨진다.** 그러면 첫 step이 나머지 step의 일을 전부 삼켜야 빌드가 통과한다.

**옛 것을 옆으로 치우고 빈 자리에서 새로 지으면** 각 step이 자기 몫만 하고, 매 step마다 빌드와 테스트가
실제로 통과한다. 그리고 **새 코드가 옛 이름에 끌려가지 않는다** — 빈 자리에서 시작하므로 `naming-map.md`가
정한 이름을 그대로 쓸 수 있다.

## 관련 문서

- `docs/specs/payment-refund-model/naming-map.md` — **이름의 정본.** 이 step은 옛 이름을 그대로 옮기지만,
  다음 step부터 쓸 새 이름이 여기 있다
- `docs/specs/payment-refund-model/architecture.md` — 새 구조가 앉을 자리
- `src/main/java/com/commerce/payment/` — 옮길 대상 74개
- `src/test/java/com/commerce/payment/` — 함께 옮길 대상 45개
- `src/main/java/com/commerce/order/application/service/CancelPaidOrderService.java` 및
  `.../usecase/CancelOrderUseCase.java` — **payment를 쓰는 유일한 바깥 코드.** import 여덟 줄
- `src/test/java/com/commerce/architecture/ArchitectureRulesTest.java` — 규칙이 패키지 경로에 걸려 있다

## 검증 대상

**밖에서 보이는 동작이 하나도 바뀌지 않는다.** 이 step은 시나리오를 새로 만족시키지 않는다 —
**기존 테스트가 전부 그대로 통과하는 것**이 검증이다.

## 구현 지시

1. **`src/main/java/com/commerce/payment/` 아래 전부를 `src/main/java/com/commerce/payment/legacy/`로
   옮긴다.** 패키지 선언과 import 경로만 바뀌고 **클래스 내용은 한 글자도 바꾸지 않는다.**
   - 테스트도 같은 방식으로 `src/test/java/com/commerce/payment/legacy/`로 옮긴다.
   - 옮긴 뒤 `payment/` 바로 아래는 `legacy/` 하나만 남는다.
2. **주문 쪽 import 여덟 줄을 새 경로로 고친다.** 주문 테스트 4개도 같다.
3. **설정에서 패키지 경로를 명시한 곳을 확인해 함께 고친다.** 컴포넌트 스캔 범위나 엔티티 스캔이 경로를
   박아 두었으면 대상이다.
4. **아키텍처 테스트가 `legacy/`를 어떻게 볼지 정한다.** 지금 규칙은 `application.usecase`에
   `@Transactional`을 금지하는 식으로 **패키지 경로 패턴**에 걸려 있다.
   - **`legacy/` 아래도 같은 규칙을 그대로 받게 둔다.** 옮기기만 했으므로 규칙을 어기지 않는다.
   - 경로에 `legacy`가 끼어 패턴이 안 맞는 규칙이 있으면 **그 패턴을 넓혀 legacy도 포함시킨다.**
     지금 지키던 것을 이 step에서 잃지 않는다.
5. **옛 테이블 이름을 비켜 준다.** Flyway 스크립트로 결제·예약 테이블을 `tbl_legacy_` 접두어가 붙은
   이름으로 바꾸고, **legacy 엔티티의 테이블 이름 표기도 같은 step에서 함께 고친다.**
   - **데이터는 그대로 남는다.** 이름만 바꾸는 것이라 legacy 경로가 계속 돈다.
   - **테이블 이름이 문자열로 박힌 곳을 함께 고친다. 이것이 "내용을 바꾸지 않는다"의 예외다.**
     - **제약명을 문자열로 비교하는 자리**가 있다. 유일 제약 위반을 잡아 보상으로 넘기는 판정인데,
       MySQL이 돌려주는 제약명에 테이블 이름이 들어간다. **이름이 바뀌면 그 비교가 항상 거짓이 되어
       legacy 가 승인을 맡는 동안 내내 이중결제 보상이 돌지 않는다.** 예외 처리 규약이 정본으로
       서술하는 판정이므로 반드시 함께 고친다.
     - **통합 테스트가 쓰는 네이티브 SQL**에도 테이블 이름이 박혀 있다. 함께 고친다.
   - **왜 지금 해야 하나**: 다음 step이 최종 이름으로 새 테이블을 만드는데, 옛 테이블이 그 이름을 쥐고
     있으면 **엔티티와 스키마가 어긋나 애플리케이션이 뜨지 않는다.** 이 저장소는 시작할 때 매핑을
     검증한다.
   - **새 테이블을 임시 이름으로 만들었다가 나중에 바꾸는 방법은 쓰지 않는다.** 새로 짓는 쪽이 최종
     이름을 처음부터 갖는 편이, 어차피 지울 옛 쪽이 비켜 주는 것보다 낫다.

## 주의사항

- 하지 마라: 옮기면서 이름을 고치는 것. 이유: 이 step의 diff는 **경로 변경만**이어야 리뷰가 가능하다.
  이름은 새 코드에서 정한다.
- 하지 마라: 옮기면서 죽은 코드를 지우는 것. 이유: 같은 이유다. 지우는 것은 legacy 제거 step이 한 번에 한다.
- 하지 마라: `legacy`를 패키지가 아니라 별도 모듈로 가르는 것. 이유: 빌드 설정이 늘고, 어차피 지울 것이다.
- 하지 마라: 옛 테스트를 `@Disabled`로 끄는 것. 이유: **그 테스트가 통과하는 것이 이 step의 유일한
  검증**이다. 끄면 옮기다 깨진 것을 못 잡는다.

## Acceptance Criteria

```bash
./gradlew compileJava compileTestJava
```

```bash
./gradlew test
```

```bash
./gradlew integrationTest
```

```bash
./gradlew test --tests "com.commerce.architecture.*"
```
