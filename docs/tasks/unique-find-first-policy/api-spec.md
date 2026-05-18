# 태스크 API 스펙

## 개요

본 태스크는 신규 API 를 추가하지 않으며, 기존 외부 API 응답 코드 명세도 변경하지 않는다.

## 변경 없음 — 검증 근거

`docs/api-spec.md` 와 `commerce-workspace/docs/api-contract.md` 에 다음 race-window 관련 응답 코드는 **명시되어 있지 않음** (grep 결과):

- `MEMBER-409`
- `PAYMENT-409`
- `DUPLICATE_EMAIL` (4xx 응답 형태로의 명세)
- `PAYMENT_DUPLICATE` (4xx 응답 형태로의 명세)

본 태스크의 행위 변경은 모두 race window 한정이며, 외부에 공개된 API 응답 명세에는 포함되어 있지 않다. 정상 흐름의 사전 체크 4xx 응답(예: 회원 가입 시 중복 이메일 검증)은 정책 변경 후에도 그대로 보존된다.

따라서 `docs/api-spec.md` 갱신은 불필요하다.

## 워크스페이스 계약 영향

`commerce-workspace/docs/api-contract.md` 는 Frontend 가 소비하는 계약 문서다. 본 세션은 backend 서브모듈 컨텍스트이므로 직접 수정하지 않는다. 영향 평가와 갱신은 워크스페이스 CLAUDE.md 의 "계약 싱크" 역할에 따라 Frontend 세션에서 수행한다.

위 grep 결과로 보아 race-window 응답 코드가 계약에 명시되어 있지 않을 가능성이 높으나, 최종 확인은 Frontend 세션의 몫이다.

## 비고

- 새 안전망 ErrorCode `COMMON-500-2` (`DATA_ACCESS_ERROR`) 는 내부 운영 모니터링 분류용이며, 외부 API 응답 명세에는 별도 명시하지 않는다 (500 응답 자체는 모든 클라이언트가 일반적인 서버 오류로 처리해야 함).
