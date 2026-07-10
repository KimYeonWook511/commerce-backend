# PG 호출 예외는 "요청 전송 시점"을 경계로 전파(가시화)와 UNKNOWN 보존(이중결제 방어)을 가른다

- Status: accepted
- Date: 2026-06-07

## Context

#206. `NaverPayGatewayImpl.approve`·`NaverPayClient.post`의 후행 `catch (Exception)`이 `NullPointerException` 같은 프로그래밍 버그까지 `UNKNOWN`/`INVALID_RESPONSE`로 흡수했다. UNKNOWN 행은 `existsUnknownByOrderId`로 reserve/approve를 영구 차단(brick)하고(결제 도메인 재설계의 UNKNOWN 마킹/차단 정책, → PR#205), 자동 해소(`PaymentReconciliationService`)는 미구현이라 일시적 버그 한 번에 결제가 수동 개입 전까지 막혔다. 반대로 버그를 무작정 전파하면, 요청 전송 후 응답 해석 중 버그 발생 시 `markUnknown`이 누락돼 Payment에 UNKNOWN 흔적이 남지 않고, `existsUnknownByOrderId` 차단을 우회해 재결제 → PG가 이미 승인했다면 이중결제가 발생한다.

결제에서 **이중결제(돈 이중 출금) > brick(결제 차단)**. *요청 전송 시점*은 두 요구(버그 가시화 / 이중결제 방어)를 동시에 만족시키는 유일한 경계다. 전송 전 버그는 PG 부작용이 0이라 전파해도 안전하고, 전송 후 버그는 PG 처리 가능성 때문에 보존해야 한다. 예외 분류의 결정축은 errorCode 종류가 아니라 *재시도 안전성(PG 처리 가능성)*이며, 세분화해도 결국 FAILED(처리 안 함 확실)/UNKNOWN(처리 가능) 두 갈래로 수렴한다.

## Decision

- PG 호출 경로의 예외를 *PG에 요청이 전송됐는가*를 경계로 분기한다.
  - **전송 전**(요청 빌드 등) 예외: PG 부작용이 없으므로 잡지 않고 전파해 안전망(500)으로 가시화한다.
  - **전송 후 / 전송 여부 불명** 예외: PG가 이미 처리했을 수 있으므로 `UNKNOWN`(승인 결과) / `INVALID_RESPONSE`(client)로 보존해 재시도를 차단한다(이중결제 방어).
- 알려진 외부 응답 이상(`ResponseEntity` 본문 null, `NaverPayResponse.body`/`detail` null, `JsonProcessingException`, `RestClientException` 통신 계열)은 도메인 결과로 보존한다. 우리 객체를 다루는 영역(`NaverPayGatewayImpl` 응답 분기)은 명시적 null 체크로 처리하고 그 외 예상 못 한 버그는 전파하며, 외부 JSON 파싱 영역(`NaverPayClient.post` 해석 단계)은 예외를 보존한다.
- PG가 `Success`(승인 확정)로 응답했는데 응답 본문(`detail`)이 비어 있으면 `FAILED`가 아니라 `UNKNOWN`으로 보존한다.

**적용 범위**: 본 결정은 approve **직접 승인 경로**에 적용된다. PG가 `AlreadyComplete`로 응답한 뒤 `getApprovalHistory`로 결과를 재확인하는 경로(history 조회 실패 시 UNKNOWN 보존)와 `cancel` 경로의 동일 일관화는 후속(#219)으로 분리한다.

## Consequences

전송 후 발생하는 *예상 못 한* 버그는 `UNKNOWN`/`INVALID_RESPONSE`로 보존되어 즉시 500 가시화를 일부 양보한다. 단 원본 예외를 `cause`로 보존해 로그(stack trace)와 `INVALID_RESPONSE` 모니터링으로 추적 가능하다. 알려진 외부 이상을 모두 명시 처리하므로 이 회색지대에 남는 케이스는 극히 드물다. `postForEntity` 진입 후 발생한 예외는 실제 소켓 write 여부를 코드로 구분할 수 없어 *전송 여부 불명*으로 보고 보존한다(전송 전 빌드 단계만 `try` 밖으로 빼 전파). 인프라 예외를 common 베이스로 통합하는 후속(#198)과 *PG 부작용 축의 일급 모델링*은 본 결정 범위 밖이다.

*적용 범위*에서 미뤄둔 `AlreadyComplete` history 재확인 경로와 `cancel` 경로의 동일 일관화는 후속 결정(→ PR#220, #219)에서 해소한다.

연계: UNKNOWN 마킹/차단 정책을 정한 결제 도메인 재설계 결정(→ PR#205) — 본 결정이 그 마킹 *경계*를 정교화한다. PG 호출 트랜잭션 경계 결정(→ PR#97), `docs/exception-strategy.md` "결제 결과 UNKNOWN 처리".
