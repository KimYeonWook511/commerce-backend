# CLAUDE.md

Java, Spring Boot, Gradle, MySQL, JPA(Hibernate) 백엔드 프로젝트.

---

## 핵심 규칙 (항상, 최우선)

이 다섯 가지는 다른 어떤 규칙보다 우선하며 예외 없이 지킨다.

1. **언어**: 답변·설명은 한국어. 코드 식별자(클래스·메서드·변수·패키지·테스트명)는 영어. 코드 식별자에 한국어를 섞지 않는다.
2. **승인 게이트**: 사용자 승인 전에는 파일을 생성·수정하지 않는다. 일반 구현은 Plan Mode로 계획을 제시하고 `ExitPlanMode`로 승인을 받은 뒤 실행한다. harness 계열 skill(`/harness`, `/harness-v2`, `/harness-v3` 등)은 각 skill이 정의한 자체 승인 절차를 따르며, 그 절차대로 승인받기 전에는 실행기(`execute.py`)를 실행하지 않는다.
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
| `gh issue create` 전 | `.github/issue_template.md` — 섹션 구조 |
| `git worktree add` 전 | `docs/branch-conventions.md` — 형식 |

### 코드 변경 후 루트 문서 동기화

코드를 수정·작성한 뒤, 변경이 아래 표의 종류에 해당하면 **같은 작업 안에서** 해당 루트 문서를 현재 코드 기준으로 갱신한다. 내부 구현만 바뀐 경우는 동기화하지 않는다. 동기화 여부를 "판단"하지 말고 표와 **대조**한다.

| 변경 종류 | 동기화 대상 | 동작 |
| --- | --- | --- |
| API 계약 (엔드포인트·요청·응답·실패코드) | `docs/api-spec.md` | 현재 상태로 갱신 |
| DB 스키마 (테이블·컬럼·인덱스·제약) | `docs/db-schema.md` | 현재 상태로 갱신 (실제 DDL은 Flyway V스크립트가 단일 출처) |
| 구조 (모듈·레이어·책임 이동, 서비스 신설/이동) | `docs/architecture.md` | 현재 상태로 갱신 |
| 설계 결정 (정책·트레이드오프) | `docs/adr.md` | **append** (기존 ADR 수정 금지, 새 번호 추가 + supersede 표시) |
| 정책 문서 내용 (예외/충돌/로깅/패키지 배치 규칙) | `docs/exception-strategy.md`, `docs/optimistic-lock-design.md`, `docs/logging-conventions.md`, `docs/package-structure-guide.md`, `docs/testing-conventions.md` 중 해당 문서 | 그 정책이 바뀌면 해당 문서 갱신 |
| 내부 구현만 (이름 정리·로직 리팩터) | 없음 | 동기화 불필요 |

동기화 규율:
- 갱신은 기억으로 재작성하지 말고 **루트 현재 파일 + 변경된 코드를 둘 다 보고** 이번 변경분만 반영한다. 안 바뀐 부분은 보존한다.
- 갱신 후 무엇을 바꿨는지 한 줄로 보고한다.
- 루트 문서는 **개념**을 기술하고, 클래스·메서드 이름은 최소로만 박는다. 이름을 박으면 그 코드를 리팩터(이름 변경 등)할 때 문서도 같이 고쳐야 하며, 안 고치면 문서가 코드와 안 맞게 된다. 정확한 클래스·메서드 목록은 코드가 기준이다.
- AI와의 자유 작업이든 harness Stage 8이든, 코드 작업을 마칠 때 이 표를 한 번 대조하는 것을 기본 절차로 한다.

---

## 코드 작성 원칙 (상시)

- 도메인 중심 네이밍을 우선하고 기존 프로젝트 패턴을 따른다.
- 비즈니스 로직은 Domain 또는 application 계층에 둔다. Controller는 요청 수신·입력 검증·서비스 위임·응답 반환만 담당한다.
- 외부 시스템 연동(Redis, 이메일, 결제 PG 등)은 `application/port/` 인터페이스로만 의존한다.
- application service 클래스는 유스케이스 단위 단일 행위만 담당한다. **역할별 접미사**(ADR-006 supersede): `application/usecase/`는 `…UseCase`(흐름 조립·정책 선택, tx 없음), `application/service/`는 `…Service`(tx 단위작업, `@Transactional`). 예: `NaverPayApprovalUseCase`, `CreateOrderService`. `@Transactional`은 `service` 패키지에만 둔다. 여러 단위작업을 한 tx로 묶을 땐 usecase가 아니라 묶는 메서드를 `service`에 만들어 거기에 tx를 단다. 단순 작업(조율 없음)은 usecase 없이 Controller가 service를 직접 호출한다. 배치 기준은 `docs/package-structure-guide.md`.
- DB 무결성 위반은 Application/Adapter에서 catch 하지 않고 `GlobalExceptionHandler` 안전망(500)으로 위임한다.
- 낙관 락(@Version) 충돌은 tx 경계 안에서 catch하지 않고(도메인 예외로 전파시켜 깨끗이 rollback), skip/retry/전파는 tx 경계 밖에서 정한다. 변환은 `infrastructure/persistence/` adapter가 한다. 상세는 `docs/optimistic-lock-design.md`.
- 위 구조 규칙(@Transactional 위치, 예외 격리 등) 중 기계로 검증 가능한 것은 `ArchitectureRulesTest`(ArchUnit)가 강제한다.
- 불필요한 추상화와 과한 설계를 피한다. 사용처 없는 인터페이스 메서드를 남기지 않는다.
- 코드를 수정할 때 기존 주석을 삭제하지 않으며, 코드 위치가 바뀌면 주석도 함께 이동한다.

---

## 표기·Git 세부

- 한국어 문장에서 영문 용어 뒤 조사는 붙여 쓴다 (`race가`, `mock으로`, `latch는`, `stub한다`). 의존명사(`자체`, `간`, `등`)나 일반 명사는 띄어 쓴다 (`mock 응답`, `thread 간`).
- 문서 용어: 개발자에게 익숙한 표준 기술 용어(멱등, 낙관 락, 단일 출처 등)는 그대로 쓰고, 일반적이지 않은 비유·축약 표현(예: "심볼", "부패")은 풀어쓴다. 누가 읽어도 바로 이해되는 쪽을 택한다. 단어 목록(사전)을 만들지 말고 이 기준으로 그때그때 판단한다.
- 커밋 메시지에 `Co-Authored-By` 줄을 붙이지 않는다.

---

## 참고 문서

핵심 설계·스펙
- 기능 범위: `docs/prd.md`
- 설계 결정: `docs/adr.md`
- 백엔드 구조와 의존성: `docs/architecture.md`
- API 스펙: `docs/api-spec.md`
- DB 스키마: `docs/db-schema.md`
- 예외 처리 정책: `docs/exception-strategy.md`
- 낙관 락(@Version) 충돌 처리 설계: `docs/optimistic-lock-design.md`
- 패키지 배치 기준: `docs/package-structure-guide.md`

컨벤션
- 브랜치 / 커밋 / PR / 테스트 / 로깅: `docs/branch-conventions.md`, `docs/commit-conventions.md`, `docs/pr-conventions.md`, `docs/testing-conventions.md`, `docs/logging-conventions.md`

task·하네스 운영
- task별 문서 운영 가이드: `docs/tasks/README.md`
- Claude Code hook 구조: `docs/claude/hooks/overview.md`
- Claude Code skill 문서: `docs/claude/skills/*`
