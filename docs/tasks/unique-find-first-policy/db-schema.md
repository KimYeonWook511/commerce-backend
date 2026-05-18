# 태스크 DB 스키마

## 개요

본 태스크는 DB 스키마(테이블/컬럼/인덱스/제약조건) 를 변경하지 않는다.

## 변경 없음 — 검증

- 신규 테이블 없음.
- 기존 테이블 컬럼 변경 없음.
- 인덱스 변경 없음.
- unique 제약 추가/제거/수정 없음 — 기존 unique 제약을 그대로 둔 채 Application 처리 정책만 변경한다.

## 비고

본 태스크의 unique 위반 처리 정책 변경은 코드 수준 변경이며 DB 구조에 영향을 주지 않는다. 안전망(`DataIntegrityViolationException` 핸들러) 이 unique 위반을 가시화하는 메커니즘은 그대로 유효하며, `JpaConfig` 의 `SQLErrorCodeSQLExceptionTranslator` 빈도 유지된다.
