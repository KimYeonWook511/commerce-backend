# application class-level @Transactional을 전 도메인에서 폐지한다

- Status: accepted
- Date: 2026-06-14

## Context

응용 Service의 `@Transactional`을 method-level에만 부착하기로 한 기존 결정(→ PR#166)의 적용 범위를 전 도메인으로 확장하는 결정이다. 그 결정의 근거(메서드별 tx 정책이 코드 표면에 명시됨, 누락이 silent readOnly가 아니라 "tx 없음"으로 즉시 드러남)는 신설 Service뿐 아니라 기존 도메인에도 동일하게 유효하다. 기존 결정(→ PR#166)이 "기존 도메인 마이그레이션은 후속 트랙으로 분리"라고 예고한 그 후속 트랙이다.

## Decision

method-level `@Transactional` 결정(→ PR#166)의 적용 범위를 기존 도메인(Order/Stock/Auth/Member/Product/Payment) 전체로 확장한다. class-level `@Transactional(readOnly = true)` + method override 패턴을 제거하고, 조회 메서드도 `@Transactional(readOnly = true)`를 메서드마다 명시한다. ArchUnit으로 application 패키지의 class-level `@Transactional`을 금지한다.

## Consequences

메서드 수만큼 애너테이션이 반복된다(기존 결정(→ PR#166)과 동일한 트레이드오프). 의도 명세 역할이라 가독성 손실이 아니다.

관련: 회원가입 트랜잭션 분리(`Propagation.NOT_SUPPORTED`) 결정(→ PR#97).
