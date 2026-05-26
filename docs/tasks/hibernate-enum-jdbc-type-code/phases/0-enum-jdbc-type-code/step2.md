# Step 2: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 프로젝트의 설계 의도를 파악하라:

- `/docs/tasks/hibernate-enum-jdbc-type-code/prd.md`
- `/docs/tasks/hibernate-enum-jdbc-type-code/adr.md`
- `/docs/ADR.md` (기존 ADR 톤·형식 확인)

이전 step에서 변경된 entity 파일도 함께 확인하여 ADR 본문에 정확히 반영한다.

## 작업

`docs/ADR.md` 끝에 `ADR-018` 항목을 추가한다. 기존 ADR(`ADR-001` ~ `ADR-017`)의 헤더·문체·구조를 그대로 따른다.

### 추가할 ADR 항목 (참고 구조)

```markdown
### ADR-018: Hibernate 6.x ENUM 매핑은 @JdbcTypeCode(SqlTypes.VARCHAR)로 회피한다

- **결정**: 모든 entity의 `@Enumerated(EnumType.STRING)` 필드에 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 함께 부착한다. 컬럼 길이는 명시하지 않고 Hibernate 기본값(VARCHAR(255))을 사용한다. 신규로 추가되는 entity의 `@Enumerated(EnumType.STRING)` 필드도 동일 패턴을 따른다.
- **배경**: Hibernate 6.x부터 MySQL dialect가 `@Enumerated(EnumType.STRING)`을 VARCHAR가 아닌 MySQL ENUM 타입으로 매핑한다. MySQL ENUM은 INSERT 시 컬럼 생략 시 첫 번째 ENUM 값이 조용히 삽입되며, `ddl-auto: update`로 컬럼 추가 시 기존 row에 첫 번째 값이 자동 채워진다. VARCHAR였다면 NOT NULL 위반으로 즉시 드러났을 결함이 ENUM에서는 묻힌다. Hibernate 6.5 공식 마이그레이션 가이드는 `@JdbcTypeCode(SqlTypes.VARCHAR)` 또는 `@Column(columnDefinition = "varchar(N)")` 두 방식을 제시한다.
- **이유**: `@JdbcTypeCode`가 dialect-agnostic 하고 선언적이며 `@Column(length=N)`과의 분리가 가능하다. `columnDefinition`은 raw SQL fragment를 박아 dialect 변경에 fragile하다. 또한 향후 native ENUM을 채택할 때 annotation 하나만 제거하면 되는 전환 비용이 낮다. 컬럼 길이를 명시하지 않는 이유는, enum 값은 개발자가 정의한 코드 상수만 저장되어 외부 입력 길이 제한 같은 보안/검증 의미가 없고, length를 명시하면 enum 추가 시 동기화 부담만 발생하기 때문이다.
- **트레이드오프**: JPA 표준에서 벗어나 Hibernate-specific annotation을 도입한다. 다만 entity 코드는 이미 Hibernate에 결합되어 추가 부담은 미미하다.
- **한계**: Hibernate `ddl-auto: update`는 컬럼 타입 변경(ENUM → VARCHAR)을 보장하지 않는다. 본 코드 변경만으로는 운영 DB의 기존 ENUM 컬럼이 그대로 남을 가능성이 있다. 운영 DB ALTER는 Flyway 도입 시 일괄 마이그레이션 스크립트로 정리한다. ENUM 컬럼 생성 시점부터 본 fix 전까지 "첫 번째 enum 값이 조용히 삽입된" 의심 row 점검은 별도 후속 트랙이다.
- **참고**: Hibernate 6.5 Migration Guide, Hibernate Discourse "String Enum mapping for MySQL only". 상세는 `docs/tasks/hibernate-enum-jdbc-type-code/adr.md` 참조.
```

세부 어휘는 기존 ADR과 톤을 맞춰 자연스럽게 조정할 수 있다. 결정/배경/이유/트레이드오프/한계 5개 항목은 빠지지 않도록 한다.

### 손대지 않을 것

- 기존 ADR-001 ~ ADR-017 본문
- `docs/ADR.md` 머리말 (`# Architecture Decision Records`)
- 다른 root docs (`architecture.md`, `db-schema.md` 등)
- `commerce-backend/CLAUDE.md` (의도적으로 건드리지 않음, 가벼움 유지)

## Acceptance Criteria

```bash
./gradlew test
```

(문서 변경만이지만 phase 통일을 위해 test로 회귀 없는지 확인.)

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `docs/ADR.md`에 `### ADR-018:` 헤더가 추가됐는지 `rg "ADR-018" docs/ADR.md`로 확인.
   - 추가된 항목이 기존 ADR과 동일한 헤더·문체를 따르는가?
   - 다른 ADR 항목이 의도치 않게 수정되지 않았는가? (`git diff docs/ADR.md`)
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 기존 ADR-001 ~ ADR-017을 수정하지 마라. 이유: 결정 기록은 immutable. 새 결정은 새 ADR로 추가한다.
- `commerce-backend/CLAUDE.md`를 수정하지 마라. 이유: CLAUDE.md는 가볍게 유지하기로 결정됨. 컨벤션은 ADR에서 관리.
- 본 작업 범위 외의 root 문서를 수정하지 마라.
