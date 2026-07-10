# 인증 토큰 Redis 저장 실패는 strict 정책으로 즉시 실패시킨다

- Status: accepted
- Date: 2026-05-15

## Context

- **배경**: Redis 장애 시 soft fail(로깅만, access token은 발급)을 선택하면 클라이언트에 refresh token을 발급했으나 Redis에 없는 상태가 된다. 사용자는 access token 만료 시 재발급을 시도하다가 예상치 못한 "token not found" 에러를 받게 된다. 이는 "동작하는 것처럼 보이지만 실제로는 망가진" 상태로, 더 나쁜 사용자 경험을 유발한다.
- **이유**: refresh token은 Redis가 저장소 자체다. Redis 없이 발급된 refresh token은 반드시 실패한다. 명확한 즉각 실패가 지연된 묵시적 실패보다 사용자 경험이 낫다. 기존 로그인 사용자(유효한 access token 보유)는 Redis 장애에 영향받지 않는다. Redis 장애는 인프라 레벨(HA)에서 해결해야 할 문제다.

## Decision

Redis 저장/조회 실패 시 `AuthException(INTERNAL_ERROR)`을 던진다. Redis 장애 시 신규 로그인/회원가입이 일시적으로 불가하다.

## Consequences

Redis 장애 시 신규 로그인/회원가입이 일시적으로 불가하다. 기존 세션(유효한 access token)은 영향받지 않는다. 향후 과제: Redis 단일 장애점 해소를 위해 Sentinel 또는 Cluster 구성 필요.
