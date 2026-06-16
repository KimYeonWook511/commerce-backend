# Step 4: rename-order-domain

## 읽어야 할 파일

먼저 아래 파일들을 읽고 작업 맥락을 파악하라:

- `docs/tasks/application-layer-rename/prd.md`
- `src/main/java/com/commerce/order/application/service/OrderCreateService.java`
- `src/main/java/com/commerce/order/application/service/OrderCancelService.java`
- `src/main/java/com/commerce/order/application/service/OrderExpirationService.java`
- `src/main/java/com/commerce/order/application/service/OrderConcurrencyService.java`
- `src/main/java/com/commerce/order/application/usecase/OrderCreateUseCase.java`

## 작업

order 도메인 Service/UseCase 클래스를 ADR-054 컨벤션으로 리네임한다.
동작 변경 없이 파일명·클래스명·주입 변수명·테스트명만 바꾼다.
이 step은 순수 리네임만이며, 분리(split)는 없다.

### 리네임 목록

| 현재 | 변경 후 |
|---|---|
| `OrderCreateService` | `CreateOrderService` |
| `OrderCancelService` | `CancelOrderService` |
| `OrderExpirationService` | `ExpireOrderService` |
| `OrderConcurrencyService` | `OrderCreateConcurrencyService` |
| `OrderCreateUseCase` | `CreateOrderUseCase` |

### 절차

1. 각 대상 클래스를 사용하는 모든 파일을 확인한다.

   ```bash
   grep -rl "OrderCreateService\|OrderCancelService\|OrderExpirationService\|OrderConcurrencyService\|OrderCreateUseCase" src/
   ```

2. 각 클래스를 새 이름으로 파일 생성 후 기존 파일 삭제한다. 내부 메서드·로직은 그대로 유지한다.

3. 모든 참조 파일에서 업데이트한다:
   - 타입 선언 및 import
   - 변수명 (camelCase: `orderCreateService` → `createOrderService`, `orderCancelService` → `cancelOrderService` 등)

4. 테스트 파일에서 클래스명·메서드명·`@DisplayName`을 새 이름 기준으로 갱신한다.

### 주의사항

- `OrderConcurrencyService` → `OrderCreateConcurrencyService` 리네임 시, 내부의 동시성 전략 메서드(`createOrderWithoutLock`, `createOrderWithSynchronized` 등)는 그대로 유지한다.
- `OrderExpirationService` → `ExpireOrderService` 리네임 시, scheduler(`@Scheduled`)가 이 빈을 참조한다면 빈 이름을 검토한다. Spring은 클래스명 기반 빈 이름을 자동 생성하므로, 빈 이름이 명시적으로 지정돼 있지 않으면 별도 조치 없이 새 클래스명으로 자동 등록된다.

### 금지사항

- 메서드 내부 로직을 변경하지 마라. 이유: 동작 불변 원칙.
- `OrderCreateService`와 `OrderCreateUseCase`를 혼동하지 마라. 이유: Service는 tx 단위 작업, UseCase는 흐름 조립이며 역할이 다르다.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다:
   - 구 클래스명이 `src/` 하위에 남아 있지 않은가.
     ```bash
     grep -r "OrderCreateService\b\|OrderCancelService\b\|OrderExpirationService\b\|OrderConcurrencyService\b\|OrderCreateUseCase\b" src/
     ```
