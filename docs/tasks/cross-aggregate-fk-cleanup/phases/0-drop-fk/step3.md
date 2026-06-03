# Step 3: write-retrospective

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/cross-aggregate-fk-cleanup/prd.md`
- `/docs/tasks/cross-aggregate-fk-cleanup/architecture.md`
- `/docs/tasks/cross-aggregate-fk-cleanup/adr.md`
- `/docs/tasks/cross-aggregate-fk-cleanup/api-spec.md`
- `/docs/tasks/cross-aggregate-fk-cleanup/db-schema.md`
- step1 / step2 에서 생성·수정된 파일:
  - `/src/main/resources/db/migration/V4__drop_cross_aggregate_fk_constraints.sql`
  - `/docs/ADR.md`
  - `/docs/db-schema.md`
  - `/docs/architecture.md`

선행 series 의 회고도 톤·구조 참조용으로 읽는다.

- `/docs/tasks/stock-jpa-association-decouple/retrospective.md`
- `/docs/tasks/order-jpa-association-decouple/retrospective.md`
- `/docs/tasks/payment-jpa-association-decouple/retrospective.md`

## 작업

`docs/tasks/cross-aggregate-fk-cleanup/retrospective.md` 를 신규 작성한다. 본 회고는 ADR-020 후속 트랙 series (Stock #199 / Order #200 / Payment #202 / 본 FK cleanup) 4 트랙의 마무리 시점 회고다.

### 회고 구조

선행 회고 (특히 payment-jpa-association-decouple/retrospective.md) 의 섹션 구조를 따른다.

1. **개요** — 본 트랙이 series 마무리 단계임을 명시. 코드 변경 0건, schema 만 변경 (V4 1개 + 루트 docs 3개) 으로 좁은 변경 면적.
2. **결정 흐름** — ADR 의 결정 5개 (단일 V 파일 / UNIQUE 와 잔류 KEY 유지 / same-aggregate FK 제외 / 운영 배포 별도 결정 / 완료 task 불변) 의 흐름과 트레이드오프.
3. **기각된 옵션** — 도메인별 V 파일 분리 / FK + KEY 함께 DROP / 운영 배포 절차 포함 / lag 표준 ADR 명시 등을 표 형태로.
4. **series 전체 baseline 정리** — 4 트랙 series 의 메타 원칙·패턴 회수:
   - schema 변경 0건 → V4 1건으로 정합성 회복 (코드와 schema lag 종료)
   - Long ID 시그니처 패턴 (`Order.create(Long memberId)` / `Payment.createCompleted(Long orderId, int amount, ...)`)
   - fetch join 대체 일반 원칙 (Order PR #200 결정 2)
   - 응답 DTO 외부 주입 패턴 (Stock PR #199 → Order PR #200)
   - 응답 echo 정리 / 결제 시점 가격 snapshot 등 series 동안 분리해둔 별도 트랙들
5. **운영 점검** — 본 PR 머지 후 local / CI 환경의 즉시 적용, 운영 DB 배포 절차는 별도 결정으로 분리됨을 명시. lock 영향에 대한 일반 사실 (`DROP FOREIGN KEY` 는 짧은 metadata lock 만) 만 짧게 언급.
6. **자기 평가** — series 4 트랙 전체의 잘된 점 / 아쉬운 점.

### 본 트랙 특유의 baseline 항목 (필수 포함)

- **코드-schema lag 의 사실 기록**: 선행 sub-PR series 가 진행되는 동안 코드는 association 해제, schema 는 FK 유지의 *과도기 상태* 가 있었고, 본 트랙 머지 시점에 lag 가 종료됐다. lag 기간 (선행 PR 첫 머지 ~ 본 PR 머지) 을 사실로 기록.
- **lag 표준 정책 미정의**: 본 트랙에서 향후 다른 series 의 lag 허용 기간 표준을 ADR 로 박지 않기로 결정 (Discuss 단계). 표본 1건으로 표준화하지 않고, 향후 다른 series 에서 lag 가 반복 등장하면 그때 ADR 정립.
- **운영 DB 배포 별도 결정**: 본 PR 의 범위가 Flyway 파일 추가 + local/test 검증까지로 한정됨을 명시. 운영 배포 시점·절차는 후속.

### 회고 작성 톤

- 선행 회고들과 일관된 톤 유지 — 사실 기록 중심, 의도 추론은 사후 정직하게.
- 본 트랙은 변경 면적이 좁고 결정 항목도 적으므로, 결정 흐름 섹션은 선행 회고보다 짧게. baseline 정리 섹션은 series 4 트랙 전체를 회수하므로 비교적 길게.
- 한국어 / 영문 식별자 / 한국어 조사 컨벤션 (CLAUDE.md 언어 규칙) 그대로.

## 수정 가능 경로

- `docs/tasks/cross-aggregate-fk-cleanup/retrospective.md` (신규 파일만)
- `docs/tasks/cross-aggregate-fk-cleanup/**` (필요 시 보정)

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `docs/tasks/cross-aggregate-fk-cleanup/retrospective.md` 파일이 존재하는가?
   - 섹션 구조가 선행 회고 (`payment-jpa-association-decouple/retrospective.md`) 와 일관된가? (개요 / 결정 흐름 / 기각된 옵션 / series baseline / 운영 점검 / 자기 평가)
   - series 4 트랙 (Stock #199 / Order #200 / Payment #202 / 본 FK cleanup) 의 마무리 시점 baseline 이 명시됐는가?
   - 코드-schema lag 의 사실 기록과 lag 표준 미정의 결정이 명시됐는가?
   - 운영 DB 배포가 별도 결정으로 분리됐다는 사실이 명시됐는가?
   - 완료된 task 폴더의 ADR / retrospective 를 수정하지 않았는가?
     - `git diff --name-only docs/tasks/stock-jpa-association-decouple docs/tasks/order-jpa-association-decouple docs/tasks/payment-jpa-association-decouple` 결과 0건.
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 완료된 task 폴더 (`docs/tasks/stock-jpa-association-decouple/`, `docs/tasks/order-jpa-association-decouple/`, `docs/tasks/payment-jpa-association-decouple/`) 의 ADR / retrospective / phases 를 수정하지 마라. 이유: 완료된 tasks 불변 원칙 (CLAUDE.md / `docs/tasks/README.md` / ADR 결정 5).
- 회고에 lag 허용 기간 표준 정책을 명시하지 마라. 이유: 표본 1건으로 표준화하지 않기로 Discuss 단계에서 결정. 사실 기록 + 미정의 결정만.
- 회고에 운영 DB 배포 절차를 박지 마라. 이유: 본 PR 범위 밖 (ADR 결정 4). "운영 배포는 별도 결정" 사실만 명시.
- 코드 / Flyway / 루트 docs 를 추가로 수정하지 마라. 이유: 본 step 의 산출물은 회고 1개에 한정. step1 / step2 결정의 사후 보정이 필요하면 사용자 승인 후 별도 step 으로 진행.
- 기존 테스트를 깨뜨리지 마라.
