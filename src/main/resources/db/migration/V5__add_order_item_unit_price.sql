-- OrderItem 에 결제 시점 가격 snapshot 컬럼을 추가한다.
-- 기존 row 는 tbl_product.price (현재가) 로 backfill 후 NOT NULL 로 전환한다.
-- product 가 hard-delete 된 row 는 LEFT JOIN + COALESCE 로 0 fallback 한다.
-- backfill 정확도의 한계와 0 fallback 의미는 docs/tasks/order-item-price-snapshot/adr.md 결정 2 에 명문화돼 있다.

ALTER TABLE `tbl_order_item` ADD COLUMN `unit_price` INT NULL AFTER `quantity`;

UPDATE `tbl_order_item` oi
LEFT JOIN `tbl_product` p ON oi.product_id = p.id
SET oi.unit_price = COALESCE(p.price, 0)
WHERE oi.unit_price IS NULL;

ALTER TABLE `tbl_order_item` MODIFY COLUMN `unit_price` INT NOT NULL;
