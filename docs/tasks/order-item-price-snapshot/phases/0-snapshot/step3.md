# Step 3: write-retrospective

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/order-item-price-snapshot/prd.md`
- `/docs/tasks/order-item-price-snapshot/architecture.md`
- `/docs/tasks/order-item-price-snapshot/adr.md`
- `/docs/tasks/order-item-price-snapshot/api-spec.md`
- `/docs/tasks/order-item-price-snapshot/db-schema.md`
- `/docs/tasks/order-item-price-snapshot/phases/0-snapshot/step1.md`
- `/docs/tasks/order-item-price-snapshot/phases/0-snapshot/step2.md`

이전 step 에서 생성 / 수정된 파일:

- `/src/main/resources/db/migration/V5__add_order_item_unit_price.sql`
- `/src/main/java/com/commerce/order/domain/OrderItem.java`
- `/src/main/java/com/commerce/order/domain/Order.java`
- `/src/test/java/com/commerce/order/domain/OrderTest.java`
- `/src/test/java/com/commerce/order/infrastructure/OrderRepositoryJpaAdapterTest.java`
- `/docs/adr.md` (Task ADR 색인 표 갱신)
- `/docs/db-schema.md` (`tbl_order_item` 섹션 갱신)

회고 작성 컨벤션 참고:

- `/docs/tasks/cross-aggregate-fk-cleanup/retrospective.md`
- `/docs/tasks/payment-jpa-association-decouple/retrospective.md`
- `/docs/tasks/order-jpa-association-decouple/retrospective.md`

## 작업

본 task 의 회고록을 작성한다.

### 신규 파일

`docs/tasks/order-item-price-snapshot/retrospective.md` 를 신규 작성한다.

### 회고 구성 요소

다음 섹션을 모두 포함한다 (기존 회고 컨벤션과 일관).

1. **개요**: PR #200 series 종료 후 결제 시점 가격 snapshot 컬럼을 신설한 트랙임을 한 문단으로 정리.
2. **결정 흐름**:
   - 타입: `int` 채택. Money VO 도입은 별도 트랙으로 보류.
   - Backfill: `tbl_product.price` JOIN 으로 채움. 결제 시점 가격 정확성은 보장 못 함.
   - 응답 DTO 노출: 본 PR 범위 밖.
   - adr.md 본문 ADR 신규는 만들지 않고 task adr + 색인 표 한 줄로 관리.
3. **기각된 옵션**:
   - NOT NULL + 0 backfill: 통계 / 영수증 사용처가 생겼을 때 0 이 더 큰 오해를 부른다는 판단으로 기각.
   - NULL 허용: snapshot 정책의 무력화 우려로 기각.
   - 기존 task 폴더 (`order-jpa-association-decouple`) 의 회고 보강: 완료 task 폴더 불변 원칙 위반.
4. **series baseline 과의 관계**: schema 무변경 원칙 (PR #199 / #200 / #202) 이 종료된 뒤 처음으로 schema 를 변경한 후속 트랙임을 명시. ADR-020 series 와의 시점 관계.
5. **사실 기록 (lag)**:
   - `OrderItem.java` 의 미해결 주석이 PR #200 series 종료 후에도 코드에 남아 있었던 lag 가 있었다. 본 task 에서 결정 명문화로 해소.
   - 기존 row 의 `unit_price` 가 "migration 적용 시점의 product 현재가" 라는 부정확성이 데이터에 남는다.
6. **아쉬운 점**:
   - 응답 DTO 노출까지 한 번에 가지 못하고 별도 PR 로 분리한 것이 큰 그림에서 보면 partial 변경이라 향후 trace 가 어렵다.
   - Money VO 도입을 다시 미뤘다는 점. 결제 / 정산 도메인 전반의 가격 표현 통일은 별도 series 가 필요하다.
7. **후속 작업**:
   - 주문 상세 조회 / 영수증 응답에 `unitPrice` 노출 (별도 PR).
   - 가격 정책 변경 / Money VO 도입 검토 (별도 series).

### 작성 규칙

- 한국어로 작성한다.
- 본문 분량은 cross-aggregate-fk-cleanup 회고를 참고로 비슷한 분량 (200~400행) 으로 유지한다. 본 task 의 변경 규모가 작으므로 과도하게 길게 쓰지 않는다.
- 코드 식별자는 영어로 작성한다.
- 의존명사 / 일반 명사는 띄어 쓴다 (`mock 응답`, `thread 간`). 영문 용어 뒤 조사는 붙여 쓴다 (`race가`, `mock으로`).

## 수정 가능 경로

- `docs/tasks/order-item-price-snapshot/retrospective.md` (신규 파일만)
- `docs/tasks/order-item-price-snapshot/**` (필요 시 본 task 의 기존 문서 보정만)

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `docs/tasks/order-item-price-snapshot/retrospective.md` 가 신규 작성됐는가?
     - `test -f docs/tasks/order-item-price-snapshot/retrospective.md && echo OK` 결과 OK.
   - 회고에 모든 필수 섹션이 포함되어 있는가? (개요 / 결정 흐름 / 기각된 옵션 / series baseline / 사실 기록 / 아쉬운 점 / 후속 작업)
     - `grep -E "결정|기각|baseline|아쉬운|후속" docs/tasks/order-item-price-snapshot/retrospective.md` 결과 ≥ 4.
   - main / test 자바 코드 변경이 없는가?
     - `git diff --name-only HEAD -- src/main/java src/test/java` (step 2 commit 이후) 결과 0건.
   - 루트 docs (adr.md / db-schema.md 외) 변경이 없는가?
     - `git diff --name-only HEAD -- docs/prd.md docs/architecture.md docs/api-spec.md docs/adr.md docs/db-schema.md` 결과 0건 (step 2 commit 이후).
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 머지된 task 폴더 (`docs/tasks/order-jpa-association-decouple/*`, `docs/tasks/payment-jpa-association-decouple/*`, `docs/tasks/cross-aggregate-fk-cleanup/*`) 의 문서를 수정하지 마라. 이유: 완료 task 폴더 불변 원칙.
- main / test 자바 코드를 수정하지 마라. 이유: step 1 의 책임.
- 루트 docs (adr.md / db-schema.md) 를 본 step 에서 수정하지 마라. 이유: step 2 의 책임.
- 회고를 미래형 / 추측 문체로 쓰지 마라. 이유: 회고는 결정과 trade-off 의 시점 기록이다.
- 기존 테스트를 깨뜨리지 마라.
