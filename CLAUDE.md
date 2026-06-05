# CLAUDE.md

Java, Spring Boot, Gradle, MySQL, JPA(Hibernate) 백엔드 프로젝트.

---

## 핵심 규칙 (항상, 최우선)

이 네 가지는 다른 어떤 규칙보다 우선하며 예외 없이 지킨다.

1. **언어**: 답변·설명은 한국어. 코드 식별자(클래스·메서드·변수·패키지·테스트명)는 영어. 코드 식별자에 한국어를 섞지 않는다.
2. **승인 게이트**: 사용자 승인 전에는 파일을 생성·수정하지 않는다. 구현 전 Plan Mode로 계획을 제시하고 `ExitPlanMode`로 승인을 받은 뒤 실행한다. `/harness` skill의 `execute.py`도 진행 확인 후 실행한다.
3. **불명확하면 멈춤**: 임의로 판단하지 않고 구현 전에 사용자에게 먼저 확인한다. 근거 없이 기존 컨벤션을 무시하거나 사용처 없는 코드를 추가하지 않는다.
4. **완료 task 문서 불변**: 머지된 `docs/tasks/<task-name>/` 문서는 이후 수정하지 않는다. 머지 후 발생한 변경은 루트 `docs/` 문서로만 표현한다. 상세는 `docs/tasks/README.md`의 "완료된 tasks 불변 원칙".
5. **컨벤션 준수**: commit / PR / issue / 브랜치 생성은 예외 없이 해당 컨벤션을 따른다. 어떤 문서를 언제 읽을지는 아래 "시점별 규칙" 표를 따른다.

---

## 시점별 규칙 (트리거 → 행동)

특정 작업을 하기 직전/직후에 반드시 수행한다.

### 명령 실행 전 컨벤션 확인

| 시점 | 먼저 읽고 확인할 것 |
| --- | --- |
| `git commit` 전 | `docs/commit-conventions.md` — 타입, subject 문체(`~한다`), 형식 |
| `gh pr create` 전 | `docs/pr-conventions.md` — 타입, 형식, draft 여부 |
| `gh pr merge` 전 | `docs/pr-conventions.md` 머지 규칙 — extended description 정리, 후속 작업 체크 상태 |
| `gh issue create` 전 | `.github/ISSUE_TEMPLATE.md` — 섹션 구조 |
| `git worktree add` 전 | `docs/branch-conventions.md` — 형식 |

### 코드 변경 후 루트 문서 동기화

코드를 수정·작성한 뒤, 변경이 아래 표의 종류에 해당하면 **같은 작업 안에서** 해당 루트 문서를 현재 코드 기준으로 갱신한다. 내부 구현만 바뀐 경우는 동기화하지 않는다. 동기화 여부를 "판단"하지 말고 표와 **대조**한다.

| 변경 종류 | 동기화 대상 | 동작 |
| --- | --- | --- |
| API 계약 (엔드포인트·요청·응답·실패코드) | `docs/api-spec.md` | 현재 상태로 갱신 |
| DB 스키마 (테이블·컬럼·인덱스·제약) | `docs/db-schema.md` | 현재 상태로 갱신 (실제 DDL은 Flyway V스크립트가 단일 출처) |
| 구조 (모듈·레이어·책임 이동, 서비스 신설/이동) | `docs/architecture.md` | 현재 상태로 갱신 |
| 설계 결정 (정책·트레이드오프) | `docs/ADR.md` | **append** (기존 ADR 수정 금지, 새 번호 추가 + supersede 표시) |
| 내부 구현만 (이름 정리·로직 리팩터) | 없음 | 동기화 불필요 |

동기화 규율:
- 갱신은 기억으로 재작성하지 말고 **루트 현재 파일 + 변경된 코드를 둘 다 보고** 이번 변경분만 반영한다. 안 바뀐 부분은 보존한다.
- 갱신 후 무엇을 바꿨는지 한 줄로 보고한다.
- 루트 문서는 **개념**을 기술하고 메서드·클래스명 같은 코드 심볼은 최소로 박는다. 심볼을 박을 때는 리팩터 시 함께 갱신해야 함을 인지한다.
- AI와의 자유 작업이든 harness Stage 8이든, 코드 작업을 마칠 때 이 표를 한 번 대조하는 것을 기본 절차로 한다.

---

## 코드 작성 원칙 (상시)

- 도메인 중심 네이밍을 우선하고 기존 프로젝트 패턴을 따른다.
- 비즈니스 로직은 Domain 또는 application 계층에 둔다. Controller는 요청 수신·입력 검증·서비스 위임·응답 반환만 담당한다.
- 외부 시스템 연동(Redis, 이메일, 결제 PG 등)은 `application/port/` 인터페이스로만 의존한다.
- Service 클래스는 유스케이스 단위 단일 행위만 담당한다 (`CreateOrderService`, `CancelOrderService` 형식).
- DB 무결성 위반은 Application/Adapter에서 catch 하지 않고 `GlobalExceptionHandler` 안전망(500)으로 위임한다.
- 불필요한 추상화와 과한 설계를 피한다. 사용처 없는 인터페이스 메서드를 남기지 않는다.
- 코드를 수정할 때 기존 주석을 삭제하지 않으며, 코드 위치가 바뀌면 주석도 함께 이동한다.

---

## 표기·Git 세부

- 한국어 문장에서 영문 용어 뒤 조사는 붙여 쓴다 (`race가`, `mock으로`, `latch는`, `stub한다`). 의존명사(`자체`, `간`, `등`)나 일반 명사는 띄어 쓴다 (`mock 응답`, `thread 간`).
- 커밋 메시지에 `Co-Authored-By` 줄을 붙이지 않는다.

---

## 참고 문서

핵심 설계·스펙
- 기능 범위: `docs/PRD.md`
- 설계 결정: `docs/ADR.md`
- 백엔드 구조와 의존성: `docs/architecture.md`
- API 스펙: `docs/api-spec.md`
- DB 스키마: `docs/db-schema.md`
- 예외 처리 정책: `docs/exception-strategy.md`

컨벤션
- 브랜치 / 커밋 / PR / 테스트 / 로깅: `docs/branch-conventions.md`, `docs/commit-conventions.md`, `docs/pr-conventions.md`, `docs/testing-conventions.md`, `docs/logging-conventions.md`

task·하네스 운영
- task별 문서 운영 가이드: `docs/tasks/README.md`
- Claude Code hook 구조: `docs/claude/hooks/README.md`
- Claude Code skill 문서: `docs/claude/skills/*`
