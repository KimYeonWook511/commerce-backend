-- 앞 스크립트가 옛 테이블을 옆으로 치워 준 자리에 새 결제 모델을 짓는다.
-- 옛 테이블(tbl_legacy_payment·tbl_legacy_payment_reservation)은 건드리지 않는다 — 지우면 legacy 경로가
-- 죽는다. 지우는 것은 legacy 제거 단계가 한다.
--
-- 결제는 시도 원장이 되어 결제창을 띄우기 전에 행이 먼저 생기고, 환불이 별도 테이블로 갈라진다.
-- 결제사 호출 기록은 어느 aggregate에도 들지 않는 별도 테이블이다.

CREATE TABLE `tbl_payment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `member_id` bigint NOT NULL,
  `payment_key` varchar(64) NOT NULL,
  `idempotency_key` varchar(64) NOT NULL,
  `pg_payment_id` varchar(64) DEFAULT NULL,
  `pg_transaction_id` varchar(64) DEFAULT NULL,
  `amount` int NOT NULL,
  `approved_amount` int DEFAULT NULL,
  -- 이 결제에 딸린 환불 금액의 합. 한도 판정이 읽는 유일한 값이고, 환불을 만드는 트랜잭션이 여기에
  -- 더해 결제 행의 버전을 올린다 — 그 갱신이 없으면 동시에 온 두 환불 요청이 서로를 감지하지 못한다.
  `total_refunded_amount` int NOT NULL DEFAULT 0,
  `pg` varchar(255) NOT NULL,
  `status` varchar(255) NOT NULL,
  -- 활성 슬롯. 살아 있으면 order_id, 종결되면 NULL. MySQL이 NULL 중복을 허용하므로 이 값으로
  -- "살아 있는 것만 유일"을 표현한다.
  `active_order_key` bigint DEFAULT NULL,
  `close_code` varchar(255) DEFAULT NULL,
  `close_detail` varchar(255) DEFAULT NULL,
  `last_requested_at` datetime(6) DEFAULT NULL,
  `last_reconcile_at` datetime(6) DEFAULT NULL,
  `last_notify_at` datetime(6) DEFAULT NULL,
  -- 승인 호출 멱등키에 붙일 시도 번호. 부른 횟수가 아니다.
  `attempt_seq` int NOT NULL DEFAULT 0,
  -- 대사가 이 건을 몇 번 집었나. 다시 집는 간격을 고르는 값이지 회수 횟수의 상한이 아니다.
  `reconcile_count` int NOT NULL DEFAULT 0,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `version` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_payment_key` (`payment_key`),
  UNIQUE KEY `uk_payment_member_idempotency` (`member_id`, `idempotency_key`),
  UNIQUE KEY `uk_payment_active_order_key` (`active_order_key`),
  KEY `idx_payment_order` (`order_id`),
  -- 대사·통지 대상 조회. 선두가 전부 status이고 그것만으로 크게 좁혀진다. 다시 집는 것이 회차별로
  -- 나뉘므로 reconcile_count가 둘째, 셋째가 임계 시각으로 범위를 좁힌다.
  KEY `idx_payment_status_reconcile` (`status`, `reconcile_count`, `last_reconcile_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- pg_payment_id에는 유일 제약도 인덱스도 두지 않는다. 그 값으로 결제 행을 찾는 조회를 만들지 않아
-- 인덱스를 탈 자리가 없고, 클라이언트가 보내는 값을 검증 전에 채우므로 제약을 걸면 아무 번호나 실어
-- 그 번호를 묶고 정당한 주인의 승인을 막을 수 있다.

CREATE TABLE `tbl_refund` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  -- 결제 참조. 경계를 넘는 참조라 외래 키를 걸지 않는다. 조회를 받는 인덱스만 둔다.
  `payment_id` bigint NOT NULL,
  -- 환불 사건 키. 시도 번호를 붙인 값이 결제사 한도(64자)를 넘지 못하게 컬럼을 좁혀 막는다.
  `refund_key` varchar(40) NOT NULL,
  -- 환불 요청 멱등키. 회원 환불은 밖에서 받은 값을, 시스템 환불은 사유 값을 담는다.
  `idempotency_key` varchar(64) NOT NULL,
  -- enum이지만 여러 컬럼 유일 제약에 들어가므로 길이를 명시한다.
  `requester` varchar(20) NOT NULL,
  `amount` int NOT NULL,
  `reason` varchar(255) NOT NULL,
  -- 결제사 호출 멱등키. 사건 키에 시도 번호를 붙여 파생한다.
  `pg_idempotency_key` varchar(64) NOT NULL,
  `status` varchar(255) NOT NULL,
  `pg_transaction_id` varchar(64) DEFAULT NULL,
  `review_code` varchar(255) DEFAULT NULL,
  `review_detail` varchar(255) DEFAULT NULL,
  `last_requested_at` datetime(6) DEFAULT NULL,
  `last_reconcile_at` datetime(6) DEFAULT NULL,
  `last_notify_at` datetime(6) DEFAULT NULL,
  `attempt_seq` int NOT NULL DEFAULT 0,
  `reconcile_count` int NOT NULL DEFAULT 0,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `version` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_refund_key` (`refund_key`),
  -- 중복 요청 차단. 기존 사건을 찾는 범위가 그 결제에 딸린 환불들이므로 제약도 같은 범위로 둔다 —
  -- 어긋나면 조회가 못 찾은 것을 제약이 잡아 안전망이 정상 흐름에서 터진다.
  -- 요청자가 범위에 들어 회원 값과 시스템 값이 다른 공간에 놓인다.
  UNIQUE KEY `uk_refund_payment_idempotency` (`payment_id`, `requester`, `idempotency_key`),
  UNIQUE KEY `uk_refund_pg_idempotency_key` (`pg_idempotency_key`),
  KEY `idx_refund_payment` (`payment_id`),
  KEY `idx_refund_status_reconcile` (`status`, `reconcile_count`, `last_reconcile_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tbl_pg_call_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  -- 환불 호출도 결제에 딸리므로 항상 채운다.
  `payment_id` bigint NOT NULL,
  -- 환불 호출일 때만 채운다.
  `refund_id` bigint DEFAULT NULL,
  `call_type` varchar(255) NOT NULL,
  `pg_idempotency_key` varchar(64) NOT NULL,
  `requested_at` datetime(6) NOT NULL,
  -- 비어 있으면 그 호출의 결과를 모른다.
  `responded_at` datetime(6) DEFAULT NULL,
  -- 응답을 못 받은 원인. 응답 원본으로 역산할 수 없어 따로 담는다.
  `error_type` varchar(255) DEFAULT NULL,
  `result_code` varchar(64) DEFAULT NULL,
  `http_status` int DEFAULT NULL,
  `raw_response` text,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_pg_call_log_payment_requested` (`payment_id`, `requested_at`),
  KEY `idx_pg_call_log_refund` (`refund_id`),
  KEY `idx_pg_call_log_type_requested` (`call_type`, `requested_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 낙관 락 컬럼(version)을 두지 않는다. 한 행이 한 호출이고 결과를 채우는 갱신이 한 번뿐이라
-- 두 주체가 같은 행을 고칠 일이 없다.
