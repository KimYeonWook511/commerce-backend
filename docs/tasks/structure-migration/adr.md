# Task ADR (staging)

> 이 파일은 이번 Task에서 **새로 채택된** 결정만 쌓는 staging 로그다.
> 여기 번호(L1, L2…)는 task 내 임시 번호이며, Stage 8(Root Sync)에서 루트 전역 번호(ADR-XXXX)로 다시 부여하며 루트에 append한다.

---

## ADR-L1: Spring Batch fault-tolerance의 DAO 예외 참조는 ArchUnit 규칙 예외처로 인정한다

- 상태: accepted
- supersedes: 없음
- superseded-by: 없음

### 배경

- `daoExceptionsConfinedToPersistence` 규칙은 JPA/DAO 예외 타입(`ObjectOptimisticLockingFailureException`, `OptimisticLockingFailureException`, `DataIntegrityViolationException`, `OptimisticLockException`)을 `infrastructure.persistence` 밖에서 참조하지 못하게 강제한다.
- `OrderExpirationBatchConfig`는 `.retry(OptimisticLockingFailureException.class)` / `.skip(OptimisticLockingFailureException.class)`로 Spring DAO 예외 타입을 직접 명명한다. 이 클래스는 `presentation/batch/`에 있으므로 규칙 위반이 된다.

### 고려한 대안

- **persistence로 이동**: batch config는 inbound adapter(진입점)인데 `infrastructure.persistence`로 내리면 레이어가 무너진다. 기각.
- **도메인 예외로 변환**(Payment의 `saveChecked` 패턴 미러링): 변환은 "예외를 직접 catch하는 위치"에서만 가능하다. 그런데 `.retry(...)`는 catch가 아니라 프레임워크에 "이 타입이 나면 재시도해라"라고 선언적으로 신고하는 코드다 — 충돌은 batch 내부 작업의 flush에서 터지고 그걸 잡는 것도 Spring Batch 프레임워크라, 변환할 예외가 내 손에 없다. 변환 대상 자체가 없으므로 적용 불가. 기각.
- **freeze 유지**: 마이그레이션 종료 신호인 freeze 제거와 모순. 기각.

### 결정 내용

- `daoExceptionsConfinedToPersistence` 규칙에서 `OrderExpirationBatchConfig`를 `GlobalExceptionHandler`와 동일하게 `areNotAssignableTo(...)`로 명시적 예외처로 제외한다.
- 예외 범위는 batch 패키지 전체가 아니라 `OrderExpirationBatchConfig` 클래스로 좁게 잡는다(batch listener 등이 나중에 DAO 예외를 참조하면 규칙이 잡도록).

### 근거

- `GlobalExceptionHandler`(HTTP 매핑)와 `OrderExpirationBatchConfig`(batch fault-tolerance) 둘 다 "DAO 예외를 잡아 비즈니스 분기하는 곳"이 아니라 "프레임워크 경계에 예외 타입을 선언적으로 넘기는 곳"이다. 같은 부류라 같은 방식(예외처)으로 다룬다.
- 이는 임시방편이 아니라 **영구 예외처**다. batch fault-tolerance를 도메인 예외로 바꾸는 후속 작업은 불필요하다(런타임 흐름과 무관).

### 결과

- freeze를 완전히 제거하고 strict로 전환할 수 있다.
- batch가 Spring DAO 예외 타입을 명명하더라도 규칙이 통과한다. 단 그 범위는 명시된 한 클래스로 한정돼 다른 곳의 DAO 예외 누수는 계속 차단된다.
