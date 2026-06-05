# Step 2: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/payment-jpa-association-decouple/prd.md`
- `/docs/tasks/payment-jpa-association-decouple/architecture.md`
- `/docs/tasks/payment-jpa-association-decouple/adr.md`
- `/docs/tasks/payment-jpa-association-decouple/api-spec.md`
- `/docs/tasks/payment-jpa-association-decouple/db-schema.md`
- `/docs/tasks/payment-jpa-association-decouple/phases/0-decouple/step1.md`

루트 docs 갱신 대상은 아래와 같다.

- `/docs/adr.md` — Task ADR 색인에 본 태스크 한 줄 추가 / ADR-020 본문에 후속 적용 노트 추가 (series 완료).
- `/docs/architecture.md` — Payment 도메인의 cross-aggregate ID 참조 컨벤션 적용 반영.

선행 sub-PR 의 sync-root-docs 결과도 참고한다.

- 선행 stock / order sub-PR 머지 후 `/docs/adr.md` 의 ADR-020 노트 / Task ADR 색인 형태를 그대로 따른다.

## 작업

이전 step 에서 만들어진 코드와 task 문서를 꼼꼼히 읽고, 본 sub-PR 의 결정 내용을 루트 docs 에 정확히 반영한다. 본 sub-PR 은 series 의 마지막 sub-PR 이므로 ADR-020 후속 트랙 완료를 명시한다.

### `/docs/adr.md` 갱신

- **Task ADR 색인** 표에 본 태스크 한 줄 추가:
  - Task: `payment-jpa-association-decouple`
  - adr 파일: `docs/tasks/payment-jpa-association-decouple/adr.md`
  - 주요 결정 키워드: Payment JPA cross-aggregate association 해제 (`@OneToOne Order` → `Long orderId`), `Payment.createCompleted(Long orderId, int amount, ...)` Long ID 시그니처, schema 무변경 원칙 (ADR-020 / 선행 sub-PR 연계), series 완료
- **ADR-020 본문**의 후속 노트 갱신:
  - 선행 stock / order sub-PR 노트 옆에 Payment aggregate 도 ADR-020 의 cross-aggregate ID 참조 원칙이 적용됨을 명시한다.
  - **ADR-020 후속 트랙 완료** 를 명시 — 기존 도메인 (Stock / Order / Payment) 모두 cross-aggregate ID 참조로 통일.
  - 적용 방식: JPA `@OneToOne` 제거 + `Long` ID 필드 도입, schema 변경 없음, FK 유지.
  - DB FK 일괄 제거 트랙이 별도 issue 로 발행될 예정임을 짧게 명시.

### `/docs/architecture.md` 갱신

- Payment 도메인의 cross-aggregate 참조 컨벤션 통일 반영.
  - 현재 architecture.md 에서 Payment 관련 aggregate / 참조 구조를 다루는 섹션을 확인하고, 해당 섹션의 객체 참조 표현을 ID 참조로 갱신한다.
  - Payment ↔ Order 는 별 aggregate 이며 `Long orderId` 로 참조하는 점을 명시.
  - PaymentAttempt 는 객체 참조 없이 식별자 (`merchantPayKey`, `pgPaymentId`) 기반으로 동작하는 점을 짧게 언급 (이미 그렇게 되어 있는 사실 기록).
  - fetch join 대체 일반 원칙은 Order PR #200 ADR 에서 단일 관리됨을 참조만 한다 — Payment 는 fetch join 사용처가 없어 본 PR 에서 새로 추가할 내용 없음.
  - ADR-020 후속 트랙 series (Stock / Order / Payment) 완료 사실을 footnote 로 명시. 후속 FK 일괄 제거 트랙 예고.

### 갱신 시 유의사항

- `/docs/db-schema.md` 는 schema 변경이 없으므로 손대지 않는다.
- `/docs/api-spec.md` 는 응답 계약이 그대로이므로 손대지 않는다.
- ADR-020 본문은 결정 사실은 보존하고 후속 노트만 추가/확장한다. 본문 자체를 재서술하지 않는다.
- 선행 stock / order sub-PR 의 ADR 노트와 형식이 충돌하지 않도록 동일 톤 유지.
- 표 순서는 알파벳 순 또는 기존 정렬 규칙을 유지한다.
- 머지 완료된 task 문서 (`docs/tasks/stock-jpa-association-decouple/**`, `docs/tasks/order-jpa-association-decouple/**`) 는 손대지 않는다 (완료된 task 폴더 불변 원칙).

## 수정 가능 경로

- `docs/adr.md`
- `docs/architecture.md`
- `docs/tasks/payment-jpa-association-decouple/**`

## Acceptance Criteria

```bash
./gradlew test
```

(문서만 변경하는 step 이지만 markdown rendering / 표 정합성 회귀를 막기 위해 빌드를 함께 돌린다.)

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `docs/adr.md` 의 Task ADR 색인에 `payment-jpa-association-decouple` 한 줄이 추가됐는가?
   - `docs/adr.md` 의 ADR-020 본문에 후속 노트 (series 완료) 가 본문 결정 사실을 침범하지 않고 짧게 부착됐는가?
   - `docs/architecture.md` 의 Payment 관련 섹션이 ID 참조 컨벤션을 반영하는가?
   - schema / api-spec 문서는 손대지 않았는가?
   - 머지 완료된 선행 task 문서 (`docs/tasks/stock-jpa-association-decouple/**`, `docs/tasks/order-jpa-association-decouple/**`) 는 손대지 않았는가? — `git status` 결과 확인.
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- ADR-020 본문을 재서술하지 마라. 이유: 결정의 사실은 그 시점 기록 그대로 보존한다. 후속 노트 형태로 부착한다.
- `docs/db-schema.md`, `docs/api-spec.md` 를 수정하지 마라. 이유: 본 sub-PR 은 schema / API 계약 변경 없음.
- 다른 task 의 ADR 색인 항목을 손대지 마라. 이유: 이번 sub-PR 의 변경 범위가 아니다.
- 선행 stock / order sub-PR 의 docs 항목을 수정하지 마라. 이유: 머지 완료된 task 문서 / 그 시점 기록은 불변 원칙.
- 기존 테스트를 깨뜨리지 마라.
