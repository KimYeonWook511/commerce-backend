# Retrospective: auth-service-to-usecase

## 작업 개요

`AuthLoginService`·`AuthTokenReissueService`가 `application/service/` 패키지에 있으면서 `application/usecase/`의 `AuthTokenIssueUseCase`를 주입받는 역전 구조를 해소한 리팩터링.

- PR: #249
- 브랜치: `refactor/auth-service-to-usecase`
- 완료일: 2026-06-14

## 탐색·설계 과정 (Stage 1~3)

### B분할 범위 정의

처음 스캔 단계에서 5개 클래스가 후보로 나왔다.

- `OrderCancelService`, `OrderExpirationService`, `StockRestoreOutboxConsumeService` — ADR-054의 "여러 단위작업을 한 tx로 묶는 service" 허용 조항 확인 후 제외. 현행 구조가 올바름.
- `AuthLoginService`, `AuthTokenReissueService` — `service/`가 `usecase/`를 주입받는 역전 구조. UseCase 전환 대상으로 확정.

### @Transactional(readOnly=true) 제거 판단

`@Transactional(readOnly=true)` 제거가 안전한지 트랜잭션 이론 관점에서 검토했다.

- 단건 DB 조회 하나씩, 재조회·락 없음 → 상위 tx 경계를 추가로 열 실질적 이유 없음
- `MemberQueryService`가 자체 `@Transactional(readOnly=true)`를 보유 → 호출된 쪽에서 이미 tx 관리
- ArchUnit `transactionalOnlyInServicePackage` 규칙이 `usecase/` 패키지의 `@Transactional`을 금지

read-only tx를 하나로 묶는 것이 의미있는 경우(같은 데이터 재조회 일관성, SELECT FOR SHARE, replica routing)가 모두 해당하지 않아 제거 결정.

### 외부 검토에서 발견한 보완 항목

다른 agent에게 step1.md 검토를 의뢰해 critical 3개를 보완했다.

1. **`@MockBean` → `@MockitoBean` 정정** — Spring Boot 3.4+에서 어노테이션이 변경됨. 미정정 시 agent가 잘못된 코드를 작성할 위험.
2. **`@Slf4j` 이전 명시 누락** — `AuthLoginService`에 `@Slf4j`와 `log.info()`가 있었는데 step 문서에 명시 없었음.
3. **`docs/adr.md` ADR-021 예시 처리 방침 미명시** — 역사적 배경 문장을 수정해야 하는지 모호해 금지사항에 추가.

## 실행 (Stage 6)

step 1회, 재시도 없이 1차 시도에서 `BUILD SUCCESSFUL` 완료.

변경 파일 6개:
- `AuthLoginService.java` 삭제 → `AuthLoginUseCase.java` 신규
- `AuthTokenReissueService.java` 삭제 → `AuthTokenReissueUseCase.java` 신규
- `AuthController.java` import·타입 변경
- `AuthLoginServiceTest.java` 삭제 → `AuthLoginUseCaseTest.java` 신규
- `AuthTokenReissueServiceTest.java` 삭제 → `AuthTokenReissueUseCaseTest.java` 신규
- `AuthControllerTest.java` import·mock 타입 변경

## Root Sync (Stage 8)

동기화 불필요. `docs/architecture.md`는 `usecase/`·`service/` 개념을 이미 정의하고 있고 특정 클래스명 없이 개념 수준으로 기술되어 있어 이번 이동분을 별도 반영할 내용이 없었다. API·DB·ADR 변경 없음.

## 교훈

### 역전 구조 감지 패턴

`service/`가 `usecase/`를 주입받는 구조는 "방향이 거꾸로인 것"이 신호다. ADR-054가 정착된 이후 패키지를 순차 적용하는 과정에서 발생하기 쉬운 패턴으로, 이번 B분할 스캔에서 처음 발견됐다.

### read-only tx 경계 판단 기준

상위 read-only tx 경계가 실질적으로 의미있는 경우는 세 가지다: (1) 같은 데이터를 두 번 이상 조회하며 일관성이 필요할 때, (2) SELECT FOR SHARE 같은 공유 잠금, (3) DB replica routing 힌트. 단건 조회 하나라면 하위 서비스가 자체 tx를 열므로 상위 경계가 불필요하다.

### 외부 agent 검토의 효용

step 문서를 별도 agent에게 검토 의뢰하니 `@MockBean` → `@MockitoBean` 같은 버전 변경 사항을 잡아냈다. 직접 작성자가 놓치기 쉬운 어노테이션 버전 차이나 누락된 파일 처리 방침을 독립 시선에서 잡을 수 있다.
