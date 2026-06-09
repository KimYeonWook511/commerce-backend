# Step 1: reservation-version-guard

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/approval-concurrency-guard/prd.md`
- `/docs/tasks/approval-concurrency-guard/architecture.md`
- `/docs/tasks/approval-concurrency-guard/adr.md`
- `/docs/tasks/approval-concurrency-guard/api-spec.md`
- `/docs/tasks/approval-concurrency-guard/db-schema.md`

작업 대상 코드와 테스트:

- `src/main/java/com/commerce/payment/domain/PaymentReservation.java`
- `src/main/java/com/commerce/payment/application/PaymentApprovalRecordService.java`
- `src/main/java/com/commerce/payment/naverpay/application/NaverPayApprovalService.java`
- `src/main/java/com/commerce/payment/domain/repository/PaymentReservationRepository.java`
- `src/main/java/com/commerce/payment/infrastructure/PaymentReservationRepositoryAdapter.java`
- `src/main/java/com/commerce/payment/domain/repository/PaymentRepository.java` (saveApproved 전용 저장 경로 패턴 참고)
- `src/main/java/com/commerce/payment/infrastructure/PaymentRepositoryAdapter.java` (saveAndFlush 충돌 → 도메인 예외 변환 패턴 참고)
- `src/main/java/com/commerce/payment/exception/PaymentErrorCode.java`
- `src/main/resources/db/migration/V6__redesign_payment_to_reservation_and_attempt.sql`
- `src/test/java/com/commerce/payment/domain/PaymentReservationTest.java`
- `src/test/java/com/commerce/payment/application/concurrency/PaymentApprovalRecordServiceConcurrencyTest.java`
- `src/test/java/com/commerce/payment/naverpay/application/concurrency/NaverPayServiceConcurrencyTest.java`

Task 문서만으로 부족한 공통 맥락이 있으면 루트 `/docs/adr.md`(ADR-3, ADR-8, ADR-9), `/docs/testing-conventions.md`를 추가로 읽는다.

## 작업

같은 예약(merchantPayKey)에 **다른 pgPaymentId** 승인 2건이 동시에 들어올 때 둘 다 `use()`를 통과해 REQUESTED payment 2건이 생기고 PG 청구가 2번 나가는 구멍을 낙관적 락으로 막는다. 설계 근거는 ADR-L1이다.

1. **Flyway 마이그레이션**: `src/main/resources/db/migration/V7__add_payment_reservation_version.sql`
   - `tbl_payment_reservation`에 `version BIGINT NOT NULL DEFAULT 0` 컬럼을 추가한다.

2. **도메인 `@Version` 추가**: `PaymentReservation`
   - 낙관적 락용 `version` 필드를 추가하고 `@Version`을 매핑한다(타입·접근자는 프로젝트 엔티티 컨벤션을 따른다).
   - 기존 `use()`, `expire()`, `isReusableFor()` 등 도메인 메서드의 시그니처와 동작은 **그대로 둔다**. `use()`는 지금처럼 `status`와 `reservedKey`를 함께 set한다.

3. **충돌 변환 — adapter 전용 저장 경로**: `PaymentReservationRepository` / `PaymentReservationRepositoryAdapter`
   - 예약 소비(use) 전용 저장 경로를 추가한다(기존 `PaymentRepository.saveApproved`와 대칭. 이름 `saveUsed` 권장).
   - adapter에서 `saveAndFlush`로 저장하고, 같은 예약을 동시에 소비한 진 쪽이 만나는 `org.springframework.orm.ObjectOptimisticLockingFailureException`을 차단용 `PaymentException`으로 변환해 던진다.
   - `saveAndFlush`의 조기 flush가 낙관적 락 충돌을 **이 메서드 호출 안에서** 확정하는 것이 load-bearing이다. 기존 `PaymentRepositoryAdapter.saveApproved`가 `uk_payment_approved_order_key` 위반을 같은 방식으로 확정·변환하는 패턴과 동일하다. DB 제약/락 위반 → 도메인 예외 매핑은 adapter 책임이라는 기존 컨벤션을 따른다.
   - 신규 에러코드 `PaymentErrorCode.PAYMENT_RESERVATION_ALREADY_USED`를 추가한다(HTTP 상태·코드 문자열·메시지는 기존 `PaymentErrorCode` 컨벤션을 따른다. 의미: "이미 다른 승인이 예약을 소비함").

4. **호출 경로 연결**: `PaymentApprovalRecordService.create()`
   - find-first miss 시 `reservation.use()` 후 기존 `paymentReservationRepository.save(reservation)`를 위 전용 저장 경로(`saveUsed`)로 교체한다.
   - `create()`의 호출 위치(`NaverPayApprovalService`에서 PG `approve` 호출보다 앞)와 `@Transactional` 경계는 그대로 둔다. 진 쪽이 PG 호출 전에 차단되는 것이 이 step의 핵심 요건이며, 짧은 `create()` 트랜잭션이 PG 호출 전에 끝나므로 자연히 충족된다.

5. **동시성 테스트**: 같은 reservation에 다른 pgPaymentId 승인 2건을 동시에 실행했을 때, 한쪽만 payment가 생성되고 PG approve가 호출되며, 나머지는 PG 호출 전에 차단(`PAYMENT_RESERVATION_ALREADY_USED`)됨을 검증한다. 진 쪽이 reservation·payment 어느 행도 남기지 않음(트랜잭션 롤백)을 함께 확인한다. 기존 동시성 테스트 클래스(`PaymentApprovalRecordServiceConcurrencyTest` 또는 `NaverPayServiceConcurrencyTest`)의 패턴과 태그(`@Tag("concurrency")`, Testcontainers)를 따른다.

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
./gradlew concurrencyTest
```

`concurrencyTest`를 포함하는 이유: 이 step의 핵심 검증이 동시 이중 use 차단이며 해당 테스트는 `concurrency` 태그로 분리 실행되기 때문이다.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - 도메인 `use()`의 "두 필드 동시 set" 캡슐화가 유지되는가(ADR-3)?
   - 진 쪽 차단이 PG approve 호출보다 앞에서 일어나는가?
   - `PaymentReservationTest`의 기존 단언이 깨지지 않는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 낙관적 락을 CAS(`UPDATE ... WHERE status='RESERVED'`)로 바꾸지 마라. 이유: ADR-L1에서 도메인 표현력·캡슐화 보존을 근거로 `@Version`을 채택했다.
- `use()`에서 `reservedKey = null` set을 빼거나 `status`/`reservedKey`를 분리하지 마라. 이유: NULL trick 캡슐화(ADR-3)가 깨지면 정합성이 무너진다.
- 전용 저장 경로(`saveUsed`)의 catch 블록에서 같은 트랜잭션에 추가 DB 쓰기를 하지 마라. 이유: 낙관적 락 충돌은 `create()` 트랜잭션을 rollback-only로 만들어 이후 쓰기가 `UnexpectedRollbackException`으로 실패한다. catch 후에는 차단 예외만 던진다.
- `create()`의 `save` 호출을 일반 `save`(지연 flush)로 되돌리지 마라. 이유: 충돌이 `create()` 트랜잭션 안에서 확정되려면 `saveAndFlush`의 조기 flush가 필요하다. 지연 flush면 충돌이 커밋 시점(프록시 경계)에 나서 변환되지 못한 채 전파된다.
- 최종 보루(`uk_payment_approved_order_key`, `succeedApproval`) 로직을 건드리지 마라. 이유: #230 정합성 보장은 이 Task 범위 밖이다.
- 기존 테스트를 깨뜨리지 마라.
