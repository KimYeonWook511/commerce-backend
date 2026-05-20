# Product Query Retrospective

## 배경

`product-query` 구현 과정에서 `dev-start` 실행기를 여러 차례 재실행했다. 주요 원인은 구현 자체보다 step 문서 범위와 하네스 상태 관리가 실제 작업 흐름을 충분히 따라가지 못했기 때문이다.

## 발생한 문제

### 1. step 수정 가능 경로가 실제 변경 범위를 덜 잡았다

- 초기 `step0.md`는 `src/main/java/com/commerce/product/**`, `src/test/java/com/commerce/product/**`만 허용했다.
- 하지만 실제 작업 중에는 아래 경로도 함께 영향받았다.
  - `docs/features/product-query/**`
  - `src/main/java/com/commerce/auth/filter/JwtAuthenticationFilter.java`
- 그 결과 review 단계에서 "허용 범위 밖 변경"으로 반복 차단됐다.

### 2. reviewer diff에 메타데이터가 섞여 핵심 구현이 묻혔다

- 실행기는 reviewer에게 보여줄 diff를 만들 때 step output, phase index 같은 메타데이터 경로도 함께 포함할 수 있었다.
- 이 경우 `ProductController`, `ProductService`, 테스트 같은 핵심 코드 diff가 prompt 안에서 잘리거나 묻혀 reviewer가 근거 부족으로 blocked 판단을 내릴 수 있었다.
- 이후 `execute.py`에서 reviewer diff는 구현 변경 경로 중심으로만 구성하도록 수정했다.
- 현재 하네스는 diffText 전달 대신 변경 경로와 실행 산출물을 바탕으로 repo 파일을 read-only 검토한다.

### 3. step 상태 기록과 실제 Git 상태가 완전히 일치하지 않을 수 있었다

- step1 구현과 테스트는 통과했지만, 재시도와 review/commit 흐름 중 housekeeping 정리가 완전히 끝나기 전에 다음 step이 이어졌다.
- 그 상태에서 step1 review가 step0 산출물까지 같이 보게 되면서 다시 허용 범위 오류가 발생했다.
- 즉 phase/step JSON 상태와 실제 워킹트리 상태가 잠시 어긋날 수 있었다.

## 이번 작업에서 적용한 수정

### feature 문서/step 보완

- `step0.md`의 수정 가능 경로에 `src/main/java/com/commerce/auth/filter/JwtAuthenticationFilter.java`를 추가했다.
- 이번 feature에서는 공개 API 요구사항 때문에 인증 필터 수정이 도메인 구현의 일부라는 점을 문서로 명시했다.

### 하네스 보완

- `.codex/skills/dev-start/scripts/execute.py`
  - reviewer diff는 구현 변경(`editable_paths`) 기준으로만 만들도록 조정했다.
  - feature phase 상태 갱신 시 이전 `blocked_at`, `failed_at`, `completed_at` 같은 timestamp를 정리하도록 보완했다.
  - step 시작 시 feature phase를 `in_progress`로 동기화하도록 보완했다.
- `.codex/skills/dev-start/scripts/git_ops.py`
  - step 코드 커밋 또는 housekeeping 커밋이 실패하면 경고만 남기지 않고 즉시 실행을 중단하도록 변경했다.
  - 부분 커밋 상태에서 다음 step으로 넘어가는 위험을 줄였다.

## 얻은 교훈

- step의 `수정 가능 경로`는 도메인 패키지만 적으면 부족하다.
- 공개 API, 인증, 공통 예외, 루트 문서 동기화처럼 횡단 관심사가 있으면 처음부터 step 경로에 포함해야 한다.
- reviewer에게는 메타데이터보다 구현 diff를 우선 보여줘야 한다.
- 실행기 bookkeeping이 실패를 경고로만 처리하면 다음 step에서 더 큰 혼선을 만든다.

## 다음 feature에서의 체크리스트

- step 작성 시 실제 변경 후보를 먼저 나열한다.
- 아래 유형이 있으면 `수정 가능 경로`에 포함한다.
  - 인증 필터, 인터셉터, WebConfig
  - 공통 예외/응답 매핑
  - feature 문서 자체
  - 루트 `docs/*.md` 동기화 파일
- review blocked가 발생하면 구현 문제와 step 범위 문제를 구분해서 본다.
- 단계별 커밋이 실패하면 다음 step으로 넘기지 말고 먼저 Git 상태를 정리한다.
