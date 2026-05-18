# 기능 API 스펙

## 변경 없음

이번 변경은 내부 멱등성 보장 구조를 개선하는 것으로 API 스펙 변경은 없다.

기존 주문 생성 API(`POST /orders`)의 요청/응답 형식은 그대로 유지된다.
`Idempotency-Key` 헤더 요구사항도 변경 없다.

자세한 API 스펙은 루트 `docs/api-spec.md`를 참고한다.
