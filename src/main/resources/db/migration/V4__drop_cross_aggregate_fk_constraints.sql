-- cross-aggregate FK 5건을 일괄 제거한다.
-- 선행 series (stock #199 / order #200 / payment #202) 에서 JPA 매핑 차원의 cross-aggregate association 이 모두 해제됐으므로,
-- DB schema 와 코드 정합성 (둘 다 cross-aggregate 객체 참조 없음) 을 회복한다.
-- UNIQUE 제약 (uk_stock_product_id, uk_payment_order_id) 과 same-aggregate FK 는 유지한다.

ALTER TABLE `tbl_stock` DROP FOREIGN KEY `fk_stock_product_id`;
ALTER TABLE `tbl_stock_history` DROP FOREIGN KEY `fk_stock_history_stock_id`;
ALTER TABLE `tbl_order` DROP FOREIGN KEY `fk_order_member_id`;
ALTER TABLE `tbl_order_item` DROP FOREIGN KEY `fk_order_item_product_id`;
ALTER TABLE `tbl_payment` DROP FOREIGN KEY `fk_payment_order_id`;
