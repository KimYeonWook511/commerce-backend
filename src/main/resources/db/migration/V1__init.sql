CREATE TABLE `tbl_member` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(12) NOT NULL,
  `email` varchar(50) NOT NULL,
  `password` varchar(60) NOT NULL,
  `role` varchar(255) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tbl_product` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) NOT NULL,
  `description` varchar(255) DEFAULT NULL,
  `image_url` varchar(255) DEFAULT NULL,
  `price` int NOT NULL,
  `status` varchar(255) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tbl_stock` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `quantity` int NOT NULL,
  `version` bigint NOT NULL DEFAULT 0,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stock_product_id` (`product_id`),
  CONSTRAINT `FKo8mybc2mw82rhti4t1n9i1d0e` FOREIGN KEY (`product_id`) REFERENCES `tbl_product` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tbl_stock_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `stock_id` bigint NOT NULL,
  `admin_member_id` bigint NOT NULL,
  `quantity_change` int NOT NULL,
  `reason` varchar(255) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKts4aivqh4ede6fty5u650jwc` (`stock_id`),
  CONSTRAINT `FKts4aivqh4ede6fty5u650jwc` FOREIGN KEY (`stock_id`) REFERENCES `tbl_stock` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tbl_cart_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `quantity` int NOT NULL,
  `version` bigint NOT NULL DEFAULT 0,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cart_item_member_product` (`member_id`,`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tbl_order` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `idempotency_key` varchar(255) DEFAULT NULL,
  `merchant_pay_key` varchar(255) DEFAULT NULL,
  `status` varchar(255) NOT NULL,
  `total_price` int NOT NULL,
  `version` bigint NOT NULL DEFAULT 0,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_member_idempotency` (`member_id`,`idempotency_key`),
  UNIQUE KEY `uk_order_merchant_pay_key` (`merchant_pay_key`),
  CONSTRAINT `FKfm4jjqr8ueinggxwcyi809xkf` FOREIGN KEY (`member_id`) REFERENCES `tbl_member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tbl_order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `quantity` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKmkqpajkg6p2wq4owcv1v08pc5` (`order_id`),
  KEY `FK1oy9x003q55eqmuiv0y8a15e` (`product_id`),
  CONSTRAINT `FK1oy9x003q55eqmuiv0y8a15e` FOREIGN KEY (`product_id`) REFERENCES `tbl_product` (`id`),
  CONSTRAINT `FKmkqpajkg6p2wq4owcv1v08pc5` FOREIGN KEY (`order_id`) REFERENCES `tbl_order` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tbl_payment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `merchant_pay_key` varchar(255) DEFAULT NULL,
  `pg_payment_id` varchar(255) DEFAULT NULL,
  `provider` varchar(255) NOT NULL,
  `status` varchar(255) NOT NULL,
  `amount` int NOT NULL,
  `approved_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_order_id` (`order_id`),
  UNIQUE KEY `uk_payment_merchant_pay_key` (`merchant_pay_key`),
  UNIQUE KEY `uk_payment_pg_payment_id` (`pg_payment_id`),
  CONSTRAINT `FKac54xp3r2r3m9datds9351ric` FOREIGN KEY (`order_id`) REFERENCES `tbl_order` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tbl_payment_attempt` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `merchant_pay_key` varchar(64) NOT NULL,
  `provider` varchar(32) NOT NULL,
  `payment_id` varchar(64) NOT NULL,
  `type` varchar(32) NOT NULL,
  `status` varchar(255) NOT NULL,
  `fail_code` varchar(255) DEFAULT NULL,
  `fail_detail` varchar(255) DEFAULT NULL,
  `amount` int NOT NULL,
  `responded_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_attempt_merchant_pay_key_provider_payment_id_type` (`merchant_pay_key`,`provider`,`payment_id`,`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tbl_outbox_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` varchar(26) NOT NULL,
  `event_type` varchar(255) NOT NULL,
  `aggregate_type` varchar(255) NOT NULL,
  `aggregate_id` bigint NOT NULL,
  `payload` text NOT NULL,
  `status` varchar(255) NOT NULL,
  `attempt_count` int NOT NULL,
  `last_error` varchar(1000) DEFAULT NULL,
  `trace_id` varchar(64) DEFAULT NULL,
  `next_retry_at` datetime(6) NOT NULL,
  `published_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_outbox_event_event_id` (`event_id`),
  KEY `idx_outbox_event_type_status_next_retry_id` (`event_type`,`status`,`next_retry_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tbl_processed_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` varchar(26) NOT NULL,
  `consumer_type` varchar(255) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_processed_event_event_id_consumer_type` (`event_id`,`consumer_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
