# API 스펙

> [!NOTE]
> 본 문서의 정책은 후속 태스크 `docs/tasks/unique-find-first-policy/` 에서 재정의되었다. 현재 정책은 루트 `docs/architecture.md` 의 예외 처리 섹션과 `docs/tasks/unique-find-first-policy/adr.md` 를 참조한다.

이 태스크는 API 인터페이스를 변경하지 않는다.

## 변경 내용

- 기존 API 경로, 요청/응답 스펙은 변경 없음.
- `GlobalExceptionHandler`의 `DataIntegrityViolationException` 응답이 **409 → 500**으로 변경됨.
  - 이 응답은 정상 흐름에서 도달하지 않으며, application catch 누락 시 발생하는 안전망 응답이다.
  - 클라이언트가 이 응답을 받으면 서버 버그 상황이므로 재시도 불가.
