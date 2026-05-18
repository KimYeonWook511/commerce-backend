# 태스크 아키텍처

## 개요

DB 무결성 예외 처리 정책을 3계층(Application / GlobalExceptionHandler / Spring 안전망)으로 명확히 분리한다.
코드 변경은 catch 타입 교체와 GlobalExceptionHandler 응답 코드 수정에 집중되며, 새 클래스나 레이어 도입은 없다.

## 정책

### 3계층 책임 분리

| 위반 종류 | Spring 예외 타입 | Application 처리 | 최종 응답 |
|---|---|---|---|
| **Unique** | `DuplicateKeyException` | 좁게 catch → 도메인 의미에 맞게 처리 | 도메인 4xx 또는 정상 흐름 |
| **NOT NULL / FK / CHECK** | `DataIntegrityViolationException` (unique 제외) | **catch 안 함** → 그대로 전파 | 안전망 **500** + ERROR 로그 |

### Unique 위반의 두 종류

| 종류 | 예시 | 대응 |
|---|---|---|
| **비즈니스 unique** | email, idempotency_key, merchantPayKey, eventId | catch → 도메인 의미에 맞게 처리 |
| **기술적 unique** (시스템 생성 ID) | orderNumber(ULID) | catch 안 함 → 안전망 (충돌 = 코드 버그) |

### Unique 종류를 코드에서 분리하는 방법

Spring의 `DuplicateKeyException`은 어느 unique 제약을 위반했는지 표준 메서드를 제공하지 않는다. 다음 세 가지 케이스로 자연스럽게 해결한다.

**케이스 1 — unique 하나뿐**: 분기 불필요. catch되면 그 unique 의미로 확정.
- `Member.email`, `PaymentAttempt(paymentId, type)`, `ProcessedEvent(eventId, consumerType)`

**케이스 2 — unique 여러 개지만 의미 통일**: 어느 unique가 터졌든 도메인 응답이 같음.
- `Payment`의 `merchantPayKey` / `order_id` / `pgPaymentId` 모두 `PAYMENT_DUPLICATE`로 통일

**케이스 3 — unique 여러 개고 의미가 다름**: fallback 재조회 시도 결과로 분리.
- `Order`의 `(member_id, idempotency_key)`(비즈니스) vs `orderNumber`(기술적 ULID)
- 비즈니스 키로 재조회 성공 → 멱등 흡수
- 비즈니스 키로 재조회 실패 → 다른 unique 위반 또는 데이터 소멸 = 코드 버그 → rethrow → 안전망 500

### GlobalExceptionHandler 역할

- `DataIntegrityViolationException` 핸들러는 **안전망**으로만 존재. 정상 흐름에선 도달하지 않음.
- 도달했다면 application catch 누락 = 코드 버그.
- 응답: **500**, 로그: ERROR + stack trace 포함.
- `DuplicateKeyException` 전용 핸들러는 **신설하지 않음** (unique도 application에서 다 처리).
- `OptimisticLockingFailureException` 핸들러는 **변경 없음** (낙관적 락 = 정상 시나리오 → 409 유지).

### Unique 처리 모드 (5곳 분류)

| 위치 | 모드 | 처리 |
|---|---|---|
| `MemberRegistrationService.java:32` | A (도메인 예외 변환) | `MemberException(DUPLICATE_EMAIL)` throw |
| `PaymentApprovalService.java:67` | A (도메인 예외 변환) | `PaymentException(PAYMENT_DUPLICATE)` throw |
| `PaymentAttemptService.java:42, 73` | B (멱등 흡수) | 기존 attempt 재조회 후 반환 |
| `OrderCreateService.java:64` | B (멱등 흡수) | 멱등키로 재조회 후 반환. 실패 시 rethrow. |
| `StockRestoreOutboxConsumeService.java:46` | B (멱등 흡수) | `return false` (silent skip) |

## 변경 대상

| 파일 | 변경 내용 |
|---|---|
| `PaymentAttemptService.java:42, 73` | catch 타입 교체 |
| `OrderCreateService.java:64, 72-75` | catch 타입 교체 + rethrow |
| `PaymentApprovalService.java:67` | catch 타입 교체 |
| `MemberRegistrationService.java:32` | catch 타입 교체 |
| `StockRestoreOutboxConsumeService.java:46` | catch 타입 교체 |
| `GlobalExceptionHandler.java:71-78` | 500 재정의 + stack trace 로그 |
| `CommonErrorCode.java:8` | 상태 코드 500, 코드 `COMMON-500-1` |

## 예외 및 실패 처리

- NOT NULL/FK/CHECK 위반은 application에서 catch하지 않는다. GlobalExceptionHandler 안전망 → 500 응답.
- unique 위반 fallback 재조회 실패(OrderCreateService) → 원래 예외 rethrow → 안전망 500.

## 테스트 포인트

1. `DuplicateKeyException` mock으로 기존 fallback 경로 검증 (각 단위 테스트)
2. OrderCreateService fallback 재조회 실패 시 `DuplicateKeyException`이 그대로 던져지는지 검증
3. Repository 슬라이스 테스트: unique 위반이 `DuplicateKeyException` 타입인지 검증
4. Testcontainers 통합 테스트: 실제 MySQL에서 unique 위반 → `DuplicateKeyException` 변환 회귀 방어
