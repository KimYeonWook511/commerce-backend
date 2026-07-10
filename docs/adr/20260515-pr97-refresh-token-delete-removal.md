# 사용처 없는 `RefreshTokenStore.delete()`를 제거한다

- Status: accepted
- Date: 2026-05-15

## Context

- **배경**: `RefreshTokenStore` 인터페이스에 `delete(Long memberId)`가 정의되어 있으나, 현재 로그아웃 서비스가 구현되어 있지 않아 어디서도 호출되지 않는다. 사용되지 않는 인터페이스 메서드는 CLAUDE.md 원칙("불필요한 추상화와 과한 설계를 피한다")에 어긋난다.
- **이유**: 호출부가 없는 코드를 유지하는 것은 잠재적 혼란을 유발한다. Git 히스토리가 이 메서드의 존재와 제거 이유를 기록한다. 로그아웃 구현 시 그 PR에서 `delete()`를 재추가하고 Redis 실패 정책을 함께 설계하는 것이 더 안전하다.

## Decision

`RefreshTokenStore` 인터페이스와 `RedisRefreshTokenStore` 구현체에서 `delete()` 제거.

## Consequences

인터페이스가 실제 사용 범위로 좁혀진다. 향후 과제: 로그아웃 기능 구현 시 `delete()` 재추가 및 Redis 실패 정책 결정 필요. 로그아웃은 보안 목적이므로 strict / soft 정책 선택이 신중히 검토되어야 한다.
