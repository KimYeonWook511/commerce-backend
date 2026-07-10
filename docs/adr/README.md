# 레거시 ADR 매핑

2026-07 이전의 결정들은 단일 파일 `docs/adr.md`에 `ADR-001`~`ADR-068` 순차 번호로 누적되어 있었고,
결정별 파일 체계로 마이그레이션하면서 번호를 폐기했다. 동결된 과거 문서(`docs/tasks/**`)나 git history에서
`ADR-NNN` 번호를 만나면 아래 표로 해당 파일을 찾는다.

`ADR-002 (갱신)` 행은 원래 ADR-002 본문 안의 갱신 소절이었던 결정을 별도 파일로 분리한 것이다.

| 구 번호 | 파일 | 결정 (원제목) |
| --- | --- | --- |
| ADR-001 | [20260422-pr59-jwt-redis-auth.md](20260422-pr59-jwt-redis-auth.md) | JWT + Redis 기반 인증 유지 |
| ADR-002 | [20260422-pr59-order-idempotency-key.md](20260422-pr59-order-idempotency-key.md) | 주문 생성에 멱등 키 적용 (Redis 1차 방어선 + RDB unique 제약 최종 보장) |
| ADR-002 (갱신) | [20260601-pr180-order-idempotency-inflight-guard.md](20260601-pr180-order-idempotency-inflight-guard.md) | ADR-002 갱신: Redis in-flight 차단 + DB unique 제약 최종 보장 |
| ADR-003 | [20260422-pr59-stock-pessimistic-lock.md](20260422-pr59-stock-pessimistic-lock.md) | 재고 차감 기본 전략으로 비관적 락 사용 |
| ADR-004 | [20260501-pr68-stock-admin-history-separation.md](20260501-pr68-stock-admin-history-separation.md) | 관리자 재고 관리와 변경 이력 분리 |
| ADR-005 | [20260514-pr91-cache-after-commit.md](20260514-pr91-cache-after-commit.md) | Redis 캐싱은 RDB 커밋 이후 실행 |
| ADR-006 | [20260514-pr91-application-service-suffix.md](20260514-pr91-application-service-suffix.md) | application 계층 클래스명은 Service suffix를 사용한다 *(superseded: ADR-054)* |
| ADR-007 | [20260515-pr97-token-store-failure-strict.md](20260515-pr97-token-store-failure-strict.md) | 인증 토큰 Redis 저장 실패 정책 — strict |
| ADR-008 | [20260515-pr97-signup-tx-not-supported.md](20260515-pr97-signup-tx-not-supported.md) | 회원가입 트랜잭션 분리 — `Propagation.NOT_SUPPORTED` |
| ADR-009 | [20260515-pr97-refresh-token-delete-removal.md](20260515-pr97-refresh-token-delete-removal.md) | `RefreshTokenStore.delete()` 제거 |
| ADR-010 | [20260518-pr101-payment-amount-mismatch-reject.md](20260518-pr101-payment-amount-mismatch-reject.md) | PaymentAttempt 멱등 재요청 amount mismatch는 명시적 예외로 거부 |
| ADR-011 | [20260519-pr109-db-unique-find-first.md](20260519-pr109-db-unique-find-first.md) | DB unique 위반은 안전망 500 으로 위임하고 정상 흐름은 사전 `find` 로 처리한다 (find-first 패턴 통일) |
| ADR-012 | [20260519-pr112-payment-state-transition-domain.md](20260519-pr112-payment-state-transition-domain.md) | PaymentAttempt succeed/fail 메서드는 상태 전이를 도메인에서 검증한다 |
| ADR-013 | [20260519-pr113-compensation-second-exception-logging.md](20260519-pr113-compensation-second-exception-logging.md) | 보상 catch 2차 예외 처리는 1차 예외 ERROR 로깅 + 의도 캡슐화 메서드 패턴을 따른다 |
| ADR-014 | [20260520-pr118-compensation-by-payment-existence.md](20260520-pr118-compensation-by-payment-existence.md) | 보상 진행 여부는 Payment 엔티티 존재 여부로 판단한다 |
| ADR-015 | [20260520-pr125-compensation-policy-ownership.md](20260520-pr125-compensation-policy-ownership.md) | 보상 정책은 payment.application 책임이고, PG 어댑터는 cancel 콜백만 제공한다 |
| ADR-016 | [20260523-pr141-k6-load-testing.md](20260523-pr141-k6-load-testing.md) | 부하 테스트 도구로 k6 + InfluxDB + Grafana 채택 |
| ADR-017 | [20260525-pr149-kafka-traceid-interceptors.md](20260525-pr149-kafka-traceid-interceptors.md) | Kafka traceId 전파는 ProducerInterceptor + RecordInterceptor 조합으로 구현한다 |
| ADR-018 | [20260526-pr155-hibernate-enum-jdbc-type.md](20260526-pr155-hibernate-enum-jdbc-type.md) | Hibernate 6.x ENUM 매핑은 `@JdbcTypeCode(SqlTypes.VARCHAR)`로 회피한다 |
| ADR-019 | [20260527-pr157-async-traceid-explicit.md](20260527-pr157-async-traceid-explicit.md) | 비동기/이벤트 경계 traceId 전파는 명시적 동봉 방식으로 구현한다 |
| ADR-020 | [20260529-pr166-cross-aggregate-id-reference.md](20260529-pr166-cross-aggregate-id-reference.md) | 신규 도메인의 cross-aggregate 참조는 ID로 한다 |
| ADR-021 | [20260529-pr166-method-level-transactional.md](20260529-pr166-method-level-transactional.md) | 응용 Service의 `@Transactional`은 method-level에만 부착한다 |
| ADR-022 | [20260529-pr166-explicit-persistence-call.md](20260529-pr166-explicit-persistence-call.md) | 응용 계층은 영속화 호출을 명시적으로 표현한다 |
| ADR-023 | [20260601-pr179-unique-column-length.md](20260601-pr179-unique-column-length.md) | multi-column unique constraint 대상 컬럼은 `@Column(length=...)`을 명시한다 |
| ADR-024 | [20260602-pr184-flyway-migration.md](20260602-pr184-flyway-migration.md) | DB 스키마 마이그레이션 도구로 Flyway 도입 (ddl-auto: validate 전환) |
| ADR-025 | [20260602-pr184-no-enum-check-constraint.md](20260602-pr184-no-enum-check-constraint.md) | enum 컬럼의 DB CHECK 제약을 두지 않는다 |
| ADR-026 | [20260605-pr205-payment-order-boundary-redesign.md](20260605-pr205-payment-order-boundary-redesign.md) | 결제 도메인 재설계 — Order↔Payment 경계 분리 + RESERVE 별도 거주지 (B안) |
| ADR-027 | [20260607-pr218-pg-exception-unknown-preserve.md](20260607-pr218-pg-exception-unknown-preserve.md) | PG 호출 예외는 "요청 전송 시점"을 경계로 전파(가시화) / UNKNOWN 보존(이중결제 방어)을 가른다 |
| ADR-028 | [20260607-pr220-unknown-already-complete-extension.md](20260607-pr220-unknown-already-complete-extension.md) | ADR-027의 "결과 불명 → UNKNOWN 보존"을 AlreadyComplete history 재확인·cancel 경로로 확장한다 |
| ADR-029 | [20260608-pr224-postprocess-status-based.md](20260608-pr224-postprocess-status-based.md) | 결제 후처리 대상 식별을 failCode 열거에서 status(UNKNOWN/stale REQUESTED) 중심으로 전환한다 |
| ADR-030 | [20260608-pr224-reconcile-threshold-manual.md](20260608-pr224-reconcile-threshold-manual.md) | 대사 시작 임계를 NaverPay 승인 가능 시간(10분)에서 파생하고 UNKNOWN/REQUESTED 를 분리하며 장기 미해소는 MANUAL 로 승급한다 |
| ADR-031 | [20260608-pr224-manual-review-isolation.md](20260608-pr224-manual-review-isolation.md) | mismatch·자동 포기·대사 장기 미해소를 MANUAL_REVIEW 로 격리하고 RelatedOrderStatus 를 제거한다 |
| ADR-032 | [20260608-pr226-approve-record-failure-complete-first.md](20260608-pr226-approve-record-failure-complete-first.md) | 정상 승인 후 transient 기록 실패는 환불하지 않고 REQUESTED 로 두어 reconcile 이 완료시킨다(완료 우선) |
| ADR-033 | [20260608-pr226-duplicate-payment-adapter-mapping.md](20260608-pr226-duplicate-payment-adapter-mapping.md) | 이중결제 탐지를 application raw DAO catch 에서 adapter 도메인 예외 매핑으로 전환한다(ADR-011 carve-out) |
| ADR-034 | [20260609-pr228-constraint-name-identification.md](20260609-pr228-constraint-name-identification.md) | `SQLErrorCodeSQLExceptionTranslator` 빈을 제거하고 제약 위반 식별을 `getConstraintName()` 기반으로 전환한다 |
| ADR-035 | [20260609-pr233-compensation-unconditional-cancel.md](20260609-pr233-compensation-unconditional-cancel.md) | 보상 완료 가드(`hasCompletedPayment`)를 제거하고 보상 대상 pgPaymentId 를 무조건 취소한다 |
| ADR-036 | [20260609-pr235-reservation-optimistic-lock.md](20260609-pr235-reservation-optimistic-lock.md) | reservation 동시 이중 use 가드를 `@Version` 낙관적 락으로 구현한다 |
| ADR-037 | [20260609-pr235-approval-success-precheck.md](20260609-pr235-approval-success-precheck.md) | 승인 진입에 주문 기준 성공결제 사전 차단을 추가한다 |
| ADR-038 | [20260609-pr235-reservation-lookup-unification.md](20260609-pr235-reservation-lookup-unification.md) | reservation 조회를 `(memberId, merchantPayKey)` 로 단일화하고 예약 미발견을 `PAYMENT_RESERVATION_NOT_FOUND` 로 응답한다 |
| ADR-039 | [20260609-pr236-compensated-approve-stays-failed.md](20260609-pr236-compensated-approve-stays-failed.md) | 보상된 APPROVE 결제 상태는 FAILED 로 유지하고 새 상태 도입은 의도적으로 미룬다 |
| ADR-040 | [20260610-pr237-reconcile-scheduled-loop.md](20260610-pr237-reconcile-scheduled-loop.md) | UNKNOWN/stale REQUESTED 대사를 `@Scheduled` 서비스 루프로 구현한다 |
| ADR-041 | [20260610-pr237-no-distributed-lock-reconcile.md](20260610-pr237-no-distributed-lock-reconcile.md) | 대사 배치에 분산 락을 이번엔 도입하지 않는다 |
| ADR-042 | [20260610-pr237-order-expiry-exclude-pending-payment.md](20260610-pr237-order-expiry-exclude-pending-payment.md) | 주문 만료 배치는 미확정 결제가 걸린 주문을 만료 대상에서 제외한다 |
| ADR-043 | [20260610-pr237-reconcile-refund-canceled-order.md](20260610-pr237-reconcile-refund-canceled-order.md) | 대사가 승인 확정한 결제의 주문이 이미 취소됐으면 보상 환불한다 |
| ADR-044 | [20260610-pr237-no-manual-review-status.md](20260610-pr237-no-manual-review-status.md) | 대사 종착에 새 결제 상태(MANUAL_REVIEW)를 도입하지 않고 ADR-039를 따른다 |
| ADR-045 | [20260610-pr237-notification-port-abstraction.md](20260610-pr237-notification-port-abstraction.md) | 대사·보상 통지는 NotificationPort 추상화로 두고 채널 adapter는 후속으로 분리한다 |
| ADR-046 | [20260610-pr237-postprocess-policy-promotion.md](20260610-pr237-postprocess-policy-promotion.md) | 후처리 결정 정책을 테스트 코드에서 main 코드로 승격한다 |
| ADR-047 | [20260610-pr237-escalation-time-window.md](20260610-pr237-escalation-time-window.md) | escalation은 새 상태 대신 대사 스캔 시간 윈도우 상한으로 자동 제외한다 |
| ADR-048 | [20260610-pr237-non-init-order-terminal-transition.md](20260610-pr237-non-init-order-terminal-transition.md) | 대사 중 주문이 비-INIT이면 건너뛰지 않고 종착 상태로 전이한다 |
| ADR-049 | [20260611-pr242-escalation-orthogonal-field.md](20260611-pr242-escalation-orthogonal-field.md) | escalation 종착·통지를 새 상태 대신 escalatedAt 직교 필드로 표현한다 |
| ADR-050 | [20260612-pr245-payment-optimistic-lock.md](20260612-pr245-payment-optimistic-lock.md) | Payment에 @Version 낙관 락을 도입해 같은 행 동시 전이 lost update를 막는다 |
| ADR-051 | [20260612-pr245-optimistic-conflict-skip-outside-tx.md](20260612-pr245-optimistic-conflict-skip-outside-tx.md) | 낙관 락 충돌을 transition은 tx 안에서 전파하고 useCase는 tx 밖에서 skip한다 |
| ADR-052 | [20260612-pr245-escalation-idempotency-version.md](20260612-pr245-escalation-idempotency-version.md) | escalation 멱등을 조건부 UPDATE에서 @Version + escalate() 도메인 메서드로 환원한다 |
| ADR-053 | [20260614-pr247-batch-dao-exception-archunit-exception.md](20260614-pr247-batch-dao-exception-archunit-exception.md) | Spring Batch fault-tolerance의 DAO 예외 참조를 ArchUnit 규칙 예외처로 인정한다 |
| ADR-054 | [20260614-pr248-application-role-suffix.md](20260614-pr248-application-role-suffix.md) | application 계층은 역할별 접미사·빈 애너테이션으로 흐름과 tx 단위작업을 가른다 (ADR-006 supersede) |
| ADR-055 | [20260614-pr248-no-class-level-transactional.md](20260614-pr248-no-class-level-transactional.md) | application class-level @Transactional을 전 도메인에서 폐지한다 (ADR-021 적용 범위 확장) |
| ADR-056 | [20260618-pr258-refund-intent-single-tx.md](20260618-pr258-refund-intent-single-tx.md) | PAID 주문 취소의 환불 의도를 주문 취소와 단일 tx로 영속화한다 |
| ADR-057 | [20260618-pr258-user-refund-cancel-record.md](20260618-pr258-user-refund-cancel-record.md) | 사용자 주도 환불은 approve 결제를 FAILED로 만들지 않고 CANCEL 레코드로만 표현한다 |
| ADR-058 | [20260618-pr258-cancel-response-accepted-basis.md](20260618-pr258-cancel-response-accepted-basis.md) | 취소 응답은 취소 접수 시점에서 끊고 PG 환불 결과는 best-effort로 담는다 |
| ADR-059 | [20260618-pr258-standalone-cancel-reconcile.md](20260618-pr258-standalone-cancel-reconcile.md) | standalone CANCEL 결제 대사를 신설해 환불을 보장하고, FAILED는 escalation으로 surface한다 |
| ADR-060 | [20260618-pr258-cancel-idempotency-unique.md](20260618-pr258-cancel-idempotency-unique.md) | CANCEL 생성 멱등은 기존 `(merchantPayKey, provider, pgPaymentId, type)` unique로 하드 보장된다 |
| ADR-061 | [20260618-pr258-paid-cancel-row-lock-split.md](20260618-pr258-paid-cancel-row-lock-split.md) | PAID 취소의 주문 락은 fetch join 단일 쿼리 대신 단일 행 락 + 아이템 별도 로드로 분리한다 |
| ADR-062 | [20260618-pr262-payment-approval-facade.md](20260618-pr262-payment-approval-facade.md) | 결제 승인 확정 조율을 provider 중립 facade로 모으고 결제→주문 단방향 결합으로 정리한다 |
| ADR-063 | [20260618-pr262-complete-payment-error-codes.md](20260618-pr262-complete-payment-error-codes.md) | order.completePayment 거부 사유를 errorCode로 세분화해 주문 상태 재조회 분기를 제거한다 |
| ADR-064 | [20260618-pr262-paid-non-dup-notify-fail.md](20260618-pr262-paid-non-dup-notify-fail.md) | PAID 성공-주체 확정 분기를 제거하고 비중복 PAID는 통지+fail로 둔다 (ADR-048 supersede) |
| ADR-065 | [20260618-pr262-no-gateway-resolver.md](20260618-pr262-no-gateway-resolver.md) | gateway resolver·공통 승인 진입 UseCase는 이번에 도입하지 않는다 |
| ADR-066 | [20260619-pr263-reconcile-backoff-field.md](20260619-pr263-reconcile-backoff-field.md) | 대사 재조회 backoff를 status-직교 `next_reconcile_at` 필드 + 스캔 게이트로 구현한다 |
| ADR-067 | [20260619-pr263-reconcile-backoff-fixed-interval.md](20260619-pr263-reconcile-backoff-fixed-interval.md) | 대사 재조회 backoff 간격은 단일 고정 값으로 둔다 (지수 backoff 미도입) |
| ADR-068 | [20260619-pr263-backoff-write-wait-only.md](20260619-pr263-backoff-write-wait-only.md) | backoff write는 wait로 끝나는 분기에만 적용하고, 상태 확정 경로는 자기 cadence를 따른다 |
