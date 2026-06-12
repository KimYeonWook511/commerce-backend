# Step 1: add-payment-version

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 아키텍처와 설계 의도를 파악하라:

- `/docs/tasks/payment-optimistic-lock/prd.md`
- `/docs/tasks/payment-optimistic-lock/adr.md`
- `/docs/tasks/payment-optimistic-lock/db-schema.md`
- `/src/main/java/com/commerce/payment/domain/Payment.java`
- `/src/main/java/com/commerce/payment/domain/PaymentReservation.java` (직전 선례 — `@Version` 선언 형태)
- `/src/main/java/com/commerce/order/domain/Order.java` (`@Version` 선언 형태 참고)
- `/src/main/resources/db/migration/V7__add_payment_reservation_version.sql` (version 컬럼 추가 마이그레이션 선례)
- `/src/main/resources/db/migration/V8__add_payment_escalated_at.sql` (직전 마이그레이션 — 다음 번호 V9 확인)

Task 문서만으로 부족한 공통 맥락이 있으면 아래를 추가로 읽는다.

- `/docs/db-schema.md` (`tbl_payment` 현재 스키마)

이전 step에서 만들어진 코드와 task 문서를 꼼꼼히 읽고, 설계 의도를 이해한 뒤 작업하라.

## 작업

`Payment` 엔티티에 낙관 락(`@Version`)을 추가한다. 이 step은 **필드·컬럼 추가까지만** 한다(충돌 흡수/전파 처리는 Step 2, escalation 전환은 Step 3).

### 1. Flyway 마이그레이션

- 파일: `src/main/resources/db/migration/V9__add_payment_version.sql`
- 내용: `ALTER TABLE tbl_payment ADD COLUMN version BIGINT NOT NULL DEFAULT 0;`
- `NOT NULL DEFAULT 0`이라 기존 행 백필 불필요. `V7__add_payment_reservation_version.sql`과 같은 형태를 따른다.

### 2. `Payment` 도메인 — `@Version` 필드

- `Payment`에 `@Version` 낙관 락 필드를 추가한다. 선언 형태는 `PaymentReservation`/`Order`의 version 필드와 동일하게 맞춘다(`Long version`, JPA가 관리).
- 빌더/정적 팩토리(`createRequested`/`createCancelRequested` 등)에 `version`을 넣지 않는다(JPA가 INSERT 시 0으로 채우고 이후 자동 증가). 기존 생성 시그니처를 바꾸지 않는다.

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
```

- `integrationTest`(Testcontainers)는 V9 마이그레이션 적용 + JPA 매핑 정합을 검증한다(entity에 `@Version` 추가 → 스키마-매핑 일치 확인).

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `Payment.version`이 `PaymentReservation`/`Order`와 동일한 형태로 선언됐는가?
   - 생성 팩토리/빌더 시그니처가 바뀌지 않았는가? (version은 JPA 관리)
   - V9 마이그레이션이 V8 다음 번호이고 `NOT NULL DEFAULT 0`인가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 충돌 흡수/전파(`OptimisticLockException` 처리)를 이 step에서 건드리지 마라. 이유: 그건 Step 2의 범위다. 이 step은 필드·컬럼만 추가한다.
- escalation 관련 코드(`escalateIfPending` 등)를 이 step에서 손대지 마라. 이유: Step 3 범위다.
- `version`을 생성 팩토리/빌더 파라미터로 노출하지 마라. 이유: JPA가 자동 관리하는 값이라 도메인 생성 계약을 오염시킨다.
- 기존 테스트를 깨뜨리지 마라.
