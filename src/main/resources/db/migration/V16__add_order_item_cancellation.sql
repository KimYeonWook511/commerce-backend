-- 주문 품목이 "지금까지 얼마나 취소됐는가"를 들고, 그 취소가 어느 환불로 몇 개였는지를 따로 남긴다.
-- 부분취소는 같은 품목을 여러 번 나눠 취소하므로 누계만으로는 어느 요청이 무엇을 취소했는지 알 수 없고,
-- 환불이 자동으로 끝나지 않아 사람이 이어받을 때 그 내역이 유일한 복구 근거가 된다.

-- 기존 행은 기본값 0으로 채워진다. 이미 전액취소된 주문의 행도 0으로 남는다 — 되메움을 하지 않는다.
-- 상한(cancelled_quantity <= quantity)은 제약으로 두지 않는다. 도메인이 지키며, 제약으로 옮기면
-- 위반이 정상 흐름에서 안전망으로 터진다.
ALTER TABLE `tbl_order_item`
  ADD COLUMN `cancelled_quantity` int NOT NULL DEFAULT 0 AFTER `unit_price`;

CREATE TABLE `tbl_order_item_cancellation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_item_id` bigint NOT NULL,
  -- 그 취소로 열린 환불. 다른 aggregate의 루트라 외래 키를 걸지 않고 식별자로만 가리킨다.
  `refund_id` bigint NOT NULL,
  `quantity` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  -- 환불 하나 안에서 품목 하나당 한 줄. 한 요청에 같은 품목이 여러 줄로 오는 것은 요청 경계가
  -- 거부하므로, 이 제약은 그 거부가 빠졌을 때의 안전망이다.
  UNIQUE KEY `uk_order_item_cancellation_refund_item` (`refund_id`, `order_item_id`),
  -- 환불 하나의 내역을 모아 읽는 조회가 타는 키. 사람이 이어받을 때의 경로다.
  KEY `idx_order_item_cancellation_refund` (`refund_id`),
  -- 같은 aggregate 안이라 외래 키를 건다.
  CONSTRAINT `fk_order_item_cancellation_order_item`
    FOREIGN KEY (`order_item_id`) REFERENCES `tbl_order_item` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
