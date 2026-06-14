# Retrospective: application-layer-relocate

> PR #248 · 브랜치 `refactor/application-layer-relocate`

---

## 작업 개요

application 계층을 UseCase / Service 두 역할로 이원화하고, `@Transactional` 범위를 method-level로 좁히며, ArchUnit strict 모드로 전환하는 리팩터. ADR-006 초안 정책을 ADR-054(UseCase/Service 역할 분리)·ADR-055(ArchUnit strict)로 대체했다.

---

## Step 1 — role-suffix-and-component

**목표**: 기존 `usecase/` 클래스 7개의 접미사를 `...UseCase`로 바꾸고 `@Service` → `@Component`로 전환한다.

**결과**: 1회 시도, BUILD SUCCESS.

**교훈**
- cart 도메인의 두 클래스(`AddCartItemProcessor`, `UpdateCartItemQuantityProcessor`)가 `usecase/`가 아닌 `service/`에 있어야 했는데 `usecase/`에 배치돼 있었다. 패키지 배치 기준을 정할 때 `@Transactional` 유무를 함께 확인해야 한다.

---

## Step 2 — class-tx-to-method

**목표**: `application/service/` 클래스의 class-level `@Transactional`을 method-level로 이동한다.

**결과**: API 세션 한도 초과로 3회 연속 실패(응답 없음), 4번째 시도에서 성공.

**교훈**
- 11개 대상 클래스 중 7개는 이미 method-level이었다 — 사전 탐색으로 실제 변경 대상을 좁혀 step scope를 줄일 수 있었다.
- 세션 한도 초과는 harness 실행기 재시도 로직으로 자동 흡수됐다. 재시도 횟수 기록(step output) 덕분에 원인을 사후 확인할 수 있었다.

---

## Step 3 — orchestrator-to-usecase

**목표**: `OrderCreateService`, `AuthSignUpService`의 역할을 UseCase로 이동하고, `NOT_SUPPORTED` 패턴을 제거한다. ArchUnit 규칙도 추가한다.

**결과**: 1회 시도, BUILD SUCCESS. 단 ArchUnit 규칙에서 중첩 클래스 오탐 1건 발생.

**시행착오**
- `usecaseClassesShouldEndWithUseCase` 규칙이 `StockRestoreOutboxRelayUseCase$PublishResult`, `PaymentReconciliationUseCase$PaymentReconcileOutcome` 등 내부 클래스/익명 클래스 5건에 걸렸다. `.and().areTopLevelClasses()` 조건 추가로 해결.
- 교훈: ArchUnit 접미사 규칙은 항상 `areTopLevelClasses()` 필터를 먼저 적용한다. 중첩·익명 클래스는 이름 규칙 대상이 아니다.

---

## Stage 7 — PR Review (Gemini Code Assist)

**코멘트 15개 전부 accept 처리.**

**주요 수정 (3개 커밋)**

| 커밋 | 내용 |
|---|---|
| `977fc83` | UseCase 타입 필드·파라미터명을 UseCase 접미사에 맞게 통일 (메인 코드 14개 파일) |
| `2698820` | OrderCreateUseCase 주석에서 NOT_SUPPORTED 언급 제거 |
| `82300de` | 테스트 코드 변수명·클래스명 UseCase 접미사로 통일 (27개 파일 + 클래스 2개 git mv) |

**Gemini가 놓친 것 (agent가 추가 발굴)**

Gemini는 메인 코드 필드명만 지적하고 테스트 코드는 검사하지 않았다. 정규식 `UseCase [a-z][A-Za-z]*Service\b`로 전체 스캔해 27개 파일을 추가 수정했고, `NaverPayServiceIntegrationTest` / `NaverPayServiceConcurrencyTest` 클래스명도 UseCase 이름으로 git mv했다.

**교훈**
- Gemini 등 외부 리뷰어는 메인 코드 위주로 검사한다. review 완료 후 테스트 코드에도 동일 패턴이 남아 있는지 별도 스캔이 필요하다.
- 타입과 변수명 접미사 불일치는 리네임 시점에 grep으로 한 번에 잡아야 한다. 리네임 후 "참조 업데이트"가 메인 코드에만 적용되고 테스트가 빠지는 경우가 생긴다.

---

## Stage 8 — Root Sync

- `docs/adr.md`: ADR-054(UseCase/Service 역할 분리·접미사), ADR-055(ArchUnit strict) 추가. ADR-006 상태 → `superseded`.
- `docs/architecture.md`: application 계층 이원화·tx 정책 반영.
- `docs/package-structure-guide.md`: UseCase/Service 배치 기준, @Transactional 위치 규칙, ArchUnit 보장 범위 갱신.

PR review 변경(변수명·주석)은 내부 구현이므로 루트 문서 추가 동기화 없음.

---

## 남은 후속 작업

- **ADR-L* 주석 정리**: `NaverPayApprovalUseCase`, `PaymentReconciliationUseCase`, `PaymentCancellationService`, Flyway SQL 등에 남아 있는 ADR-L1~L8 주석을 루트 번호 또는 의도 직접 서술로 교체하는 작업이 별도 이슈로 예정돼 있다.

---

## 교훈 요약

1. **ArchUnit 접미사 규칙에는 `areTopLevelClasses()` 필터를 기본 적용한다.** 중첩 클래스가 규칙에 걸리면 진단보다 예방이 낫다.
2. **리네임 후 테스트 코드 스캔을 별도로 수행한다.** 외부 리뷰어는 테스트 코드를 빠뜨리는 경향이 있다.
3. **세션 한도 초과는 harness 재시도 로직으로 흡수된다.** 단, 재시도 횟수가 많으면 step 범위를 줄이거나 실행 시간대를 조정하는 것이 효율적이다.
4. **UseCase 타입 필드명 접미사 정책은 구현 시점에 즉시 적용한다.** 리뷰 단계에서 14개 파일을 일괄 수정하는 비용은 step 내 처리보다 크다.
