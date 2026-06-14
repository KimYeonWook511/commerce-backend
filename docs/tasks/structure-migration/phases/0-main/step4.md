# Step 4: move-controllers-to-http

## 읽어야 할 파일

- `/docs/tasks/structure-migration/prd.md`
- `/docs/tasks/structure-migration/architecture.md`
- `/docs/package-structure-guide.md` (4장 presentation — http 서브패키지)

## 작업

전 도메인의 Controller와 그 `request/` DTO를 `presentation/http/`로 **순수 이동**한다. `git mv` + package/import 갱신(main·test). 내용 불변.

이동 규칙: `com.commerce.<domain>.presentation.<Controller>` → `com.commerce.<domain>.presentation.http.<Controller>`, `com.commerce.<domain>.presentation.request.*` → `com.commerce.<domain>.presentation.http.request.*`.

이동 대상:

- **auth**: `AuthController` + `request/`(`AuthLoginRequest`, `AuthSignUpRequest`, `AuthTokenReissueRequest`)
- **cart**: `CartController` + `request/`(`CartItemAddRequest`, `CartItemUpdateRequest`)
- **order**: `OrderController` + `request/`(`OrderCreateItemRequest`, `OrderCreateRequest`)
- **payment**: `ReservePaymentController` + `request/`(`ReservePaymentRequest`)
- **payment/naverpay**: `NaverPayController` + `request/`(`NaverPayApproveRequest`) → `com.commerce.payment.naverpay.presentation.http`
- **product**: `AdminProductController`, `ProductController` + `request/`(`AdminProductCreateRequest`, `AdminProductUpdateRequest`)
- **stock**: `AdminStockController` + `request/`(`AdminStockAdjustRequest`, `AdminStockCreateRequest`, `StockRequestValidation`)

주의:
- `request/` 하위 DTO도 함께 `http/request/`로 옮긴다. Controller만 옮기고 request를 두면 import가 어긋난다.
- `@WebMvcTest`/slice 테스트가 Controller FQN을 참조하므로 test import를 빠짐없이 갱신한다.
- `AuthExceptionHandler`(@RestControllerAdvice)는 이 step에서 다루지 않는다(현재 `auth/exception/`에 있고 Step 5에서 `presentation/`으로 옮긴다).

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
```

- 컨트롤러 slice 테스트(`test`)와 E2E/통합(`integrationTest`)로 라우팅·바인딩이 그대로인지 확인한다.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - 모든 Controller가 `presentation/http/`, request DTO가 `presentation/http/request/`에 있는가?
   - test의 Controller·request import가 갱신됐는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 클래스 이름·엔드포인트 매핑·DTO 필드를 바꾸지 마라. 이유: 순수 이동 PR, API 계약 불변.
- `AuthExceptionHandler`를 옮기지 마라. 이유: Step 5(exception 재배치) 범위다.
- 기존 테스트를 깨뜨리지 마라.
