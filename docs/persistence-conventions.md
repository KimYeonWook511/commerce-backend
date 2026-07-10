# 영속성 컨벤션

JPA로 DB를 다룰 때 따르는 규칙을 정의한다. 스택은 Java 21, Spring Boot, JPA(Hibernate), MySQL(InnoDB)이다.

네 규칙은 서로를 방어한다 — 스키마 검증(3)이 못 잡는 틈을 매핑 규칙(1)과 코드 규칙(2)이 메우고, 무결성 위반의 런타임 처리(4)는 예외 전략과 이어진다.

---

## 1. enum 매핑 — `@JdbcTypeCode(SqlTypes.VARCHAR)`

`@Enumerated(EnumType.STRING)` 필드에는 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 함께 부착한다 (→ PR#155).

```java
@Enumerated(EnumType.STRING)
@JdbcTypeCode(SqlTypes.VARCHAR)
private PaymentStatus status;
```

**이유**: Hibernate 6.x의 MySQL dialect는 enum 문자열을 native `ENUM` 컬럼으로 매핑한다. 이 경우 INSERT에서 컬럼을 빠뜨려도 MySQL이 enum의 첫 값을 조용히 넣고, 기존 row도 자동으로 채워진다 — VARCHAR였다면 `NOT NULL` 위반으로 즉시 드러났을 결함이 은폐된다. `@JdbcTypeCode`로 VARCHAR 매핑을 강제해 이 함정을 피한다.

- `@JdbcTypeCode`는 dialect 무관하고 선언적이다. `columnDefinition="varchar(N)"`은 raw SQL이라 지양한다.
- enum 컬럼 길이는 명시하지 않는다(Hibernate 기본 `VARCHAR(255)`). enum은 코드 상수만 저장하므로 길이 검증의 의미가 없다. 단 규칙 2(multi-column unique)에 포함되는 enum 컬럼은 예외다.
- enum 값에 DB CHECK 제약은 두지 않는다 (→ PR#184).

## 2. multi-column unique 컬럼 — `@Column(length=...)` 명시

multi-column `@UniqueConstraint`에 포함되는 `String`/`Enum` 컬럼은 `length`를 명시한다 (→ PR#179). 묶인 컬럼들의 바이트 합계가 InnoDB unique key 한도(3072 bytes)를 넘지 않도록 산정한다.

**이유**: 길이를 명시하지 않으면 `VARCHAR(255)`로 생성된다. utf8mb4는 한 글자가 최대 4바이트라 여러 컬럼을 unique로 묶으면 합계가 3072를 쉽게 넘는다. MySQL이 제약 생성을 거부해도 Hibernate 기본 핸들러는 경고 로그만 남기고 부팅을 계속하므로, unique 제약이 빠진 채 운영될 수 있다.

이 규칙은 규칙 1의 "enum 길이 미명시"에 대한 좁은 예외다 — unique로 묶인 enum 컬럼은 길이를 명시한다.

## 3. 스키마 마이그레이션 — Flyway + `ddl-auto: validate`

DB 스키마 변경은 Flyway 마이그레이션 스크립트로 관리한다 (→ PR#184).

- 위치: `src/main/resources/db/migration/`
- 네이밍: `V{번호}__{snake_case_설명}.sql`
- 엔티티(`@Entity`) 변경 PR은 같은 PR에서 대응되는 V 스크립트를 함께 작성한다. `ddl-auto: validate`라 누락 시 부팅 실패한다.
- 적용된 V 스크립트는 수정하지 말고 새 V로 보정한다(Flyway checksum).

**이유**: `ddl-auto: update`는 일부 변경을 누락하고 실패를 경고 로그로만 처리해 schema drift가 조용히 발생한다. `validate`는 엔티티와 실제 스키마의 컬럼·타입 불일치를 부팅 실패로 가시화한다.

**한계**: `validate`는 unique 제약·인덱스 누락은 검사하지 않으므로 규칙 2의 코드 규칙으로 1차 방어한다. enum vs varchar 타입 차이도 비교하지 않으므로 규칙 1의 `@JdbcTypeCode`로 방어한다.

## 4. DB unique 위반 처리 — find-first + 안전망

unique 제약을 다루는 작업은 "사전 조회 후 분기(find-first)"를 정상 흐름으로 삼고, unique 위반 예외는 정상 흐름에서 catch하지 않아 `GlobalExceptionHandler` 안전망(500)에 위임한다 (→ PR#109).

```
DB find → 없으면 insert → 충돌 시 안전망 500 (catch 하지 않음)
```

**이유**: application이 `DuplicateKeyException` 같은 인프라 예외 타입을 catch하면 그 계층이 `org.springframework.dao.*`에 의존하게 되어 의존 방향(domain을 향함)이 깨진다.

find-first의 적용 조건·비적용 상황(충돌이 잦으면 try-save-catch), unique 위반과 낙관 락 충돌의 처리 차이(500 vs 409), 안전망 계층 구조는 `docs/exception-strategy.md`가 단일 출처다. 낙관 락(@Version) 충돌의 tx 경계·변환·정책은 `docs/optimistic-lock-design.md`를 따른다.
