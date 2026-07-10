# 대사 시작 임계를 NaverPay 승인 가능 시간(10분)에서 파생하고 UNKNOWN/REQUESTED를 분리하며 장기 미해소는 MANUAL로 승급한다

- Status: accepted
- Date: 2026-06-08

## Context

기존 정책은 approve/cancel 공통 5분 단일 임계였다. NaverPay는 인증 후 **10분** 안에 승인(capture)해야 하며 초과 시 `TimeExpired`(`NaverPayApproveCode`)다. 5분은 이 승인 윈도우 한가운데라 정상 진행 중(AlreadyOnGoing)인 REQUESTED를 stale로 오판한다.

stale 판단 시간 = "PG 처리 시간 + 마진"(Epic #208). UNKNOWN은 `existsUnknownByOrderId`로 재결제를 차단하므로 빠른 복구 가치가 크다(capture 후 ack 유실이면 `getApprovalHistory`가 즉시 APPROVED를 줘 unblock). REQUESTED는 차단이 아니고 일찍 물어도 진행 중만 나오므로 윈도우가 닫힌 뒤 대사한다.

## Decision

임계를 둘로 분리하고 NaverPay 시간에서 파생한다 — `UNKNOWN_RECONCILE_DELAY`≈1분(UNKNOWN은 빠른 폴링), `REQUESTED_STALE_DELAY`≈15분(승인 가능 시간 10분 + 마진 5분). reconcile 대상이 `ESCALATION_DELAY`(시간 단위)를 넘도록 PG가 결론을 못 내면 `MANUAL_REVIEW`로 승급한다. 경과 기준 시각은 REQUESTED=`createdAt`, UNKNOWN/FAILED=`respondedAt`. cancel은 시간 임계가 아니라 응답 코드로 가른다(`CancelDeadlineExpired`→MANUAL, `CancelNotComplete`/`AlreadyOnGoing`→폴링).

## Consequences

UNKNOWN을 빨리 폴링해 PG 조회 부하가 약간 늘지만 배치 주기가 실효 간격을 지배한다. escalation은 poll-count가 아닌 age 기반 근사다. 임계 상수는 정책 내 `Duration`으로 두되 #208 운영 config 승격을 전제한다.

연계: `NaverPayApproveCode.TIME_EXPIRED`(10분), 요청 전송 시점 경계 결정과 그 확장(→ PR#218, PR#220 — 분류축 계승), Epic #208.
