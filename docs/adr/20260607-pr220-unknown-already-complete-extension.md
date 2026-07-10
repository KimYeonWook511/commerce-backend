# "결과 불명 → UNKNOWN 보존" 원칙을 AlreadyComplete history 재확인·cancel 경로로 확장한다

- Status: accepted
- Date: 2026-06-07

## Context

기존 결정(→ PR#218)은 PG 호출 예외를 "요청 전송 시점"을 경계로 전파(가시화)와 UNKNOWN 보존(이중결제 방어)으로 갈랐고, 이를 approve **직접 승인 경로**에만 적용하면서 AlreadyComplete history 경로와 cancel 경로를 *적용 범위*에서 후속으로 분리해 뒀다. 본 결정은 그 확장이다.

#219. 그 사이 `getApprovalHistory`는 결과 불명 예외까지 전부 `FAILED`로 떨어뜨려, AlreadyComplete(=PG가 이미 처리한 상태) 후 이력조회가 네트워크 오류로 실패하면 Payment가 REQUESTED로 남아 UNKNOWN 흔적이 안 남았다 → `existsUnknownByOrderId` 차단을 우회하고 "결제됐는데 미결제 박제 + 결과 불명 미보존"이 됐다. cancel도 결과 불명을 `FAILED`로 박제해, PG가 실제로 취소했어도 cancel 기록이 FAILED로 남아 대사에서 누락될 수 있었다.

AlreadyComplete는 PG가 멱등하게 "이미 됨"을 반환하는 상태라 기존 결정(→ PR#218)의 "전송 후/불명 → UNKNOWN 보존" 원칙이 가장 강하게 적용돼야 하는 지점이다. 결과 불명을 FAILED로 두면 결제 도메인 재설계(→ PR#205)의 UNKNOWN 차단 안전망이 무력화된다. 분류축은 기존 결정과 동일하게 *재시도 안전성(PG 처리 가능성)*이다.

## Decision

- approve **직접 승인 경로**에만 적용했던 *결과 불명(NETWORK/SERVER_ERROR/INVALID_RESPONSE) → UNKNOWN 보존* 원칙을 다음 두 경로로 동일하게 확장한다.
  - **AlreadyComplete history 재확인**: PG가 `AlreadyComplete`로 응답해 `getApprovalHistory`로 결과를 재확인하는 경로에서, 이력조회가 결과 불명류 예외나 외부 응답 이상(이력 목록·상세 누락, 승인 이력인데 `merchantPayKey` 누락)으로 결과를 확정하지 못하면 `FAILED`가 아니라 `NaverPayHistoryResult.UNKNOWN`으로 반환한다. application은 이를 받아 `markUnknownIfRequested`로 흔적을 남기고 `PAYMENT_RESULT_PENDING`(409)을 던진다(approve 직접 경로의 `case UNKNOWN`과 동일). 외부 응답 이상은 `catch (NPE)`가 아니라 명시적 null 체크로 가르고, 그 외 예상 못 한 NPE는 전파해 안전망(500)에 위임한다(#218 일관화). `merchantPayKey`가 누락(null)이 아니라 **존재하나 우리 키와 다른** 경우는 외부 응답 이상이 아니라 확정적 키 불일치이므로 `FAILED`(MERCHANT_PAY_KEY_MISMATCH)로 가른다.
  - **cancel(보상 취소)**: `NaverPayGatewayImpl.cancel`이 결과 불명류 예외를 만나면 `NaverPayCancelResult.UNKNOWN` → `CancelOutcome.UNKNOWN`으로 전달하고, 보상 흐름은 cancel 기록을 `markUnknownIfRequested`(CANCEL 타입)로 UNKNOWN 보존한다.
- 명시적 실패(InvalidMerchant 등 PG가 요청을 거절), 이력 없음(빈 목록), 인증 실패/잘못된 요청(CLIENT_ERROR/AUTHENTICATION)은 결과가 확정적이므로 기존대로 `FAILED`를 유지한다.

## Consequences

cancel의 UNKNOWN 자동 해소(보상 취소 재시도)는 본 결정 범위 밖이며 결제 도메인 배치/스케줄러(Epic #208)로 분리한다. CANCEL 타입 UNKNOWN 행은 `existsUnknownByOrderId`(APPROVE 한정)에 잡히지 않으므로 주문의 재결제를 brick하지 않는다(대사 흔적만 남김).

연계: 요청 전송 시점 경계 결정(→ PR#218) — 본 결정이 그 *적용 범위*를 확장한다. UNKNOWN 마킹/차단 정책(→ PR#205), Epic #208 (cancel UNKNOWN·보상 취소 실패 재처리), `docs/exception-strategy.md` "결제 결과 UNKNOWN 처리".
