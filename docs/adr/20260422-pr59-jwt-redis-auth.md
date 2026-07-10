# 인증은 JWT + Redis 기반으로 유지한다

- Status: accepted
- Date: 2026-04-22

## Context

토큰 재발급 시 서버 검증과 강제 무효화가 가능하다.

## Decision

Access Token은 JWT로 처리하고 Refresh Token은 Redis에 저장한다.

## Consequences

완전한 stateless 인증보다 저장소 관리 비용이 늘어난다.
