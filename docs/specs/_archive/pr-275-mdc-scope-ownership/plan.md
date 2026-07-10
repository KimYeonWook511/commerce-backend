# Plan: mdc-scope-ownership

- 상태: Draft
- 상위 spec: `spec.md` (확정)
- 결정 로그: `adr.md` (ADR-L1)

## Summary

memberId MDC의 요청당 2회 put/remove + request attribute 릴레이를 제거하고, MDC 정리를 **2-규칙 모델**(최외곽 `TraceIdFilter`가 요청 끝 `MDC.clear()`로 스레드 스코프 정리 + nested 스코프는 자기 키만 remove)로 전환한다. 관측 결과(접근 로그에 memberId 실림, 요청 종료 후 잔류 0)는 유지하면서 구조를 단순화하고 `docs/logging-conventions.md`의 표면 모순을 정합화한다.

## 기술 맥락 (이 작업에서 달라지는 것만)

- 스택 고정값(Java 21 / Spring Boot 3.5.9 / OncePerRequestFilter 기반 필터 체인)은 루트 컨벤션이 단일 출처. 여기서는 델타만 적는다.
- **필터 체인 order 불변**: `TraceIdFilter`(+10) → `AccessLogFilter`(+20) → `JwtAuthenticationFilter`(+30). 이 포갬이 모델의 전제.
- **달라지는 것**:
  - `LogContext`: `clear()` 추가(내부 `MDC.clear()`), `removeMemberId()` 삭제(사용처 소멸). `removeTraceId()`·`getMemberId()`는 유지.
  - `TraceIdFilter`: `finally`의 `removeTraceId()` → `LogContext.clear()`. "clear() 금지" 주석 교체.
  - `JwtAuthenticationFilter`: memberId `put`만. `finally`의 `removeMemberId()` 삭제, `request.setAttribute(MEMBER_ID_ATTRIBUTE)` 삭제, `AccessLogFilter` import 제거. `AuthenticationContext.clear()`는 유지.
  - `AccessLogFilter`: `MEMBER_ID_ATTRIBUTE` 상수·attribute 소비·memberId 재삽입/재제거 삭제 → 순수 접근 로거.

## Constitution Check (GATE)

`docs/spec-constitution.md` 대조.

- **위험영역(인증·상태 전이)**: 인증 필터 동작·MDC 컨텍스트 수명을 건드리나, 정리 모델을 Clarify(2026-07-10)에서 사용자와 확정했다(추측 아님). 미확정 마커 0.
- **참조 규약**:
  - `package-structure-conventions` — 레이어 의존 방향 불변. `security` 필터가 `common.log.LogContext`(횡단 공통 유틸)에 의존하는 것은 로거 의존과 동급이며 ArchUnit 규제 대상 아님(확인함). 오히려 `security → common.log.filter.AccessLogFilter` 결합이 제거되어 의존이 준다.
  - `exception-strategy` — 예외 처리 변경 없음.
  - `test-code-conventions` — 테스트 갱신은 기존 구조·태그 유지.
- 위반 없음 → 진행.

## 구조 결정

- 새 모듈·레이어·서비스 없음. 필터/유틸의 책임 재배치만.
- MDC 접근은 `LogContext`로 단일 관리 유지(§8) — `TraceIdFilter`가 `MDC`를 직접 import하지 않고 `LogContext.clear()` 경유.

## 하위 설계 문서

- `architecture.md`: **미작성**. 레이어·책임 이동 없음(필터 내부 로직·유틸 API 변경뿐). 루트 architecture.md의 필터/MDC 서술은 Stage 8에서 필요 시만 확인.
- `data-model.md`·`db-schema.md`·`api-spec.md`: **미작성**(DB·API 변경 없음).
- `adr.md`: 작성됨(ADR-L1).

## Phase 설계

통합 지점이 하나인 작은 리팩터라 **단일 phase `0-main`**. step은 커밋 목적으로 2개.

- **step1 `mdc-scope-ownership-refactor`** (refactor): 코드 4개 파일 + 관련 테스트 6종을 한 번에. 원자적 단위 — Jwt가 remove를 멈추는데 TraceIdFilter가 아직 clear 안 하면 memberId가 새므로, 중간 상태 없이 함께 바꿔야 한다. 테스트도 같은 커밋(AC `./gradlew test` green 유지).
- **step2 `logging-conventions-sync`** (docs): `docs/logging-conventions.md`를 2-규칙 모델로 갱신. 커밋 타입이 다르고(문서) revert 단위가 분리돼야 하므로 별도 step.

> 루트 docs 동기화(ADR 승격 등)는 step으로 두지 않는다 — Stage 8(Root Sync)에서 phase 바깥에서 수행. 단 `logging-conventions.md`는 코드를 *이끄는* 규칙 문서라 상태 스냅샷 유예(api-spec·architecture·db-schema) 대상이 아니며, 이 변경의 일부로 Execution에서 갱신한다(step2).
