# Auth Redis Timing Retrospective

## 배경

`auth-redis-timing`은 회원가입에서 RDB commit 이전에 Redis에 refresh token이 저장되는 불일치를 수정한 작업이다. (이슈 #76)

`AuthSignUpService.signUp()`의 `@Transactional`이 외부 트랜잭션 경계를 형성해 `register()`가 합류한 상태에서 `issue()`가 Redis에 저장하므로, Redis 저장이 DB commit 전에 발생하는 구조적 문제가 있었다. 추가로 Redis 저장/조회 실패 시 Infrastructure 예외가 Application 계층을 통과해 Presentation까지 전파되는 CLAUDE.md 규칙 위반도 함께 수정했다.

이번 작업은 execute.py를 통해 harness를 처음으로 온전히 실행한 사례다. 이 과정에서 여러 harness 구조 문제가 발견됐고 개선 사항이 도출됐다.

## 주요 설계 결정

### strict 정책 채택

Redis 저장/조회 실패 시 soft fail(로깅만)이 아닌 strict(예외 처리)를 선택했다.
refresh token은 Redis가 저장소 자체이므로, Redis에 없는 refresh token을 클라이언트에 발급하면 access token 만료 시 재발급이 반드시 실패한다. "동작하는 것처럼 보이지만 망가진" 상태보다 명확한 즉각 실패가 낫다.

### `Propagation.NOT_SUPPORTED` 선택

`@TransactionalEventListener(AFTER_COMMIT)` 방식은 응답 반환 후 이벤트가 실행되므로 Redis 저장 실패를 클라이언트에 전달할 수 없어 strict 정책과 양립 불가하다. `NOT_SUPPORTED`로 `signUp()`을 트랜잭션 없이 실행해 `register()`가 자체 트랜잭션으로 commit 후 반환되도록 분리했다. 기존 `OrderCreateService.createOrder()` 패턴과 일치한다.

## 발생한 문제

### 1. fix/ 브랜치로 생성했다가 feature/로 재생성해야 했다

- 브랜치 컨벤션에 따라 `fix/auth-redis-timing`으로 생성했으나, execute.py가 `feature/<name>` 브랜치명을 하드코딩해 실행 시 검증 실패가 발생했다.
- `feature/auth-redis-timing`으로 worktree를 재생성해 파일을 복원했다.

### 2. Workflow 상태 표에 "Worktree 생성" 단계가 없었다

- SKILL.md Workflow 표(6단계)에 Worktree 생성 단계가 없어, File Drafting 설명에서 worktree를 생성한다고 하면서도 표에는 나타나지 않았다.
- 상태 보고 시 워크트리 생성 여부를 표로 확인할 수 없었다.

### 3. CWD가 worktree root가 아닌 상태로 파일을 작성했다

- worktree를 생성하고 이동하지 않은 채 절대 경로로 파일을 작성했다.
- 가시성이 낮고 실수 가능성이 있었다.

### 4. developer worker가 step 문서의 커밋 메시지를 따르지 않았다

- step 문서에 `fix:`, `refactor:` 등 커밋 메시지를 명시했음에도 worker가 모두 `feat:`으로 자체 생성했다.
- PR 전에 `git rebase -i`로 수동 수정이 필요했다.

### 5. execute.py step 분모 표시 버그

- 진행 레이블이 `"Step {step_num}/{self.total_steps - 1}"`으로 계산돼 5개 step에서 "Step 5/4"처럼 표시됐다.
- 실행 로직에는 영향 없으나 혼란을 유발했다.

### 6. `approved_at` 에 임의 시각을 입력했다

- `workflow-checklist.json`의 `Execution Authorization.approved_at`에 `00:00:00`을 입력했다.
- 기록 전 `date` 명령으로 실제 시각을 확인하지 않았다.

## harness 개선 필요 사항

### Workflow 상태 표에 "Worktree 생성 및 이동" 단계 추가

- 현재 6단계: Explore → Discuss → Step Design → File Drafting → Execution Authorization → Execution
- 개선 7단계: Explore → Discuss → Step Design → **Worktree 생성 및 이동** → File Drafting → Execution Authorization → Execution
- SKILL.md Workflow 섹션 + `workflow-checklist.json` 템플릿 + 상태 표 형식 모두 수정 필요
- "Worktree 생성 및 이동"은 `git worktree add` 실행 후 `cd` 이동까지 완료해야 ✅

### execute.py 브랜치 타입 하드코딩 문제

- execute.py가 `feature/<name>` 브랜치명만 허용한다.
- `phases/index.json`에 `branch_type` 필드 추가 후 execute.py가 읽어 브랜치명을 조합하는 방향 검토 필요.
- `docs/features/` 폴더명도 `fix`, `refactor` 등 작업 타입과 의미상 불일치하는 문제와 함께 설계 재검토 필요.

### phase 표준 step 구조에 docs 동기화 + 회고록 작성 step 추가

현재 phase step 구조에 루트 docs 동기화와 회고록 작성이 표준으로 포함되지 않아 매번 수동으로 추가했다.
모든 phase의 마지막 두 step을 아래처럼 표준화한다.

```
step(N-1): sync-root-docs    — docs/adr.md 등 루트 문서 동기화
step(N):   write-retrospective — 회고록 작성 + phase index.json + workflow-checklist.json 최종 상태를 한 커밋에 포함
```

회고록 step 커밋 시 아래를 함께 포함한다:
- `docs/features/<feature-name>/retrospective.md`
- `docs/features/<feature-name>/phases/<phase-name>/index.json` (모든 step completed 상태)
- `docs/features/<feature-name>/phases/<phase-name>/workflow-checklist.json`

### developer worker 커밋 메시지 준수 강화

- step 문서에 명시된 커밋 메시지를 그대로 사용하도록 worker 프롬프트에 지시 강화 필요.

### execute.py step 분모 표시 버그 수정

- `scripts/execute.py` 646번째 줄: `self.total_steps - 1` → `self.total_steps`

### `approved_at` 실제 시각 기록

- `Execution Authorization` 기록 전 `date '+%Y-%m-%dT%H:%M:%S+0900'` 실행 후 결과를 `approved_at`에 입력한다.

## 얻은 교훈

- execute.py가 `feature/` 브랜치만 허용하므로, 이슈 타입과 무관하게 현재는 `feature/` 브랜치로 생성해야 한다.
- worktree 생성 직후 반드시 `cd`로 이동하고, 이후 모든 작업을 worktree root 기준으로 수행한다.
- Redis를 필수 인프라로 볼 때 strict 정책은 단순하고 일관적이나, Redis HA 구성을 전제로 한다.
- 기존 코드베이스 패턴(`NOT_SUPPORTED`, `DataAccessException`, `OrderCreateService`)을 탐색하고 적용하면 설계 일관성을 유지할 수 있다.

## 다음 feature에서의 체크리스트

- [ ] worktree 생성 후 반드시 `cd worktrees/<name>`으로 이동한다
- [ ] `fix/`, `refactor/` 등 타입이라도 현재는 `feature/` 브랜치로 생성한다 (execute.py 제약)
- [ ] `Execution Authorization.approved_at`에 `date` 명령 결과를 입력한다
- [ ] phase 마지막 step에 루트 docs 동기화 + 회고록 작성을 포함한다
- [ ] 회고록 커밋 시 `index.json`, `workflow-checklist.json` 최종 상태를 함께 포함한다
