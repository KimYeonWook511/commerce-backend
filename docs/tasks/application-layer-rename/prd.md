# Task PRD

> 이 문서는 이 기능의 **의도를 담은 정본**이며, 작업 종료 후 동결한다.
> 이후 다른 Task에서 이 결정이 바뀌면 이 문서를 거슬러 수정하지 않고,
> 아래 상태/포인터 한 줄만 추가한다. 본문은 당시 기록으로 보존한다.

- 상태: active
- 변경 포인터: 없음

---

## Task명

- `application-layer-rename`

## 배경

ADR-054에서 application 계층의 명명 컨벤션을 확정했다.

- `application/usecase/` → `{행위}UseCase` (흐름 조립·정책 선택, tx 없음)
- `application/service/` → `{행위}Service` (tx 단위 작업, `@Transactional`)
- 어순: `{행위}{대상}` (예: `CreateOrderService`, `CancelOrderService`)

PR #248에서 UseCase/Service 역할 이원화 리네임과 변수명 접미사 통일을 부분적으로 진행했으나, 코드베이스에는 아직 과거 `{도메인}{개념명}Service` 명사형 패턴(`MemberQueryService`, `AdminProductService` 등)과 `{도메인}{행위}` 어순 불일치(`OrderCreateService`)가 잔존한다.

## 목표

- 코드베이스 전체 Service/UseCase 클래스명을 ADR-054 컨벤션으로 통일한다.
- 클래스명 변경에 맞춰 주입 변수명·파라미터명·테스트 클래스명·메서드명·`@DisplayName`도 함께 갱신한다.

## 범위

**포함**

- member, product, stock, order, payment 도메인의 Service/UseCase 클래스
- 각 클래스를 주입받는 모든 클래스의 변수명·파라미터명
- 테스트 클래스명·메서드명·`@DisplayName`
- `AdminProductService`, `AdminStockService`, `StockInventoryService`, `PaymentApprovalRecordService`, `PaymentApprovalService`, `PaymentCancellationService` 분리(메서드별 별도 Service 파일)

**제외**

- cart, auth, outbox 도메인 — 이미 컨벤션을 준수하고 있음
- 동작 변경 없음 — 순수 rename + split(기존 메서드를 별도 파일로 이동)
- DB 스키마·API 계약 변경 없음

## 주요 시나리오

해당 없음 — 외부 동작이 바뀌지 않는 내부 리팩터링이다.

## 요구사항

- 클래스명은 ADR-054의 `{행위}{대상}Service` / `{행위}{대상}UseCase` 형식을 따른다.
- Admin 한정 Service는 `Admin{행위}{대상}Service` 형식을 사용한다.
- 분리된 Service는 기존 메서드를 1:1로 이동하며, 내부 로직을 변경하지 않는다.
- 주입 변수명은 새 클래스명의 camelCase를 따른다.
- 테스트의 클래스명·메서드명·`@DisplayName`도 새 이름 기준으로 갱신한다.

## 제약사항

- 각 step 완료 후 `./gradlew test` 가 통과해야 한다.
- 기존 테스트를 삭제하지 않는다. 이름만 변경한다.
- Spring Bean 등록 방식(`@Service`, `@Component`)은 변경하지 않는다.
