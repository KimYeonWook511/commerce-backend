# Step 2: signup-transaction-split

## 읽어야 할 파일

- `docs/features/auth-redis-timing/prd.md`
- `docs/features/auth-redis-timing/architecture.md`
- `docs/features/auth-redis-timing/adr.md`
- `docs/commit-conventions.md`
- `docs/testing-conventions.md`
- `src/main/java/com/commerce/auth/application/AuthSignUpService.java`
- `src/main/java/com/commerce/member/application/MemberRegistrationService.java`
- `src/test/java/com/commerce/auth/application/AuthSignUpServiceTest.java`
- `src/test/java/com/commerce/order/application/OrderCreateServiceIdempotencyTest.java` (통합 테스트 패턴 참고)
- `src/test/java/com/commerce/support/TestcontainersSupport.java`
- `src/test/java/com/commerce/support/PersistenceCleanupTestSupport.java`
- `src/test/java/com/commerce/member/infrastructure/persistence/support/MemberPersistenceTestSupport.java`

## 작업

**`AuthSignUpService`** — method-level `@Transactional` 교체:
- `@Transactional` → `@Transactional(propagation = Propagation.NOT_SUPPORTED)` 로 교체
- class-level `@Transactional(readOnly = true)`는 유지
- `import org.springframework.transaction.annotation.Propagation` 추가

`NOT_SUPPORTED` 적용 후 흐름:
- `signUp()`은 트랜잭션 없이 실행
- `register()`는 외부 트랜잭션이 없으므로 자체 `@Transactional`로 새 트랜잭션 시작 → commit → 반환
- `issue()` 호출 시점 = DB commit 완료 이후

**`AuthSignUpServiceIntegrationTest`** — 통합 테스트 신규 작성:
- `@SpringBootTest`, `@Tag("docker")`, `@ActiveProfiles("test")`
- `@Import({PersistenceCleanupTestSupport.class, MemberPersistenceTestSupport.class})`
- `@DynamicPropertySource`: `TestcontainersSupport.registerMySql()` + `TestcontainersSupport.registerRedis()` 등록
- `@Autowired`: `AuthSignUpService`, `RefreshTokenStore`, `PersistenceCleanupTestSupport`, `MemberPersistenceTestSupport`
- `@AfterEach tearDown()`: `persistenceCleanup.deleteAllInBatch(memberPersistence)` — Redis는 별도 삭제 없음

Redis cleanup을 별도로 하지 않는 이유: 각 테스트가 `memberPersistence.save()`로 새 member를 생성하므로 각자 고유한 memberId → 고유한 Redis 키(`refresh:{memberId}`)를 갖는다. TTL 만료로 자연 정리되며 테스트 격리는 unique 키로 보장한다. 기존 `OrderCreateServiceIdempotencyTest` / `OrderApplicationServiceIntegrationTest`와 동일한 방식.

검증 케이스 (결과 기반 검증, 순서 직접 검증 없음):
1. 회원가입 성공 후 `refreshTokenStore.get(memberId)` 값이 존재한다
2. 회원가입 실패(중복 이메일) 시 `MemberException`이 던져진다 — `issue()` 호출 전 실패이므로 예외 자체가 Redis 미저장의 증거

`AuthSignUpServiceTest`(단위) 변경 없음 — `NOT_SUPPORTED`는 트랜잭션 경계 변화이며 단위 테스트 흐름 검증에 영향을 주지 않는다.

## 수정 가능 경로

- `src/main/java/com/commerce/auth/application/AuthSignUpService.java`
- `src/test/java/com/commerce/auth/application/AuthSignUpServiceIntegrationTest.java`
- `docs/features/auth-redis-timing/**`

## Acceptance Criteria

```bash
# 단위 테스트
./gradlew test

# 통합 테스트 (Docker 필요)
./gradlew test -Ptags="docker"
```

## 검증 절차

1. 단위 테스트 통과 확인
2. Docker 환경에서 통합 테스트 통과 확인
3. `AuthSignUpService.signUp()`에 `NOT_SUPPORTED`가 적용됐는지 확인

## 금지사항

- class-level `@Transactional(readOnly = true)`를 제거하지 마라. 이유: 다른 메서드 추가 시 기본값으로 필요하다.
- 통합 테스트에서 `@Transactional`을 사용하지 마라. 이유: commit이 발생하지 않으면 Redis 저장 검증이 불가하다.
- 기존 `AuthSignUpServiceTest` 단위 테스트를 수정하지 마라.

## 커밋 메시지

```
fix: 회원가입 트랜잭션을 RDB commit 이후 Redis 저장으로 분리한다
```
