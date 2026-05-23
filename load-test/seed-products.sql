-- 부하 테스트용 상품 더미 데이터 삽입 (100개)
-- 실행: docker exec -i commerce-mysql-local mysql -u root -proot0511 commerce_db < load-test/seed-products.sql

DELIMITER $$
CREATE PROCEDURE insert_dummy_products()
BEGIN
  DECLARE i INT DEFAULT 1;
  WHILE i <= 100 DO
    INSERT INTO tbl_product (name, price)
    VALUES (
      CONCAT('테스트 상품 ', i),
      FLOOR(RAND() * 90000) + 10000
    );
    INSERT INTO tbl_stock (version, product_id, quantity)
    VALUES (0, LAST_INSERT_ID(), 100);
    SET i = i + 1;
  END WHILE;
END$$
DELIMITER ;

CALL insert_dummy_products();
DROP PROCEDURE insert_dummy_products;

SELECT COUNT(*) AS inserted_count FROM tbl_product;
