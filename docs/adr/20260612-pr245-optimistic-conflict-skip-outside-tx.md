# 낙관 락 충돌을 transition은 tx 안에서 전파하고 useCase는 tx 밖에서 skip한다

- Status: accepted
- Date: 2026-06-12

## Context

Payment에 `@Version` 낙관 락을 도입한 결정(→ PR#245)을 전제로 한다. 흡수를 트랜잭션 안에서 하면(같은 메서드 catch, `@Transactional(REQUIRES_NEW)` 포함) flush 충돌이 그 tx를 rollback-only로 만들어 commit 시 `UnexpectedRollbackException`이 난다. 문제의 본질은 "흡수했다"가 아니라 "흡수를 트랜잭션 안에서 했다"이다(본 task 초기 구현이 application 직접 catch + `@Transactional` 제거로 이를 위반해 재작성).

흡수를 tx 경계 밖(useCase)으로 옮기면 adapter 변환(인프라 예외 도메인 격리)·보상 단계별 독립 commit(payment-compensation-to-domain)과 모두 양립한다. 충돌은 일반 코드(`PAYMENT_CONCURRENTLY_MODIFIED` = "다른 처리가 먼저 상태를 바꿈")로 던지고 "무엇이 됐는지"는 재조회로 판정한다. unique 위반(`PAYMENT_DUPLICATE`)과 version 충돌은 정책이 달라(보상 vs skip/재시도) 한 코드로 합치지 않는다.

## Decision

충돌 처리를 두 계층으로 가른다. transition(별도 빈의 public `@Transactional`: `find → 도메인 전이 → saveChecked`)은 충돌·가드 위반을 catch하지 않고 도메인 예외로 전파해 트랜잭션을 깨끗이 rollback시킨다. useCase(orchestrator, **트랜잭션 없음**)는 private 래퍼에서 skip 대상 도메인 예외(`PAYMENT_CONCURRENTLY_MODIFIED`/`PAYMENT_STATUS_TRANSITION_NOT_ALLOWED`/`PAYMENT_RECORD_NOT_FOUND`)를 트랜잭션 경계 밖에서 흡수한다. DAO 예외(`ObjectOptimisticLockingFailureException`)는 application/domain이 직접 다루지 않고 adapter `saveChecked`(`saveAndFlush`)가 `PAYMENT_CONCURRENTLY_MODIFIED`로 변환한다. `succeed`·무조건 `fail`(APPROVE 종착)은 skip하지 않고 전파해 `OptimisticLockingFailureException → 409` 핸들러가 받는다.

## Consequences

transition은 useCase와 반드시 별도 빈의 public 메서드여야 한다(private이면 `@Transactional` 무효, 같은 빈 self-call이면 프록시 우회). CANCEL `succeed`/`fail` 충돌은 보상 `runPgCancel`의 best-effort `catch(PaymentException)`가 멱등 흡수하며("전파" 원칙은 APPROVE 종착 기준), 미해소분은 REQUESTED로 남아 CANCEL 대사(#208)에서 재확정된다.

관련: escalation 멱등을 `@Version` 기반으로 환원한 결정(→ PR#245), adapter 예외 변환(sql-exception-translator-removal task), 보상 단계별 독립 commit(payment-compensation-to-domain task), #243.
