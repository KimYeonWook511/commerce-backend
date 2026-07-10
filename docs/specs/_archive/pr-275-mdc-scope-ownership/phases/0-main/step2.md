# step2 — logging-conventions-sync (docs)

`docs/logging-conventions.md`의 MDC 정리 규칙을 2-규칙 모델로 정합화한다. 코드(step1)가 새 모델을 따르므로, 그 모델을 이끄는 규칙 문서를 같은 방향으로 갱신한다. 커밋 타입이 다르고(문서) revert 단위가 코드와 분리돼야 하므로 별도 step이다.

## 배경 (관련 문서)

- 결정: `docs/specs/mdc-scope-ownership/spec.md`(FR-005), `adr.md`(ADR-L1).
- 현재 문서의 모순: §핵심 원칙 요약은 "자신이 push한 키만 remove, `MDC.clear()` 주의", §8 "정리"는 "요청 종료 시 **반드시 `MDC.clear()`를 호출**". 이 둘을 적용 스코프가 다른 2-규칙으로 정립한다.

## 관련 파일

- `docs/logging-conventions.md` (§핵심 원칙 요약의 MDC 줄, §8 "정리")

## 구현 지시

### 1. §핵심 원칙 요약의 MDC 줄
현재: "**MDC**: Filter가 `traceId`·`memberId` push, 요청 종료 시 `finally`에서 정리(자신이 push한 키만 remove, `MDC.clear()` 주의). 비동기 경계(Kafka/Outbox)는 명시 전파."

→ 2-규칙을 한 줄로 압축해 반영한다. 핵심: **최외곽 요청 필터가 요청 끝에 `MDC.clear()`로 스레드 스코프를 정리하고, 그 안쪽 nested 스코프(도메인 키·비동기 경계 복원분)는 자신이 push한 키만 remove**한다. 비동기 경계 명시 전파 문구는 유지.

### 2. §8 "정리" 절
현재 "요청 종료 시 반드시 `MDC.clear()`를 호출한다. Filter의 `finally` 블록 책임이다. 안 하면 스레드 풀에서…"를 다음 2-규칙으로 재서술한다.

- **(a) 최외곽 요청 필터 = 스레드 스코프 정리.** 요청 스레드의 가장 바깥 필터가 `finally`에서 `MDC.clear()`로 그 스레드의 MDC를 통째 비운다. 모든 안쪽 스코프가 풀린 요청 종료 지점이라 남의 키를 조기 삭제하지 않으며, 스레드 풀 반납 전 잔류를 막는 최종 보루다. 이 규칙은 그 필터가 MDC를 만지는 최외곽으로 유지될 때 성립한다(더 바깥에 MDC 키를 push하는 필터를 두면 재검토).
- **(b) nested 스코프 = 자신이 push한 키만 remove.** 최외곽이 아닌 곳에서 push한 키(유스케이스의 `orderId`·`pgPaymentId`, 비동기 경계(Kafka/Outbox)에서 복원한 `traceId`)는 자신이 push한 키만 `finally`에서 remove한다. 여기서 `clear()`를 부르면 바깥·형제 스코프의 살아있는 키를 날린다.
- **운영 코드에서 nested 스코프의 `MDC.clear()`는 금지**한다. `clear()`는 (a)의 최외곽 필터에서만 쓴다. 단 **테스트 격리용 `MDC.clear()`(`@BeforeEach`/`@AfterEach`)는 허용**한다 — 겹침 계약을 지킬 필요 없이 스레드 로컬을 비우는 용도다.

> §8의 "도메인 확장"·"비동기 경계 traceId 전파" 하위 절은 (b) 규칙과 정합하므로 내용은 유지한다. 구현 클래스명은 코드가 단일 출처라는 기존 문구도 유지한다.

## 주의사항

- 코드 클래스명(`TraceIdFilter` 등)을 문서에 새로 박지 마라. 이유: 규칙 문서는 개념을 기술하고 정확한 구현은 코드가 단일 출처다(기존 §8 서술 방침 유지).
- §8 외의 절(레벨·마스킹·포맷 등)을 건드리지 마라. 이유: 이 step 범위는 MDC 정리 규칙뿐이다.

## Acceptance Criteria

> `# expect: N`은 **바로 다음 줄 명령**의 기대 exit를 지정한다(지정 없으면 0). exit 1 = "매치 없음(제거됐어야 함)".

```bash
# expect: 1
rg -q '반드시.*MDC\.clear' docs/logging-conventions.md
rg -q '최외곽' docs/logging-conventions.md
rg -q '자신이 push한 키만' docs/logging-conventions.md
rg -q '테스트 격리' docs/logging-conventions.md
```
