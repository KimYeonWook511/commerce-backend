# Step 3: write-retrospective

## 읽어야 할 파일

먼저 아래 파일들을 읽고 이번 sub-PR 에서 진행된 결정 흐름과 변경 내용을 파악하라:

- `/docs/tasks/stock-jpa-association-decouple/prd.md`
- `/docs/tasks/stock-jpa-association-decouple/architecture.md`
- `/docs/tasks/stock-jpa-association-decouple/adr.md`
- `/docs/tasks/stock-jpa-association-decouple/api-spec.md`
- `/docs/tasks/stock-jpa-association-decouple/db-schema.md`
- `/docs/tasks/stock-jpa-association-decouple/phases/0-decouple/step1.md`
- `/docs/tasks/stock-jpa-association-decouple/phases/0-decouple/step2.md`

참고:

- `/docs/tasks/cart/retrospective.md` — ADR-020 의 최초 적용 사례 회고. 본 sub-PR 회고의 톤 / 구조 참고용.

## 작업

`docs/tasks/stock-jpa-association-decouple/retrospective.md` 를 작성한다. 본 sub-PR 진행 중 내려진 결정의 흐름, trade-off, 후속 sub-PR 에 넘기는 baseline 을 기록한다.

회고는 결정 사후 정리이지 새 결정 도입의 공간이 아니다. ADR 에 이미 명시된 결정은 그대로 인용하고, 결정에 이르기까지의 토론·기각된 옵션·향후 트랙 의 의미만 보강한다.

### 필수 섹션

- **개요** — 본 sub-PR 의 목적과 결과를 짧게.
- **결정 흐름** — 주요 분기점 4가지 정리:
  1. 도메인별 sub-PR 분리 (vs 한 번에 처리). 근거.
  2. StockHistory 의 productId 처리 옵션 비교 (응답 필드 제거 / 외부 주입 / 컬럼 신설) 와 외부 주입 선택 이유.
  3. schema 변경 없이 진행하는 메타 원칙 채택 이유. Hibernate `validate` 통과 가능성 분석.
  4. fetch join 대체 패턴을 본 sub-PR 의 ADR 에 미리 박지 않은 이유.
- **기각된 옵션** — 검토했으나 채택하지 않은 옵션과 사유. ADR 의 "근거" 와 중복되지 않도록 토론 중에 나왔던 추가 맥락을 보강.
- **후속 트랙으로 넘기는 baseline** — Order / Payment sub-PR 이 본 sub-PR 을 어떻게 참조하면 되는지. fetch join 대체 패턴 결정은 Order sub-PR 에서 처음 정립.
- **운영 점검** — DB FK 가 schema 에 남아있고 JPA 가 인식하지 않는 상태의 운영 모니터링 영향. 별도 트랙으로 FK 일괄 제거 시점이 올 때까지 유지되는 상태.

### 작성 톤

- 사후 정리. 결정의 사실 기록과 미래에 다시 읽을 때 도움이 되는 정도의 맥락 보강.
- ADR 본문을 그대로 복붙하지 않는다. ADR 은 결정 자체를 다루고 회고는 결정에 이르는 토론을 다룬다.
- 자기비판이 필요한 부분은 명시 (예: 응답 echo 정리를 별도 트랙으로 미룬 점, 본 sub-PR 의 변경 면적이 test fixture 호출부 다수에 걸친 점 등).

## 수정 가능 경로

- `docs/tasks/stock-jpa-association-decouple/retrospective.md`

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
   - 후속 Order / Payment sub-PR 의 baseline 이 명시됐는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- ADR 의 결정 사실을 회고에서 재정의하지 마라. 이유: 회고는 결정의 사실 기록이 아니라 결정 흐름의 사후 정리.
- 새 결정을 회고에 도입하지 마라. 이유: 결정은 ADR / architecture 의 책임.
- 다른 task 의 회고를 손대지 마라. 이유: 본 sub-PR 의 범위가 아니다.
- 기존 테스트를 깨뜨리지 마라.
