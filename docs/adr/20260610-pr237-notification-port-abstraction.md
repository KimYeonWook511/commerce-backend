# 대사·보상 통지는 NotificationPort 추상화로 두고 채널 adapter는 후속으로 분리한다

- Status: accepted
- Date: 2026-06-10

## Context

실제 채널까지 이번에 붙이면 운영 웹훅 URL·환경별 설정·전송 실패 처리가 본 task 범위를 넓힌다. 반대로 port 자체를 안 두면 후속에서 hook 지점을 다시 찾아야 한다.

- **이유**: 진실 원천은 `FAILED`+failCode(보상)·`UNKNOWN`+로그이고 알림은 부가 push다. port로 hook만 확보하면 채널 교체가 adapter 교체로 끝난다.

## Decision

보상 취소·escalation 시 운영자 통지를 위해 `NotificationPort`(알림 추상화) + no-op(로그) 구현만 둔다. 통지 hook 지점을 대사/보상 flow에 미리 박고, 실제 채널 adapter(디스코드 웹훅 등)는 별도 후속으로 분리한다. 통지는 commit 이후 best-effort이며 전송 실패가 트랜잭션을 막지 않는다.

## Consequences

이번엔 통지가 로그로만 남는다. 실제 채널은 후속에서 adapter만 추가하면 된다.

관련: 취소 주문 보상 환불의 통지 결정(→ PR#237), MANUAL_REVIEW 상태 미도입·escalation 시간 윈도우 결정(→ PR#237), #238.
