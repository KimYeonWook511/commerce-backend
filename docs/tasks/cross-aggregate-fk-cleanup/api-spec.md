# 태스크 API 스펙

## 개요

- 본 태스크는 **API 응답 / 요청 / 엔드포인트 계약을 변경하지 않는다**.
- 변경 대상은 DB schema (Flyway migration 1건) + 루트 docs 동기화 + 회고록뿐이다. Controller / request / response / validation 형식은 모두 그대로 유지된다.

## 엔드포인트

- 변경 없음.

## 요청

- 변경 없음.

## 응답

- 변경 없음.

## 검증 규칙

- 변경 없음.

## 비고

- 선행 series (Stock #199 / Order #200 / Payment #202) 가 이미 API 응답 계약을 유지한 채 cross-aggregate 객체 참조를 해제했고, 본 태스크는 그 코드 상태와 schema 정합성만 회복한다. 따라서 frontend 영향 0건.
