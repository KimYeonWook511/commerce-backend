# Architecture: application 계층 역할별 접미사·트랜잭션 선언 정리

루트 구조의 단일 출처는 `docs/architecture.md`·`docs/package-structure-guide.md`다. 이 문서는 이번 task가 바꾸는 부분만 기록한다.

## 변경 전 (structure-migration 직후)

```
<domain>/application/
├── usecase/   # tx 없는 orchestrator — 그러나 클래스명이 …Service (역할 불일치)
└── service/   # tx 단위작업 — …Service / 일부 …Processor
```

- usecase/에 `…Service` 8개, service/에 `…Processor` 3개 → 패키지(역할)와 클래스명 불일치.
- class-level `@Transactional(readOnly = true)` + method override 패턴이 기존 도메인 15개 클래스에 잔존.
- `NOT_SUPPORTED`(tx 안 엶) orchestrator 3개가 `service/`에 잔존.

## 변경 후 (목표)

```
<domain>/application/
├── usecase/   # tx 없는 orchestrator. 클래스명 …UseCase, 빈 등록 @Component
└── service/   # tx 단위작업. 클래스명 …Service, 빈 등록 @Service, @Transactional은 method-level만
```

세 축의 정합:

1. **접미사 = 패키지 = 빈 애너테이션**: `usecase/…UseCase/@Component` ↔ `service/…Service/@Service`. import·스택 트레이스·로그처럼 패키지 경로가 안 보이는 곳에서도 흐름(UseCase)인지 tx 단위작업(Service)인지 드러난다.
2. **tx 선언은 method-level만**: class-level `@Transactional` 폐지. 조회 메서드도 `@Transactional(readOnly = true)`를 메서드마다 명시. (ADR-021 전 도메인 확장)
3. **분류 기준 = "실제 tx를 여는가"**: `@Transactional` 애너테이션 유무가 아니다. `NOT_SUPPORTED`는 tx를 열지 않으므로, 그런 메서드만 가진 orchestrator는 `usecase/`로 간다. tx를 실제로 여는 메서드를 하나라도 가지면 `service/`.

## 분류 결과 요약

- **usecase/ (이동·리네임)**: 기존 usecase 8개(`…Service`→`…UseCase`) + NOT_SUPPORTED orchestrator 3개(AuthSignUp/OrderCreate/StockRestoreOutboxRelay).
- **service/ 유지(+class tx 제거)**: 실제 tx 메서드 보유 클래스. 혼합 클래스(PaymentCancellation·StockConcurrency: NOT_SUPPORTED 메서드 + tx 메서드 공존)도 service.
- **Processor 리네임**: cart 2개·order 1개 `…Processor`→`…Service`. `OrderCreateProcessor`→`OrderCreateService`는 orchestrator가 usecase로 빠진 뒤 이름 충돌이 해소되어 성립.
- **제거**: `OutboxService`(조율 없는 단순 위임). `OrderExpirationService`가 `StockRestoreOutboxCreateService`를 직접 호출하도록 바꾼다. CLAUDE.md "불필요한 추상화를 피한다" + ADR-L1 "조율 없으면 usecase를 두지 않는다"에 따른 정리이며, 기존 `service → usecase` 역방향 의존도 함께 해소된다.

> 리네임은 **접미사만** 바꾼다(`…Service`→`…UseCase`). 어순(`{도메인}{행위}`)은 보존하며 어순 통일은 별도 PR로 분리한다.

## 비자명 사항: NOT_SUPPORTED orchestrator의 self-invocation 안전성

3개 orchestrator가 호출하는 tx 작업은 모두 **별도 빈**에 있다(AuthSignUp→register/issue, OrderCreate→processor, Relay→repository). 자기 클래스에 `@Transactional` 메서드를 self-invoke하는 곳이 없으므로 `usecase/`로 옮겨 애너테이션을 제거해도 프록시 적용이 깨지지 않는다. (만약 self-invoke하는 tx 메서드가 있었다면 그 메서드를 service 빈으로 분리해야 했다.)
