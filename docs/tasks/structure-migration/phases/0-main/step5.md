# Step 5: relocate-exceptions

## 읽어야 할 파일

- `/docs/tasks/structure-migration/prd.md`
- `/docs/tasks/structure-migration/architecture.md`
- `/docs/package-structure-guide.md` (5.1 예외 — 도메인 예외 vs 인프라 기술 예외 위치 기준)
- `/docs/exception-strategy.md` (예외 분류·처리 정책)
- `/src/main/java/com/commerce/auth/exception/AuthExceptionHandler.java` (advice 이동 대상)

## 작업

각 도메인의 `<domain>/exception/` 폴더 내용을 성격에 따라 올바른 레이어로 **순수 이동**한다. `git mv` + package/import 갱신(main·test). 내용 불변.

### 1. 도메인 예외 → `<domain>/domain/exception/`

`XxxException`, `XxxErrorCode` 쌍을 도메인 레이어로 내린다.

- `cart`: `CartException`, `CartErrorCode`
- `member`: `MemberException`, `MemberErrorCode`
- `order`: `OrderException`, `OrderErrorCode`
- `payment`: `PaymentException`, `PaymentErrorCode`
- `product`: `ProductException`, `ProductErrorCode`
- `stock`: `StockException`, `StockErrorCode`
- `auth`: `AuthException`, `AuthErrorCode` → `com.commerce.auth.domain.exception` (auth는 domain 패키지가 없으므로 신설)
- `payment/naverpay`: `NaverPayException`, `NaverPayErrorCode` → `com.commerce.payment.naverpay.domain.exception` (naverpay는 domain 패키지가 없으므로 신설)

### 2. 인프라 기술 예외 → `<domain>/infrastructure/`

"외부 store가 죽었다" 류 기술 신호.

- `auth`: `RefreshTokenStoreUnavailableException` (`com.commerce.auth.exception` → `com.commerce.auth.infrastructure`)
- `order`: `OrderIdempotencyStoreUnavailableException` (`com.commerce.order.exception` → `com.commerce.order.infrastructure`)

### 3. 도메인 advice → `<domain>/presentation/`

- `auth`: `AuthExceptionHandler`(@RestControllerAdvice) (`com.commerce.auth.exception` → `com.commerce.auth.presentation`)

주의:
- `common/exception/`(`GlobalExceptionHandler`, `CommonException`, `CustomException`, `ErrorCode`, `CommonErrorCode`)는 **건드리지 않는다**.
- 도메인 엔티티/서비스가 `XxxException`/`XxxErrorCode`를 참조하므로, 옮긴 뒤 도메인·application·adapter·test의 import를 모두 갱신한다.
- 이동 후 빈 `<domain>/exception/` 디렉터리는 자동 정리된다.

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
```

- 예외→HTTP 매핑(`AuthExceptionHandler`, `GlobalExceptionHandler`)이 그대로 동작하는지 통합 테스트로 확인한다.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - 도메인 예외가 `domain/exception/`, 인프라 예외가 `infrastructure/`, advice가 `presentation/`에 있는가?
   - `domainDoesNotDependOnOuterLayers` 규칙이 깨지지 않는가?(도메인 예외는 자기완결적이라 outer 의존 없음)
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 예외 클래스/에러코드 값·이름을 바꾸지 마라. 이유: 순수 이동 PR, 동작·계약 불변.
- `common/exception/`를 옮기지 마라. 이유: 공통 레이어로 마이그레이션 대상이 아니다.
- 도메인 예외를 infrastructure로, 인프라 예외를 domain으로 잘못 분류하지 마라. 이유: 5.1 기준상 "변환 결과물(도메인 예외)은 domain 소유, 변환 전 기술 신호는 infra 소유"다.
- 기존 테스트를 깨뜨리지 마라.
