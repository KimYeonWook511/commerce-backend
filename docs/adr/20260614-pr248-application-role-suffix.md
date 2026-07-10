# application 계층은 역할별 접미사·빈 애너테이션으로 흐름과 tx 단위작업을 가른다

- Status: accepted
- Date: 2026-06-14

## Context

application 계층 클래스명에 `…Service` 단일 접미사를 쓰기로 한 기존 결정(→ PR#91)은 Spring 관습 일관성을 위한 것이었으나, structure-migration(usecase/service 패키지 물리 분리, → PR#247)으로 패키지(역할)와 클래스명이 어긋났다. 이 결정은 그 단일 접미사 규칙을 대체한다.

접미사·빈 애너테이션을 역할과 일치시키면 패키지 경로가 안 보이는 곳(import·스택 트레이스·로그)에서도 역할이 드러난다.

## Decision

application 계층 클래스의 접미사·패키지·빈 애너테이션을 역할에 맞춰 이원화한다.

- `usecase/` (흐름 조립·정책 선택, tx 없음) → 클래스명 `…UseCase`, 빈 등록 `@Component`
- `service/` (tx 단위작업) → 클래스명 `…Service`, 빈 등록 `@Service`

**분류 기준**: `@Transactional` 애너테이션 유무가 아니라 **실제 tx를 여는가**로 판단한다. `Propagation.NOT_SUPPORTED`는 tx를 열지 않으므로, NOT_SUPPORTED 메서드만 가진 orchestrator는 `usecase/`로 분류하고 애너테이션을 제거한다. tx를 실제로 여는 메서드(REQUIRED 등)를 하나라도 가지면 `service/`로 둔다(혼합 클래스 포함).

## Consequences

기존 코드의 클래스명·빈 애너테이션 일괄 변경. 동작은 불변(이동·리네임). `@Service`만 쓰던 관습과 달라진다.
