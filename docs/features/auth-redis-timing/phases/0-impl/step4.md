# Step 4: remove-delete

## 읽어야 할 파일

- `docs/features/auth-redis-timing/adr.md`
- `docs/commit-conventions.md`
- `src/main/java/com/commerce/auth/application/port/RefreshTokenStore.java`
- `src/main/java/com/commerce/auth/infrastructure/RedisRefreshTokenStore.java`

## 작업

현재 로그아웃 서비스가 구현되어 있지 않아 `delete(Long memberId)`는 어디서도 호출되지 않는다.
사용되지 않는 인터페이스 메서드를 제거한다.

제거 전 호출부 없음 확인:
```bash
grep -rn "refreshTokenStore\.delete" src/main/java/
```

**`RefreshTokenStore`** (port): `void delete(Long memberId)` 선언 제거
**`RedisRefreshTokenStore`** (infrastructure): `delete()` 구현 제거

호출부가 없으므로 다른 파일 변경 없음.

## 수정 가능 경로

- `src/main/java/com/commerce/auth/application/port/RefreshTokenStore.java`
- `src/main/java/com/commerce/auth/infrastructure/RedisRefreshTokenStore.java`
- `docs/features/auth-redis-timing/**`

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 전체 테스트 통과 확인
2. `grep -rn "refreshTokenStore\.delete" src/main/java/` 로 호출부 없음 확인

## 금지사항

- `delete()`를 주석 처리하지 마라. 이유: 주석 처리된 코드는 혼란을 유발한다. 완전 제거가 원칙이다.
- grep 확인 없이 제거하지 마라.
- 기존 테스트를 깨뜨리지 마라.

## 커밋 메시지

```
refactor: 미사용 RefreshTokenStore.delete()를 제거한다
```
