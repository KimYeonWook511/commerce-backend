# Step 2: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/stock-jpa-association-decouple/prd.md`
- `/docs/tasks/stock-jpa-association-decouple/architecture.md`
- `/docs/tasks/stock-jpa-association-decouple/adr.md`
- `/docs/tasks/stock-jpa-association-decouple/api-spec.md`
- `/docs/tasks/stock-jpa-association-decouple/db-schema.md`
- `/docs/tasks/stock-jpa-association-decouple/phases/0-decouple/step1.md`

루트 docs 갱신 대상은 아래와 같다.

- `/docs/ADR.md` — ADR-020 본문에 후속 트랙 적용 노트 추가 / task ADR 색인 갱신.
- `/docs/architecture.md` — Stock / StockHistory aggregate 의 cross-aggregate ID 참조 컨벤션 적용 반영.

## 작업

이전 step 에서 만들어진 코드와 task 문서를 꼼꼼히 읽고, 본 sub-PR 의 결정 내용을 루트 docs 에 정확히 반영한다.

### `/docs/ADR.md` 갱신

- **Task ADR 색인** 표에 본 태스크 한 줄 추가:
  - Task: `stock-jpa-association-decouple`
  - adr 파일: `docs/tasks/stock-jpa-association-decouple/adr.md`
  - 주요 결정 키워드: Stock·StockHistory JPA cross-aggregate association 해제, application 외부 주입 패턴, schema 무변경 원칙 (ADR-020 연계)
- **ADR-020 본문**에 "후속" 노트 추가:
  - Stock / StockHistory aggregate 에 ADR-020 의 cross-aggregate ID 참조 원칙이 적용됨을 명시한다.
  - 적용 방식: JPA `@OneToOne` / `@ManyToOne` 제거 + `Long` ID 필드 도입, schema 변경 없음, FK 유지.
  - 관련 결정 (응답 조립 패턴, schema 무변경 원칙) 은 task adr 로 위임한다.
  - 후속 트랙 (`order-jpa-association-decouple`, `payment-jpa-association-decouple`) 명시.

### `/docs/architecture.md` 갱신

- Stock / StockHistory aggregate 의 cross-aggregate 참조 컨벤션 통일 반영.
  - 현재 architecture.md 에서 Stock / StockHistory 관련 aggregate / 참조 구조를 다루는 섹션을 확인하고, 해당 섹션의 객체 참조 표현을 ID 참조로 갱신한다.
  - Stock·StockHistory 가 별도 aggregate 임을 명시하고, 후속 sub-PR (Order, Payment) 의 진행 방향을 footnote 로 짧게 언급.

### 갱신 시 유의사항

- `/docs/db-schema.md` 는 schema 변경이 없으므로 손대지 않는다.
- `/docs/api-spec.md` 는 응답 계약이 그대로이므로 손대지 않는다.
- ADR-020 본문은 결정 사실은 보존하고 후속 노트만 추가한다. 본문 자체를 재서술하지 않는다.
- 표 순서는 알파벳 순 또는 기존 정렬 규칙을 유지한다.

## 수정 가능 경로

- `docs/ADR.md`
- `docs/architecture.md`
- `docs/tasks/stock-jpa-association-decouple/**`

## Acceptance Criteria

```bash
./gradlew test
```

(문서만 변경하는 step 이지만 markdown rendering / 표 정합성 회귀를 막기 위해 빌드를 함께 돌린다.)

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `docs/ADR.md` 의 Task ADR 색인에 `stock-jpa-association-decouple` 한 줄이 추가됐는가?
   - `docs/ADR.md` 의 ADR-020 본문에 후속 노트가 본문 결정 사실을 침범하지 않고 짧게 부착됐는가?
   - `docs/architecture.md` 의 Stock / StockHistory 관련 섹션이 ID 참조 컨벤션을 반영하는가?
   - schema / api-spec 문서는 손대지 않았는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- ADR-020 본문을 재서술하지 마라. 이유: 결정의 사실은 그 시점 기록 그대로 보존한다. 후속 노트 형태로 부착한다.
- `docs/db-schema.md`, `docs/api-spec.md` 를 수정하지 마라. 이유: 본 sub-PR 은 schema / API 계약 변경 없음.
- 다른 task 의 ADR 색인 항목을 손대지 마라. 이유: 이번 sub-PR 의 변경 범위가 아니다.
- 기존 테스트를 깨뜨리지 마라.
