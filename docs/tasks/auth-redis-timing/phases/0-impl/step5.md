# Step 6: sync-root-docs

## 읽어야 할 파일

- `docs/features/auth-redis-timing/adr.md`
- `docs/commit-conventions.md`
- `docs/adr.md`

## 작업

이번 기능에서 내린 설계 결정을 루트 ADR에 반영한다.

**`docs/adr.md`** — 기존 마지막 항목 다음에 아래 세 결정을 추가한다:
- ADR-1: 인증 토큰 Redis 저장 실패 정책 — strict
- ADR-2: 회원가입 트랜잭션 분리 — `Propagation.NOT_SUPPORTED`
- ADR-3: `RefreshTokenStore.delete()` 제거

각 결정의 배경, 결정 내용, 근거, 결과는 `docs/features/auth-redis-timing/adr.md`를 기준으로 작성한다.

루트 `docs/api-spec.md`, `docs/architecture.md`, `docs/db-schema.md`는 이번 변경으로 인한 구조 변경이 없으므로 수정하지 않는다.

## 수정 가능 경로

- `docs/adr.md`
- `docs/features/auth-redis-timing/**`

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 전체 테스트 통과 확인
2. `docs/adr.md`에 ADR-1, ADR-2, ADR-3 세 결정이 반영됐는지 확인

## 금지사항

- 기존 ADR 항목을 수정하지 마라. 이유: 기존 결정은 역사 기록이다.
- `docs/api-spec.md`, `docs/architecture.md`, `docs/db-schema.md`를 수정하지 마라. 이유: 이번 변경으로 인한 구조 변경이 없다.

## 커밋 메시지

```
docs: auth-redis-timing 설계 결정을 루트 ADR에 반영한다
```
