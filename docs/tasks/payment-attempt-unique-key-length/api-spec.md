# 태스크 API 스펙

## 개요

- 본 task는 API를 추가하거나 변경하지 않는다.
- 외부 응답 코드는 동일하지만 race window 동시 요청에서 발생하던 `IncorrectResultSizeDataAccessException` → `DataIntegrityViolationException`으로 정상화된다 (안전망 500 응답은 동일하지만 내부 분류가 `COMMON-500` → `COMMON-500-2`로 정확해진다).

## 엔드포인트

- 해당 없음.

## 요청

- 해당 없음.

## 응답

- 해당 없음.

## 검증 규칙

- 해당 없음.

## 비고

- ADR-011 (find-first 패턴)의 안전망 동작(DB unique 위반 → `DataAccessException` 핸들러 → 500)이 본 fix 이후 실제로 작동하게 된다. 외부 응답 코드 자체는 변경되지 않으나, 동일 키 동시 요청에서의 응답 패턴이 정상화된다.
