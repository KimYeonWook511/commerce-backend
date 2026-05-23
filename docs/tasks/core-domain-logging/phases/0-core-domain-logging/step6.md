# Step 6: sync-root-docs

## 읽어야 할 파일

- `docs/tasks/core-domain-logging/prd.md`
- `docs/tasks/core-domain-logging/architecture.md`
- `docs/architecture.md` — 현재 로깅/Application 계층 절 확인
- `docs/logging-conventions.md` — 단일 진실의 원천 (수정 금지, 참고만)

step1~step5에서 변경된 14개 파일 목록(architecture.md 참조).

## 작업

`docs/architecture.md`에 Application 계층 로깅 정책 절을 짧게 보강한다.

포함할 내용:
- Application Service가 유스케이스 시작·완료 시 INFO 로그를 남긴다는 책임 정의
- 메시지 패턴: 한국어 본문 + 영어 식별자 필드 + SLF4J placeholder
- 컨벤션 단일 진실의 원천은 `docs/logging-conventions.md`임을 명시 (architecture.md는 요약만)
- 본 작업으로 보강된 14개 컴포넌트의 도메인 범위 (Order, Outbox, Payment, Stock, Auth, Member, Product)

기존 문서 구조를 유지하고, 이미 P2(`#136` traceid-mdc-filter)에서 추가된 Filter 절과 중복되지 않게 작성한다.

## 수정 가능 경로

- `docs/architecture.md`
- `docs/tasks/core-domain-logging/**`

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. `docs/architecture.md`에 Application 계층 로깅 절이 추가됐는가 확인
2. 기존 문서 구조가 유지됐는가 확인
3. P2 TraceIdFilter 절과 중복되지 않았는가 확인
4. `docs/logging-conventions.md`가 단일 진실의 원천임을 명시했는가 확인
5. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- `docs/logging-conventions.md` 수정 금지. 이유: §2~§9의 정책 결정은 P0(#127)에서 합의된 단일 진실의 원천. 구현 단계에서 정책 변경 권한 없음.
- `docs/architecture.md`에 logging-conventions.md의 정책 내용을 통째로 복사 금지. 이유: 단일 진실의 원천 위반.
- 코드 수정 금지. 이유: 본 step은 문서 동기화만.
