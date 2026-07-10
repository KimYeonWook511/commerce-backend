# 취소 응답은 취소 접수 시점에서 끊고 PG 환불 결과는 best-effort로 담는다

- Status: accepted
- Date: 2026-06-18

## Context

취소·환불 보장은 영속된 의도(주문 취소와 단일 tx로 영속화한 환불 의도 → PR#258) + 대사가 책임지므로 사용자가 PG 왕복을 끝까지 기다릴 필요가 없다.

## Decision

취소 API 응답은 조율 service tx 커밋(주문 CANCELED + 환불 의도 영속화) 시점에 보장되는 "취소 접수"를 기준으로 반환한다. 커밋 후 인라인 best-effort PG 환불을 시도해 happy path는 환불 결과까지, UNKNOWN/실패는 "환불 처리중"으로 응답하고 standalone CANCEL 대사(→ PR#258)가 마무리한다. 응답 DTO에 환불 진행 상태 필드를 둔다.

## Consequences

완전 비동기 인프라는 현재 불필요하며 필요해지면 가산적으로 도입한다.
