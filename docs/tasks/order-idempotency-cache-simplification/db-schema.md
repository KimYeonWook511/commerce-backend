# 태스크 DB 스키마

## 개요

DB 스키마 변경 없음. `tbl_order` 의 `(member_id, idempotency_key)` unique 제약을 *멱등성 진실의 단일 원천* 으로 그대로 유지한다.

## 신규 테이블

없음.

## 변경 테이블

없음.

## 인덱스

기존 인덱스 그대로 유지.

- `tbl_order` `uk_order_member_idempotency (member_id, idempotency_key) UNIQUE` — 본 태스크에서 *멱등성 진실의 단일 원천* 으로 의미가 강화됨. Redis 마커는 in-flight 차단 최적화 레이어로만 동작.

## 데이터 무결성

- 멱등성 보장은 본 unique 제약이 *최종 보장* 한다. Redis 장애·timeout 시에도 본 제약이 중복 INSERT 를 차단한다.
- `idempotency_key` 는 기존 정책대로 NULL 허용 (멱등성 없는 경로와의 호환). MySQL 에서 NULL 값은 unique 제약 대상에서 제외된다.

## 마이그레이션 고려사항

없음. 스키마 변경 없으므로 배포 순서·백필·롤백 이슈 없음.
