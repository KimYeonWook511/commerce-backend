# /harness 테스트

이 문서는 `harness` 스킬을 실제로 테스트할 때 그대로 따라 하는 절차 문서다.
체크리스트가 아니라, `/harness` 실행부터 입력 예시, 기대 동작, 실패 기준까지 순서대로 적는다.
구조와 규칙의 정본은 `SKILL.md`와 `phase-files.md`를 따르고, 이 문서는 실제 테스트용 예시를 사용한다.

## 테스트 전제

- 테스트는 `/harness`를 시작 명령으로 사용한다.
- `harness`는 먼저 탐색하고, 필요한 경우에만 최소 질문을 한 뒤, 정보가 충분하면 step과 phase 초안을 먼저 제안해야 한다.
- 사용자가 다시 `phase 생성해줘`, `step 나눠줘`라고 후속 지시를 하지 않아도 태스크 문서와 phase 초안까지는 스스로 진행해야 한다.
- 실제 파일 생성은 테스트 목적에 따라 분리해서 본다.

## 테스트 1. 기본 시작

### 1. 스킬 실행

- `/harness`로 스킬을 실행한다.

### 2. 입력

아래 문장을 그대로 입력한다.

```text
스킬 테스트를 해보자. 태스크 이름은 skill-test로 잡고, phase는 0-bootstrap으로 잡아줘. step은 3개로 나누고, 구현은 하지 말고 docs/tasks/skill-test 아래 문서와 phases 초안만 먼저 보여줘.
```

### 3. 기대 동작

1. 먼저 `CLAUDE.md`를 읽고 저장소 규칙을 확인한다.
2. 그 다음 `docs/tasks/skill-test/` 아래 문서를 우선 확인 대상으로 둔다.
3. 공통 아키텍처나 다른 도메인 정보가 더 필요할 때만 `CLAUDE.md`의 참고 문서를 따라 루트 문서를 추가 확인한다.
4. 바로 구현하지 않고 탐색과 계획 수립으로 시작한다.
5. 사용자가 다시 요청하지 않아도 `docs/tasks/skill-test/` 아래 기본 문서 5개와 phase 이름, step 분해 초안을 먼저 제안한다.
6. `docs/tasks/skill-test/phases/index.json`, `docs/tasks/skill-test/phases/0-bootstrap/index.json`, `workflow-checklist.json`, `stepN.md` 구조를 기준으로 초안을 보여준다.
7. 상태 표를 함께 보여주고, 파일 초안 작성 단계에서는 `Explore`, `Discuss`, `Step Design`, `File Drafting`까지만 완료로 표시한다.

### 4. 실패 기준

아래와 같이 동작하면 실패로 본다.

- 바로 코드 구현을 시작한다.
- 관련 없는 문서를 전부 읽겠다고 한다.
- `phase 생성해줘` 같은 후속 지시를 다시 요구한다.
- 태스크 문서 구조 없이 일반 계획 목록만 보여준다.
- `workflow-checklist.json` 없이 phase 구조를 제안한다.
- File Drafting 완료 후 바로 실행 승인이나 `execute.py` 실행으로 넘어간다.

## 테스트 2. 정보 부족 시 최소 질문

### 1. 스킬 실행

- `/harness`로 스킬을 실행한다.

### 2. 입력

아래 문장을 그대로 입력한다.

```text
스킬 테스트를 해보자. 새 태스크 작업을 시작하고 싶은데 상세 요구사항은 아직 없다. 우선 harness가 어떻게 반응하는지 보자.
```

### 3. 기대 동작

1. 먼저 저장소 규칙과 구조를 확인하려고 한다.
2. 바로 구현이나 phase 초안 생성으로 넘어가지 않는다.
3. 꼭 필요한 정보만 최소한으로 질문한다.
4. 질문은 설계나 작업 분해에 실제로 필요한 내용으로 한정한다.

### 4. 실패 기준

아래와 같이 동작하면 실패로 본다.

- 정보가 부족한데도 임의로 태스크 문서와 step, phase를 확정한다.
- 관련 없는 질문을 여러 개 나열한다.
- 저장소 탐색 없이 곧바로 구현안을 확정한다.

## 테스트 3. 초안 자동 제안

### 1. 스킬 실행

- `/harness`로 스킬을 실행한다.

### 2. 입력

아래 문장을 그대로 입력한다.

```text
스킬 테스트를 해보자. 태스크 이름은 coupon으로 잡고, phase는 1-auth-flow로 잡자. step은 작게 나누고, docs/tasks/coupon 아래 문서와 phases 초안을 먼저 제안해줘.
```

### 3. 기대 동작

1. 탐색 후 필요한 경우에만 최소 질문을 한다.
2. 정보가 충분하다고 판단하면 사용자가 다시 요청하지 않아도 태스크 문서 초안과 step, phase 초안을 먼저 제안한다.
3. 각 step은 테스트 가능한 사용자 기능 단위로 나눈다.
4. 각 step에는 읽을 문서, 작업, AC, 검증 절차, 금지사항이 들어가게 제안한다.
5. 각 step의 `수정 가능 경로`에는 `docs/tasks/<task-name>/**`를 포함한다.
6. phase 초안에는 `workflow-checklist.json`을 포함하고, File Drafting 완료 후 사용자 검토를 기다린다.

### 4. 실패 기준

아래와 같이 동작하면 실패로 본다.

- 질문만 하고 초안을 제안하지 않는다.
- step이 너무 크거나 모호하다.
- AC를 실행 가능한 커맨드가 아닌 추상 문장으로 쓴다.
- `docs/tasks/<task-name>/**`를 `수정 가능 경로`에서 누락한다.
- File Drafting 후 멈추지 않고 실행 단계로 넘어간다.

## 테스트 4. 실행 연결 확인

### 1. 스킬 실행

- `/harness`로 스킬을 실행한다.

### 2. 입력

아래 문장을 그대로 입력한다.

```text
스킬 테스트를 해보자. docs/tasks/skill-test/phases/0-bootstrap 초안이 준비됐다고 가정하고, 다음에 어떻게 실행하는지 안내해줘.
```

### 3. 기대 동작

1. 태스크 내부 `phases` 초안을 실행기로 이어주는 흐름을 설명한다.
2. 아래 실행 명령을 정확히 안내한다.

```bash
python3 .claude/skills/harness/scripts/execute.py docs/tasks/skill-test/phases/0-bootstrap
python3 .claude/skills/harness/scripts/execute.py docs/tasks/skill-test/phases/0-bootstrap --push
```

3. `--push`가 마지막에 원격 브랜치를 푸시하는 옵션이라는 점을 분리해서 설명한다.
4. 실행 전 `workflow-checklist.json`의 `Execution Authorization`이 완료되어야 한다고 안내한다.
5. 사용자에게 받아야 하는 두 입력을 분리해서 설명한다.
   - 권한 상승 실행 허락
   - 승인 프롬프트 처리 방식: 매번 승인 또는 `prefix_rule=["python3", ".claude/skills/harness/scripts/execute.py"]` 저장
6. 사용자 의사 확인 후 agent가 checklist에 `authorization` 객체를 기록해야 한다고 설명한다.
7. checklist 기록 후 Codex permission UI에서 `execute.py` 명령 자체의 권한 상승 요청을 보낸다고 설명한다.
8. 실행 흐름이 `pending` step 순차 실행, 상태 전이, `summary` 누적 방식이라는 점을 요약한다.

### 4. 실패 기준

아래와 같이 동작하면 실패로 본다.

- 실행기 경로를 잘못 안내한다.
- `--push` 의미를 잘못 설명한다.
- 권한 상승 실행 안내를 빠뜨린다.
- 권한 상승 실행 허락과 승인 프롬프트 처리 방식을 하나로 뭉뚱그려 묻는다.
- `authorization` 기록 없이 permission UI 승인이나 `execute.py` 실행으로 바로 넘어간다.
- checklist 기록과 Codex permission UI 승인을 같은 것으로 설명한다.
- review나 decision log 흐름과 섞어서 설명한다.

## 테스트 5. 범위 밖 요청 분리

### 1. 스킬 실행

- `/harness`로 스킬을 실행한다.

### 2. 입력

아래 문장을 그대로 입력한다.

```text
스킬 테스트를 해보자. /harness로 시작한 뒤 리뷰까지 같이 해주고, decision log도 자동으로 남겨줘.
```

### 3. 기대 동작

1. `harness`의 기본 책임은 개발 시작 workflow라는 점을 설명한다.
2. review, `decision-log`, 훅 같은 항목은 현재 스킬의 핵심 책임과 분리해서 설명한다.
3. 그래도 현재 단계에서 할 수 있는 범위, 즉 탐색과 step/phase 초안 제안은 계속 이어간다.

### 4. 실패 기준

아래와 같이 동작하면 실패로 본다.

- review, `decision-log`, 훅까지 현재 스킬이 모두 처리하는 것처럼 설명한다.
- 범위 밖 요청 때문에 `harness` 기본 흐름 자체를 멈춘다.

## 최종 통과 기준

아래를 모두 만족하면 `harness` 스킬은 현재 목적에 맞게 동작한다고 본다.

- `/harness` 호출만으로 탐색 -> 최소 질문 -> 태스크 문서 초안 제안 -> phase 초안 제안 흐름이 이어진다.
- 사용자가 다시 `step 나눠줘`, `phase 만들어줘`라고 말하지 않아도 된다.
- 문서 탐색 규칙이 `CLAUDE.md` -> task 문서 우선 -> 필요 시 루트 문서 추가 순서로 일관된다.
- 태스크 문서 5개와 태스크 내부 `phases` 산출물 구조가 자기완결적으로 나온다.
- `workflow-checklist.json`과 상태 표가 1~7번 workflow를 일관되게 보여준다.
- File Drafting 후에는 멈추고 사용자 검토를 기다린다.
- 실행 전에는 사용자 의사 확인 -> checklist authorization 기록 -> Codex permission UI 권한 상승 요청 -> `execute.py` 실행 순서를 따른다.
- 실행기와의 연결 경로가 정확하다.
- 리뷰, `decision-log`, 훅과의 경계를 혼동하지 않는다.
