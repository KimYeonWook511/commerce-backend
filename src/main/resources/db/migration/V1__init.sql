CREATE TABLE `tbl_member` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL,
  `email` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `password` varchar(60) COLLATE utf8mb4_unicode_ci NOT NULL,
  `role` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK31to4n8j2vslkf7jvfo408sta` (`email`),
  CONSTRAINT `tbl_member_chk_1` CHECK ((`role` in (_utf8mb4'ROLE_ADMIN',_utf8mb4'ROLE_USER')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tbl_product` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `image_url` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `price` int NOT NULL,
  `status` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `tbl_product_chk_1` CHECK ((`status` in (_utf8mb4'ON_SALE',_utf8mb4'SOLD_OUT',_utf8mb4'STOPPED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tbl_stock` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `quantity` int NOT NULL,
  `version` bigint NOT NULL DEFAULT 0,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKta0674wotw5fo969ewn24i93b` (`product_id`),
  CONSTRAINT `FKo8mybc2mw82rhti4t1n9i1d0e` FOREIGN KEY (`product_id`) REFERENCES `tbl_product` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tbl_stock_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `stock_id` bigint NOT NULL,
  `admin_member_id` bigint NOT NULL,
  `quantity_change` int NOT NULL,
  `reason` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKts4aivqh4ede6fty5u650jwc` (`stock_id`),
  CONSTRAINT `FKts4aivqh4ede6fty5u650jwc` FOREIGN KEY (`stock_id`) REFERENCES `tbl_stock` (`id`),
  CONSTRAINT `tbl_stock_history_chk_1` CHECK ((`reason` in (_utf8mb4'INBOUND',_utf8mb4'DISPOSAL',_utf8mb4'ADMIN_ADJUSTMENT',_utf8mb4'ORDER_CANCEL_RESTORE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tbl_cart_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `quantity` int NOT NULL,
  `version` bigint NOT NULL DEFAULT 0,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cart_item_member_product` (`member_id`,`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tbl_order` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `idempotency_key` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `merchant_pay_key` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `total_price` int NOT NULL,
  `version` bigint NOT NULL DEFAULT 0,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_member_idempotency` (`member_id`,`idempotency_key`),
  UNIQUE KEY `UKnq5soaqnn4op8p7lodagyifts` (`merchant_pay_key`),
  CONSTRAINT `FKfm4jjqr8ueinggxwcyi809xkf` FOREIGN KEY (`member_id`) REFERENCES `tbl_member` (`id`),
  CONSTRAINT `tbl_order_chk_1` CHECK ((`status` in (_utf8mb4'INIT',_utf8mb4'CANCELED',_utf8mb4'RECEIVED',_utf8mb4'PAID',_utf8mb4'COMPLETED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tbl_order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `quantity` int NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKmkqpajkg6p2wq4owcv1v08pc5` (`order_id`),
  KEY `FK1oy9x003q55eqmuiv0y8a15e` (`product_id`),
  CONSTRAINT `FK1oy9x003q55eqmuiv0y8a15e` FOREIGN KEY (`product_id`) REFERENCES `tbl_product` (`id`),
  CONSTRAINT `FKmkqpajkg6p2wq4owcv1v08pc5` FOREIGN KEY (`order_id`) REFERENCES `tbl_order` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tbl_payment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `merchant_pay_key` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `pg_payment_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `provider` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `amount` int NOT NULL,
  `approved_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKf32395wa9xoapy5h23u5d643p` (`order_id`),
  UNIQUE KEY `UKmm09lhbqi475utuekiuim6ahw` (`merchant_pay_key`),
  UNIQUE KEY `UK46apuj2eugc24hwctahklklgn` (`pg_payment_id`),
  CONSTRAINT `FKac54xp3r2r3m9datds9351ric` FOREIGN KEY (`order_id`) REFERENCES `tbl_order` (`id`),
  CONSTRAINT `tbl_payment_chk_1` CHECK ((`provider` = _utf8mb4'NAVERPAY')),
  CONSTRAINT `tbl_payment_chk_2` CHECK ((`status` in (_utf8mb4'COMPLETED',_utf8mb4'CANCELED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tbl_payment_attempt` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `merchant_pay_key` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `provider` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `payment_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `fail_code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `fail_detail` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `amount` int NOT NULL,
  `responded_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_attempt_merchant_pay_key_provider_payment_id_type` (`merchant_pay_key`,`provider`,`payment_id`,`type`),
  CONSTRAINT `tbl_payment_attempt_chk_1` CHECK ((`provider` = _utf8mb4'NAVERPAY')),
  CONSTRAINT `tbl_payment_attempt_chk_2` CHECK ((`type` in (_utf8mb4'APPROVE',_utf8mb4'CANCEL'))),
  CONSTRAINT `tbl_payment_attempt_chk_3` CHECK ((`fail_code` in (_utf8mb4'TIME_EXPIRED',_utf8mb4'ALREADY_CANCELED',_utf8mb4'AMOUNT_MISMATCH',_utf8mb4'MERCHANT_PAY_KEY_MISMATCH',_utf8mb4'INVALID_PAYMENT_ID',_utf8mb4'INVALID_MERCHANT',_utf8mb4'OWNER_AUTH_FAILED',_utf8mb4'NOT_ENOUGH_ACCOUNT_BALANCE',_utf8mb4'DUPLICATE_PAYMENT',_utf8mb4'APPROVE_PROCESS_FAILED',_utf8mb4'CANCEL_PROCESS_FAILED',_utf8mb4'PG_REQUEST_REJECTED',_utf8mb4'PG_INVALID_RESPONSE',_utf8mb4'PG_MAINTENANCE',_utf8mb4'PG_NETWORK_ERROR',_utf8mb4'PG_SERVER_ERROR'))),
  CONSTRAINT `tbl_payment_attempt_chk_4` CHECK ((`status` in (_utf8mb4'REQUESTED',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tbl_outbox_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` varchar(26) COLLATE utf8mb4_unicode_ci NOT NULL,
  `event_type` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `aggregate_type` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `aggregate_id` bigint NOT NULL,
  `payload` tinytext COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `attempt_count` int NOT NULL,
  `last_error` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `trace_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `next_retry_at` datetime(6) NOT NULL,
  `published_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK14kuuvaqf7i0f77l53asv43gp` (`event_id`),
  KEY `idx_outbox_event_type_status_next_retry_id` (`event_type`,`status`,`next_retry_at`,`id`),
  CONSTRAINT `tbl_outbox_event_chk_1` CHECK ((`aggregate_type` = _utf8mb4'ORDER')),
  CONSTRAINT `tbl_outbox_event_chk_2` CHECK ((`event_type` = _utf8mb4'STOCK_RESTORE_REQUESTED')),
  CONSTRAINT `tbl_outbox_event_chk_3` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'PUBLISHING',_utf8mb4'SENT',_utf8mb4'FAILED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tbl_processed_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` varchar(26) COLLATE utf8mb4_unicode_ci NOT NULL,
  `consumer_type` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_processed_event_event_id_consumer_type` (`event_id`,`consumer_type`),
  CONSTRAINT `tbl_processed_event_chk_1` CHECK ((`consumer_type` = _utf8mb4'STOCK_RESTORE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
