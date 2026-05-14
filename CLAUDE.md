@docs/claude-harness.md

# CLAUDE.md

## 프로젝트 컨텍스트

- Java, Spring Boot, Gradle, MySQL, JPA(Hibernate)를 사용하는 백엔드 프로젝트입니다.

## 언어 규칙

- 모든 설명과 답변은 반드시 한국어로 작성합니다.
- 클래스, 메서드, 변수, 패키지, 테스트 메서드명 같은 코드 식별자는 반드시 영어로 작성합니다.
- 코드 식별자에는 한국어를 섞지 않습니다.

## 구현 규칙

- 도메인 중심 네이밍을 우선하고, 기존 프로젝트 패턴을 따릅니다.
- 비즈니스 로직은 Domain 또는 application 계층에 둡니다.
- Controller는 요청 수신, 입력 검증, 서비스 위임, 응답 반환만 담당합니다.
- 외부 시스템 연동(Redis, 이메일, 결제 PG 등)은 `application/port/` 인터페이스로만 의존합니다.
- Service 클래스는 유스케이스 단위로 단일 행위만 담당합니다 (`CreateOrderService`, `CancelOrderService` 형식).
- Infrastructure 예외(`DataIntegrityViolationException` 등)는 Application 계층에서 도메인 예외로 변환하고 Presentation으로 넘기지 않습니다.
- 불필요한 추상화와 과한 설계를 피합니다.
- 코드를 수정하거나 작성할 때 기존에 작성된 주석을 삭제하지 않으며, 코드 위치가 바뀌는 경우 주석도 함께 이동합니다.

## 안전 규칙

- 근거 없이 기존 컨벤션을 무시하거나 사용하지 않는 코드를 추가하지 않습니다.
- 불명확한 점은 임의로 판단하지 않고 구현 전에 사용자에게 먼저 확인합니다.

## Git 규칙

- 커밋 메시지에 `Co-Authored-By` 줄을 붙이지 않습니다.

## 컨벤션 확인 규칙

- `git commit` 실행 전: `docs/commit-conventions.md`를 읽고 타입, subject 문체(`~한다`), 형식을 확인한다.
- `gh pr create` 실행 전: `docs/pr-conventions.md`를 읽고 타입, 형식, draft 여부를 확인한다.
- 브랜치 생성(`git worktree add`) 전: `docs/branch-conventions.md`를 읽고 형식을 확인한다.

## Plan Mode

- 구현 전에 Plan Mode로 계획을 작성하고 `ExitPlanMode`로 사용자 승인을 받은 뒤 실행합니다.
- 사용자가 승인하기 전에는 파일을 생성하거나 수정하지 않습니다.
- `/harness` skill을 사용할 때는 `workflow-checklist.json`의 `Execution Authorization`이 완료된 뒤에만 `execute.py`를 실행합니다.

## 참고 문서

- 기능 범위: `docs/PRD.md`
- 설계 결정: `docs/ADR.md`
- 백엔드 구조와 의존성: `docs/architecture.md`
- API 스펙: `docs/api-spec.md`
- DB 스키마: `docs/db-schema.md`
- 기능별 문서 운영 가이드: `docs/features/README.md`
- 브랜치 컨벤션: `docs/branch-conventions.md`
- 커밋 컨벤션: `docs/commit-conventions.md`
- PR 컨벤션: `docs/pr-conventions.md`
- 테스트 컨벤션: `docs/testing-conventions.md`
- Claude Code 하네스 원칙: `docs/claude-harness.md`
- Claude Code hook 구조: `docs/claude/hooks/README.md`
- Claude Code skill 문서: `docs/claude/skills/*`
