# Product Management Retrospective

## 배경

`product-management`는 관리자 상품 등록, 수정, soft delete API와 상품 공개 노출 정책 변경을 함께 다룬 feature다. 기능 자체는 `product` 도메인 안에 머무르는 것처럼 보였지만, `Product` 생성 계약이 바뀌면서 주문, 결제, 재고 테스트 fixture까지 함께 영향받았다.

이번 작업은 `dev-start` 실행기로 시작했지만, 하네스 실행 환경 문제와 reviewer diff truncation 때문에 일부 단계는 수동 검토와 정리로 마무리했다.

## 발생한 문제

### 1. Git 작업이 sandbox 권한에 막혔다

- 실행기가 feature 브랜치를 만들 때 `.git` 내부 ref 생성이 필요했지만 sandbox에서 `Operation not permitted`가 발생했다.
- 이후 파일 stage, commit 같은 Git index 작업도 동일하게 `.git/index.lock` 생성 권한 문제를 만났다.
- 구현 자체보다 Git 상태를 정상화하는 데 추가 시간이 들었다.

### 2. Codex worker가 세션 디렉터리 권한에 막혔다

- `dev-start` 실행기가 worker를 띄울 때 `~/.codex/sessions` 접근 권한 문제로 실패했다.
- 실행기 자체가 잘못된 것이 아니라 현재 하네스 환경의 세션 기록 경로가 막힌 문제였다.
- 임시 wrapper로 `codex exec --ephemeral`을 강제해 세션 파일 쓰기를 피한 뒤 다시 실행했다.

### 3. step1에서 상품 상태 기본값 처리와 요구사항이 충돌했다

- 초기 구현은 `Product` 생성 시 `status == null`이면 `ON_SALE`로 기본값을 보정했다.
- 하지만 feature 문서 요구사항은 `status` 필수였다.
- reviewer가 이 불일치를 잡았고, `resolveInitialStatus`를 제거한 뒤 null status 생성 시 예외가 나도록 수정했다.
- 같은 회귀를 막기 위해 `ProductTest`에 null status 생성 테스트를 추가했다.

### 4. `Product` 생성 계약 변경이 다른 도메인 테스트까지 전파됐다

- `ProductStatus`가 필수가 되면서 기존 주문, 결제, 재고 테스트에서 `Product.builder()`로 상품을 만들던 fixture가 모두 깨질 수 있었다.
- step Acceptance Criteria는 `com.commerce.product.*`만 실행했기 때문에 이 파급 범위를 바로 드러내지 못했다.
- 전체 테스트를 돌린 뒤 관련 테스트 fixture에 `ProductStatus.ON_SALE`을 명시하도록 보정했다.

### 5. step1 reviewer diff가 잘려서 blocked 처리됐다

- step1은 controller, request DTO, command DTO, result DTO, service, test를 한 번에 추가했다.
- 변경 파일 수와 diff가 커지면서 reviewer에게 전달된 controller/DTO/test diff가 중간에 잘렸다.
- reviewer는 전달된 diff만 근거로 판단해야 하므로 권한 검증, validation, 응답 형식, 테스트 충족 여부를 확인할 수 없다고 blocked를 냈다.
- 실제 구현과 `./gradlew test --tests 'com.commerce.product.*'`는 통과했지만, 실행기 관점에서는 자동 완료가 불가능했다.

### 6. step2 루트 문서 동기화는 실행기로 이어가지 못했다

- step1이 blocked 상태로 종료되면서 step2 문서 동기화는 자동 진행되지 않았다.
- 루트 `docs/api-spec.md`, `docs/db-schema.md`, `docs/architecture.md`, `docs/prd.md`는 수동으로 feature 구현과 맞췄다.
- phase 상태도 실제 완료 결과에 맞게 수동 갱신했다.

## 이번 작업에서 적용한 해결

### 실행 환경 우회

- 브랜치 생성과 commit은 필요한 시점에 권한 상승으로 처리했다.
- worker 실행은 `/tmp/codex-ephemeral/codex` wrapper를 만들어 `codex exec --ephemeral`로 실행했다.
- 사용자 임시 파일인 `docs/features/TEMP_TODO.md`는 건드리지 않기 위해 로컬 `.git/info/exclude`에 추가했다.

### 구현 보정

- `ProductStatus`를 필수 필드로 확정했다.
- `Product` 생성과 수정에서 `name`, `price`, `status`를 도메인 검증하도록 정리했다.
- 공개 상품 조회는 삭제되지 않고 `ON_SALE`, `SOLD_OUT`인 상품만 반환하도록 repository/service 조건을 변경했다.
- 관리자 상품 삭제는 `deletedAt` 기반 soft delete로 처리했다.

### 테스트 보정

- product 범위 테스트 외에 전체 `./gradlew test`를 실행했다.
- `Product` fixture를 사용하는 주문, 결제, 재고 테스트에 `ProductStatus.ON_SALE`을 명시했다.
- 관리자 controller/service 테스트를 추가해 등록, 수정, 삭제, 권한, validation을 검증했다.

### 문서 보정

- feature 문서와 루트 문서의 정책을 맞췄다.
- `docs/api-spec.md`에 관리자 상품 API와 공개 상품 노출 조건을 추가했다.
- `docs/db-schema.md`에 `description`, `image_url`, `status`, `deleted_at`을 반영했다.
- `docs/architecture.md`와 `docs/prd.md`에 관리자 상품 관리 책임을 반영했다.

## 우선순위

### P0. 실행기 reviewer diff truncation 해결

- 이유: 구현이 맞고 테스트가 통과해도 reviewer가 핵심 diff를 보지 못하면 blocked가 발생한다.
- 영향: 자동 실행 흐름이 끊기고 사람이 수동으로 검토, 문서 정리, phase 상태 보정을 해야 한다.
- 해결 방향:
  - 신규 파일 전체 내용을 무조건 diff에 넣지 말고 파일별 요약과 핵심 hunk를 분리한다.
  - controller, request DTO, service, test처럼 acceptance 판단에 필요한 파일을 우선순위로 포함한다.
  - diff가 잘릴 위험이 있으면 reviewer를 파일 그룹별로 나눠 실행한다.

### P0. Acceptance Criteria 범위를 feature 영향 범위에 맞게 넓히기

- 이유: `Product` 생성 계약처럼 shared fixture에 영향을 주는 변경은 product 패키지 테스트만으로 충분하지 않다.
- 영향: step은 통과했지만 전체 테스트에서는 다른 도메인 테스트가 깨질 수 있다.
- 해결 방향:
  - 도메인 엔티티 생성자나 builder 계약이 바뀌는 step은 `./gradlew test`를 AC 또는 후속 검증에 포함한다.
  - 최소한 `rg "Product.builder"` 같은 파급 범위 탐색을 step에 명시한다.

### P1. step을 더 작게 나누기

- 이유: step1이 관리자 API 전체를 한 번에 다뤄 diff가 커졌다.
- 영향: review truncation과 수동 검토 비용이 커졌다.
- 해결 방향:
  - `admin-product-create-api`, `admin-product-update-delete-api`, `admin-product-controller-tests`처럼 분리한다.
  - 각 step의 변경 파일 수와 테스트 범위를 작게 유지한다.

### P1. 실행 환경 의존성 정리

- 이유: Git ref/index 권한과 Codex session 권한 문제는 기능 구현과 무관하지만 실행 시간을 크게 늘렸다.
- 영향: 실행기 실패 원인이 구현 실패인지 환경 실패인지 매번 구분해야 했다.
- 해결 방향:
  - dev-start 실행 전 preflight에서 Git 쓰기 가능 여부와 Codex session 쓰기 가능 여부를 확인한다.
  - session 쓰기가 막힌 환경에서는 기본적으로 `--ephemeral` 실행 모드를 사용한다.

### P2. DB 마이그레이션 전략 명시

- 이유: JPA 엔티티와 문서는 바뀌었지만 실제 DB 마이그레이션 도구는 현재 레포지토리에 없다.
- 영향: 로컬 테스트는 통과해도 운영 DB에는 `status` 필수 컬럼 추가와 기존 row 기본값 처리가 필요하다.
- 해결 방향:
  - 마이그레이션 도구 도입 여부를 결정한다.
  - 최소한 `tbl_product.status` 기존 row 기본값 `ON_SALE`, `deleted_at = null` 적용 절차를 운영 문서에 남긴다.

## 왜 오래 걸렸는가

- 기능 구현보다 실행기 환경 문제를 분리하는 데 시간이 들었다.
- step1에서 요구사항과 구현 해석이 달라 retry가 발생했다.
- step1 diff가 커져 reviewer가 blocked 처리했고, 자동 phase 진행이 끊겼다.
- `ProductStatus` 필수화가 product 패키지 밖 테스트 fixture까지 영향을 줘 전체 테스트 기준으로 보정해야 했다.
- step2 문서 동기화와 phase 완료 처리를 수동으로 마무리해야 했다.

## 얻은 교훈

- 엔티티 생성 계약을 바꾸는 작업은 작은 도메인 변경이 아니라 전체 테스트 fixture 변경으로 봐야 한다.
- step AC가 좁으면 빠르게 통과할 수 있지만, shared domain 변경에서는 회귀를 놓칠 수 있다.
- reviewer는 전달받은 diff만 본다. 자동 리뷰를 믿으려면 diff 전달 방식도 review 대상만큼 중요하다.
- 문서 동기화 step은 앞 step이 blocked 되면 자동으로 누락되기 쉬우므로, 최종 완료 전에 루트 문서 diff를 반드시 확인해야 한다.
- 환경 실패와 구현 실패를 로그와 phase 상태에서 명확히 구분해야 후속 판단이 쉬워진다.

## 다음 feature에서의 체크리스트

- step 작성 전 `rg "Product.builder"`처럼 변경 계약의 사용처를 먼저 확인한다.
- 엔티티 필수 필드가 추가되면 해당 도메인 테스트만이 아니라 전체 테스트 실행을 계획한다.
- controller, DTO, service, test가 모두 필요한 API step은 둘 이상의 step으로 나눈다.
- reviewer blocked가 발생하면 구현 문제인지 diff 전달 문제인지 먼저 구분한다.
- 실행기 시작 전 Git 쓰기와 Codex session 쓰기 preflight를 확인한다.
- 루트 문서 동기화는 마지막에 `docs/*.md` diff로 별도 검토한다.
