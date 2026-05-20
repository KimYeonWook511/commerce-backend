# Step 8: write-retrospective

## 읽어야 할 파일

먼저 아래 파일들을 읽고 작업 전체를 회고할 수 있도록 맥락을 파악하라:

- `/docs/tasks/unique-find-first-policy/prd.md`
- `/docs/tasks/unique-find-first-policy/architecture.md`
- `/docs/tasks/unique-find-first-policy/adr.md`
- `/docs/tasks/db-constraint-violation-handling/retrospective.md` (이전 태스크 회고 — 참고용)
- 본 phase 의 step 0~6 문서와 각 step 의 실제 변경 내역
- 본 phase 에서 발생한 결정 변경, 보류, 우회 사례

step 6 까지 모두 끝나 있어야 한다.

## 작업

`docs/tasks/unique-find-first-policy/retrospective.md` 를 새로 작성한다. 회고록은 immutable 정책 적용 대상이므로 신중하게 한 번에 작성한다.

### 구조 (참고용 — 이전 회고록을 참고해 맞춤)

```markdown
# 회고록: unique-find-first-policy

## 1. 작업 요약

- 새 정책의 본질 흐름과 적용 결과를 3-5문장으로 요약
- 5곳의 변경 범위 한 줄 요약
- 안전망 보강(DataAccessException) 한 줄 요약

## 2. 결정한 정책

- 본질 흐름: `DB find → 없으면 insert → 충돌 시 500`
- 적용 조건 / 비적용 상황 표
- 5곳 매핑 표 (변경 패턴 / 행위 변경 / 안전망 도달 케이스)
- DataAccessException 안전망 계층 구조

## 3. 주요 발견 및 논의

- 옵션 A/B/C 비교와 옵션 B 선택 근거
- OrderCreate 의 두 unique 제약 분기 불필요 결론
- DataAccessException 부모 핸들러 vs Exception fallback 보강 비교
- spy 제거 가능성 검증 결과 (step 5)
- 그 외 구현 중 발견한 사항

## 4. 변경 범위 정리

- 파일별 변경 요약 표 (production / test / docs)

## 5. 미결 과제

- Exception.class fallback 의 stack trace 누락 보강 (별도 과제로 분리)
- workspace docs (api-contract.md) 동기화 (Frontend 세션 책임)
- 그 외 본 PR 에서 보류한 사항

## 6. 회고

### 잘된 점

- 정책 단순화 (5곳 → 단일 본질 흐름)
- 인프라 예외 의존 제거
- 운영 모니터링 카테고리 분리 (COMMON-500-2)
- 그 외 구체 사례

### 개선할 점

- step 분할/결합 판단에서 시행착오가 있었다면 기록
- 통합 테스트 setup 비용으로 시나리오를 좁혔다면 기록
- harness 개선 제안 (있으면 별도 섹션)
```

### 작성 원칙

- **사실 기반**: 실제 코드 변경과 step 실행 결과에 근거한다. 모델 진술이 아닌 실제 산출물 기준.
- **결정 근거 보존**: 옵션 A/B/C 비교, 적용 조건 등 사용자가 ADR 에 요청한 핵심 근거를 그대로 회고에 반영한다.
- **이전 회고 참조**: 형식과 톤은 `docs/tasks/db-constraint-violation-handling/retrospective.md` 를 따른다.
- **harness 개선 제안 별도 섹션**: 본 작업 중 harness skill 운영에서 발견한 개선점이 있으면 미결 과제 안에 별도 항목으로 분리한다.

### 작성 위치

`docs/tasks/unique-find-first-policy/retrospective.md` 신규 작성.

## Acceptance Criteria

```bash
./gradlew test
```

(본 step 은 회고 문서 작성만 수행하므로 코드 빌드/테스트는 회귀 방어 차원에서 통과해야 한다.)

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `retrospective.md` 가 6 개 표준 섹션을 모두 갖추고 있는가?
   - ADR 의 핵심 결정 근거가 회고 §2 와 §3 에 반영되어 있는가?
   - 미결 과제가 명확히 분리되어 있는가?
   - 회고록 본문이 실제 변경 사실에 근거해 작성되었는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 이전 회고록(`docs/tasks/db-constraint-violation-handling/retrospective.md`) 을 수정하지 마라. 이유: 회고 문서 immutable 정책.
- 회고록 본문에 모델 진술만 적지 마라. 이유: 실제 변경 사실 / step 실행 결과 / ADR 의 결정 근거에 기반해야 한다.
- 회고록에 행위 변경을 누락하지 마라. 이유: PR 본문과 향후 운영 모니터링에서 참조될 핵심 정보다.
- 본 태스크의 task 문서(`prd.md`, `architecture.md`, `adr.md`) 를 본 step 에서 수정하지 마라. 이유: File Drafting 시점에 작성된 문서이며 회고는 사실 기록이다. 변경이 필요하면 별도 step / 별도 태스크로 처리한다.
- 기존 테스트를 깨뜨리지 마라.
