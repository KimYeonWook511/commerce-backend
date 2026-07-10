# enum 컬럼의 DB CHECK 제약을 두지 않는다

- Status: accepted
- Date: 2026-06-02

## Context

Flyway 도입 결정(→ PR#184)의 PR review 단계에서 외부 조언으로 enum CHECK 제약의 silent mismatch 함정이 제기되었다. 시나리오는 다음과 같다 — Java enum에 새 값을 추가하면 `ddl-auto: validate`는 "varchar 맞네" 하고 통과시킨다. 그러나 그 새 값으로 INSERT하는 순간 DB의 CHECK 제약에 걸려 런타임에 실패한다. 컴파일·기동 다 통과한 변경이 실제 저장에서 터지는, 정확히 Hibernate ENUM 매핑을 `@JdbcTypeCode(SqlTypes.VARCHAR)`로 회피한 결정(→ PR#155)·multi-column unique 대상 컬럼에 `@Column(length=...)`을 명시한 결정(→ PR#179)과 같은 결의 silent drift 패턴이다.

이유:

- **이중 안전망의 실용 가치 작음**: 본 프로젝트는 단일 백엔드, JPA 단일 INSERT 경로, 외부 시스템의 직접 INSERT 경로 없음. `@Enumerated(STRING)`이 application layer에서 invalid enum을 차단하므로 DB CHECK는 실질적으로 발동될 일이 없는 layer.
- **enum 진화 마찰**: 결제 fail_code(16개), 주문 status(5개), 이벤트 type 등 enum은 도메인이 자라면 종종 추가된다. CHECK를 유지하면 enum 추가마다 마이그레이션 스크립트가 필요하다.
- **silent mismatch 위험**: validate가 통과시킨 변경이 운영에서 INSERT 실패로 발견되는 디버깅 비용이 크다. 보통 운영 알람 → 롤백 흐름.
- **Hibernate가 자동 생성한다는 점**: 두는 결정도 안 두는 결정도 의식적이어야 하는데 자동이라 의식 안 됨. 본 결정은 그 자동 동작을 명시적으로 우회하는 의미.

대안 비교:

- 옵션 A (CHECK 유지): 이중 안전망. enum 추가마다 V 스크립트 부담 + silent mismatch 위험 그대로.
- 옵션 B (V1에서만 제거, 향후 자동 생성 그대로): 다음 dump 시 회귀. 운영 부담.
- **옵션 C (본 결정 — 의식적 제거 + 향후 자동 생성 차단 검토)**: 마찰 최소화 + silent drift 차단.

## Decision

`@Enumerated(STRING) + @JdbcTypeCode(SqlTypes.VARCHAR)`로 매핑되는 enum 컬럼에 대해 Hibernate가 자동 생성하는 `CHECK (column in (...))` 제약을 V1__init.sql과 이후 마이그레이션에서 모두 제거한다. enum 값의 유효성 보장은 애플리케이션 layer(Java enum 타입 시스템 + `@Enumerated(STRING)` Hibernate 매핑)에 위임한다.

## Consequences

운영 적용:

- Flyway 도입 PR(→ PR#184)에서 V1__init.sql의 모든 `*_chk_N CHECK (... in (...))` 제약을 제거했다.
- 향후 ddl-auto: create 기반 dump 시 Hibernate가 CHECK를 또 자동 생성한다. 의식적으로 제거가 필요하다.
- Hibernate 차원의 CHECK 자동 생성 차단 방법(설정 또는 엔티티 어노테이션 차원)은 후속 task에서 검토한다.

트레이드오프:

- **외부 시스템이 같은 DB에 INSERT하는 시나리오가 추가되면 본 결정을 재검토해야 한다**. 마이크로서비스 분리, BI/ETL 도구 직접 접근, 운영자 raw SQL 수정 같은 경로가 일상화되면 application layer만으로는 안전망이 부족할 수 있다.
- 본 결정이 적용되는 영역은 enum CHECK 한정이다. `NOT NULL`, `UNIQUE`, `FOREIGN KEY` 같은 다른 제약은 본 결정 대상이 아니며 각자의 도메인 의도에 따라 유지한다.

ENUM → VARCHAR 매핑 결정(→ PR#155), Flyway 도입의 silent drift 트레이드오프 섹션(→ PR#184)과 같은 결의 결정이다.
