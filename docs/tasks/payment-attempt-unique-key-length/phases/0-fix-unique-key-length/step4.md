# Step 4: sync-root-docs

## 읽어야 할 파일

- `/docs/tasks/payment-attempt-unique-key-length/prd.md`
- `/docs/tasks/payment-attempt-unique-key-length/adr.md`
- `/docs/tasks/payment-attempt-unique-key-length/db-schema.md`
- `/docs/ADR.md`
- `/docs/db-schema.md`
- `/docs/testing-conventions.md`
- 이전 step 산출물.

## 작업

루트 docs를 본 task의 결정과 정합되게 갱신한다.

### (a) `docs/ADR.md`

새 ADR 항목을 추가한다. 위치는 현재 마지막 ADR(ADR-022) 다음으로, 다음 형식의 한 항목을 추가한다. ADR 번호는 현재 색인의 다음 번호(ADR-023 또는 그 시점의 다음 번호)를 사용한다.

```markdown
### ADR-N: multi-column unique constraint 대상 컬럼은 `@Column(length=...)`을 명시한다
- **결정**: multi-column `@UniqueConstraint`에 포함되는 String/Enum 컬럼은 `@Column(length=...)`을 명시한다. 합계 바이트가 InnoDB unique key 한도(3072 bytes)를 넘지 않도록 산정한다. 본 결정은 ADR-018("enum length 미명시")의 좁은 예외다. 함께 `hibernate.hbm2ddl.halt_on_error: true`를 `application-local.yml`에만 적용해 schema 회귀를 부팅 시점에 노출시킨다 (test/prod는 적용 제외).
- **배경**: `tbl_payment_attempt`의 4개 컬럼이 `VARCHAR(255)` 기본값으로 생성되어 utf8mb4 환경에서 4080 bytes를 차지, MySQL이 unique key 생성을 거부. Hibernate 기본 핸들러가 silent로 넘어가 schema에 unique가 없는 채 운영됨.
- **이유**: 옵션 A(대상 컬럼만 length 명시)가 ADR-018의 합리성을 일반 영역에서 유지하면서 본 사고만 좁게 해결한다. 옵션 B(전 컬럼 length 명시)는 ADR-018을 폐기해야 한다. `halt_on_error`를 test에 적용하지 않은 이유는 Testcontainer fresh MySQL 부팅 시 `ALTER TABLE ... DROP FOREIGN KEY ...`가 `IF EXISTS` 없이 실행되어 무해 실패가 발생하기 때문이며, test 환경의 회귀 감지는 PaymentAttempt concurrency 테스트의 단언 이중화로 대체한다.
- **트레이드오프**: ADR-018과 본 ADR의 좁은 예외가 공존한다. 신규 multi-column unique 도입 시 length를 계산해 명시해야 하는 인지 부담이 있다. `halt_on_error`는 local의 `ddl-auto: update` 전제에 묶이며, local ddl-auto 변경 시 함께 재검토해야 한다 (fragile dependency).
- **참고**: 상세는 `docs/tasks/payment-attempt-unique-key-length/adr.md` 참조.
```

### (b) `docs/db-schema.md`

`tbl_payment_attempt` 섹션의 INDEX 항목 아래에 비고를 보강한다. 다음 한 줄 형태로 추가한다.

```markdown
비고:
- unique key 대상 4개 컬럼(`merchant_pay_key`, `provider`, `payment_id`, `type`)은 `@Column(length=...)`을 명시한다 (각각 64/32/64/32). utf8mb4 + InnoDB unique key 한도 3072 bytes 안에 들어오도록 산정. 상세는 ADR-N(또는 그 시점의 번호) 및 `docs/tasks/payment-attempt-unique-key-length/adr.md` 참조.
```

기존에 다른 비고가 있다면 그 아래에 추가한다.

### (c) `docs/testing-conventions.md`

`hibernate.hbm2ddl.halt_on_error: true` 적용 사실을 한 줄 명시한다. 적절한 섹션(예: "테스트 환경" 또는 "schema 생성")이 있으면 그 안에, 없으면 적절한 위치에 짧은 항목으로 추가한다.

다음 내용을 포함한다:

- 적용 환경: `local`.
- 적용 안 한 환경:
  - `test` — Testcontainer fresh MySQL 부팅 시 `ALTER TABLE ... DROP FOREIGN KEY ...`가 `IF EXISTS` 없이 실행되어 발생하는 무해 실패와 충돌하므로 제외. test 환경의 schema 회귀 감지는 concurrency 테스트의 단언 이중화로 대체.
  - `prod` — 운영 미가동 + 추후 Flyway 도입과 함께 ddl-auto: validate로 전환되면 의미가 사라짐.
- 효과: ddl-auto의 schema 생성/alter 실패가 silent로 넘어가지 않고 부팅 단계에서 실패해 회귀를 즉시 노출한다.
- Fragility: local의 `halt_on_error` 적용은 `ddl-auto: update` 전제에 의존한다. local ddl-auto가 `create-drop`/`create`로 변경되면 같은 ALTER FK DROP 충돌이 재발하므로 함께 재검토해야 한다.

## Acceptance Criteria

```bash
./gradlew test
```

문서 변경뿐이라 빌드 자체에는 영향이 없지만 sanity check.

## 검증 절차

1. AC 명령을 실행한다.
2. 아래를 확인한다.
   - `docs/ADR.md`에 새 ADR 항목이 추가되었는가?
   - `docs/db-schema.md`의 `tbl_payment_attempt` 섹션 비고가 보강되었는가?
   - `docs/testing-conventions.md`에 `halt_on_error` 적용 사실과 환경 범위가 명시되었는가?
   - 다른 ADR 본문, 다른 task의 retrospective는 변경되지 않았는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- ADR-011, ADR-018 본문을 수정하지 마라. 이유: 본 task는 ADR-011 정책의 전제 복원이며, ADR-018의 일반 정책은 유지된다. 두 ADR과의 정합은 신규 ADR 본문에서 표현한다.
- 다른 task의 retrospective 문서를 수정하지 마라. 이유: 회고 문서는 역사 기록으로 사후 수정하지 않는다.
- `docs/PRD.md`, `docs/architecture.md`, `docs/api-spec.md`를 변경하지 마라. 이유: 본 task는 사용자/제품 기능을 변경하지 않는다.
