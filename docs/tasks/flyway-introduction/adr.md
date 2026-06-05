# 태스크 ADR

## 결정 제목

Flyway 도입 결정은 cross-cutting이므로 본 `docs/adr.md` 본문에 **ADR-024**로 기록한다. 이 task-local adr은 본 결정과 직접 연계된 task 내부 결정 한 가지만 다룬다.

## 배경

Flyway 도입은 단일 도메인이 아니라 전 환경의 스키마 관리 정책을 바꾸는 결정이라, task 범위 안에 두면 후속 task adr에서 cross-reference하기 어려워진다. ADR-002(주문 멱등성), ADR-018(ENUM 매핑), ADR-023(unique 길이 명시) 같은 cross-cutting 결정들은 모두 본 adr.md에 두는 컨벤션을 따른다.

## 결정 내용

- **본 결정은 `docs/adr.md`의 ADR-024로 기록**한다. (task adr이 아닌 root adr)
- ADR-024 본문 구성:
  - 결정
  - 배경 (그동안 미뤄온 입장 / 입장을 뒤집은 두 사고 / 공통 패턴 / 시점 선택)
  - 이유 (대안 비교 — `ddl-auto: update` 유지 / `validate`만 적용 / Liquibase / Flyway 선택)
  - 운영/테스트 적용 방식
  - 트레이드오프 (인정한 비용 / validate 한계 / Flyway 10 추적 부담 / test 회귀 미검증)
  - 연계 ADR / 이슈
- 본 task adr은 위 결정의 위치(root adr)와 연계 이유만 기록한다.

## 근거

- `docs/adr.md` 색인 본문 안내 그대로: "코드베이스 전반에 영향을 주는 cross-cutting 결정은 본 adr.md 본문에, 특정 도메인 한정 결정은 task adr에 둔다."
- Flyway 도입은 의존성, 모든 프로파일의 yml, Testcontainers 지원, 부팅 흐름까지 전 영역에 영향을 준다. cross-cutting 정의에 정확히 부합.

## 결과

- 후속 task adr에서 `(ADR-024 연계)` 형태로 Flyway 마이그레이션 요구를 cross-reference 할 수 있다.
- task-local adr은 가벼워지고, 큰 결정 컨텍스트는 root에서 단일 출처로 유지된다.
- 연계 ADR: ADR-018, ADR-023 / 연계 이슈: #142, #176 / 연계 PR: #179
