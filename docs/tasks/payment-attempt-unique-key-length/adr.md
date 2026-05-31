# 태스크 ADR

## 결정 제목

- ADR-N: multi-column unique constraint 대상 컬럼은 `@Column(length=...)`을 명시한다 (ADR-018 "enum length 미명시" 정책의 좁은 예외)

## 배경

`tbl_payment_attempt`의 unique constraint `uk_payment_attempt_merchant_pay_key_provider_payment_id_type`는 entity에 선언되어 있으나 DB schema에는 적용되지 않았다. 원인:

- 4개 컬럼(`merchantPayKey`, `paymentId`, `provider`, `type`)이 `@Column(length=...)` 미지정으로 Hibernate 기본 `VARCHAR(255)`로 생성됨.
- MySQL InnoDB + utf8mb4 환경에서 unique index 한 개의 바이트 합이 `4 × 255 × 4 = 4080 bytes` → 한도 3072 bytes 초과.
- Hibernate ddl-auto가 `ALTER TABLE ... ADD CONSTRAINT`를 시도하지만 MySQL이 `Specified key was too long`로 거부.
- 기본 핸들러 `ExceptionHandlerLoggedImpl`이 WARN으로만 로그하고 부팅을 계속.

ADR-018(Hibernate 6.x ENUM 매핑)은 "컬럼 길이는 명시하지 않고 Hibernate 기본값(VARCHAR(255))을 사용한다"고 결정되어 있다. enum 값은 길이 제한의 의미가 없고 enum 추가 시 동기화 부담만 발생한다는 이유다. 그러나 본 사고는 그 결정이 **multi-column unique constraint 대상 컬럼**에서는 InnoDB 안전 한도를 깨뜨리는 부작용을 드러냈다.

세 가지 옵션을 검토했다.

- **옵션 A** — 본 사고 대상 컬럼 4개만 length 명시. ADR-018 정책은 그 외 영역에서 유지.
- **옵션 B** — ADR-018을 폐기하고 모든 enum/string 컬럼에 length 명시. 광범위한 변경.
- **옵션 C** — `@Index` 또는 `@UniqueConstraint`에 컬럼별 prefix length를 지정하는 SQL 작성. JPA 표준에 없는 hack에 가깝다.

`halt_on_error` 적용 환경 범위도 함께 결정했다 (test/local만 / 전체 / 적용 안 함).

`dockerTest`와 `concurrencyTest`의 task 실행 영역 disjoint 처리는 본 task에서 `build.gradle` 한 줄로 임시 처리하고, tag 차원 자체 재설계는 이슈 #177로 분리한다.

## 결정 내용

- **옵션 A 채택**: `PaymentAttempt`의 4개 컬럼에 `@Column(length=...)`를 명시한다. 표기 순서는 unique key columnNames 순서(`merchant_pay_key`, `provider`, `payment_id`, `type`)를 사용한다.
  - `merchantPayKey`: 64
  - `provider` (enum): 32
  - `paymentId`: 64
  - `type` (enum): 32
  - 합계 768 bytes (utf8mb4 기준) → InnoDB 한도 안에 충분히 들어옴.
- **`halt_on_error`는 local에만 적용**. test는 Testcontainer fresh MySQL 부팅 시 ALTER FK DROP 무해 실패와 충돌해 제외한다 (자세한 근거는 아래 "halt_on_error 범위 결정 이유"). prod는 운영 미가동 상태이며, 추후 Flyway 도입과 함께 `ddl-auto: validate`로 전환할 예정이라 `halt_on_error`의 의미가 자연스럽게 사라진다.
- ADR-018은 일반 enum/string 컬럼에 대해서는 유지한다. 본 ADR은 그 정책의 **좁은 예외**로, "multi-column unique constraint 대상 컬럼은 length 명시"를 추가한다.
- `dockerTest`에 `excludeTags "concurrency"` 한 줄을 추가해 같은 클래스 중복 실행을 해소한다. tag 차원 재설계는 이슈 #177에서 다룬다.

## 근거

### 옵션 A 선택 이유

- 옵션 B는 ADR-018이 정한 "enum length 미명시"의 합리적 근거(코드 상수만 저장, 외부 입력 길이 제한 의미 없음, enum 추가 시 동기화 부담)를 모든 곳에서 부정해야 한다. 본 사고의 원인은 **multi-column unique index의 바이트 합**이지 일반 컬럼 길이가 아니므로, 범위를 좁히는 게 정합적이다.
- 옵션 C는 JPA 표준에 없는 hack이고 명시성이 떨어진다.

### length 값 (unique key columnNames 순서로 64/32/64/32) 선택 이유

`uk_payment_attempt_merchant_pay_key_provider_payment_id_type` 의 columnNames 순서(`merchant_pay_key`, `provider`, `payment_id`, `type`)에 맞춰 적는다. entity 선언 순서(`merchantPayKey`, `paymentId`, `provider`, `type`)와는 다르므로 검수 시 혼동에 유의한다.

- `merchantPayKey`: 우리 코드가 생성하는 ID 형식(짧은 prefix + UUID/ULID)을 64자로 충분히 담을 수 있다.
- `paymentId`: 네이버페이가 발급하는 ID는 보통 20~30자. 64자면 2배 여유.
- `provider`/`type`: 현재 enum value 최대 길이가 10자 미만(`NAVERPAY`, `APPROVE` 등). 32자면 3배 이상 여유로 enum 추가에 대비.
- 합 768 bytes는 InnoDB 한도 3072의 25% 수준으로, 향후 enum 값이 늘거나 PG가 더 긴 ID를 발급해도 여유가 있다.

### `halt_on_error` 범위 결정 이유

본 task 초기 설계 시점에 "test + local 적용"을 검토했으나, 실제 적용 과정에서 test 환경 적용이 dockerTest와 충돌함이 발견되어 **local에만 적용**으로 좁혔다.

- **test (적용 제외)**: Testcontainer로 신선한 MySQL을 띄우는 dockerTest 부팅 단계에서 Hibernate가 `ddl-auto: create-drop`의 schema drop 단계로 `ALTER TABLE ... DROP FOREIGN KEY ...`를 실행한다. MySQL은 이 구문에 `IF EXISTS`를 지원하지 않아 빈 컨테이너에서 `Table doesn't exist`로 실패한다. `halt_on_error: true`가 이 진짜 무해한 drop 실패까지 잡아 Spring 컨텍스트 로드를 실패시킨다.
  - 초기 분석은 "`drop table if exists`로 도는 게 로그에서 확인되어 무해한 drop 실패는 거의 없다"였으나, 이는 *일반 테이블 drop*에 한정된 사실이고 외래키 drop은 IF EXISTS가 없다는 점을 누락했다. 본 분석을 정정한다.
  - test 환경의 schema 회귀 감지는 step 3의 단언 이중화(`countAttempts == 1` 데이터 invariant)가 같은 역할을 한다. 이번 사고와 동일한 unique 누락 회귀는 step 3 단언으로 곧장 잡힌다.
- **local (적용)**: ddl-auto: update. 부팅 시 drop을 수행하지 않으므로 위 충돌이 발생하지 않는다. 우리 환경은 전체 schema를 Hibernate가 만들고 외부 수동 schema가 없어 무해한 alter 실패 케이스도 거의 없다. 개발자가 부팅 시점에 문제를 즉시 인지할 수 있다.
  - **Fragility**: local의 ddl-auto가 미래에 `create-drop`/`create`로 변경되면 같은 ALTER FK DROP 충돌이 재발한다. ddl-auto 변경 시 `halt_on_error` 적용 여부를 함께 재검토해야 한다. 현 시점에는 local과 prod 모두 update를 사용하는 의도(prod 동작 검증)가 명확하므로 fragility 위험은 낮다.
- **prod (적용 제외)**: 운영 미가동이며 추후 Flyway 도입 시 ddl-auto: validate로 가면서 `halt_on_error`의 적용 영역이 자연스럽게 사라진다.

### ADR-011 본문은 갱신하지 않는 이유

- 본 사고는 ADR-011의 "find-first 패턴 적용 조건이 깨졌다"는 의미가 아니다. **그 패턴이 의존하는 전제(DB unique constraint 존재)가 처음부터 누락**되어 있었던 것이다.
- 따라서 ADR-011 정책 자체는 그대로 유효하며, 본 task는 그 정책의 안전망을 복원하는 fix다.

## 결과

### 기대 효과

- `tbl_payment_attempt`의 unique constraint이 schema에 정상 적용되어 ADR-011의 race window 안전망이 의도대로 작동한다.
- `halt_on_error`로 같은 류 schema 회귀가 부팅 단계에서 곧장 노출된다 (local). test 환경에서는 step 3의 단언 이중화가 같은 역할을 한다.
- `NaverPayServiceConcurrencyTest`의 단언 이중화로 향후 unique 누락 회귀를 테스트 단에서 직접 잡는다.

### Trade-off

- ADR-018("enum length 미명시")과 본 ADR("multi-column unique 대상은 명시")이 공존한다. 본 ADR이 ADR-018의 일반 원칙에 대한 좁은 예외임을 명시해 충돌 인식을 차단한다.
- length 값을 결정한 시점의 데이터 흐름 가정(`paymentId` 30자 이하 등)이 미래 PG 추가 시 깨질 수 있다. enum/PG 추가 시점에 length 재검토가 필요할 수 있다 — 본 ADR의 "한계"로 명시.
- `halt_on_error`로 부팅이 실패하면 개발자가 추가 사유 분석 부담을 진다. 무해한 케이스가 거의 없는 우리 환경에서는 이득이 부담을 초과한다는 판단.

### 한계

- prod DB schema 정합성은 본 task가 보장하지 않는다. 추후 Flyway 도입 시 schema 일관성 점검에서 처리.
- 다른 entity의 multi-column unique constraint이 한도를 넘는지는 자동 검증하지 않는다. 현 시점 인벤토리(`Order` `uk_order_member_idempotency`, `CartItem` `uk_cart_item_member_product`, `ProcessedEvent` `idx_processed_event_event_id_consumer_type`)는 한도 안에 들어옴을 확인했으나, 신규 추가 시 본 ADR을 참고해 length를 명시해야 한다.
