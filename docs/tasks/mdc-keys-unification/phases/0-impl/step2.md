# Step 2: sync-root-docs

## 읽어야 할 파일

- `docs/tasks/mdc-keys-unification/prd.md`
- `docs/tasks/mdc-keys-unification/architecture.md`
- `docs/logging-conventions.md`

## 작업

### `docs/logging-conventions.md` §8 "MDC 운영" 보강

§8 "MDC 운영" 섹션에 MDC 키의 단일 정의 위치를 명시하는 한 줄을 추가한다.

- 추가 위치: `### 설정` 서브섹션 아래 "Filter가 요청 진입 시 다음을 MDC에 push한다." 문장 다음 줄, MDC 키 목록 위에 1줄 추가하거나, MDC 키 목록 바로 다음에 1줄 추가하는 형태로 자연스럽게 삽입한다.
- 추가 내용 예시: `MDC 키는 \`com.commerce.common.log.MdcKeys\`에서 단일 관리한다.`
- 다른 섹션(§8의 "정리", "도메인 확장", "비동기·이벤트 경계의 traceId 전파")은 동작 기준 설명이므로 수정하지 않는다.

### 변경하지 않는 루트 문서

- `docs/architecture.md`: HTTP 요청 처리 Filter 절과 비동기 경계 절은 동작·키 문자열 기준 설명이라 코드 식별자 변경에 영향이 없다.
- `docs/ADR.md`: ADR-017(Kafka traceId 전파 설계 결정)은 동작 기준 결정 문서이므로 본 리팩토링으로 갱신할 필요가 없다.
- `docs/exception-strategy.md`, `docs/testing-conventions.md` 등: MDC 키 관련 언급 없음.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria를 실행한다.
2. `docs/logging-conventions.md` §8에 `MdcKeys` 단일 관리 명시가 한 줄 추가되었는지 확인한다.
3. 추가 위치가 §8 "MDC 운영" 안에서 키 목록과 자연스럽게 연결되는지 확인한다.
4. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- §8 외부 섹션을 수정하지 마라. 이유: 본 작업은 키 정의 위치 명시만 목적이며, 그 외 정책 변경은 다른 태스크의 책임이다.
- ADR-017 본문을 수정하지 마라. 이유: 동작 기준 결정 문서이며, 코드 식별자 변경은 ADR에 반영할 필요가 없다.
- `docs/architecture.md`의 traceId 전파 절을 수정하지 마라. 이유: 동작·키 문자열 기준 설명이라 영향이 없다. 굳이 코드 식별자 표기를 추가하면 문서가 구현 세부에 종속된다.
- 새로운 문서 파일을 생성하지 마라. 이유: 본 step은 한 줄 보강만 필요로 한다.
