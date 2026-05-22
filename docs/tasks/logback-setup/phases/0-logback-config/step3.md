# Step 3: write-retrospective

## 읽어야 할 파일

먼저 아래 파일들을 읽고 본 태스크 진행 과정과 결정사항을 파악하라:

- `/docs/tasks/logback-setup/prd.md`
- `/docs/tasks/logback-setup/architecture.md`
- `/docs/tasks/logback-setup/adr.md`
- 이전 step에서 신규 작성된 `/src/main/resources/logback-spring.xml`
- 이전 step에서 신규 작성된 `/src/main/java/com/commerce/common/log/MaskingMessageJsonProvider.java`
- 이전 step에서 신규 작성된 `/src/test/java/com/commerce/common/log/LoggingMaskingTest.java`
- step1, step2의 산출물 전반

## 작업

본 태스크의 회고록을 신규 작성한다.

위치: `docs/tasks/logback-setup/retrospective.md`

### 작성 구조 (필수 섹션)

1. **배경**
   - 이슈 #128, 의존 이슈 #127(`docs/logging-conventions.md`)과의 관계
   - 작업 전 상태 (logback 설정 없음, yml에 산재한 logging 섹션, prod 파일 로깅 부재)

2. **결정사항 요약**
   - prd.md의 요약 표를 참조해서 인용 (의존성 버전, rolling 수치, 환경별 레벨, 마스킹 방식 등)

3. **진행 중 트레이드오프**
   - 파일 JSON 마스킹: `<pattern>` provider 직접 조립 vs 커스텀 `MaskingMessageJsonProvider` — 커스텀 클래스로 간 이유 (JSON 따옴표/제어문자 escape 안정성)
   - p6spy + `org.hibernate.SQL` 중복 처리: 둘 다 활성 vs p6spy 단일화 — 단일화 선택 이유
   - prod p6spy 레벨: 디버깅 보존 vs 운영 침묵 — 침묵 선택 이유
   - 로그 경로: env override vs 고정 — 고정 선택 이유 (컨테이너 볼륨 책임 위임)
   - 콘솔 색상: 모두 plain vs local만 색상 — local 가독성 우선

4. **단일 진실의 원천 예외 1건**
   - `AsyncTest`의 인라인 `logging.level.p6spy=OFF` 유지 결정과 이유

5. **후속 작업 제안**
   - MDC traceId Filter 도입 (`OncePerRequestFilter` + Kafka header propagation + `@Async` TaskDecorator)
   - 마스킹 대상 키워드 확장 가능성 (현재 4개 → 운영 중 발견되는 신규 민감 필드)
   - ERROR 무손실 보장이 필요해질 경우 동기 appender 분리
   - 중앙 로그 수집 인프라 (ELK/Loki 등)
   - prod 컨테이너 볼륨 마운트 (commerce-infra 후속)

### 작성 톤

- 다른 회고록(`docs/ddd/*-ddd-migration-retrospective.md`)의 톤을 참고하되, 본 회고록은 DDD 마이그레이션이 아니라 인프라 작업이므로 구조와 강조점이 다르다.
- 한국어 동사형 종결 (`~한다`, `~했다` 혼용 가능). 사실과 의사결정에 집중.

### 회고록 immutability 주의

memory rule에 따라 회고록은 사후 수정하지 않는다. 작성 시 사실과 결정을 정확히 기록한다. 향후 추가할 내용이 생기면 별도 후속 작업/문서로 분리한다.

## 수정 가능 경로

- `docs/tasks/logback-setup/retrospective.md` (신규)

## Acceptance Criteria

```bash
./gradlew test
```

(문서만 신규 작성이므로 빌드 영향 없음. 회귀 확인 차원에서 test 1회 실행.)

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - `docs/tasks/logback-setup/retrospective.md`가 신규 생성되었는가?
   - 위 작성 구조 5개 섹션이 모두 포함되었는가?
   - 진행 중 트레이드오프 섹션에 최소 3개 이상의 결정 사유가 포함되었는가?
   - 후속 작업 제안이 logging-conventions.md의 "정하지 않는 것" 절과 일관되는가?

## 금지사항

- 기존 회고록(`docs/ddd/*-ddd-migration-retrospective.md`)을 수정하지 마라. 이유: 회고록은 역사 기록이라 사후 소급 수정 금지.
- 본 회고록에 코드 변경 지시를 포함하지 마라. 이유: 회고록은 사실 기록 문서이지 작업 지시서가 아니다.
- 회고록 안에 사용자/기여자 개인 정보를 적지 마라. 이유: 공개 저장소.
- 본 태스크 폴더의 다른 문서(`prd.md` 등)를 retrospective 작성 중에 수정하지 마라. 이유: 본 step의 범위는 회고록 신규 작성.
