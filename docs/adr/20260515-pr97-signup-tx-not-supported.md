# 회원가입 트랜잭션은 `Propagation.NOT_SUPPORTED`로 분리한다

- Status: accepted
- Date: 2026-05-15

## Context

- **배경**: `signUp()`이 `@Transactional`로 외부 트랜잭션을 열면 `MemberRegistrationService.register()`가 `REQUIRED` 전파로 합류한다. Spring에서 commit은 트랜잭션을 시작한 메서드가 종료될 때 발생하므로 `register()` 반환 후에도 DB는 미커밋 상태다. 그 사이에 `issue()`가 Redis에 저장하면 DB commit 전 Redis 저장 불일치가 발생한다. 단순히 method-level `@Transactional`을 제거하면 class-level `@Transactional(readOnly = true)`가 적용되어 readOnly 트랜잭션에 합류하며 Hibernate가 flush mode를 MANUAL로 설정하므로 의도와 다르게 동작한다. `@TransactionalEventListener(AFTER_COMMIT)` 방식은 응답 반환 후 이벤트가 실행되므로 Redis 저장 실패를 클라이언트에 전달할 수 없어 인증 토큰 Redis 저장 실패를 strict하게 즉시 실패시키는 기존 결정(→ PR#97)과 양립 불가하다.
- **이유**: `NOT_SUPPORTED`는 class-level `readOnly = true`를 명시적으로 override한다. `signUp()`이 트랜잭션 없이 실행되면 `register()`가 자체 트랜잭션으로 commit 후 반환한다. 이후 `issue()` 호출 = DB commit 이후 Redis 저장 보장. 기존 `OrderCreateService.createOrder()`가 동일 패턴을 사용한다 (ADR 일관성).

## Decision

`AuthSignUpService.signUp()` method-level annotation을 `@Transactional(propagation = Propagation.NOT_SUPPORTED)`로 교체한다.

## Consequences

DB commit 이후 Redis 저장 순서가 보장된다. Redis 저장 실패 시 strict 예외 처리와 결합하면 부분 실패 시나리오가 명확해진다 (member는 DB에 생성됐으나 auth 실패 → 다음 요청에서 DUPLICATE_EMAIL 또는 로그인 성공).
