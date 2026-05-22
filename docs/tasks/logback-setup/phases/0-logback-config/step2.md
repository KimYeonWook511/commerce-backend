# Step 2: sync-root-docs

## 읽어야 할 파일

먼저 아래 파일들을 읽고 루트 docs의 로깅 섹션 현황을 파악하라:

- `/docs/tasks/logback-setup/prd.md`
- `/docs/tasks/logback-setup/architecture.md`
- `/docs/tasks/logback-setup/adr.md`
- `/docs/architecture.md` (L143 부근 로깅 컨벤션 참조 라인)
- `/docs/logging-conventions.md`
- 이전 step에서 신규 작성된 `/src/main/resources/logback-spring.xml`

## 작업

루트 `docs/architecture.md`의 로깅 컨벤션 참조 라인 바로 다음에, logback-spring.xml이 환경별 인프라 설정의 단일 진실의 원천임을 한두 줄로 보강한다.

### 변경 위치

`docs/architecture.md` L143 부근의 다음 문장:

```
로깅 컨벤션(레이어별 로그 책임, 레벨 기준, 예외 로깅 표준, 민감 정보 마스킹 등)은
`docs/logging-conventions.md`를 참고한다.
```

### 변경 후 (예시)

```
로깅 컨벤션(레이어별 로그 책임, 레벨 기준, 예외 로깅 표준, 민감 정보 마스킹 등)은
`docs/logging-conventions.md`를 참고한다.
환경별 appender/encoder/rolling/마스킹 등 인프라 설정의 단일 진실의 원천은
`src/main/resources/logback-spring.xml`이다.
```

문구는 주변 문장 스타일에 맞춰 다듬을 수 있다. 핵심은 다음 두 가지를 명시하는 것:
1. 인프라 설정의 단일 진실의 원천이 `logback-spring.xml`이라는 사실
2. application yml의 `logging:` 섹션은 사용하지 않는다는 정책

### 변경하지 않을 것

- `docs/ADR.md`에 신규 ADR 추가 금지 (본 태스크 ADR 결정)
- `docs/logging-conventions.md` 본문 수정 금지 (이미 머지된 정책 문서, 별도 PR 책임)
- `docs/api-spec.md`, `docs/db-schema.md` 변경 없음 (해당 사항 없음)

## 수정 가능 경로

- `docs/architecture.md`

## Acceptance Criteria

```bash
./gradlew test
```

(문서만 변경이므로 빌드 영향 없음. 회귀 확인 차원에서 test 1회 실행.)

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `docs/architecture.md`의 변경 라인이 `logging-conventions.md` 참조 바로 다음에 위치하는가?
   - 추가된 문장이 "단일 진실의 원천"과 "logback-spring.xml 경로"를 명확히 가리키는가?
   - 주변 문장의 어조·들여쓰기·마크다운 형식과 일관되는가?

## 금지사항

- `docs/architecture.md`의 다른 섹션을 임의로 수정하지 마라. 이유: 본 step의 범위는 로깅 한 줄 보강.
- `docs/ADR.md`에 새로운 ADR 항목을 추가하지 마라. 이유: 본 태스크 `adr.md`에서 명시적으로 추가하지 않기로 결정.
- `docs/logging-conventions.md`를 수정하지 마라. 이유: 별도 PR 책임 영역.
