# Task ADR (staging): application-layer-relocate

> 이 파일은 이 task에서 **새로 채택된 결정의 staging**이다(임시 번호 L1·L2…). harness Stage 8(Root Sync)에서 루트 전역 번호(ADR-XXXX)로 `docs/adr.md`에 append된다.

---

## ADR-L1: application 계층은 역할별 접미사·빈 애너테이션으로 흐름과 tx 단위작업을 가른다 (ADR-006 supersede)

- **결정**: application 계층 클래스의 접미사·패키지·빈 애너테이션을 역할에 맞춰 이원화한다.
  - `usecase/` (흐름 조립·정책 선택, tx 없음) → 클래스명 `…UseCase`, 빈 등록 `@Component`
  - `service/` (tx 단위작업) → 클래스명 `…Service`, 빈 등록 `@Service`
- **분류 기준**: `@Transactional` 애너테이션 유무가 아니라 **실제 tx를 여는가**로 판단한다. `Propagation.NOT_SUPPORTED`는 tx를 열지 않으므로, NOT_SUPPORTED 메서드만 가진 orchestrator는 `usecase/`로 분류하고 애너테이션을 제거한다. tx를 실제로 여는 메서드(REQUIRED 등)를 하나라도 가지면 `service/`로 둔다(혼합 클래스 포함).
- **이유**: ADR-006은 Spring 관습 일관성을 위해 `…Service` 단일 접미사를 택했으나, structure-migration으로 `usecase/`·`service/` 패키지가 물리 분리되면서 패키지(역할)와 클래스명이 어긋났다. 접미사·빈 애너테이션을 역할과 일치시키면 패키지 경로가 안 보이는 곳(import·스택 트레이스·로그)에서도 역할이 드러난다. `@Component`/`@Service`는 기능 동일(stereotype)하지만 역할 신호로 분리한다.
- **트레이드오프**: 기존 코드의 클래스명·빈 애너테이션을 일괄 변경한다. 동작은 불변(이동·리네임). `@Service`만 쓰던 관습과 달라진다.
- **supersedes**: ADR-006.

## ADR-L2: application class-level `@Transactional`을 전 도메인에서 폐지한다 (ADR-021 적용 범위 확장)

- **결정**: ADR-021("응용 Service의 `@Transactional`은 method-level에만 부착")의 적용 범위를 **기존 도메인(Order/Stock/Auth/Member/Product/Payment) 전체**로 확장한다. class-level `@Transactional(readOnly = true)` 기본 + method override 패턴을 제거하고, 조회 메서드도 `@Transactional(readOnly = true)`를 메서드마다 명시한다. ArchUnit으로 application 패키지의 class-level `@Transactional`을 금지한다.
- **이유**: ADR-021의 근거(메서드별 tx 정책이 코드 표면에 명시됨, 누락이 silent readOnly가 아니라 "tx 없음"으로 즉시 드러남)는 신설 Service뿐 아니라 기존 도메인에도 동일하게 유효하다. ADR-021이 "기존 도메인 마이그레이션은 후속 트랙으로 분리"라고 예고한 그 후속 트랙이다.
- **NOT_SUPPORTED 함정 주의**: AuthSignUp·OrderCreate는 ADR-008대로 class-level readOnly를 method-level `NOT_SUPPORTED`로 끄고 있다. class-level을 제거할 때 단순 제거하면 안 되고(class readOnly가 사라지면 의도와 동일해지지만), 이들은 본 task에서 usecase로 이동하며 `@Transactional` 자체를 제거한다(ADR-L1). 호출처가 진입점뿐이라 동작 보존.
- **트레이드오프**: 메서드 수만큼 애너테이션이 반복된다(ADR-021 트레이드오프와 동일). 의도 명세 역할이라 가독성 손실이 아니다.
- **연계**: ADR-021, ADR-008.
