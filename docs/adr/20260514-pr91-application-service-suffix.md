# application 계층 클래스명은 Service suffix를 사용한다

- Status: superseded by [20260614-pr248-application-role-suffix](20260614-pr248-application-role-suffix.md)
- Date: 2026-05-14

## Context

Spring 기반 프로젝트 관습과의 일관성을 유지하고, 기존 코드베이스의 네이밍과 통일한다. 구조적으로는 UseCase 패턴과 동일하다 (`CreateOrderService` = `CreateOrderUseCase`).

## Decision

유스케이스 단일 책임 구조를 유지하되, 클래스 suffix는 `UseCase` 대신 `Service`로 명명한다.

## Consequences

DDD 순수론 관점에서 `UseCase`가 더 명확한 의도를 드러내나, 현재는 친숙한 네이밍을 우선한다.
