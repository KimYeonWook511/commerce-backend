# Step 5: write-retrospective

## 읽어야 할 파일

- 본 task의 모든 문서:
  - `/docs/tasks/payment-attempt-unique-key-length/prd.md`
  - `/docs/tasks/payment-attempt-unique-key-length/architecture.md`
  - `/docs/tasks/payment-attempt-unique-key-length/adr.md`
  - `/docs/tasks/payment-attempt-unique-key-length/db-schema.md`
- 이전 step 산출물 전체.

## 작업

`docs/tasks/payment-attempt-unique-key-length/retrospective.md`를 작성한다. 다음 섹션을 포함한다.

### 발견 경위

- `order-idempotency-cache-simplification` task 진행 중 `./gradlew dockerTest` AC 실행에서 `NaverPayServiceConcurrencyTest` 7/8 실패 발견.
- develop HEAD 깨끗한 상태에서도 동일 재현되어 order 변경과 무관한 payment 도메인 기존 결함으로 분리 인지.

### 진단 흐름

- 최초 가설: race window 발생 → 사전 `find`가 2건 반환 (이슈 #176 초기 본문).
- 검증 과정: 단일 테스트만 분리 실행 + Hibernate DDL 로그 dump → schema 생성 단계의 WARN 로그 발견.
  ```
  Specified key was too long; max key length is 3072 bytes
  ```
- 진단 정정: race window가 아니라 `tbl_payment_attempt`의 unique constraint가 처음부터 schema에 적용되지 않은 상태였음.

### 근본 원인

- `VARCHAR(255)` × 4컬럼 × utf8mb4(4byte) = 4080 bytes > InnoDB 한도 3072 bytes.
- Hibernate 기본 핸들러 `ExceptionHandlerLoggedImpl`이 WARN으로만 로그하고 부팅을 계속해 schema에 unique가 없는 채로 운영.

### 해결

- `PaymentAttempt`의 4개 컬럼 length 명시 (64/64/32/32, 합 768 bytes).
- `hibernate.hbm2ddl.halt_on_error: true`를 test + local에 적용 (prod는 Flyway 도입과 함께 처리).
- `NaverPayServiceConcurrencyTest`의 단언 이중화 (count==1 데이터 invariant + `DataIntegrityViolationException` anyMatch 행동 invariant).
- `dockerTest`에 `excludeTags "concurrency"` 한 줄 추가 (tag 차원 자체 정리는 이슈 #177로 분리).

### 학습

- `@Column(length=...)`을 명시하지 않으면 multi-column unique constraint에서 silent하게 schema 생성이 실패할 수 있다.
- ddl-auto의 schema 에러는 기본적으로 silent 처리된다. `halt_on_error`가 없으면 운영 schema 정합성이 깨진 채 계속 작동할 수 있다.
- ADR-011 같은 "DB 안전망 위임" 정책은 그 안전망이 실제로 작동하는지 테스트로 가시화해야 한다. count invariant는 그 가시화의 한 형태.
- 이슈 본문의 초기 가설은 디버깅 진행에 따라 정정될 수 있다. 잘못된 가설을 그대로 두면 다음 작업자가 같은 함정을 반복하므로 진단 정정 노트를 본문 상단에 명시했다.

### 향후 트랙

- Flyway 도입 시 prod schema 정합성 점검은 그 흐름에서 처리한다.
- `@Tag` 차원 정리는 이슈 #177에서 진행한다.
- 신규 multi-column unique 도입 시 본 task의 ADR을 참고해 length를 산정한다.

## Acceptance Criteria

```bash
./gradlew test
```

문서 변경뿐인 sanity check.

## 검증 절차

1. AC 명령을 실행한다.
2. `retrospective.md`가 위 섹션 구조에 맞게 작성되었는지 확인한다.
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 다른 task의 retrospective 문서를 함께 수정하지 마라. 이유: 회고 문서는 사후 수정 금지.
- task 문서(prd, architecture, adr, db-schema)를 다시 수정하지 마라. 이유: 회고 작성 단계는 history 기록 단계이지 task 본문 갱신 단계가 아니다.
- 루트 docs를 추가로 갱신하지 마라. 이유: 루트 docs 동기화는 step 4에서 완료되었어야 한다.
