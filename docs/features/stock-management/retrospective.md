# Stock Management Retrospective

## 배경

`stock-management`는 관리자 초기 재고 생성, 재고 증가/감소, 상품별 재고 변경 이력 조회를 구현한 feature다.

이번 작업은 `dev-start` 실행기로 진행했고 기능 구현과 테스트는 완료됐지만, 실행 과정에서 step 분해 방식, 실행 산출물 커밋 정책, 자동 커밋 메시지가 PR 히스토리에 맞지 않는 문제가 드러났다. 이후 기존 자동 생성 커밋을 풀고 기능 단위 커밋으로 다시 정리했다.

## 발생한 문제

### 1. step이 너무 잘게 나뉘었다

- 초기 phase는 `domain-history-model`, `stock-command-service`, `admin-stock-create-api`, `admin-stock-adjust-api`, `admin-stock-history-query-api`, `docs-sync`로 나뉘었다.
- 각 step마다 worker 실행, Acceptance Criteria 검증, reviewer 검토, output 생성, commit이 반복됐다.
- 기능 크기에 비해 실행 오버헤드가 컸고, 문서와 코드 맥락을 반복해서 확인하는 비용도 커졌다.
- Spring API feature에서는 레이어 단위보다 사용자 기능 단위 vertical slice가 더 적합하다는 점이 드러났다.

### 2. 실행 산출물이 커밋 히스토리에 섞였다

- 실행기는 성공한 step도 `stepN-output.json`, `stepN-ac-output.json`, `stepN-review-output.json`, `workflow-checklist.json`을 커밋했다.
- 이 파일들은 구현 결과라기보다 실행 로그에 가까워 PR 히스토리를 읽기 어렵게 만들었다.
- 성공 산출물을 repo history에 남기는 명확한 이점보다 커밋 오염 비용이 더 컸다.

### 3. 자동 커밋 메시지가 기능 단위와 맞지 않았다

- 기존 자동 커밋은 `feat: 0-admin-stock-management-api 2단계 admin-stock-create-api 작업을 반영한다`처럼 phase와 step 이름 중심이었다.
- 프로젝트 커밋 컨벤션은 `<type>: <subject>`이며 subject는 한국어 현재형이어야 한다.
- 자동 메시지는 기능 변경의 의미를 설명하기보다 실행 단계명을 기록해서, 리뷰어가 커밋 단위와 의도를 파악하기 어려웠다.

### 4. 문서 동기화에서 구현과 문서가 어긋났다

- `docs-sync` step에서 `tbl_stock_history`의 `idx_stock_history_stock_id_created_at` 인덱스를 실제 스키마처럼 문서화하려 했다.
- 하지만 `StockHistory` 엔티티나 별도 마이그레이션에는 해당 인덱스 정의가 없었다.
- reviewer는 구현과 문서의 불일치로 retry를 요구했고, 최종적으로 실제 JPA 스키마에 없는 인덱스는 후속 검토 후보로 정리했다.

### 5. 초기 재고 0과 이력 정책이 충돌했다

- 요구사항은 초기 재고 수량 `0`을 허용했다.
- 동시에 `StockHistory.quantityChange`는 `0`을 허용하지 않도록 설계했다.
- 결과적으로 초기 재고가 `0`이면 재고만 생성하고 이력은 저장하지 않는 예외 정책이 생겼다.
- 이 정책은 구현 단계에서 해결됐지만, 계획 단계에서 먼저 명시했어야 했다.

## 이번 작업에서 적용한 해결

### 커밋 히스토리 재정리

- 기존 자동 생성 커밋 13개를 `develop` 기준으로 풀었다.
- 최종 구현 상태는 보존하고 아래 기능 단위 커밋으로 다시 구성했다.
  - `chore: dev-start 실행 산출물을 무시한다`
  - `feat: 재고 변경 이력 도메인을 추가한다`
  - `feat: 관리자 재고 명령 기능을 추가한다`
  - `feat: 관리자 재고 관리 API를 추가한다`
  - `docs: 재고 관리 문서를 추가한다`
- 커밋 메시지는 `docs/commit-conventions.md`의 한국어 현재형 규칙에 맞췄다.

### 실행 산출물 제외

- `.gitignore`에 dev-start 실행 산출물 패턴을 추가했다.
- 성공 output, AC output, review output, workflow checklist는 커밋 대상에서 제외했다.
- 이후 같은 산출물이 생성돼도 기본적으로 Git에 잡히지 않도록 했다.

### 기능 단위 step 기준 정리

- `domain`, `repository`, `service`, `controller`처럼 레이어별로 나누는 방식은 이번 범위에 과하다고 판단했다.
- 향후 API feature는 테스트 가능한 사용자 기능 단위로 묶는다.
- 재고 관리라면 `관리자 재고 생성/조정`, `재고 이력 조회`, `문서 동기화` 정도가 적절하다.

## 우선순위

### P0. 성공 실행 산출물은 커밋하지 않기

- 이유: 성공 output은 기능 구현 산출물이 아니라 실행 로그다.
- 영향: PR 히스토리가 불필요하게 길어지고 기능 커밋의 의도가 흐려진다.
- 해결 방향:
  - 성공 output과 workflow checklist는 `.gitignore`로 제외한다.
  - 실패 분석이 필요하면 로컬 산출물이나 별도 보고로 처리한다.

### P0. step을 vertical slice 단위로 설계하기

- 이유: Spring API 구현은 도메인, 서비스, 컨트롤러, 테스트가 함께 움직여야 하나의 동작이 완성된다.
- 영향: 레이어별 step은 반복 검증 비용이 크고 실제 사용자 기능 완성 시점도 늦어진다.
- 해결 방향:
  - 한 step은 테스트 가능한 사용자 기능 결과를 만든다.
  - 증가/감소처럼 같은 정책과 코드 경로를 공유하는 동작은 묶는다.
  - 조회처럼 데이터 흐름과 검증 기준이 다른 기능은 필요할 때만 분리한다.

### P1. 실패 산출물 정책 분리

- 이유: 현재 파일명은 성공과 실패 모두 `stepN-output.json` 계열이라 `.gitignore`만으로 실패 산출물만 구분하기 어렵다.
- 영향: 실패 산출물을 커밋해야 하는 상황이 생기면 성공 output ignore 정책과 충돌한다.
- 해결 방향:
  - 실패나 blocked는 `stepN-failure-report.json`처럼 별도 파일명으로 남긴다.
  - 성공 output은 계속 무시하고, 실패 report만 선택적으로 커밋 가능하게 한다.

### P1. 문서 동기화는 마지막에 한 번 수행하기

- 이유: 구현 중간마다 문서를 맞추면 아직 확정되지 않은 세부사항이 문서에 먼저 들어갈 수 있다.
- 영향: 실제 구현과 문서가 어긋나 retry가 발생할 수 있다.
- 해결 방향:
  - 구현 step에서는 feature 문서 변경을 최소화한다.
  - root docs 동기화는 최종 구현과 테스트가 끝난 뒤 한 번 수행한다.

## 얻은 교훈

- 자동화된 step이 많다고 항상 안전해지는 것은 아니다.
- 작은 Spring API feature는 레이어 단위보다 기능 단위 vertical slice가 더 빠르고 명확하다.
- 성공 로그를 Git history에 남기면 추적성보다 노이즈가 커질 수 있다.
- 커밋 메시지는 실행기 내부 단계가 아니라 리뷰어가 이해할 기능 변경 단위를 설명해야 한다.
- 문서는 구현의 의도를 설명해야 하지만, 실제 스키마나 코드에 없는 내용을 확정된 사실처럼 적으면 안 된다.
- 요구사항끼리 충돌할 수 있는 정책은 구현 전에 먼저 잠가야 한다.

## 다음 feature에서의 체크리스트

- step을 만들기 전에 사용자 기능 단위로 먼저 나눈다.
- controller, service, domain, repository, test가 같은 기능을 위해 함께 필요하면 한 step에 포함한다.
- command 기능과 query 기능은 흐름이 다르면 분리하되, 같은 정책을 공유하면 묶는다.
- 성공 output, AC output, review output, workflow checklist는 커밋하지 않는다.
- 실패 산출물 보존이 필요하면 성공 output과 다른 파일명으로 남긴다.
- root docs 동기화는 구현과 전체 테스트가 끝난 뒤 마지막에 수행한다.
- 자동 생성 커밋 메시지는 PR 제출 전 기능 단위로 정리한다.
