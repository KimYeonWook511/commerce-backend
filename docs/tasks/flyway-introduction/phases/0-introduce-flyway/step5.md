# Step 5: write-retrospective

## 읽어야 할 파일

먼저 아래 파일들을 읽고 본 phase에서 일어난 실제 변경/논의를 회상하라:

- `/docs/tasks/flyway-introduction/prd.md`
- `/docs/tasks/flyway-introduction/architecture.md`
- `/docs/tasks/flyway-introduction/adr.md`
- `/docs/tasks/flyway-introduction/db-schema.md`
- `/docs/ADR.md` ADR-024 (Step 4 결과)
- `/src/main/resources/db/migration/V1__init.sql` (Step 2 결과)
- 본 phase 안의 git 커밋 로그: `git log --oneline develop..HEAD`

다른 task의 회고 톤도 참고:
- `/docs/tasks/payment-attempt-unique-key-length/retrospective.md` (있다면)
- `/docs/tasks/hibernate-enum-jdbc-type-code/retrospective.md` (있다면)
- 다른 task들의 retrospective.md를 한두 개 더 살펴 톤/구조를 파악

## 작업

`docs/tasks/flyway-introduction/retrospective.md`를 생성한다. 본 task가 끝난 시점(2026-06-01 또는 실제 실행일)에 작성한다고 가정한다.

구성:

```markdown
# 회고: flyway-introduction

## 결정 요약

(ADR-024 결정의 한 문단 요약. 결정과 가장 본질적인 이유.)

## 도입 배경 — 두 사고

(사고 1, 사고 2 간략 — PRD와 ADR-024 본문을 짧게 압축. 회고 본문에는 "이 패턴을 두 번 본 시점에 입장이 뒤집혔다"는 사후 시선의 한 줄을 포함.)

## 진행 과정에서 검토한 대안

(plan/논의 과정에서 거른 대안. 예시:
- "test 프로파일을 전체 Testcontainers MySQL로 통일"이 고려됐지만 부팅 속도/Docker 의존도 증가로 탈락.
- "dockerTest에서도 Flyway 비활성 유지"가 초기 plan이었으나, create-drop이 매 테스트 drop이 아니고 컨텍스트 시작 시만 drop이라는 사실을 사용자가 짚어줘서 입장 변경. 결과적으로 dockerTest에 Flyway 활성이 자연스럽게 맞물림을 확인.
- ADR-024 작성 시 일반적 Flyway 장점 나열로 시작했지만, 사용자가 두 사고가 본질적 동기라고 짚어 톤을 재구성.)

## 진행 중 발견한 이슈와 처리

(V1 생성 단계에서 dump 정리 체크리스트 중 실제로 걸린 항목, Flyway 의존성 해석 결과, 부팅 검증에서 발견한 문제 등 — 없으면 "없음"으로 솔직히 적는다.)

## validate 도입 후 개발 흐름 변화

(엔티티만 수정하고 부팅 시 실패하는 흐름이 어떻게 작동했는지. Step 3 검증의 "의도적 불일치 시나리오"를 돌렸다면 그 결과 포함.)

## 남은 과제 / 후속 task

- PR 컨벤션에 "DB 마이그레이션" 섹션 추가 (별도 chore PR)
- `docs/db-schema.md`와 `V*__*.sql`의 역할 분담 상세 가이드 (별도 docs PR)
- CI(`ciTest`)가 dockerTest를 포함하는지 점검. 미포함 시 마이그레이션 회귀 자동 검증 미흡.
- (추가 발견 사항이 있으면 여기에)

## 다음에 비슷한 결정을 할 때 참고할 것

(시점 의존적 결정이라는 점, "단일 DB라도 silent drift는 일어난다"는 일반화, 회고 인용을 ADR에 그대로 박는 게 결정 근거 추적에 효과적이라는 메타 관찰 등 — 사후에 다시 읽었을 때 가치 있는 한 단락.)
```

회고는 *사후에 다시 읽었을 때 의사결정 과정을 복원할 수 있는 자료*가 되어야 한다. 단순 변경 요약이 아니라 **왜 그 선택을 했는지 / 어떤 막다른 길을 거쳐갔는지 / 무엇이 의외였는지**를 적는다.

## Acceptance Criteria

```bash
# (a) 파일 존재
test -f docs/tasks/flyway-introduction/retrospective.md

# (b) 핵심 섹션 존재
grep -q '## 결정 요약' docs/tasks/flyway-introduction/retrospective.md
grep -q '## 도입 배경' docs/tasks/flyway-introduction/retrospective.md
grep -q '## 진행 과정에서 검토한 대안' docs/tasks/flyway-introduction/retrospective.md
grep -q '## 남은 과제' docs/tasks/flyway-introduction/retrospective.md

# (c) 본문에 두 사고 키워드 포함
grep -q 'ENUM' docs/tasks/flyway-introduction/retrospective.md
grep -q 'unique' docs/tasks/flyway-introduction/retrospective.md

# (d) ADR/이슈 참조
grep -q 'ADR-024' docs/tasks/flyway-introduction/retrospective.md

# (e) 빈 placeholder 잔여 없음
! grep -q '(예시:' docs/tasks/flyway-introduction/retrospective.md
! grep -q '(없으면' docs/tasks/flyway-introduction/retrospective.md
```

위 모든 명령이 exit 0이어야 한다.

## 검증 절차

1. 본 phase의 git log를 보고 실제로 어떤 step에서 어떤 일이 일어났는지 회상한다.
2. ADR-024의 결정 요약과 두 사고 인용을 회고에 압축해 포함한다.
3. 진행 과정에서 실제로 막혔거나 입장이 바뀐 지점을 솔직히 기록한다 — 없으면 "없음"으로 적는다.
4. Acceptance Criteria 모든 명령 exit 0 확인.

## 금지사항

- 두 사고를 사후 시선으로 미화하지 마라. 이유: 회고는 솔직해야 가치가 있다.
- 다른 task의 회고를 복붙하지 마라. 이유: 도입 배경이 본 task만의 특수성에 있다.
- 다른 task의 retrospective.md를 수정하지 마라. 이유: 과거 회고는 immutable.
- "Flyway는 좋다" 같은 일반론으로 회고를 채우지 마라. 이유: ADR-024가 이미 일반론을 다 다룬다. 회고는 *이 task의 진행 과정*을 기록한다.
