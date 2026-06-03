# Step 3: write-retrospective

## 읽어야 할 파일

먼저 아래 파일들을 읽고 이번 sub-PR 에서 진행된 결정 흐름과 변경 내용을 파악하라:

- `/docs/tasks/order-jpa-association-decouple/prd.md`
- `/docs/tasks/order-jpa-association-decouple/architecture.md`
- `/docs/tasks/order-jpa-association-decouple/adr.md`
- `/docs/tasks/order-jpa-association-decouple/api-spec.md`
- `/docs/tasks/order-jpa-association-decouple/db-schema.md`
- `/docs/tasks/order-jpa-association-decouple/phases/0-decouple/step1.md`
- `/docs/tasks/order-jpa-association-decouple/phases/0-decouple/step2.md`

참고:

- `/docs/tasks/stock-jpa-association-decouple/retrospective.md` — 선행 sub-PR 의 회고. 본 sub-PR 회고의 톤 / 구조 / "후속 트랙으로 넘기는 baseline" 형식 참고용.
- `/docs/tasks/cart/retrospective.md` — ADR-020 의 최초 적용 사례 회고.

## 작업

`docs/tasks/order-jpa-association-decouple/retrospective.md` 를 작성한다. 본 sub-PR 진행 중 내려진 결정의 흐름, trade-off, 후속 sub-PR (Payment) 에 넘기는 baseline 을 기록한다.

회고는 결정 사후 정리이지 새 결정 도입의 공간이 아니다. ADR 에 이미 명시된 결정은 그대로 인용하고, 결정에 이르기까지의 토론·기각된 옵션·향후 트랙의 의미만 보강한다.

### 필수 섹션

- **개요** — 본 sub-PR 의 목적과 결과를 짧게. 선행 stock sub-PR 과의 관계 (패턴 계승 + Order 특유 결정 추가) 를 명시.
- **결정 흐름** — 주요 분기점 정리:
  1. fetch join 대체 패턴을 단일 원칙 vs 사용처별 분석 사이에서 사용처별 분석으로 정한 이유. PaymentReady (productName 필요) 와 cancel/expiration (productId 만 필요) 의 데이터 양상 차이.
  2. `Order.create` / `addOrderItem` 시그니처를 Long ID 로 전환한 이유. application 검증을 `existsById` 로 효율화한 결정의 배경 (객체 로드 불필요 사용처와 객체 필요 사용처의 구분 기준).
  3. same-aggregate 관계 (`Order.orderItems`, `OrderItem.order`) 를 유지한 근거. ADR-020 의 "같은 aggregate 내 root-child 는 객체 참조 허용" 원칙 적용 판단.
  4. 응답 echo 정리를 본 sub-PR 에 섞지 않은 이유 (선행 stock sub-PR 의 동일 정책 계승).
- **기각된 옵션** — 검토했으나 채택하지 않은 옵션과 사유. ADR 의 "근거" 와 중복되지 않도록 토론 중에 나왔던 추가 맥락을 보강.
  - 전부 DTO projection 단일 원칙 / 전부 QueryService 분리 / `findById` 유지 시그니처 등.
- **후속 트랙으로 넘기는 baseline** — Payment sub-PR 이 본 sub-PR 을 어떻게 참조하면 되는지.
  - fetch join 대체 일반 원칙 (same-aggregate 유지 / cross-aggregate 제거 + 데이터 양상별 batch composition or 컬럼 직접 사용) 적용 가이드.
  - 도메인 시그니처 (Long ID + `existsById` 검증) 패턴 적용 가이드.
  - schema 무변경 / 응답 계약 무변경 메타 원칙 유지.
- **운영 점검** — DB FK 가 schema 에 남아있고 JPA 가 인식하지 않는 상태의 운영 모니터링 영향. PaymentReady 의 batch 쿼리 1회 추가가 hot path 에 미치는 영향 (단일 order 의 OrderItem 개수는 보통 한 자릿수이므로 미미함) 등.
- **자기 평가** — 잘된 점 / 아쉬운 점.
  - 잘된 점 예: 사용처별 분석으로 cancel/expiration 의 추가 쿼리 0회를 확보, `existsById` 신설로 검증 효율화, Hibernate validate 통과 확인.
  - 아쉬운 점 예: fixture 변경 면적이 payment / cart 도메인까지 침투, 응답 echo 정리를 별도 트랙으로 미룬 점.

### 작성 톤

- 사후 정리. 결정의 사실 기록과 미래에 다시 읽을 때 도움이 되는 정도의 맥락 보강.
- ADR 본문을 그대로 복붙하지 않는다. ADR 은 결정 자체를 다루고 회고는 결정에 이르는 토론을 다룬다.
- 선행 stock 회고의 톤 / 구조 / 길이감을 참고해 series 일관성을 유지한다.

## 수정 가능 경로

- `docs/tasks/order-jpa-association-decouple/retrospective.md`

## Acceptance Criteria

```bash
./gradlew test
```

(문서 step 이지만 회귀 안전망으로 빌드를 함께 확인한다.)

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `retrospective.md` 가 작성됐는가?
   - 결정 흐름 4가지가 모두 다뤄졌는가?
   - ADR 에 이미 명시된 결정을 그대로 복붙하지 않고 토론 맥락을 보강했는가?
   - 후속 Payment sub-PR 의 baseline 이 명시됐는가?
   - 선행 stock 회고의 톤과 series 일관성을 유지했는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- ADR 의 결정 사실을 회고에서 재정의하지 마라. 이유: 회고는 결정의 사실 기록이 아니라 결정 흐름의 사후 정리.
- 새 결정을 회고에 도입하지 마라. 이유: 결정은 ADR / architecture 의 책임.
- 다른 task 의 회고를 손대지 마라. 이유: 본 sub-PR 의 범위가 아니다. 선행 stock sub-PR 의 retrospective.md 는 완료된 task 문서 불변 원칙.
- 기존 테스트를 깨뜨리지 마라.
