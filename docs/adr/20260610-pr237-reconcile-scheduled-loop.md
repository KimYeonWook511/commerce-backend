# UNKNOWN/stale REQUESTED 대사를 `@Scheduled` 서비스 루프로 구현한다

- Status: accepted
- Date: 2026-06-10

## Context

실행 메커니즘 후보로 Spring Batch(주문 만료 배치 선례)와 단순 스케줄러(outbox 선례)가 있었다. 대사는 건별 PG 외부 호출 + 실패 격리가 핵심이라 chunk 트랜잭션과 외부 호출 경계를 분리하는 처리가 별도로 필요하다.

- **이유**: 건별로 트랜잭션 경계를 좁히고 PG 호출을 경계 밖에 두는 편이 안전하며, outbox 스케줄러와 동일 패턴이라 운영 일관성도 확보된다.

## Decision

결과 불명(UNKNOWN)·응답 저장 전 끊긴(stale REQUESTED) APPROVE 결제를 PG 이력 조회로 확정하는 대사를, Spring Batch가 아니라 `@Scheduled` 트리거 + 서비스 루프로 구현한다. stale 후보를 한 번 조회한 뒤 건별 단건 트랜잭션으로 처리하고, PG 외부 호출은 트랜잭션 경계 밖에서 수행한다.

## Consequences

대량 처리·정교한 재시도 정책이 필요해지면 후속에서 Batch로 승격할 수 있다. 한 주기 처리량은 배치 size 상한으로 제한된다.

관련: 후처리 대상을 status 중심으로 식별하는 결정(→ PR#224), 대사 시작 임계 결정(→ PR#224), Epic #208(batch #1), #222.
