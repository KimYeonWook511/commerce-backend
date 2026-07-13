# CLAUDE.md

Java, Spring Boot, Gradle, MySQL, JPA(Hibernate) 백엔드 프로젝트.

## 기술 스택

- **언어/프레임워크**: Java 21, Spring Boot 3.5.9
- **빌드**: Gradle 8.14.3 (wrapper)
- **DB/영속성**: MySQL 8, JPA(Hibernate), Flyway
- 정확한 버전과 전체 의존성은 `build.gradle`이 단일 출처다. 위 목록은 주요 스택 요약이며, 버전을 올릴 때 함께 갱신한다.

---

## 핵심 규칙 (항상, 최우선)

이 일곱 가지는 다른 어떤 규칙보다 우선하며 예외 없이 지킨다.

1. **언어**: 답변·설명은 한국어. 코드 식별자(클래스·메서드·변수·패키지·테스트명)는 영어. 코드 식별자에 한국어를 섞지 않는다.
2. **승인 게이트**: 사용자 승인 전에는 파일을 생성·수정하지 않는다. 일반 구현은 Plan Mode로 계획을 제시하고 `ExitPlanMode`로 승인을 받은 뒤 실행한다. `spec-harness-v1` skill은 자체 승인 절차(Analyze 통과 후 중단·보고 → 사용자 진행 확인)를 따르며, 그 절차대로 승인받기 전에는 실행기(`execute.py`)·workflow를 실행하지 않는다.
3. **불명확하면 멈춤**: 임의로 판단하지 않고 구현 전에 사용자에게 먼저 확인한다. 근거 없이 기존 컨벤션을 무시하거나 사용처 없는 코드를 추가하지 않는다.
4. **완료 산출물 불변**: 머지된 작업 산출물은 수정하지 않는다 — 레거시 `docs/tasks/`(동결)와 승격된 `docs/specs/_archive/` 모두. 머지 후 발생한 변경은 루트 `docs/` 문서로만 표현한다. 상세는 `docs/tasks/README.md`.
5. **컨벤션 준수**: commit / PR / issue / 브랜치 생성은 예외 없이 해당 컨벤션을 따른다. 어떤 문서를 언제 읽을지는 아래 "시점별 규칙" 표를 따른다.
6. **문서 용어**: **모든 문서(md)를 작성할 때**, 개발자에게 익숙한 표준 기술 용어(멱등, 낙관 락, 단일 출처 등)는 그대로 쓴다. 반면 일반적이지 않은 비유·축약이나 난해한 표현은 쉽게 풀어써 명료하게 다듬는다. 판단 기준은 "누가 읽어도 바로 이해되는가"라는 가독성이며, 단어 목록(사전)을 만들지 말고 이 기준으로 그때그때 판단한다.
7. **브랜치 보호**: `main`과 `develop`은 보호 브랜치다. **어떤 경우에도 이 두 브랜치를 직접 변경하지 않는다** — 직접 push·commit·merge·force push·삭제 금지. 모든 변경은 **피처 브랜치 → PR → 머지**로만 반영한다. 세부 금지·허용은 아래 "브랜치 보호" 절을 따른다.

---

## 브랜치 보호 (main · develop)

`main`·`develop`은 보호 브랜치다. 직접 변경하지 않고 **피처 브랜치 → PR 머지**로만 반영한다. 차단 명령·플래그와 3중 방어 구조(hook → 이 규칙 → 서버 branch protection)는 `docs/claude/hooks/pre-tool-use-policy.md`를 따른다. agent는 어떤 경우에도 merge하지 않는다 — merge는 사람이 수동으로 한다.

특히 hook이 막지 못하는 다음은 hook·서버 상태와 무관하게 **항상** 지킨다.

- GitHub API 직접 쓰기(`gh api`·`curl`)로 차단을 우회하지 않는다.
- hook·서버 보호를 끄거나 `--admin` 머지로 차단을 무시하지 않는다.
- hook이 막은 작업을 다른 경로로 우회하지 않는다 — 막힌 작업은 PR 흐름으로 다시 진행한다.

올바른 흐름: 피처 분기(`docs/branch-conventions.md`) → 작업·push → `gh pr create`(`docs/pr-conventions.md`) → PR 머지.

---

## 시점별 규칙 (트리거 → 행동)

특정 작업을 하기 직전/직후에 반드시 수행한다.

### 명령 실행 전 컨벤션 확인

| 시점 | 먼저 읽고 확인할 것 |
| --- | --- |
| `git commit` 전 | `docs/commit-conventions.md` — 타입, subject 문체(`~한다`), 형식 |
| `gh pr create` 전 | `docs/pr-conventions.md` — 타입, 형식, draft 여부 |
| `gh pr merge` 전 | `docs/pr-conventions.md` 머지 규칙 (단, 보호 브랜치 셀프 머지·`--admin` 금지 — "브랜치 보호" 절 참고) |
| `gh issue create` 전 | `.github/issue_template.md` — 섹션 구조 |
| `git worktree add` 전 | `docs/branch-conventions.md` — 형식 |

### 코드 변경 후 루트 문서 동기화

코드를 수정·작성한 뒤, 변경이 아래 표의 종류에 해당하면 **같은 작업 안에서** 해당 루트 문서를 현재 코드 기준으로 갱신한다. 내부 구현만 바뀐 경우는 동기화하지 않는다. 동기화 여부를 "판단"하지 말고 표와 **대조**한다.

단, **spec-harness 실행 중에는 이 절이 적용되지 않는다** — 구현과 PR 리뷰 반영이 끝나 harness가 **Root Sync(Stage 8)**에 이르기 전까지는 루트 상태 문서(`docs/api-spec.md`·`docs/architecture.md`·`docs/db-schema.md`)를 갱신하지 않는다. 코드가 spec 설계와 달라지면 해당 spec 폴더의 설계 md를 as-built로 갱신하고, 루트 승격은 Root Sync에서 한 번에 한다. (근거: 이 repo는 squash-merge라 중간 커밋이 사라지고, PR 리뷰가 계약·스키마·구조를 바꿀 수 있어, 실행 중 미리 동기화하면 재작업·stale 위험만 크다.)

| 변경 종류 | 동기화 대상 | 동작 |
| --- | --- | --- |
| API 계약 (엔드포인트·요청·응답·실패코드) | `docs/api-spec.md` | 현재 상태로 갱신 |
| DB 스키마 (테이블·컬럼·인덱스·제약) | `docs/db-schema.md` | 현재 상태로 갱신 (실제 DDL은 Flyway V스크립트가 단일 출처) |
| 구조 (모듈·레이어·책임 이동, 서비스 신설/이동) | `docs/architecture.md` | 현재 상태로 갱신 |
| 설계 결정 (정책·트레이드오프) | `docs/adr/` | **새 파일 추가** (기존 ADR 수정 금지, supersede 시 옛 ADR의 Status만 갱신) |
| 내부 구현만 (이름 정리·로직 리팩터) | 없음 | 동기화 불필요 |

> 이 표에는 **현재 상태를 기록하는 문서**(api-spec, db-schema, architecture, adr)만 둔다. 규칙·전략 문서(`*-conventions.md`, `exception-strategy.md`, `optimistic-lock-design.md`)는 코드를 *따라가는* 게 아니라 코드를 *이끄는* 결정이므로 이 표에 넣지 않는다. 그 문서가 바뀌는 것은 코드 변경의 부산물이 아니라 방침을 바꾸는 결정이며, ADR로 남기고 그 후속으로 해당 규칙·전략 문서를 갱신한다.

동기화 규율:
- 갱신은 기억으로 재작성하지 말고 **루트 현재 파일 + 변경된 코드를 둘 다 보고** 이번 변경분만 반영한다. 안 바뀐 부분은 보존한다.
- 갱신 후 무엇을 바꿨는지 한 줄로 보고한다.
- 루트 문서는 **개념**을 기술하고, 클래스·메서드 이름은 최소로만 박는다. 이름을 박으면 그 코드를 리팩터할 때 문서도 같이 고쳐야 하며, 안 고치면 문서가 코드와 안 맞게 된다. 정확한 목록은 코드가 기준이다.

---

## 코드 작성 원칙 (상시)

- 도메인 중심 네이밍을 우선하고 기존 프로젝트 패턴을 따른다.
- 비즈니스 로직은 Domain 또는 application 계층에 둔다. Controller는 요청 수신·입력 검증·서비스 위임·응답 반환만 담당한다.
- 외부 시스템 연동(Redis, 이메일, 결제 PG 등)은 `application/port/` 인터페이스로만 의존한다.
- 레이어 배치·역할 접미사(`…UseCase`/`…Service`)·`@Transactional` 위치는 `docs/package-structure-conventions.md`를 따른다.
- 예외 처리(DB 무결성 위반 안전망 위임, 낙관 락 충돌, 보상 catch)는 `docs/exception-strategy.md`를, 낙관 락(@Version) 충돌의 tx 경계 처리 상세는 `docs/optimistic-lock-design.md`를 따른다.
- 영속성(enum 매핑·unique 제약·마이그레이션)은 `docs/persistence-conventions.md`, 테스트는 `docs/test-code-conventions.md`, 로깅은 `docs/logging-conventions.md`를 따른다.
- 위 구조 규칙(@Transactional 위치, 예외 격리 등) 중 기계로 검증 가능한 것은 `ArchitectureRulesTest`(ArchUnit)가 강제한다.
- 불필요한 추상화와 과한 설계를 피한다. 사용처 없는 인터페이스 메서드를 남기지 않는다.
- 코드를 수정할 때 기존 주석을 삭제하지 않으며, 코드 위치가 바뀌면 주석도 함께 이동한다.
- 코드 주석·테스트명은 **자립적**으로 쓴다. spec·ADR의 내부 식별자(`FR-###`·`SC-###` 등)는 코드에 남기지 않는다 — spec은 작업 후 아카이브되고 코드만 남아 그 ID가 코드 독자에게 무의미해지기 때문이다. "왜"는 ID 없이 문장으로 설명한다.

---

## 표기·Git 세부

- 한국어 문장에서 영문 용어 뒤 조사는 붙여 쓴다 (`race가`, `mock으로`, `latch는`, `stub한다`). 의존명사(`자체`, `간`, `등`)나 일반 명사는 띄어 쓴다 (`mock 응답`, `thread 간`).
- 커밋 메시지에 `Co-Authored-By` 줄을 붙이지 않는다.
- 브랜치 운영은 "브랜치 보호" 절을 최우선으로 따른다. `main`·`develop` 직접 변경 금지, 모든 변경은 PR 머지로만.

---

## 참고 문서

핵심 설계·스펙 (상태·요구)
- 기능 범위(PRD): `docs/prd.md`
- 백엔드 구조와 의존성: `docs/architecture.md`
- API 스펙: `docs/api-spec.md`
- DB 스키마: `docs/db-schema.md`
- 설계 결정(ADR): `docs/adr/` (결정 1개 = 파일 1개), 작성 규칙은 `docs/adr-conventions.md`

규칙 (conventions)
- 브랜치 / 커밋 / PR: `docs/branch-conventions.md`, `docs/commit-conventions.md`, `docs/pr-conventions.md`
- 패키지 배치: `docs/package-structure-conventions.md`
- 영속성(JPA/DB): `docs/persistence-conventions.md`
- 테스트 코드: `docs/test-code-conventions.md`
- 로깅: `docs/logging-conventions.md`

전략·설계 (strategy)
- 예외 처리: `docs/exception-strategy.md`
- 낙관 락(@Version) 충돌 처리: `docs/optimistic-lock-design.md`
- 명세 불가침 원칙(위험영역): `docs/spec-constitution.md`

명세·하네스 운영
- spec 작업 체계·8-Stage 흐름: `docs/claude/skills/spec-harness-v1.md` (skill 본문: `.claude/skills/spec-harness-v1/SKILL.md`)
- 레거시 task 문서 운영 가이드(동결): `docs/tasks/README.md`
- Claude Code hook 구조: `docs/claude/hooks/overview.md`
- Claude Code skill 문서: `docs/claude/skills/*`
