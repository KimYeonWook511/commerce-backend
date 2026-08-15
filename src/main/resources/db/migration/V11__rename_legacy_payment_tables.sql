-- 옛 결제·예약 테이블이 tbl_payment / tbl_payment_reservation 이름을 비켜 준다.
-- 새 결제 모델이 같은 이름으로 테이블을 새로 만드는데, 옛 테이블이 그 이름을 쥐고 있으면
-- 두 엔티티가 한 테이블에 매핑돼 시작 시 매핑 검증에서 실패한다.
-- RENAME이라 데이터는 그대로 남아 legacy 경로가 계속 동작한다.

RENAME TABLE `tbl_payment` TO `tbl_legacy_payment`;

RENAME TABLE `tbl_payment_reservation` TO `tbl_legacy_payment_reservation`;
