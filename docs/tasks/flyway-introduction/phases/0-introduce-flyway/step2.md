# Step 2: generate-v1-init-sql

## 읽어야 할 파일

먼저 아래 파일들을 읽어 V1 베이스라인의 의도와 dump 정리 체크리스트를 정확히 파악하라:

- `/docs/tasks/flyway-introduction/prd.md`
- `/docs/tasks/flyway-introduction/architecture.md`
- `/docs/tasks/flyway-introduction/db-schema.md` ← dump 정리 체크리스트 10개 항목 포함
- `/docs/ADR.md` ADR-018 (Hibernate ENUM → VARCHAR), ADR-023 (multi-column unique 컬럼 길이 명시)
- `/docker-compose.local.yml`
- `/build.gradle` (Step 1 결과 반영 확인)
- `/src/main/resources/application-local.yml`
- 엔티티 12개 파일 — 적어도 `tbl_payment_attempt`(`PaymentAttempt.java`)와 `tbl_outbox_event`(`OutboxEvent.java`)는 읽어 ENUM/Unique/length 정의를 직접 확인

## 작업

빈 MySQL 컨테이너에 Hibernate `ddl-auto: create`로 한 번 부팅해 엔티티 12개의 스키마를 적용한 뒤, `mysqldump`로 스키마만 추출해 `V1__init.sql`로 저장한다.

### 명령 흐름

worktree 루트(`worktrees/chore-flyway-introduction/`)에서 실행:

```bash
# 1. mysql-data-local 초기화
# 사용자가 plan 단계에서 destructive 삭제를 명시적으로 승인했다 (운영 데이터 없음).
# Step Design / PRD의 "제약사항"에 기록된 사항이다. 이 step에서 별도 재확인 없이 진행한다.
rm -rf mysql-data-local

# 2. MySQL 컨테이너 시작
docker compose -f docker-compose.local.yml up -d mysql

# 3. MySQL ready 대기 (mysqladmin ping)
for i in {1..30}; do
  if docker exec commerce-mysql-local mysqladmin ping -h localhost -uroot -proot0511 --silent 2>/dev/null; then
    break
  fi
  sleep 2
done

# 4. ddl-auto: create로 부팅 (백그라운드, Flyway/Batch 비활성)
SPRING_FLYWAY_ENABLED=false \
SPRING_JPA_HIBERNATE_DDL_AUTO=create \
SPRING_BATCH_JDBC_INITIALIZE_SCHEMA=never \
./gradlew bootRun --args='--spring.profiles.active=local' > /tmp/v1-bootrun.log 2>&1 &
BOOTRUN_PID=$!

# 5. 부팅 완료 대기 (Tomcat ready 로그)
timeout 180 bash -c 'until grep -q "Started CommerceApplication" /tmp/v1-bootrun.log 2>/dev/null; do sleep 2; done'

# 6. 부팅 프로세스 종료
kill $BOOTRUN_PID 2>/dev/null || true
wait $BOOTRUN_PID 2>/dev/null || true
# Gradle daemon이 자식 프로세스를 잡고 있을 수 있다. 잔여 Java 프로세스 정리:
pkill -f 'org.springframework.boot.devtools' 2>/dev/null || true

# 7. mysqldump로 스키마 추출
mkdir -p src/main/resources/db/migration
docker exec commerce-mysql-local mysqldump \
  -uroot -proot0511 \
  --no-data --skip-comments --skip-add-drop-table \
  --set-gtid-purged=OFF \
  commerce_db > src/main/resources/db/migration/V1__init.sql

# 8. 자동 정리 — 헤더와 AUTO_INCREMENT 옵션 제거
sed -i.bak -E '/^\/\*!/d; /^-- /d; s/ AUTO_INCREMENT=[0-9]+//g' src/main/resources/db/migration/V1__init.sql
rm src/main/resources/db/migration/V1__init.sql.bak
```

### 추가 수동 검토

자동 정리(`sed`)가 처리하지 못하는 항목은 worker가 파일을 읽어 직접 확인한다. PRD/db-schema.md의 체크리스트 10개를 모두 점검:

1. 헤더(`/*!40101 SET ... */`, `-- ...`) 제거 완료
2. `AUTO_INCREMENT=N` 모두 제거 완료
3. 모든 테이블이 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=...` 확인
4. `DEFINER=...` 절 없음 (mysqldump --no-data로는 보통 없음)
5. `BATCH_*` 테이블 없음
6. ENUM 필드가 `enum(...)` 타입이 아닌 `varchar(...)` 타입 확인 — 특히 `OrderStatus`, `PaymentStatus`, `OutboxEventStatus`, `StockChangeReason` 등
7. 도메인 그룹 순서: member → product → stock/stock_history → cart_item → order/order_item → payment/payment_attempt → outbox_event/processed_event. mysqldump는 외래키 의존성 순으로 정렬하지만, 그룹 단위 직관성이 무너졌다면 그룹 내 순서 보정만 한다 (FK 순서 자체는 무너뜨리지 않는다).
8. `tbl_payment_attempt`의 `uk_payment_attempt_merchant_pay_key_provider_payment_id_type` constraint가 존재하고, 각 컬럼이 `merchantPayKey VARCHAR(64)`, `provider VARCHAR(32)`, `paymentId VARCHAR(64)`, `type VARCHAR(32)` 같이 길이 명시되어 합이 192*4=768 bytes < 3072 bytes 한도 내 (ADR-023)
9. 모든 PK가 `BIGINT NOT NULL AUTO_INCREMENT`
10. `tbl_order`, `tbl_stock`, `tbl_cart_item`의 `version` 컬럼이 `NOT NULL`. 모든 도메인 테이블에 `created_at`, `updated_at` 존재

문제가 발견되면 dump를 수동 편집해 보정한다.

## Acceptance Criteria

```bash
# (a) V1 파일 존재
test -f src/main/resources/db/migration/V1__init.sql

# (b) 자동 체크리스트
! grep -i 'enum(' src/main/resources/db/migration/V1__init.sql
grep -q 'utf8mb4' src/main/resources/db/migration/V1__init.sql
grep -q 'ENGINE=InnoDB' src/main/resources/db/migration/V1__init.sql
! grep -qE 'BATCH_(JOB|STEP)_' src/main/resources/db/migration/V1__init.sql

# (c) 도메인 테이블 11개 모두 존재 (한 줄 명령 — AC executor는 multi-line shell 블록을 지원하지 않음)
for t in tbl_member tbl_product tbl_stock tbl_stock_history tbl_cart_item tbl_order tbl_order_item tbl_payment tbl_payment_attempt tbl_outbox_event tbl_processed_event; do grep -qE "CREATE TABLE \`?${t}\`?" src/main/resources/db/migration/V1__init.sql || { echo "missing: $t"; exit 1; }; done

# (d) payment-attempt unique constraint 존재 (ADR-023)
grep -q 'uk_payment_attempt_merchant_pay_key_provider_payment_id_type' src/main/resources/db/migration/V1__init.sql

# (e) AUTO_INCREMENT 옵션 잔재 없음
! grep -qE 'AUTO_INCREMENT=[0-9]+' src/main/resources/db/migration/V1__init.sql

# (f) 헤더 잔재 없음
! grep -qE '^/\*!' src/main/resources/db/migration/V1__init.sql

# (g) 기존 test가 영향 받지 않는지 (H2, Flyway 비활성이라 무변경)
./gradlew test
```

위 모든 명령이 exit 0이어야 한다.

## 검증 절차

1. `명령 흐름` 1~8을 순서대로 실행한다. 각 단계 실패 시 즉시 중단하고 사유를 보고한다.
2. `추가 수동 검토` 10개 항목을 V1__init.sql 파일을 읽어 점검한다. 자동 grep으로 확인 가능한 것은 Acceptance Criteria에 위임하고, 그룹 순서/length 명시/version NOT NULL 같은 항목은 파일 본문을 읽어 확인한다.
3. Acceptance Criteria 모든 명령을 실행해 exit 0 확인.
4. V1__init.sql의 길이와 테이블 개수를 summary에 포함해 보고한다 (예: "13 CREATE TABLE statements, 4XX lines").

## 금지사항

- bootRun을 foreground로 띄워두지 마라. 이유: worker가 hang된다.
- `--ignore-table` 같은 mysqldump 옵션을 함부로 추가하지 마라. 이유: Spring Batch 메타테이블은 Step 4의 `initialize-schema=never` 환경 변수로 이미 빠진다. ignore-table 옵션은 도메인 테이블을 실수로 누락시킬 위험이 있다.
- `mysql-data-local/` 외의 디렉토리를 삭제하지 마라. 이유: destructive.
- V1__init.sql에 데이터(`INSERT INTO ...`)를 포함시키지 마라. 이유: mysqldump `--no-data` 옵션으로 자동 제외되어야 한다.
- `application-*.yml`을 수정하지 마라. 이유: 설정 변경은 Step 3의 범위.
- 부팅이 실패한다고 `ddl-auto: update`로 바꾸지 마라. 이유: `update`는 기존 스키마 잔재가 있으면 의도와 다른 결과를 만든다. `create`로 빈 DB에 새로 만드는 것이 step 의도다.
- 임시 로그 파일(`/tmp/v1-bootrun.log`)을 커밋하지 마라. 이유: 로컬 산출물.
