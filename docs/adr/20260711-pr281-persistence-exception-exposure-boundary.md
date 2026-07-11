# 상위 계층은 구현에 독립적인 Spring DAO 추상 예외까지 다루고 구현체 예외는 infrastructure에 가둔다

- Status: accepted
- Date: 2026-07-11

## Context

기존 예외 노출 경계는 "application·domain·presentation은 Spring DAO 예외를 참조·catch하지 않는다"였다(ArchUnit이 `org.springframework.orm.ObjectOptimisticLockingFailureException`·`org.springframework.dao.OptimisticLockingFailureException`·`org.springframework.dao.DataIntegrityViolationException`·`jakarta.persistence.OptimisticLockException`을 `infrastructure.persistence` 밖에서 금지).

그런데 `org.springframework.dao.*`는 특정 영속성 구현(JPA·Hibernate·JDBC·MyBatis)에 묶이지 않은 **추상 예외**인데도, 구현체에 묶인 구체 예외(`org.springframework.orm.*`·`org.hibernate.*`·`jakarta.persistence`)와 같은 금지선에 묶여 있었다. 그 결과 순수 재시도처럼 그 예외를 따로 처리하지 않는 경우에도 adapter가 `saveAndFlush` + 도메인 예외 번역을 강제당해, 불필요한 flush·변환 코드가 늘었다.

## Decision

예외 노출 경계를 **예외의 추상 수준**으로 다시 긋는다.

- **application·presentation**: 구현체에 묶인 구체 예외(`org.springframework.orm.*`, `org.hibernate.*` 예외, `jakarta.persistence` 예외)만 참조를 금지한다. 특정 구현에 묶이지 않은 Spring DAO 추상 예외(`org.springframework.dao.*`, 예: `OptimisticLockingFailureException`·`DataIntegrityViolationException`)는 다뤄도 된다 — Spring은 교체 대상이 아니고 이 계층은 어느 영속성 구현도 가리지 않는 추상이기 때문이다. 추상 상위 타입을 catch하면 그 하위 구현체 타입이 다형적으로 걸리므로, 상위 계층이 구현체 타입 이름을 부를 일이 자체가 없다.
- **domain**: 추상·구체 **둘 다** 참조 금지한다(가장 안쪽, 순수 도메인 로직).
- **번역은 의무가 아니라 선별적이다**: 기술 예외 → 도메인 예외 번역은 **안쪽이 그 예외에 따라 다르게 처리해야 할 때만** `infrastructure/persistence/` adapter가 `saveAndFlush`로 감지를 당겨 수행한다(유니크 위반 → 이미 존재/이중결제 차단, 버전 충돌 → skip 판단 등). 순수 재시도처럼 그 예외를 따로 처리하지 않으면 번역하지 않고, usecase가 DAO 추상 예외(`OptimisticLockingFailureException`)를 직접 잡아 새 트랜잭션으로 재시도하거나 끝단 핸들러로 흘려보낸다. 판단축은 "안쪽이 그 예외를 실제로 다루는가"다.

## Consequences

- **얻는 것**: 순수 재시도 경로에서 불필요한 `saveAndFlush` + 도메인 예외 번역 보일러플레이트를 없앨 수 있다. 옛 규칙("persistence 밖에서 DAO 예외 참조 금지")이 사라지면서, DAO 추상 예외만 참조하는 `GlobalExceptionHandler`(common)를 위한 ArchUnit 예외처가 불필요해져 규칙이 단순해진다.
- **감수**: application·presentation이 Spring 추상 타입 하나(`org.springframework.dao`)에 의존하게 된다. 다만 이는 특정 구현이 아니라 추상이므로 영속성 구현 교체(JPA ↔ MyBatis 등)에 영향받지 않는다.
- **유지**: presentation Controller는 낙관 락 충돌을 전파해야 하므로 충돌 예외 계층(DAO 추상 포함)을 참조하지 않는다는 규칙은 남는다. `OrderExpirationBatchConfig`의 fault-tolerance(`.retry`/`.skip`)는 충돌 타입을 프레임워크에 선언적으로 신고하는 경계라 business catch가 아니므로, 이 규칙의 명시적 예외처로 좁게 인정한다(예외처 범위는 그대로, 근거만 새 경계 기준으로 재정의).
- **범위**: 도메인 예외의 HTTP 의존 제거(`ErrorCode`가 상태코드 대신 의미 분류를 들게 하는 변경)는 이 결정과 별개이며 후속(#270)에서 다룬다. 이 ADR은 예외 타입의 노출 경계만 재정의한다.

관련: find-first 안전망 위임(→ PR#109)과 제약명 식별(→ PR#228)은 유지된다. `GlobalExceptionHandler`·batch를 옛 DAO-격리 규칙의 명시적 예외처로 인정하던 결정은, 그 규칙이 이 경계 재정의로 대체되면서 함께 대체된다(→ pr247).
