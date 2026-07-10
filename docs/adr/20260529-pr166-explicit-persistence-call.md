# 응용 계층은 영속화 호출을 명시적으로 표현한다

- Status: accepted
- Date: 2026-05-29

## Context

dirty checking은 JPA의 "트랜잭션 종료 시 자동 flush" 동작에 묵시적으로 의존한다. 응용 코드는 "수정만 호출"하지만, 코드 작성자는 머릿속에 "트랜잭션이 끝나면 자동 저장됨"이라는 ORM-specific 동작 모델을 전제로 깔고 작성해야 한다. 이로 인해 (a) 응용 계층의 사고 모델이 JPA에 묶이고, (b) DDD의 "domain은 상태 변경, application은 영속화 조율" 책임 분리가 코드 표면에 드러나지 않는다.

`repository.save(entity)` 명시 호출은 (a) 응용 코드가 어떤 ORM/persistence 메커니즘이든 동일한 사고 모델을 유지하게 하고, (b) 영속화가 application의 명시적 책임이라는 DDD layer 분리를 코드 표면에 드러내며, (c) 코드 리뷰 시 "여기서 저장한다"라는 의도가 즉시 보인다. CLAUDE.md "비즈니스 로직은 Domain/application 계층" 원칙이 영속화 책임에도 적용된 형태다. import 수준의 의존도는 dirty checking과 같지만(둘 다 `Repository` port에만 의존), 인지적·표현적 의존도가 ORM-agnostic으로 떨어진다.

## Decision

응용 Service가 도메인 객체의 상태를 변경한 뒤 영속화가 필요한 경우, JPA dirty checking에 묵시적으로 기대지 않고 `repository.save(entity)`를 명시적으로 호출한다. managed entity의 `save()`는 JPA 내부에서 no-op이지만, 응용 코드 표면에 "이 시점에 저장 의도"를 드러내는 것이 본 ADR의 목적이다.

- **적용 범위**: 본 ADR 이후 신설되는 응용 Service에 적용한다. 본 cart phase의 `AddCartItemProcessor`, `UpdateCartItemQuantityProcessor`에 적용된다. 기존 도메인의 dirty checking 의존 코드 마이그레이션은 별도 트랙으로 분리한다.
- **새 entity insert와의 차이**: transient entity(`id == null`)의 `save()`는 JPA persist 경로라 호출이 없으면 INSERT가 일어나지 않는다. 본 ADR은 그 외 update 경로에도 동일하게 명시 호출하라는 정책이다. detached entity의 `save()`(merge)는 모든 필드를 덮어써 동시 갱신을 깨뜨리는 위험 경로이지만, 본 phase의 흐름에는 detached entity가 등장하지 않는다.

## Consequences

managed entity에 대한 `save()` 호출이 형식상 no-op이지만 코드 라인이 추가된다. 다만 이 라인은 곧 의도 명세이며, 향후 ORM 변경·JDBC 직접 사용 같은 시나리오에서도 코드 변경 부담을 낮춘다. 또한 같은 분기 안에서 신규 entity(transient)와 기존 entity(managed)를 모두 `save()`로 통일하면 분기 시각적 비대칭이 사라진다.
