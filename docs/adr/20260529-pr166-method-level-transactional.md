# 응용 Service의 `@Transactional`은 method-level에만 부착한다

- Status: accepted
- Date: 2026-05-29

## Context

기존 코드베이스는 class-level `@Transactional(readOnly = true)` 기본 + method-level `@Transactional` 쓰기 메서드 override 패턴이 광범위하다(`OrderCreateService`, `OrderCancelService`, `AuthLoginService` 등). 본 패턴은 (a) 메서드의 트랜잭션 정책이 한눈에 안 들어와 class 선언으로 시선이 이동해야 하고, (b) 새 메서드를 추가하면서 method-level 어노테이션을 누락하면 의도와 다른 정책(`readOnly`)이 silent로 적용되며, (c) 코드 리뷰 시 누락 여부가 표면에 드러나지 않는다.

method-level만 사용하면 (a) 모든 메서드의 트랜잭션 정책이 코드 표면에 명시되고, (b) 누락은 곧 "트랜잭션 없음"으로 즉시 드러나며, (c) 메서드별 정책 차이가 한눈에 비교 가능하다. class-level "기본값 + override" 구조가 주는 코드 줄 수 절약 가치보다 명시성·실수 방지 가치가 더 크다는 판단이다.

## Decision

응용 Service(`com.commerce.<domain>.application.*Service`)에 class-level `@Transactional` 부착을 금지한다. 모든 트랜잭션 경계는 method-level `@Transactional`로만 표현한다. retry loop를 포함하는 outer Service는 어노테이션 없이 두고, 트랜잭션 경계는 별도 Processor 빈의 method-level `@Transactional`이 책임진다(`OrderCreateProcessor` 패턴, 본 cart phase의 `AddCartItemProcessor`/`UpdateCartItemQuantityProcessor` 등).

- **적용 범위**: 본 ADR 이후 신설되는 응용 Service에 적용한다. 본 cart phase의 4개 Service(`AddCartItemService`, `GetMyCartService`, `UpdateCartItemQuantityService`, `RemoveCartItemService`)에 적용된다. 기존 도메인(Order/Stock/Auth 등)의 class-level `@Transactional` 마이그레이션은 본 ADR의 후속 트랙으로 분리한다.
- **Processor 패턴과의 관계**: retry/멱등 등 트랜잭션 외부에서 처리해야 할 흐름을 가진 Service는 어노테이션 없이 outer 역할만 담당하고, 실제 트랜잭션은 별도 Processor 빈에 method-level `@Transactional`로 둔다. retry attempt마다 빈 경계를 넘어가며 새 트랜잭션·새 persistence context가 시작되고, self-invocation 함정이 회피된다.

## Consequences

메서드 수만큼 어노테이션이 반복된다. 다만 어노테이션이 곧 정책 명세 역할을 하므로 가독성 손실이라기보다 의도 표현이다. 조회 전용 Service에서도 `@Transactional(readOnly = true)`를 메서드마다 부착해야 한다.

적용 범위는 이후 전 도메인으로 확장됐다(→ PR#248).
