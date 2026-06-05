# Step 4: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 ADR 컨벤션, 두 사고의 정확한 인용, root docs 갱신 범위를 파악하라:

- `/docs/tasks/flyway-introduction/prd.md`
- `/docs/tasks/flyway-introduction/architecture.md`
- `/docs/tasks/flyway-introduction/adr.md`
- `/docs/adr.md` ← 기존 ADR-001~023 형식과 마지막 번호 확인
- `/docs/db-schema.md` ← 마이그레이션 안내를 추가할 위치
- `/docs/tasks/hibernate-enum-jdbc-type-code/adr.md`
- `/docs/tasks/hibernate-enum-jdbc-type-code/retrospective.md` (있다면)
- `/docs/tasks/payment-attempt-unique-key-length/adr.md`
- `/docs/tasks/payment-attempt-unique-key-length/retrospective.md` (있다면)

## 작업

### 변경 1: `docs/adr.md`에 ADR-024 신규 추가

기존 마지막 ADR(ADR-023) 다음에 `### ADR-024: ...` 형식으로 추가한다. 기존 ADR-001~023의 `결정 / 배경 / 이유 / 트레이드오프` 구조를 따른다.

본문은 단순 한 단락이 아니라 아래 구성을 그대로 풀어 쓴다. **Flyway의 일반적 장점 나열이 아니라, 그동안 도입을 미뤄온 이유와 두 사고가 그 입장을 뒤집은 과정을 중심에 둔다.**

#### ADR-024: DB 스키마 마이그레이션 도구로 Flyway 도입 (ddl-auto: validate 전환)

##### 결정
- 의존성으로 `flyway-core` + `flyway-mysql` 추가 (Spring Boot 3.5 BOM 관리)
- 운영/로컬/dockerTest는 `spring.jpa.hibernate.ddl-auto: validate` + Flyway 활성
- test(H2)는 H2 + create-drop 유지, Flyway 비활성
- 기존 스키마는 현 엔티티 기준 `src/main/resources/db/migration/V1__init.sql`로 단일 베이스라인, baseline-on-migrate 비활성
- Spring Batch 메타테이블은 Flyway 관리 대상에서 제외하고 기존 `initialize-schema: always` 유지
- 운영 안전망으로 `spring.flyway.clean-disabled: true` 명시

##### 배경
**그동안 도입을 미뤄온 입장.** DB는 단일 MySQL 하나뿐이고 다중 DB 운영 계획도 없다. 이 상황에서 Flyway는 "지금 당장 필요하지 않은 운영 복잡성"이라고 판단해 왔다. JPA `ddl-auto: update`로 충분하다는 입장을 유지했고, `application-prod.yml`의 주석 "추후 DB 마이그레이션 학습 후 validate로 변경할 것"은 이 입장의 흔적이다. 도입의 일반적 정당성(스키마 변경 이력, 환경 간 일관성, 위험한 변경 통제)은 이미 알고 있지만, 비용 대비 우선순위가 낮다고 봐 왔다.

**입장을 뒤집은 두 사고.**

**사고 1 — Hibernate 6 dialect 변경에 의한 ENUM silent drift (이슈 #142, ADR-018, 2026-05-26).** Hibernate 6.x부터 `@Enumerated(STRING)`이 MySQL native `ENUM` 타입으로 매핑되도록 동작이 바뀌었다. MySQL ENUM은 NOT NULL 제약을 첫 번째 값 자동 삽입으로 회피한다. `ddl-auto: update`로 NOT NULL ENUM 컬럼이 추가될 때 기존 row가 의도하지 않은 첫 번째 값(예: `OutboxEventStatus.PENDING`)으로 묻혔다. ADR-018 회고 인용: "Hibernate dialect 변경은 '조용한' 결함을 만든다. ENUM 매핑은 NOT NULL 위반을 첫 번째 값 자동 삽입으로 회피하므로, 코드 레벨에서는 정상으로 보이지만 데이터 레벨에서는 의도하지 않은 값이 묻힌다." 코드 변경(`@JdbcTypeCode(SqlTypes.VARCHAR)` 적용)으로 신규 컬럼은 막을 수 있지만, **기존 운영 DB의 ENUM 컬럼이 VARCHAR로 자동 ALTER된다는 보장이 없다.** ADR-018 회고: "본 코드 변경만으로 운영 DB의 기존 ENUM 컬럼이 자동 ALTER되지 않을 가능성이 있다. Hibernate `ddl-auto: update`는 새 컬럼 추가는 자동 수행하지만, 기존 컬럼의 타입 변경(ENUM → VARCHAR)은 보장하지 않기 때문이다."

**사고 2 — multi-column unique constraint silent 미적용 (이슈 #176, PR #179, ADR-023, 2026-05-31).** `NaverPayServiceConcurrencyTest` 8개 중 7개가 `IncorrectResultSizeDataAccessException: 2 results were returned`로 실패. 초기 가설은 race window였으나 단일 테스트 실행 + Hibernate DDL 로그 dump 결과 `Specified key was too long; max key length is 3072 bytes` WARN이 발견. 근본 원인은 `tbl_payment_attempt`의 4-column unique key `uk_payment_attempt_merchant_pay_key_provider_payment_id_type`의 컬럼들이 `@Column(length=...)` 미지정 → 모두 VARCHAR(255) → utf8mb4 환경에서 4080 bytes로 InnoDB 한도 3072 bytes 초과. MySQL이 unique key 생성을 거부했지만 **Hibernate 기본 핸들러는 WARN으로만 로그하고 부팅을 계속해서**, 스키마에 unique가 빠진 채 운영돼 왔다. 동시성 테스트의 우연한 타이밍에서만 발견. payment-attempt-unique-key-length 회고 인용: "`@Column(length=...)`을 명시하지 않으면 multi-column unique constraint에서 silent하게 schema 생성이 실패할 수 있다. ddl-auto의 schema 에러는 기본적으로 silent 처리된다. `halt_on_error`가 없으면 운영 schema 정합성이 깨진 채 계속 작동할 수 있다."

**두 사고의 공통 패턴.** 둘 다 *코드는 정상으로 보이지만 실제 DB schema가 silent하게 어긋난 상태*가 문제의 본질이다. `ddl-auto: update`는 (a) 컬럼 타입 변경 같은 일부 변경을 누락하고, (b) schema 변경 실패를 WARN으로만 처리한다. 단일 DB 운영이라는 단순함은 도입 미루기의 근거였지만, **단일 DB라도 schema drift는 발생한다**는 것을 두 사고가 같은 패턴으로 보여줬다. drift 원인이 외부 시스템 분기가 아니라 Hibernate dialect 변경 / silent fail이라는 코드 내부 요인이라는 점이 결정적이다.

**시점 선택.** 운영 DB 미가동이라는 시간적 우위가 있다. V1 단일 스크립트로 출발하면 baseline-on-migrate, 운영 dump → baseline 작성 → checksum 검증 같은 복잡한 도입 절차가 모두 불필요하다.

##### 이유 (대안 비교)
- **대안 A: `ddl-auto: update` 유지** — 두 사고에서 드러난 silent drift 문제를 그대로 안고 가는 선택. 운영 가동 후 같은 패턴이 또 발생할 가능성이 코드 변화량에 비례한다.
- **대안 B: `ddl-auto: validate`만 적용하고 마이그레이션은 손으로 SQL 관리** — validate는 컬럼/타입 검사를 한다. 사고 1(ENUM 타입 변경 누락)은 부팅 실패로 잡힌다. 그러나 적용 순서/이력 관리가 코드 외부로 새고, 환경 간 일관성은 사람 기억에 의존한다. 사고 2 유형(unique 누락)은 validate가 unique constraint를 검사하지 않으므로 여전히 못 잡는다 — 코드 규칙(ADR-023)에 의존해야 한다.
- **대안 C: Liquibase** — XML/YAML/JSON DSL 추상화. MySQL/JPA 단일 스택의 본 프로젝트에서 추상화 가치 제한적이고, SQL을 그대로 다루는 게 디버깅/리뷰에 유리.
- **선택: Flyway** — SQL을 그대로 버전 관리, Spring Boot Auto-configuration 내장. ddl-auto의 silent drift 패턴을 (a) validate로 컬럼/타입 누락 가시화, (b) 명시적 스크립트로 변경 의도 코드화, (c) `flyway_schema_history`로 환경 간 일관성 추적, 세 축으로 해소한다.

##### 운영/테스트 적용 방식
- 로컬/운영: `validate`로 전환하여 엔티티-스키마 불일치를 부팅 실패로 즉시 가시화.
- test(H2): 그대로. 단위/슬라이스 테스트의 부팅 속도 자산이고 Flyway 스크립트는 MySQL 문법이라 H2에 직접 적용 불가.
- dockerTest(Testcontainers MySQL): Flyway 활성. 컨테이너 싱글톤 재사용 + 컨텍스트 캐싱 + `deleteAllInBatch()` 격리 모델이 자연스럽게 맞물린다. `application-test.yml`의 `flyway.enabled: false`는 `TestcontainersSupport`의 dynamic property로 `true` override해 무효화.
- Spring Batch 메타테이블: Flyway 관리 제외. Batch 자체 `initialize-schema` 유지. Spring Batch 버전업 시 마이그레이션 책임이 프로젝트로 옮겨오는 비용 회피.

##### 트레이드오프
- **운영 복잡성 증가 — 인정한 비용**: 도입 미뤄온 가장 큰 이유였던 "운영 복잡성"이 실제로 늘어난다. 엔티티 변경 시 마이그레이션 스크립트를 같은 PR에서 함께 작성해야 하고, 로컬에서 엔티티만 수정하고 부팅하면 실패한다. 두 사고에서 드러난 silent drift 비용보다 이 복잡성 비용이 작다고 판단해 수용한다.
- **validate가 모든 drift를 잡지는 못한다**: validate는 컬럼/타입 누락은 잡지만 unique constraint 누락, 인덱스 누락은 검사하지 않는다. 사고 2 유형은 Flyway 도입 후에도 ADR-023 같은 코드 규칙으로 1차 방어한다. Flyway는 "변경 이력이 명시적이라 리뷰에서 잡힐 가능성을 높인다"는 간접 효과로만 기여.
- **Flyway 10 추적 부담**: Flyway 10에서 DB별 모듈 분리(`flyway-mysql`), 일부 deprecated API, license 정책 변경. 메이저 업그레이드 시 release note 확인 책임이 추가된다.
- **test 프로파일 회귀 미검증**: H2 + Flyway 비활성이라 마이그레이션 스크립트 자체의 회귀는 dockerTest에서만 검증된다. CI의 `ciTest`가 dockerTest를 포함하는지 확인 필요. 미포함 시 별도 후속.

##### 연계 ADR / 이슈
- ADR-018 (Hibernate 6.x ENUM 매핑 `@JdbcTypeCode(SqlTypes.VARCHAR)`) — 사고 1 직접 연계
- ADR-023 (multi-column unique constraint 컬럼 길이 명시) — 사고 2 직접 연계, Flyway 도입 후에도 유효한 코드 규칙
- 이슈 #142, #176, PR #179 — 두 사고의 원자료

---

색인 표(상단 Task ADR 색인)는 본 ADR-024가 task-local이 아닌 root cross-cutting이라 갱신하지 않는다.

### 변경 2: `docs/db-schema.md` 상단 안내 추가

문서 상단(현재 `## 네이밍 규칙` 위 또는 그 아래)에 마이그레이션 위치/네이밍 안내 섹션을 한 단락 추가한다.

```markdown
## 마이그레이션

DB 스키마 변경은 Flyway 마이그레이션 스크립트로 관리한다 (ADR-024).

- 위치: `src/main/resources/db/migration/`
- 네이밍: `V{번호}__{snake_case_설명}.sql`
- 본 문서는 테이블/컬럼/제약의 의도를 설명하는 reference이고, 실제 DDL은 `V*__*.sql`이 단일 출처다.
- 엔티티(@Entity) 변경 PR은 같은 PR에서 대응되는 V 스크립트를 함께 작성한다. ddl-auto: validate라 누락 시 부팅 실패.
- 적용된 V 스크립트는 수정하지 말고 새 V로 보정한다 (Flyway checksum).
```

## Acceptance Criteria

```bash
# (a) ADR-024 헤더 존재
grep -q '### ADR-024: DB 스키마 마이그레이션 도구로 Flyway 도입' docs/adr.md

# (b) ADR-024 핵심 키워드들 존재
grep -q 'silent drift' docs/adr.md
grep -q 'ADR-018' docs/adr.md
grep -q 'ADR-023' docs/adr.md
grep -q 'baseline-on-migrate' docs/adr.md
grep -q 'clean-disabled' docs/adr.md
grep -q '운영 복잡성' docs/adr.md

# (c) db-schema.md 마이그레이션 섹션
grep -q '## 마이그레이션' docs/db-schema.md
grep -q 'db/migration' docs/db-schema.md
grep -q 'ADR-024' docs/db-schema.md

# (d) 기존 ADR이 깨지지 않음
grep -q '### ADR-023:' docs/adr.md
grep -q '### ADR-001:' docs/adr.md
```

위 모든 명령이 exit 0이어야 한다.

## 검증 절차

1. `docs/adr.md`에 ADR-024 추가 후 본문이 위 구성 (`결정 / 배경 / 이유 / 트레이드오프` + 연계 ADR)을 모두 포함하는지 확인.
2. 두 사고 인용문이 ADR-018 회고와 payment-attempt-unique-key-length 회고에서 그대로 가져온 한국어 문장인지 확인. 의역하지 않는다.
3. `docs/db-schema.md` 상단 안내 추가 후 기존 본문이 망가지지 않았는지 확인.
4. Acceptance Criteria 모든 명령 exit 0 확인.

## 금지사항

- 두 사고 인용문을 의역하지 마라. 이유: 회고 원문이 결정 근거의 출처다.
- adr.md의 색인 표를 갱신하지 마라. 이유: ADR-024는 task-local이 아닌 cross-cutting root ADR이라 색인 대상 아님.
- 기존 ADR-001~023 본문을 수정하지 마라. 이유: 회고/결정 문서는 사후 소급 수정 안 함.
- `docs/tasks/hibernate-enum-jdbc-type-code/` 및 `docs/tasks/payment-attempt-unique-key-length/` 안의 문서를 수정하지 마라. 이유: 과거 task 회고는 immutable.
- `docs/architecture.md`, `docs/prd.md`를 갱신하지 마라. 이유: 본 task는 그 문서들에 영향을 주지 않는다 (architecture는 변경 없고, PRD는 기능 변경 없음).
