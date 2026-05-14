# Order Idempotency Retrospective

## 배경

`order-idempotency`는 주문 생성 멱등성을 Redis 단독 SETNX 방식에서 Redis(1차 방어선) + RDB unique 제약(최종 보장)의 이중 구조로 전환한 feature다. (이슈 #72)

이번 작업은 harness를 사용하려 했으나, execute.py가 worktree 내 파일을 인식하지 못하는 문제로 수동 구현으로 전환해 진행했다. 이 과정에서 harness의 구조적 한계가 드러났고 개선 사항이 도출됐다.

## 발생한 문제

### 1. execute.py가 worktree 내 파일을 찾지 못했다

- File Drafting을 worktree(`worktrees/feature-order-idempotency/`) 안에서 진행했다.
- execute.py는 `ROOT = Path(__file__).resolve().parents[4]`로 항상 main repo 루트를 기준으로 동작한다.
- 실행 시 `docs/features/order-idempotency/phases/0-rdb-idempotency` 경로를 main repo에서 찾으므로 `phase 디렉토리를 찾을 수 없습니다` 오류가 발생했다.

### 2. harness의 File Drafting 위치와 execute.py 동작 방식이 충돌했다

- 기존 harness 설계는 File Drafting을 develop 브랜치에서 진행하고, execute.py가 실행 시 worktree를 자동 생성하는 방식이다.
- 이렇게 하면 develop 브랜치에 미완성 feature 문서가 커밋되어 오염이 발생한다.
- File Drafting을 feature 브랜치(worktree)에서 먼저 진행하고 싶었으나, execute.py가 이 방식을 지원하지 않았다.

### 3. 수동 구현 시 execute.py 자동 처리 항목을 누락했다

- execute.py가 자동으로 업데이트하는 `index.json`, `phases/index.json`, `workflow-checklist.json`의 step 상태가 모두 `pending`으로 남았다.
- 구현 완료 후 별도로 확인하고 수동 갱신해야 했다.

## 이번 작업에서 적용한 해결

### 수동 구현으로 전환

- execute.py 없이 worktree 안에서 직접 코드를 작성하고 커밋했다.
- step 파일을 지침으로 삼아 step0(기반 준비) → step1(로직 전환) → step2(문서 동기화) 순으로 진행했다.

### phase 상태 파일 수동 갱신

- 구현 완료 후 `index.json`, `phases/index.json`, `workflow-checklist.json`을 수동으로 completed 상태로 갱신했다.

## harness 개선 필요 사항 (별도 이슈로 분리)

### File Drafting 전 worktree 생성 workflow 추가

- 현재: File Drafting → develop 커밋 → execute.py 실행 (worktree 자동 생성)
- 개선: worktree 생성 → File Drafting (feature 브랜치에 커밋) → execute.py 실행 (기존 worktree 재사용)
- develop 브랜치가 미완성 feature 문서로 오염되지 않는다.

### execute.py에서 기존 worktree 재사용 로직 추가

- 현재: 항상 새 worktree를 생성하고 완료 후 삭제
- 개선: 브랜치에 해당하는 worktree가 이미 존재하면 재사용

## 얻은 교훈

- harness 도구를 사용하기 전에 실제 동작 방식(ROOT 계산, worktree 생성/삭제 흐름)을 먼저 파악해야 한다.
- 도구가 기대대로 동작하지 않을 때 우회하는 방법을 미리 정해두면 작업이 막히지 않는다.
- execute.py 없이 수동으로 진행해도 step 파일을 지침으로 삼으면 일관된 흐름을 유지할 수 있다.
- 자동화 도구가 처리하는 파일 목록을 파악해두면 수동 진행 시 누락 없이 마무리할 수 있다.

## 다음 feature에서의 체크리스트

- harness 개선 완료 전까지는 수동 worktree 생성 후 File Drafting → 수동 구현 흐름을 사용한다.
- 수동 구현 완료 후 `index.json`, `phases/index.json`, `workflow-checklist.json` 상태를 반드시 갱신한다.
- harness 개선(execute.py worktree 재사용) 완료 후에는 worktree 생성 → File Drafting → execute.py 실행 흐름으로 전환한다.
